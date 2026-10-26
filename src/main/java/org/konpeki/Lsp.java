package org.konpeki;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
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
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
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
        return analysis;
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
