package org.konpeki;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * Language server for sketches, run with "ProcessingCLI lsp". Talks LSP over stdin and stdout.
 */
final class Lsp implements LanguageServer, LanguageClientAware, TextDocumentService, WorkspaceService {
    private final Map<Path, String> open = new ConcurrentHashMap<>();
    // Everything below is only touched on the worker thread
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().factory());
    private final Map<Path, Analysis> analyses = new HashMap<>();
    private final Map<Path, SemanticTokens> lastTokens = new HashMap<>();
    // The newest analysis that got past syntax errors, for completion mid-edit
    private final Map<Path, Analysis> lastGood = new HashMap<>();
    // What the last completion offered, so resolve can find its docs
    private List<Analysis.Completion> offered = List.of();
    private final Map<Path, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final StandardJavaFileManager files =
            ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, StandardCharsets.UTF_8);
    private LanguageClient client;

    static void run() throws Exception {
        FileOutputStream protocol = new FileOutputStream(FileDescriptor.out);
        // Anything else printed to stdout would corrupt the protocol
        System.setOut(System.err);
        Lsp server = new Lsp();
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, protocol);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
        System.exit(0);
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        capabilities.setHoverProvider(true);
        capabilities.setDefinitionProvider(true);
        capabilities.setDocumentFormattingProvider(true);
        capabilities.setCompletionProvider(new CompletionOptions(true, List.of(".")));
        capabilities.setSemanticTokensProvider(new SemanticTokensWithRegistrationOptions(
                new SemanticTokensLegend(Analysis.TOKEN_TYPES, Analysis.TOKEN_MODIFIERS), true));
        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return this;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return this;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        Path file = path(params.getTextDocument().getUri());
        open.put(file, params.getTextDocument().getText());
        schedule(file.getParent());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        Path file = path(params.getTextDocument().getUri());
        // Full sync, the last change is the whole document
        open.put(file, params.getContentChanges().getLast().getText());
        schedule(file.getParent());
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        Path file = path(params.getTextDocument().getUri());
        open.remove(file);
        schedule(file.getParent());
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        schedule(path(params.getTextDocument().getUri()).getParent());
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {}

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        Path file = path(params.getTextDocument().getUri());
        Position at = params.getPosition();
        return CompletableFuture.supplyAsync(
                () -> {
                    Analysis analysis = current(file.getParent());
                    String text = analysis == null ? null : analysis.hover(file, at.getLine(), at.getCharacter());
                    return text == null ? null : new Hover(new MarkupContent(MarkupKind.MARKDOWN, text));
                },
                worker);
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        Path file = path(params.getTextDocument().getUri());
        Position at = params.getPosition();
        return CompletableFuture.supplyAsync(
                () -> {
                    Analysis analysis = current(file.getParent());
                    Analysis.Spot spot =
                            analysis == null ? null : analysis.definition(file, at.getLine(), at.getCharacter());
                    List<Location> locations = spot == null ? List.of() : List.of(location(spot));
                    return Either.forLeft(locations);
                },
                worker);
    }

    private static final List<String> KEYWORDS = List.of(
            "boolean",
            "byte",
            "char",
            "color",
            "double",
            "float",
            "int",
            "long",
            "short",
            "void",
            "if",
            "else",
            "for",
            "while",
            "do",
            "switch",
            "case",
            "default",
            "break",
            "continue",
            "return",
            "new",
            "class",
            "extends",
            "implements",
            "interface",
            "enum",
            "static",
            "final",
            "public",
            "private",
            "protected",
            "abstract",
            "this",
            "super",
            "null",
            "true",
            "false",
            "import",
            "try",
            "catch",
            "finally",
            "throw",
            "instanceof");

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        Path file = path(params.getTextDocument().getUri());
        Position at = params.getPosition();
        return CompletableFuture.supplyAsync(
                () -> {
                    List<CompletionItem> items = new ArrayList<>();
                    Analysis.Patch patch;
                    try {
                        String text = open.containsKey(file) ? open.get(file) : Files.readString(file);
                        patch = Analysis.patch(text, at.getLine(), at.getCharacter());
                    } catch (Exception e) {
                        patch = null;
                    }
                    if (patch == null) return Either.forLeft(items);

                    Map<Path, String> patched = new HashMap<>(open);
                    patched.put(file, patch.text());
                    Analysis analysis;
                    try {
                        analysis = Analysis.of(file.getParent(), patched, files);
                    } catch (Exception e) {
                        analysis = null;
                    }
                    if (analysis == null || !analysis.analysed()) analysis = lastGood.get(file.getParent());
                    offered = analysis == null ? List.of() : analysis.completions(patch.member());

                    for (int i = 0; i < offered.size(); i++) {
                        Analysis.Completion completion = offered.get(i);
                        CompletionItem item = new CompletionItem(completion.name());
                        Element element = completion.element();
                        item.setKind(kind(element));
                        item.setDetail(
                                element == null
                                        ? "int length"
                                        : analysis.signature(element)
                                                + (completion.overloads() > 1
                                                        ? " (+" + (completion.overloads() - 1) + " overloads)"
                                                        : ""));
                        item.setSortText(rank(element) + completion.name());
                        item.setData(i);
                        items.add(item);
                    }
                    if (!patch.member()) {
                        for (String keyword : KEYWORDS) {
                            CompletionItem item = new CompletionItem(keyword);
                            item.setKind(CompletionItemKind.Keyword);
                            item.setSortText("4" + keyword);
                            items.add(item);
                        }
                    }
                    return Either.forLeft(items);
                },
                worker);
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem item) {
        return CompletableFuture.supplyAsync(
                () -> {
                    if (item.getData() instanceof com.google.gson.JsonPrimitive index
                            && index.getAsInt() < offered.size()
                            && offered.get(index.getAsInt()).element() != null) {
                        String docs =
                                Reference.docs(offered.get(index.getAsInt()).element());
                        if (docs != null) item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, docs));
                    }
                    return item;
                },
                worker);
    }

    private static CompletionItemKind kind(Element element) {
        if (element == null) return CompletionItemKind.Field;
        return switch (element.getKind()) {
            case METHOD -> CompletionItemKind.Method;
            case FIELD ->
                element.getModifiers().containsAll(List.of(Modifier.STATIC, Modifier.FINAL))
                        ? CompletionItemKind.Constant
                        : CompletionItemKind.Field;
            case CLASS, RECORD -> CompletionItemKind.Class;
            case INTERFACE, ANNOTATION_TYPE -> CompletionItemKind.Interface;
            case ENUM -> CompletionItemKind.Enum;
            case ENUM_CONSTANT -> CompletionItemKind.EnumMember;
            default -> CompletionItemKind.Variable;
        };
    }

    // Locals first, then the sketch's own names, then Processing's, then types
    private static String rank(Element element) {
        if (element == null) return "2";
        ElementKind kind = element.getKind();
        if (kind.isVariable() && kind != ElementKind.FIELD) return "0";
        if (kind.isClass() || kind.isInterface()) return "3";
        Element owner = element;
        while (!(owner instanceof PackageElement)) owner = owner.getEnclosingElement();
        return ((PackageElement) owner).isUnnamed() ? "1" : "2";
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        String text = open.get(path(params.getTextDocument().getUri()));
        if (text == null) return CompletableFuture.completedFuture(List.of());
        String unit = params.getOptions().isInsertSpaces()
                ? " ".repeat(params.getOptions().getTabSize())
                : "\t";
        String formatted = Format.indent(text, unit);
        if (formatted.equals(text)) return CompletableFuture.completedFuture(List.of());
        // One edit over the whole document, its end is past the last line so it covers everything
        Range all = new Range(new Position(0, 0), new Position(Integer.MAX_VALUE, 0));
        return CompletableFuture.completedFuture(List.of(new TextEdit(all, formatted)));
    }

    // Typing restarts the timer, so a sketch is analysed once paused
    private void schedule(Path dir) {
        ScheduledFuture<?> previous = pending.put(dir, worker.schedule(() -> analyze(dir), 300, TimeUnit.MILLISECONDS));
        if (previous != null) previous.cancel(false);
    }

    // Hover and definition need the analysis to match what's on screen
    private Analysis current(Path dir) {
        ScheduledFuture<?> waiting = pending.get(dir);
        if (waiting != null && waiting.cancel(false)) analyze(dir);
        return analyses.containsKey(dir) ? analyses.get(dir) : analyze(dir);
    }

    private Analysis analyze(Path dir) {
        pending.remove(dir);
        Analysis previous = analyses.get(dir);
        Analysis analysis;
        try {
            analysis = Analysis.of(dir, open, files);
        } catch (Exception e) {
            System.err.println("Analysis failed for " + dir + ": " + e);
            return previous;
        }
        analyses.put(dir, analysis);
        if (analysis.analysed()) lastGood.put(dir, analysis);

        Map<Path, List<Diagnostic>> byFile = new HashMap<>();
        for (Path file : analysis.files()) byFile.put(file, new ArrayList<>());
        // Clear tabs that were deleted or renamed
        if (previous != null) for (Path file : previous.files()) byFile.putIfAbsent(file, new ArrayList<>());
        for (Analysis.Problem problem : analysis.problems) {
            Diagnostic diagnostic = new Diagnostic(
                    new Range(position(problem.start()), position(problem.end())),
                    problem.message(),
                    problem.error() ? DiagnosticSeverity.Error : DiagnosticSeverity.Warning,
                    "processing");
            byFile.computeIfAbsent(problem.start().file(), f -> new ArrayList<>())
                    .add(diagnostic);
        }
        byFile.forEach((file, list) -> client.publishDiagnostics(
                new PublishDiagnosticsParams(file.toUri().toString(), list)));
        // A change in one tab can change what names in the others are
        if (analysis.analysed()) client.refreshSemanticTokens();
        return analysis;
    }

    @Override
    public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
        Path file = path(params.getTextDocument().getUri());
        return CompletableFuture.supplyAsync(
                () -> {
                    Analysis analysis = current(file.getParent());
                    // Mid-edit syntax errors stop analysis, keep the last colours rather than flicker
                    if (analysis == null || !analysis.analysed()) {
                        return lastTokens.getOrDefault(file, new SemanticTokens(List.of()));
                    }
                    // Each token is relative to the one before: line, column, then length, type, modifiers
                    List<Integer> data = new ArrayList<>();
                    int line = 0, column = 0;
                    for (int[] token : analysis.semanticTokens(file)) {
                        data.add(token[0] - line);
                        data.add(token[0] == line ? token[1] - column : token[1]);
                        data.add(token[2]);
                        data.add(token[3]);
                        data.add(token[4]);
                        line = token[0];
                        column = token[1];
                    }
                    SemanticTokens tokens = new SemanticTokens(data);
                    lastTokens.put(file, tokens);
                    return tokens;
                },
                worker);
    }

    private static Path path(String uri) {
        return Path.of(URI.create(uri)).toAbsolutePath().normalize();
    }

    private static Position position(Analysis.Spot spot) {
        return new Position(spot.line(), spot.column());
    }

    private static Location location(Analysis.Spot spot) {
        return new Location(spot.file().toUri().toString(), new Range(position(spot), position(spot)));
    }
}
