package org.konpeki;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.eclipse.lsp4j.Command;
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
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Tuple;
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
    private final Map<Path, Colours> lastTokens = new HashMap<>();
    // The newest analysis that got past syntax errors, for completion mid-edit
    private final Map<Path, Analysis> lastGood = new HashMap<>();
    // What the last completion offered, so resolve can find its docs
    private List<Analysis.Completion> offered = List.of();
    private Analysis offeredBy;
    // Client takes snippets
    private boolean snippets;
    // Ranked above the rest of the API
    private static final Set<String> COMMON = Set.of(
            "println",
            "print",
            "size",
            "background",
            "fill",
            "noFill",
            "stroke",
            "noStroke",
            "strokeWeight",
            "ellipse",
            "circle",
            "rect",
            "square",
            "line",
            "point",
            "text",
            "textSize",
            "image",
            "loadImage",
            "random",
            "map",
            "constrain",
            "dist",
            "width",
            "height",
            "mouseX",
            "mouseY",
            "frameCount",
            "translate",
            "rotate",
            "pushMatrix",
            "popMatrix",
            "color",
            "sin",
            "cos",
            "abs",
            "min",
            "max");
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
        try {
            snippets = Boolean.TRUE.equals(params.getCapabilities()
                    .getTextDocument()
                    .getCompletion()
                    .getCompletionItem()
                    .getSnippetSupport());
        } catch (NullPointerException e) {
            snippets = false;
        }
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        capabilities.setHoverProvider(true);
        capabilities.setDefinitionProvider(true);
        capabilities.setDocumentFormattingProvider(true);
        capabilities.setSignatureHelpProvider(new SignatureHelpOptions(List.of("(", ","), List.of(",")));
        // * for /** doc comments
        capabilities.setCompletionProvider(new CompletionOptions(true, List.of(".", "*")));
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
                    Lookup lookup = lookup(file, at.getLine());
                    String text =
                            lookup == null ? null : lookup.analysis().hover(file, lookup.line(), at.getCharacter());
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
                    Lookup lookup = lookup(file, at.getLine());
                    Analysis.Spot spot = lookup == null
                            ? null
                            : lookup.analysis().definition(file, lookup.line(), at.getCharacter());
                    // Back to where it is now
                    if (spot != null && lookup.analysis() != analyses.get(file.getParent())) {
                        String then = lookup.analysis().text(spot.file()), now = text(spot.file());
                        // Not a tab, nothing moved
                        if (then != null) {
                            int line = now == null ? -1 : sameLine(then, now, spot.line());
                            spot = line < 0 ? null : new Analysis.Spot(spot.file(), line, spot.column());
                        }
                    }
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
                    String text = text(file);
                    CompletionItem doc = text == null ? null : docComment(text, at);
                    if (doc != null) return Either.forLeft(List.of(doc));
                    Analysis.Patch patch = text == null ? null : Analysis.patch(text, at.getLine(), at.getCharacter());
                    if (patch == null) return Either.forLeft(items);
                    Analysis analysis = patched(file, patch);
                    offeredBy = analysis;
                    offered = analysis == null ? List.of() : analysis.completions(patch.member());

                    String line = text.lines().skip(at.getLine()).findFirst().orElse("");
                    boolean afterNew = line.substring(0, Math.min(at.getCharacter(), line.length()))
                            .matches(".*\\bnew\\s+\\w*$");
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
                        String rank = rank(element);
                        if (COMMON.contains(completion.name()) && rank.equals("2")) rank = "1~";
                        // Only a class can follow new
                        if (afterNew && element != null && element.getKind().isClass()) rank = "0";
                        item.setSortText(rank + completion.name());
                        if (snippets) call(item, completion, text, at);
                        item.setData(i);
                        items.add(item);
                    }
                    if (!patch.member() && analysis != null) {
                        for (Analysis.Overridable o : analysis.overridable())
                            items.add(override(o, analysis, text, at));
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
    public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
        Path file = path(params.getTextDocument().getUri());
        Position at = params.getPosition();
        return CompletableFuture.supplyAsync(
                () -> {
                    String text = text(file);
                    Analysis.Call call = text == null ? null : Analysis.call(text, at.getLine(), at.getCharacter());
                    Analysis.Patch patch = call == null ? null : Analysis.patch(text, call.line(), call.column());
                    Analysis analysis = patch == null ? null : patched(file, patch);
                    if (analysis == null) return null;

                    List<ExecutableElement> callables = new ArrayList<>(analysis.callables(patch.member(), call));
                    callables.sort(
                            Comparator.comparingInt(m -> m.getParameters().size()));
                    List<SignatureInformation> signatures = new ArrayList<>();
                    int active = -1;
                    for (ExecutableElement method : callables) {
                        List<Reference.Parameter> parameters = analysis.parameters(method);
                        StringBuilder label = new StringBuilder(call.name()).append('(');
                        List<ParameterInformation> infos = new ArrayList<>();
                        for (int i = 0; i < parameters.size(); i++) {
                            if (i > 0) label.append(", ");
                            int start = label.length();
                            label.append(Reference.type(
                                            method.getParameters().get(i).asType()))
                                    .append(' ')
                                    .append(parameters.get(i).name());
                            ParameterInformation info = new ParameterInformation();
                            info.setLabel(Either.forRight(new Tuple.Two<>(start, label.length())));
                            String description = parameters.get(i).description();
                            if (description != null) {
                                info.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, description));
                            }
                            infos.add(info);
                        }
                        SignatureInformation signature =
                                new SignatureInformation(label.append(')').toString());
                        signature.setParameters(infos);
                        String summary = analysis.summary(method);
                        if (summary != null)
                            signature.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, summary));
                        // First overload with room for this argument
                        if (active < 0 && (parameters.size() > call.argument() || method.isVarArgs())) {
                            active = signatures.size();
                        }
                        signatures.add(signature);
                    }
                    if (signatures.isEmpty()) return null;
                    return new SignatureHelp(signatures, Math.max(active, 0), call.argument());
                },
                worker);
    }

    private String text(Path file) {
        try {
            return open.containsKey(file) ? open.get(file) : Files.readString(file);
        } catch (Exception e) {
            return null;
        }
    }

    // Analysis with the patch, or the last good one
    private Analysis patched(Path file, Analysis.Patch patch) {
        Map<Path, String> patched = new HashMap<>(open);
        patched.put(file, patch.text());
        try {
            Analysis analysis = Analysis.of(file.getParent(), patched, files);
            if (analysis.analysed()) return analysis;
        } catch (Exception e) {
            // Falls back below
        }
        return lastGood.get(file.getParent());
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem item) {
        return CompletableFuture.supplyAsync(
                () -> {
                    if (item.getData() instanceof com.google.gson.JsonPrimitive index
                            && index.getAsInt() < offered.size()
                            && offered.get(index.getAsInt()).element() != null
                            && offeredBy != null) {
                        String docs =
                                offeredBy.docs(offered.get(index.getAsInt()).element());
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
    private static final Set<String> MODIFIERS = Set.of(
            "public", "private", "protected", "static", "final", "abstract", "synchronized", "native", "default");

    // /** becomes a doc comment for the declaration below
    private static CompletionItem docComment(String text, Position at) {
        List<String> lines = text.lines().toList();
        if (at.getLine() >= lines.size()) return null;
        String line = lines.get(at.getLine());
        String before = line.substring(0, Math.min(at.getCharacter(), line.length()));
        if (!before.strip().equals("/**")
                || !line.substring(before.length()).strip().matches("(\\*/)?")) return null;

        // The declaration, up to its { or ;
        StringBuilder declaration = new StringBuilder();
        for (int i = at.getLine() + 1;
                i < lines.size() && declaration.indexOf("{") < 0 && declaration.indexOf(";") < 0;
                i++) {
            String next = lines.get(i).strip();
            if (!next.startsWith("@")) declaration.append(' ').append(next);
        }
        StringBuilder snippet = new StringBuilder("/**\n * ${1}\n");
        Matcher method = Pattern.compile("^([\\w<>\\[\\],.?\\s]*?)\\b(\\w+)\\s*\\(([^)]*)\\)")
                .matcher(declaration.toString().strip());
        if (method.find() && !method.group(2).matches("if|for|while|switch|catch|new|return")) {
            List<String> types = new ArrayList<>(List.of(method.group(1).strip().split("\\s+")));
            types.removeIf(t -> t.isEmpty() || MODIFIERS.contains(t));
            List<String> params = new ArrayList<>();
            int depth = 0;
            StringBuilder param = new StringBuilder();
            for (char c : (method.group(3) + ",").toCharArray()) {
                if (c == '<') depth++;
                if (c == '>') depth--;
                if (c == ',' && depth == 0) {
                    String[] words = param.toString().strip().split("[\\s.\\[\\]]+");
                    if (!param.isEmpty() && words.length > 0) params.add(words[words.length - 1]);
                    param.setLength(0);
                } else {
                    param.append(c);
                }
            }
            // A constructor has no return type
            boolean returns = !types.isEmpty() && !types.getLast().equals("void");
            if (!params.isEmpty() || returns) snippet.append(" *\n");
            int stop = 2;
            for (String name : params)
                snippet.append(" * @param ")
                        .append(name)
                        .append(" ${")
                        .append(stop++)
                        .append("}\n");
            if (returns) snippet.append(" * @return ${").append(stop).append("}\n");
        }
        snippet.append(" */");

        int start = line.indexOf("/**");
        CompletionItem item = new CompletionItem("/** */");
        item.setKind(CompletionItemKind.Snippet);
        item.setDetail("Doc comment");
        item.setFilterText("/**");
        item.setSortText("0");
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        Range range = new Range(new Position(at.getLine(), start), new Position(at.getLine(), line.length()));
        item.setTextEdit(Either.forLeft(new TextEdit(range, snippet.toString())));
        return item;
    }

    // Whole method, replacing what's typed like void sh
    private CompletionItem override(Analysis.Overridable o, Analysis analysis, String text, Position at) {
        ExecutableElement method = o.method();
        String name = method.getSimpleName().toString();
        List<Reference.Parameter> parameters = analysis.parameters(method);
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) params.append(", ");
            String type = analysis.type(o.parameterTypes().get(i));
            String parameter = parameters.get(i).name();
            // arg0 becomes a name from the type
            if (parameter.matches("arg\\d+")) {
                String simple = type.replaceAll("<.*|\\[.*|\\.\\.\\.", "");
                parameter = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
                if (Set.of("int", "float", "double", "long", "char", "byte", "short", "boolean")
                        .contains(parameter)) {
                    parameter = "value";
                }
            }
            params.append(type).append(' ').append(parameter);
        }
        String returns = analysis.type(method.getReturnType());
        // Processing makes .pde methods public
        String access = o.inPde()
                ? ""
                : method.getModifiers().contains(Modifier.PUBLIC)
                        ? "public "
                        : method.getModifiers().contains(Modifier.PROTECTED) ? "protected " : "";
        String body = method.getReturnType().getKind() == javax.lang.model.type.TypeKind.VOID ? "\t$0" : "\treturn $0;";
        String code = (o.sketch() ? "" : "@Override\n") + access + returns + " " + name + "(" + params + ") {\n" + body
                + "\n}";

        String line = text.lines().skip(at.getLine()).findFirst().orElse("");
        String before = line.substring(0, Math.min(at.getCharacter(), line.length()));
        int start = before.length() - before.stripLeading().length();
        CompletionItem item = new CompletionItem(name + "(" + params + ")");
        item.setKind(CompletionItemKind.Method);
        boolean implementing = method.getModifiers().contains(Modifier.ABSTRACT);
        item.setDetail((o.sketch() ? "Processing calls " : implementing ? "Implement " : "Override ")
                + method.getEnclosingElement().getSimpleName() + "." + name);
        item.setFilterText(before.stripLeading().replaceAll("\\w*$", "") + name);
        item.setSortText("0" + name);
        item.setInsertTextFormat(snippets ? InsertTextFormat.Snippet : InsertTextFormat.PlainText);
        Range range = new Range(new Position(at.getLine(), start), at);
        item.setTextEdit(Either.forLeft(new TextEdit(range, snippets ? code : code.replace("$0", ""))));
        String docs = analysis.docs(method);
        if (docs != null) item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, docs));
        return item;
    }

    // ellipse($0) and parameter hints, or move()$0
    private static void call(CompletionItem item, Analysis.Completion completion, String text, Position at) {
        Element element = completion.element();
        if (element == null) return;
        String line = text.lines().skip(at.getLine()).findFirst().orElse("");
        String before =
                line.substring(0, Math.min(at.getCharacter(), line.length())).replaceAll("\\w*$", "");
        String after =
                line.substring(Math.min(at.getCharacter(), line.length())).replaceAll("^\\w*", "");
        if (after.stripLeading().startsWith("(")) return;
        boolean method = element.getKind() == ElementKind.METHOD;
        boolean constructor = (element.getKind().isClass()) && before.matches(".*\\bnew\\s+$");
        if (!method && !constructor) return;
        boolean noArguments = method
                && completion.overloads() == 1
                && ((ExecutableElement) element).getParameters().isEmpty();
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        // new ArrayList<>() rather than a raw ArrayList
        boolean generic =
                constructor && !((TypeElement) element).getTypeParameters().isEmpty();
        item.setInsertText(completion.name() + (generic ? "<>" : "") + (noArguments ? "()$0" : "($0)"));
        if (!noArguments) item.setCommand(new Command("Parameter hints", "editor.action.triggerParameterHints"));
    }

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
        String formatted = Format.format(text, unit);
        if (formatted == null || formatted.equals(text)) return CompletableFuture.completedFuture(List.of());
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
                    String text = text(file);
                    List<int[]> tokens;
                    if (analysis != null && analysis.analysed()) {
                        tokens = analysis.semanticTokens(file);
                        if (text != null) lastTokens.put(file, new Colours(text, tokens));
                    } else {
                        // Mid-edit syntax error, keep colours on unchanged lines
                        Colours last = lastTokens.get(file);
                        tokens = last == null || text == null ? List.of() : last.on(text);
                    }
                    // Each token is relative to the one before: line, column, then length, type, modifiers
                    List<Integer> data = new ArrayList<>();
                    int line = 0, column = 0;
                    for (int[] token : tokens) {
                        data.add(token[0] - line);
                        data.add(token[0] == line ? token[1] - column : token[1]);
                        data.add(token[2]);
                        data.add(token[3]);
                        data.add(token[4]);
                        line = token[0];
                        column = token[1];
                    }
                    return new SemanticTokens(data);
                },
                worker);
    }

    private record Lookup(Analysis analysis, int line) {}

    // While it doesn't compile, the last good analysis if the line's unchanged
    private Lookup lookup(Path file, int line) {
        Analysis analysis = current(file.getParent());
        if (analysis != null && analysis.analysed()) return new Lookup(analysis, line);
        Analysis good = lastGood.get(file.getParent());
        String then = good == null ? null : good.text(file), now = text(file);
        if (then == null || now == null) return null;
        int moved = sameLine(now, then, line);
        return moved < 0 ? null : new Lookup(good, moved);
    }

    // Same line in another version, -1 if it changed
    private static int sameLine(String from, String to, int line) {
        String[] a = from.split("\n", -1), b = to.split("\n", -1);
        int same = 0;
        while (same < a.length && same < b.length && a[same].equals(b[same])) same++;
        if (line < same) return line;
        int end = 0;
        while (end < a.length - same && end < b.length - same && a[a.length - 1 - end].equals(b[b.length - 1 - end]))
            end++;
        return line >= a.length - end ? line + b.length - a.length : -1;
    }

    // Semantic tokens and the text they were for
    private record Colours(String text, List<int[]> tokens) {
        // Moved onto newer text, changed lines lose theirs
        List<int[]> on(String newer) {
            String[] old = text.split("\n", -1), now = newer.split("\n", -1);
            int same = 0;
            while (same < old.length && same < now.length && old[same].equals(now[same])) same++;
            int end = 0;
            while (end < old.length - same
                    && end < now.length - same
                    && old[old.length - 1 - end].equals(now[now.length - 1 - end])) end++;
            int moved = now.length - old.length;
            List<int[]> kept = new ArrayList<>();
            for (int[] token : tokens) {
                if (token[0] < same) kept.add(token);
                else if (token[0] >= old.length - end)
                    kept.add(new int[] {token[0] + moved, token[1], token[2], token[3], token[4]});
            }
            return kept;
        }
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
