package org.konpeki;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.CharValue;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.ModificationWatchpointEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.ModificationWatchpointRequest;
import com.sun.jdi.request.StepRequest;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.lsp4j.debug.Breakpoint;
import org.eclipse.lsp4j.debug.BreakpointEventArguments;
import org.eclipse.lsp4j.debug.BreakpointEventArgumentsReason;
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.ContinueResponse;
import org.eclipse.lsp4j.debug.DataBreakpoint;
import org.eclipse.lsp4j.debug.DataBreakpointAccessType;
import org.eclipse.lsp4j.debug.DataBreakpointInfoArguments;
import org.eclipse.lsp4j.debug.DataBreakpointInfoResponse;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.EvaluateResponse;
import org.eclipse.lsp4j.debug.ExceptionBreakMode;
import org.eclipse.lsp4j.debug.ExceptionBreakpointsFilter;
import org.eclipse.lsp4j.debug.ExceptionInfoArguments;
import org.eclipse.lsp4j.debug.ExceptionInfoResponse;
import org.eclipse.lsp4j.debug.ExitedEventArguments;
import org.eclipse.lsp4j.debug.InitializeRequestArguments;
import org.eclipse.lsp4j.debug.NextArguments;
import org.eclipse.lsp4j.debug.OutputEventArguments;
import org.eclipse.lsp4j.debug.OutputEventArgumentsCategory;
import org.eclipse.lsp4j.debug.PauseArguments;
import org.eclipse.lsp4j.debug.Scope;
import org.eclipse.lsp4j.debug.ScopesArguments;
import org.eclipse.lsp4j.debug.ScopesResponse;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetBreakpointsResponse;
import org.eclipse.lsp4j.debug.SetDataBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetDataBreakpointsResponse;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsResponse;
import org.eclipse.lsp4j.debug.SetVariableArguments;
import org.eclipse.lsp4j.debug.SetVariableResponse;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
import org.eclipse.lsp4j.debug.SourcePresentationHint;
import org.eclipse.lsp4j.debug.StackFramePresentationHint;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.StackTraceResponse;
import org.eclipse.lsp4j.debug.StepInArguments;
import org.eclipse.lsp4j.debug.StepOutArguments;
import org.eclipse.lsp4j.debug.StoppedEventArguments;
import org.eclipse.lsp4j.debug.StoppedEventArgumentsReason;
import org.eclipse.lsp4j.debug.TerminateArguments;
import org.eclipse.lsp4j.debug.TerminatedEventArguments;
import org.eclipse.lsp4j.debug.Thread;
import org.eclipse.lsp4j.debug.ThreadsResponse;
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.eclipse.lsp4j.debug.VariablesResponse;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import processing.mode.java.preproc.PdePreprocessor;
import processing.mode.java.preproc.PreprocessorResult;
import processing.mode.java.preproc.TextTransform;

/**
 * Debug adapter, run with "ProcessingCLI debug". Runs the sketch in a second ProcessingCLI under JDWP
 */
final class Debug implements IDebugProtocolServer {
    private static final Pattern ADDRESS = Pattern.compile("Listening for transport dt_socket at address: (\\d+)");
    // Stepping skips these
    private static final List<String> LIBRARIES =
            List.of("java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*", "processing.*", "com.jogamp.*", "jogamp.*");
    // PApplet fields worth showing
    private static final Set<String> PROCESSING = Set.of(
            "width",
            "height",
            "mouseX",
            "mouseY",
            "pmouseX",
            "pmouseY",
            "mousePressed",
            "mouseButton",
            "key",
            "keyCode",
            "keyPressed",
            "frameCount",
            "frameRate");

    private IDebugProtocolClient client;
    private final CompletableFuture<VirtualMachine> attached = new CompletableFuture<>();
    private Process debuggee;
    private Path sketch;
    private Lines lines;
    private boolean uncaught = true, caught;
    private ExceptionRequest exceptions;
    private final Map<Long, ObjectReference> thrown = new HashMap<>();

    private record Wanted(int id, int line, String condition, String hitCondition, String logMessage) {}

    // Breakpoints by file, and their requests
    private final Map<Path, List<Wanted>> breakpoints = new HashMap<>();
    private final Map<Path, List<BreakpointRequest>> requests = new HashMap<>();
    // Where each one really is, a blank line breaks on the next code
    private final Map<Integer, Integer> placedLines = new HashMap<>();
    private final Map<BreakpointRequest, Integer> hits = new HashMap<>();
    private int nextBreakpoint = 1;
    // Conditions and logpoints run code in the sketch, so not on the event thread
    private final java.util.concurrent.ExecutorService conditions =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    java.lang.Thread.ofPlatform().daemon().factory());

    private record Watched(ObjectReference object, Field field) {}

    private final Map<String, Watched> watchable = new HashMap<>();
    private final List<ModificationWatchpointRequest> watchpoints = new ArrayList<>();
    private ThreadReference lastStopped;
    private final Map<String, List<ReferenceType>> loaded = new HashMap<>();

    // Cleared on resume
    private final Map<Integer, Object> handles = new HashMap<>();
    private int nextHandle = 1;

    private record FrameHandle(ThreadReference thread, int index) {}

    private record Locals(FrameHandle frame) {}

    private record Fields(ObjectReference object, boolean sketchOnly, boolean processingOnly) {}

    static void run() throws Exception {
        FileOutputStream protocol = new FileOutputStream(FileDescriptor.out);
        System.setOut(System.err);
        Debug server = new Debug();
        Launcher<IDebugProtocolClient> launcher = DSPLauncher.createServerLauncher(server, System.in, protocol);
        server.client = launcher.getRemoteProxy();
        launcher.startListening().get();
        System.exit(0);
    }

    @Override
    public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {
        Capabilities capabilities = new Capabilities();
        capabilities.setSupportsConfigurationDoneRequest(true);
        capabilities.setSupportsEvaluateForHovers(true);
        capabilities.setSupportsTerminateRequest(true);
        capabilities.setSupportsConditionalBreakpoints(true);
        capabilities.setSupportsHitConditionalBreakpoints(true);
        capabilities.setSupportsLogPoints(true);
        capabilities.setSupportsSetVariable(true);
        capabilities.setSupportsExceptionInfoRequest(true);
        capabilities.setSupportsDataBreakpoints(true);
        capabilities.setExceptionBreakpointFilters(new ExceptionBreakpointsFilter[] {
            filter("uncaught", "Uncaught exceptions", true), filter("caught", "Caught exceptions", false)
        });
        // After the response
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> client.initialized());
        return CompletableFuture.completedFuture(capabilities);
    }

    private static ExceptionBreakpointsFilter filter(String id, String label, boolean on) {
        ExceptionBreakpointsFilter filter = new ExceptionBreakpointsFilter();
        filter.setFilter(id);
        filter.setLabel(label);
        filter.setDefault_(on);
        return filter;
    }

    @Override
    public CompletableFuture<Void> launch(Map<String, Object> args) {
        return CompletableFuture.runAsync(() -> {
            try {
                start(Path.of((String) args.get("sketch")).toAbsolutePath().normalize());
            } catch (Exception e) {
                attached.completeExceptionally(e);
                throw new IllegalStateException(e.getMessage(), e);
            }
        });
    }

    private void start(Path sketch) throws Exception {
        this.sketch = sketch;
        lines = Lines.of(sketch);
        List<String> command = new ArrayList<>(self());
        command.add(sketch.toString());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment()
                .put("JAVA_TOOL_OPTIONS", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0");
        debuggee = builder.start();
        // Not left suspended if VS Code goes away without disconnecting
        Runtime.getRuntime().addShutdownHook(new java.lang.Thread(this::stop));

        // The agent prints its port first
        BufferedReader out =
                new BufferedReader(new InputStreamReader(debuggee.getInputStream(), StandardCharsets.UTF_8));
        int port = -1;
        for (String line; port < 0 && (line = out.readLine()) != null; ) {
            Matcher matcher = ADDRESS.matcher(line);
            if (matcher.find()) port = Integer.parseInt(matcher.group(1));
            else output(line + "\n", OutputEventArgumentsCategory.STDOUT);
        }
        forward(out, OutputEventArgumentsCategory.STDOUT);
        forward(
                new BufferedReader(new InputStreamReader(debuggee.getErrorStream(), StandardCharsets.UTF_8)),
                OutputEventArgumentsCategory.STDERR);
        if (port < 0) throw new IllegalStateException("The sketch didn't start");

        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(c -> c.name().equals("com.sun.jdi.SocketAttach"))
                .findFirst()
                .orElseThrow();
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1");
        arguments.get("port").setValue(String.valueOf(port));
        VirtualMachine vm = connector.attach(arguments);

        // Breakpoints go in as classes load
        EventRequestManager manager = vm.eventRequestManager();
        for (String source : sources()) {
            ClassPrepareRequest prepare = manager.createClassPrepareRequest();
            prepare.addSourceNameFilter(source);
            prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            prepare.enable();
        }
        java.lang.Thread.ofPlatform().daemon().start(() -> events(vm));
        synchronized (this) {
            setExceptions(vm);
        }
        attached.complete(vm);
    }

    // Packaged app, or this JVM when run from a build
    private static List<String> self() {
        String app = System.getProperty("jpackage.app-path");
        if (app != null) return List.of(app);
        return List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "org.konpeki.Main");
    }

    // Source names in the class files
    private List<String> sources() throws Exception {
        List<String> sources = new ArrayList<>(List.of(lines.className + ".java"));
        for (Path tab : Processor.javaTabs(sketch))
            sources.add(tab.getFileName().toString());
        return sources;
    }

    private void forward(BufferedReader reader, String category) {
        java.lang.Thread.ofPlatform().daemon().start(() -> {
            try {
                for (String line; (line = reader.readLine()) != null; ) {
                    if (!line.startsWith("Picked up JAVA_TOOL_OPTIONS")) output(line + "\n", category);
                }
            } catch (Exception ignored) {
                // The sketch ended
            }
        });
    }

    private void output(String text, String category) {
        OutputEventArguments event = new OutputEventArguments();
        event.setCategory(category);
        event.setOutput(text);
        client.output(event);
    }

    @Override
    public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {
        return attached.thenAccept(VirtualMachine::resume);
    }

    private void events(VirtualMachine vm) {
        try {
            while (true) {
                EventSet set = vm.eventQueue().remove();
                boolean resume = true;
                for (Event event : set) {
                    // Hit while evaluating something, the evaluation is waiting on it
                    if (event instanceof com.sun.jdi.event.LocatableEvent located
                            && Eval.evaluating.contains(located.thread())) {
                        continue;
                    }
                    switch (event) {
                        case ClassPrepareEvent prepare -> prepared(prepare.referenceType());
                        case BreakpointEvent hit -> {
                            conditions.execute(() -> hit(hit, set));
                            resume = false;
                        }
                        case ModificationWatchpointEvent changed -> {
                            stopped(
                                    changed.thread(),
                                    StoppedEventArgumentsReason.DATA_BREAKPOINT,
                                    changed.field().name() + " changed");
                            resume = false;
                        }
                        case StepEvent step -> {
                            vm.eventRequestManager().deleteEventRequest(step.request());
                            stopped(step.thread(), StoppedEventArgumentsReason.STEP, null);
                            resume = false;
                        }
                        // Caught ones only when the sketch catches them, the JDK catches plenty itself
                        case ExceptionEvent exception
                        when exception.catchLocation() != null && spot(exception.catchLocation()) == null -> {}
                        case ExceptionEvent exception -> {
                            synchronized (this) {
                                thrown.put(exception.thread().uniqueID(), exception.exception());
                            }
                            stopped(
                                    exception.thread(),
                                    StoppedEventArgumentsReason.EXCEPTION,
                                    exception.exception().referenceType().name());
                            resume = false;
                        }
                        // configurationDone resumes
                        case VMStartEvent start -> resume = false;
                        case VMDeathEvent death -> {}
                        case VMDisconnectEvent disconnect -> {
                            ended();
                            return;
                        }
                        default -> {}
                    }
                }
                if (resume) set.resume();
            }
        } catch (Exception e) {
            ended();
        }
    }

    private void ended() {
        ExitedEventArguments exited = new ExitedEventArguments();
        try {
            exited.setExitCode(debuggee.waitFor(2, TimeUnit.SECONDS) ? debuggee.exitValue() : 0);
        } catch (InterruptedException e) {
            exited.setExitCode(0);
        }
        client.exited(exited);
        client.terminated(new TerminatedEventArguments());
    }

    // Hit counts, conditions and logpoints decide whether it stops
    private void hit(BreakpointEvent event, EventSet set) {
        BreakpointRequest request = (BreakpointRequest) event.request();
        Wanted wanted = (Wanted) request.getProperty("wanted");
        int count;
        synchronized (this) {
            count = hits.merge(request, 1, Integer::sum);
        }
        Eval.evaluating.add(event.thread());
        try {
            if (wanted != null && !hitCount(wanted.hitCondition(), count)) {
                set.resume();
                return;
            }
            Eval eval = new Eval(event.thread(), 0, lines.className);
            if (wanted != null
                    && wanted.condition() != null
                    && !wanted.condition().isBlank()) {
                Value result = eval.eval(wanted.condition());
                if (!(result instanceof com.sun.jdi.BooleanValue b) || !b.value()) {
                    set.resume();
                    return;
                }
            }
            if (wanted != null && wanted.logMessage() != null) {
                output(interpolate(eval, wanted.logMessage()) + "\n", OutputEventArgumentsCategory.CONSOLE);
                set.resume();
                return;
            }
        } catch (Eval.Failure e) {
            // A broken condition stops, so it can be fixed
            output("Breakpoint condition: " + e.getMessage() + "\n", OutputEventArgumentsCategory.STDERR);
        } finally {
            Eval.evaluating.remove(event.thread());
        }
        stopped(event.thread(), StoppedEventArgumentsReason.BREAKPOINT, null);
    }

    // 5, >5, >=5 or %5, like other Java debuggers
    private static boolean hitCount(String condition, int count) {
        if (condition == null || condition.isBlank()) return true;
        Matcher m = Pattern.compile("\\s*(>=|<=|>|<|==|%)?\\s*(\\d+)\\s*").matcher(condition);
        if (!m.matches()) return true;
        int n = Integer.parseInt(m.group(2));
        String op = m.group(1) == null ? "==" : m.group(1);
        return switch (op) {
            case ">=" -> count >= n;
            case "<=" -> count <= n;
            case ">" -> count > n;
            case "<" -> count < n;
            case "%" -> n > 0 && count % n == 0;
            default -> count == n;
        };
    }

    // x is {x}, as a logpoint prints it
    private static String interpolate(Eval eval, String message) {
        Matcher m = Pattern.compile("\\{([^}]*)}").matcher(message);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String text;
            try {
                text = eval.text(eval.eval(m.group(1)));
            } catch (Eval.Failure e) {
                text = "{" + e.getMessage() + "}";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        return m.appendTail(out).toString();
    }

    private synchronized void stopped(ThreadReference thread, String reason, String description) {
        handles.clear();
        lastStopped = thread;
        StoppedEventArguments event = new StoppedEventArguments();
        event.setReason(reason);
        event.setThreadId((int) thread.uniqueID());
        event.setAllThreadsStopped(true);
        if (description != null) event.setText(description);
        if (description != null) event.setDescription("Paused on " + description);
        client.stopped(event);
    }

    @Override
    public synchronized CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments args) {
        Path file = Path.of(args.getSource().getPath()).toAbsolutePath().normalize();
        List<Wanted> wanted = new ArrayList<>();
        for (SourceBreakpoint b : args.getBreakpoints()) {
            wanted.add(new Wanted(
                    nextBreakpoint++, b.getLine(), b.getCondition(), b.getHitCondition(), b.getLogMessage()));
        }
        breakpoints.put(file, wanted);
        if (attached.isDone() && !attached.isCompletedExceptionally()) place(file, false);

        List<Breakpoint> result = new ArrayList<>();
        for (Wanted w : wanted) result.add(breakpoint(w, file));
        SetBreakpointsResponse response = new SetBreakpointsResponse();
        response.setBreakpoints(result.toArray(Breakpoint[]::new));
        return CompletableFuture.completedFuture(response);
    }

    private record Target(String source, int line) {}

    // Compiled source and line for a sketch line
    private Target target(Path file, int line) {
        String name = file.getFileName().toString();
        if (name.endsWith(".java")) return new Target(name, line);
        Integer javaLine = lines.javaLine(file, line);
        return javaLine == null ? null : new Target(lines.className + ".java", javaLine);
    }

    private synchronized void prepared(ReferenceType type) {
        try {
            loaded.computeIfAbsent(type.sourceName(), s -> new ArrayList<>()).add(type);
        } catch (AbsentInformationException e) {
            return;
        }
        for (Path file : breakpoints.keySet()) place(file, true);
    }

    private Breakpoint breakpoint(Wanted wanted, Path file) {
        Breakpoint breakpoint = new Breakpoint();
        breakpoint.setId(wanted.id());
        breakpoint.setLine(placedLines.getOrDefault(wanted.id(), wanted.line()));
        breakpoint.setVerified(lines == null || target(file, wanted.line()) != null);
        Source source = new Source();
        source.setPath(file.toString());
        breakpoint.setSource(source);
        return breakpoint;
    }

    // tell: the editor already has these, update it where they moved
    private void place(Path file, boolean tell) {
        VirtualMachine vm = attached.join();
        EventRequestManager manager = vm.eventRequestManager();
        List<BreakpointRequest> old = requests.remove(file);
        if (old != null) manager.deleteEventRequests(old);
        List<BreakpointRequest> placed = new ArrayList<>();
        for (Wanted wanted : breakpoints.get(file)) {
            int line = wanted.line();
            Target target = target(file, line);
            if (target == null) continue;
            for (ReferenceType type : loaded.getOrDefault(target.source(), List.of())) {
                // Blank lines break on the next code
                for (int l = target.line(); l < target.line() + 5; l++) {
                    List<Location> locations;
                    try {
                        locations = type.locationsOfLine(l);
                    } catch (AbsentInformationException e) {
                        break;
                    }
                    if (locations.isEmpty()) continue;
                    BreakpointRequest request = manager.createBreakpointRequest(locations.getFirst());
                    request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                    request.putProperty("wanted", wanted);
                    request.enable();
                    placed.add(request);
                    Lines.Spot spot = spot(locations.getFirst());
                    if (spot != null && spot.line() != placedLines.getOrDefault(wanted.id(), line)) {
                        placedLines.put(wanted.id(), spot.line());
                        if (tell) {
                            BreakpointEventArguments moved = new BreakpointEventArguments();
                            moved.setReason(BreakpointEventArgumentsReason.CHANGED);
                            moved.setBreakpoint(breakpoint(wanted, file));
                            client.breakpoint(moved);
                        }
                    }
                    break;
                }
            }
        }
        requests.put(file, placed);
    }

    @Override
    public synchronized CompletableFuture<SetExceptionBreakpointsResponse> setExceptionBreakpoints(
            SetExceptionBreakpointsArguments args) {
        uncaught = List.of(args.getFilters()).contains("uncaught");
        caught = List.of(args.getFilters()).contains("caught");
        if (attached.isDone() && !attached.isCompletedExceptionally()) setExceptions(attached.join());
        return CompletableFuture.completedFuture(new SetExceptionBreakpointsResponse());
    }

    private void setExceptions(VirtualMachine vm) {
        if (exceptions != null) vm.eventRequestManager().deleteEventRequest(exceptions);
        exceptions = null;
        if (!uncaught && !caught) return;
        exceptions = vm.eventRequestManager().createExceptionRequest(null, caught, uncaught);
        exceptions.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        exceptions.enable();
    }

    @Override
    public synchronized CompletableFuture<ContinueResponse> continue_(ContinueArguments args) {
        handles.clear();
        attached.join().resume();
        ContinueResponse response = new ContinueResponse();
        response.setAllThreadsContinued(true);
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public CompletableFuture<Void> next(NextArguments args) {
        return step(args.getThreadId(), StepRequest.STEP_OVER);
    }

    @Override
    public CompletableFuture<Void> stepIn(StepInArguments args) {
        return step(args.getThreadId(), StepRequest.STEP_INTO);
    }

    @Override
    public CompletableFuture<Void> stepOut(StepOutArguments args) {
        return step(args.getThreadId(), StepRequest.STEP_OUT);
    }

    private synchronized CompletableFuture<Void> step(int threadId, int depth) {
        VirtualMachine vm = attached.join();
        ThreadReference thread = thread(threadId);
        EventRequestManager manager = vm.eventRequestManager();
        manager.deleteEventRequests(manager.stepRequests().stream()
                .filter(r -> r.thread().equals(thread))
                .toList());
        StepRequest request = manager.createStepRequest(thread, StepRequest.STEP_LINE, depth);
        for (String library : LIBRARIES) request.addClassExclusionFilter(library);
        request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        request.addCountFilter(1);
        request.enable();
        handles.clear();
        vm.resume();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> pause(PauseArguments args) {
        VirtualMachine vm = attached.join();
        vm.suspend();
        stopped(thread(args.getThreadId()), StoppedEventArgumentsReason.PAUSE, null);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> disconnect(DisconnectArguments args) {
        stop();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> terminate(TerminateArguments args) {
        stop();
        return CompletableFuture.completedFuture(null);
    }

    private void stop() {
        if (debuggee == null) return;
        // The launcher may run the JVM as a child
        debuggee.descendants().forEach(ProcessHandle::destroyForcibly);
        debuggee.destroyForcibly();
    }

    @Override
    public CompletableFuture<ThreadsResponse> threads() {
        List<Thread> threads = new ArrayList<>();
        if (attached.isDone() && !attached.isCompletedExceptionally()) {
            for (ThreadReference t : attached.join().allThreads()) {
                Thread thread = new Thread();
                thread.setId((int) t.uniqueID());
                thread.setName(t.name());
                threads.add(thread);
            }
        }
        ThreadsResponse response = new ThreadsResponse();
        response.setThreads(threads.toArray(Thread[]::new));
        return CompletableFuture.completedFuture(response);
    }

    private ThreadReference thread(int id) {
        return attached.join().allThreads().stream()
                .filter(t -> (int) t.uniqueID() == id)
                .findFirst()
                .orElseThrow();
    }

    @Override
    public synchronized CompletableFuture<StackTraceResponse> stackTrace(StackTraceArguments args) {
        ThreadReference thread = thread(args.getThreadId());
        List<org.eclipse.lsp4j.debug.StackFrame> frames = new ArrayList<>();
        try {
            List<StackFrame> stack = thread.frames();
            for (int i = 0; i < stack.size(); i++) {
                Location location = stack.get(i).location();
                org.eclipse.lsp4j.debug.StackFrame frame = new org.eclipse.lsp4j.debug.StackFrame();
                frame.setId(handle(new FrameHandle(thread, i)));
                String type = location.declaringType().name();
                String method = location.method().name();
                frame.setName(
                        type.equals(lines.className)
                                ? method
                                : type.substring(type.lastIndexOf('.') + 1).replace(lines.className + "$", "") + "."
                                        + method);
                Lines.Spot spot = spot(location);
                if (spot != null) {
                    Source source = new Source();
                    source.setName(spot.file().getFileName().toString());
                    source.setPath(spot.file().toString());
                    frame.setSource(source);
                    frame.setLine(spot.line());
                } else {
                    // Processing's and Java's own, opened from the bundled sources
                    frame.setPresentationHint(StackFramePresentationHint.SUBTLE);
                    Path file = Sources.file(type);
                    if (file != null && location.lineNumber() > 0) {
                        Source source = new Source();
                        source.setName(file.getFileName().toString());
                        source.setPath(file.toString());
                        source.setPresentationHint(SourcePresentationHint.DEEMPHASIZE);
                        frame.setSource(source);
                    }
                    frame.setLine(Math.max(location.lineNumber(), 0));
                }
                frame.setColumn(0);
                frames.add(frame);
            }
        } catch (IncompatibleThreadStateException e) {
            // Running, no frames
        }
        StackTraceResponse response = new StackTraceResponse();
        response.setStackFrames(frames.toArray(org.eclipse.lsp4j.debug.StackFrame[]::new));
        response.setTotalFrames(frames.size());
        return CompletableFuture.completedFuture(response);
    }

    // Sketch file and 1-based line, null for library code
    private Lines.Spot spot(Location location) {
        try {
            String source = location.sourceName();
            if (source.equals(lines.className + ".java")) return lines.pde(location.lineNumber());
            Path tab = sketch.resolve(source);
            if (source.endsWith(".java") && Files.isRegularFile(tab)) {
                return new Lines.Spot(tab, location.lineNumber());
            }
        } catch (AbsentInformationException ignored) {
            // No debug info
        }
        return null;
    }

    @Override
    public synchronized CompletableFuture<ScopesResponse> scopes(ScopesArguments args) {
        List<Scope> scopes = new ArrayList<>();
        if (handles.get(args.getFrameId()) instanceof FrameHandle frame) {
            scopes.add(scope("Locals", handle(new Locals(frame))));
            ObjectReference self = frame(frame).thisObject();
            // Inner classes reach the sketch through this$0
            ObjectReference sketchObject = self;
            if (self != null && !isSketch(self)) {
                scopes.add(scope("this", handle(new Fields(self, false, false))));
                sketchObject = outer(self);
            }
            if (sketchObject != null) {
                scopes.add(scope("Globals", handle(new Fields(sketchObject, true, false))));
                scopes.add(scope("Processing", handle(new Fields(sketchObject, false, true))));
            }
        }
        ScopesResponse response = new ScopesResponse();
        response.setScopes(scopes.toArray(Scope[]::new));
        return CompletableFuture.completedFuture(response);
    }

    private static Scope scope(String name, int reference) {
        Scope scope = new Scope();
        scope.setName(name);
        scope.setVariablesReference(reference);
        return scope;
    }

    private boolean isSketch(ObjectReference object) {
        return object.referenceType().name().equals(lines.className);
    }

    private ObjectReference outer(ObjectReference object) {
        Field outer = object.referenceType().fieldByName("this$0");
        return outer != null && object.getValue(outer) instanceof ObjectReference o && isSketch(o) ? o : null;
    }

    private StackFrame frame(FrameHandle handle) {
        try {
            return handle.thread().frame(handle.index());
        } catch (IncompatibleThreadStateException e) {
            throw new IllegalStateException("The sketch is running");
        }
    }

    @Override
    public synchronized CompletableFuture<VariablesResponse> variables(VariablesArguments args) {
        List<Variable> variables = new ArrayList<>();
        switch (handles.get(args.getVariablesReference())) {
            case Locals locals -> {
                StackFrame frame = frame(locals.frame());
                try {
                    for (LocalVariable local : frame.visibleVariables()) {
                        variables.add(variable(local.name(), frame.getValue(local)));
                    }
                } catch (AbsentInformationException e) {
                    // Compiled without -g
                }
            }
            case Fields fields -> {
                ReferenceType type = fields.object().referenceType();
                for (Field field : type.allFields()) {
                    if (field.isStatic() || field.isSynthetic()) continue;
                    boolean declaredBySketch = field.declaringType().name().equals(lines.className);
                    if (fields.sketchOnly() && !declaredBySketch) continue;
                    if (fields.processingOnly() && (declaredBySketch || !PROCESSING.contains(field.name()))) continue;
                    variables.add(variable(field.name(), fields.object().getValue(field)));
                }
            }
            case ArrayReference array -> {
                int count = Math.min(array.length(), 1000);
                List<Value> values = array.getValues(0, count);
                for (int i = 0; i < count; i++) variables.add(variable("[" + i + "]", values.get(i)));
            }
            case null, default -> {}
        }
        VariablesResponse response = new VariablesResponse();
        response.setVariables(variables.toArray(Variable[]::new));
        return CompletableFuture.completedFuture(response);
    }

    private Variable variable(String name, Value value) {
        Variable variable = new Variable();
        variable.setName(name);
        variable.setValue(describe(value));
        variable.setVariablesReference(expandable(value) ? handle(children(value)) : 0);
        if (value != null) variable.setType(shortType(value.type().name()));
        return variable;
    }

    private static boolean expandable(Value value) {
        return value instanceof ObjectReference && !(value instanceof StringReference);
    }

    private Object children(Value value) {
        return value instanceof ArrayReference array ? array : new Fields((ObjectReference) value, false, false);
    }

    private String describe(Value value) {
        return switch (value) {
            case null -> "null";
            case StringReference s -> "\"" + s.value() + "\"";
            case CharValue c -> "'" + escaped(c.value()) + "'";
            case PrimitiveValue p -> p.toString();
            case ArrayReference a -> shortType(a.type().name()).replaceFirst("\\[]", "[" + a.length() + "]");
            case ObjectReference o -> summary(o);
            default -> value.toString();
        };
    }

    // key starts as 0, which a raw character would garble
    private static String escaped(char c) {
        return switch (c) {
            case 0 -> "\\0";
            case '\n' -> "\\n";
            case '\t' -> "\\t";
            case '\r' -> "\\r";
            default -> c < 0x20 || c == 0x7f ? String.format("\\u%04x", (int) c) : String.valueOf(c);
        };
    }

    // Like PVector(x=1.0, y=2.0, z=0.0)
    private String summary(ObjectReference object) {
        String type = shortType(object.referenceType().name());
        List<String> parts = new ArrayList<>();
        for (Field field : object.referenceType().allFields()) {
            if (field.isStatic() || field.isSynthetic()) continue;
            Value value = object.getValue(field);
            if (!(value instanceof PrimitiveValue || value instanceof StringReference)) continue;
            if (object.referenceType().name().startsWith("java.lang.")
                    && field.name().equals("value")) {
                return describe(value);
            }
            if (parts.size() == 4) {
                parts.add("…");
                break;
            }
            parts.add(field.name() + "=" + describe(value));
        }
        return parts.isEmpty() ? type : type + "(" + String.join(", ", parts) + ")";
    }

    private String shortType(String name) {
        return name.substring(name.lastIndexOf('.') + 1).replace(lines.className + "$", "");
    }

    private int handle(Object target) {
        int id = nextHandle++;
        handles.put(id, target);
        return id;
    }

    @Override
    public synchronized CompletableFuture<EvaluateResponse> evaluate(EvaluateArguments args) {
        if (args.getFrameId() == null || !(handles.get(args.getFrameId()) instanceof FrameHandle handle)) {
            return failed("Pause the sketch first");
        }
        try {
            Value value = new Eval(handle.thread(), handle.index(), lines.className).eval(args.getExpression());
            EvaluateResponse response = new EvaluateResponse();
            response.setResult(describe(value));
            if (value != null) response.setType(shortType(value.type().name()));
            response.setVariablesReference(expandable(value) ? handle(children(value)) : 0);
            return CompletableFuture.completedFuture(response);
        } catch (Eval.Failure e) {
            return failed(e.getMessage());
        }
    }

    private static <T> CompletableFuture<T> failed(String message) {
        return CompletableFuture.failedFuture(
                new ResponseErrorException(new ResponseError(ResponseErrorCode.InvalidParams, message, null)));
    }

    // From the variables view
    @Override
    public synchronized CompletableFuture<SetVariableResponse> setVariable(SetVariableArguments args) {
        Object container = handles.get(args.getVariablesReference());
        String name = args.getName();
        try {
            Value value;
            switch (container) {
                case Locals locals ->
                    value = new Eval(locals.frame().thread(), locals.frame().index(), lines.className)
                            .assign(name, args.getValue());
                case Fields fields -> {
                    Field field = fields.object().referenceType().fieldByName(name);
                    if (field == null || lastStopped == null) return failed("Can't set " + name);
                    Eval eval = new Eval(lastStopped, 0, lines.className);
                    value = eval.fit(eval.eval(args.getValue()), field.type());
                    fields.object().setValue(field, value);
                }
                case ArrayReference array -> {
                    if (lastStopped == null) return failed("Can't set " + name);
                    Eval eval = new Eval(lastStopped, 0, lines.className);
                    value = eval.fit(
                            eval.eval(args.getValue()), ((com.sun.jdi.ArrayType) array.type()).componentType());
                    array.setValue(Integer.parseInt(name.replaceAll("[\\[\\]]", "")), value);
                }
                case null, default -> {
                    return failed("Can't set " + name);
                }
            }
            SetVariableResponse response = new SetVariableResponse();
            response.setValue(describe(value));
            if (value != null) response.setType(shortType(value.type().name()));
            response.setVariablesReference(expandable(value) ? handle(children(value)) : 0);
            return CompletableFuture.completedFuture(response);
        } catch (Eval.Failure e) {
            return failed(e.getMessage());
        } catch (Exception e) {
            return failed("Can't set " + name + ": " + e.getMessage());
        }
    }

    @Override
    public synchronized CompletableFuture<ExceptionInfoResponse> exceptionInfo(ExceptionInfoArguments args) {
        ObjectReference exception = thrown.get((long) args.getThreadId());
        if (exception == null) {
            for (ThreadReference t : attached.join().allThreads()) {
                if ((int) t.uniqueID() == args.getThreadId()) exception = thrown.get(t.uniqueID());
            }
        }
        if (exception == null) return failed("No exception");
        ExceptionInfoResponse response = new ExceptionInfoResponse();
        response.setExceptionId(exception.referenceType().name());
        Field message = exception.referenceType().fieldByName("detailMessage");
        Value text = message == null ? null : exception.getValue(message);
        response.setDescription(
                text instanceof StringReference s
                        ? s.value()
                        : shortType(exception.referenceType().name()));
        response.setBreakMode(caught ? ExceptionBreakMode.ALWAYS : ExceptionBreakMode.UNHANDLED);
        return CompletableFuture.completedFuture(response);
    }

    // Break when a field of an object changes
    @Override
    public synchronized CompletableFuture<DataBreakpointInfoResponse> dataBreakpointInfo(
            DataBreakpointInfoArguments args) {
        DataBreakpointInfoResponse response = new DataBreakpointInfoResponse();
        Object container = args.getVariablesReference() == null ? null : handles.get(args.getVariablesReference());
        if (container instanceof Fields fields
                && fields.object().referenceType().fieldByName(args.getName()) instanceof Field field) {
            String id = fields.object().uniqueID() + ":" + field.name();
            watchable.put(id, new Watched(fields.object(), field));
            response.setDataId(id);
            response.setDescription(args.getName() + " changes");
            response.setAccessTypes(new DataBreakpointAccessType[] {DataBreakpointAccessType.WRITE});
        } else {
            response.setDescription("Only fields can be watched");
        }
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public synchronized CompletableFuture<SetDataBreakpointsResponse> setDataBreakpoints(
            SetDataBreakpointsArguments args) {
        EventRequestManager manager = attached.join().eventRequestManager();
        manager.deleteEventRequests(watchpoints);
        watchpoints.clear();
        List<Breakpoint> result = new ArrayList<>();
        for (DataBreakpoint wanted : args.getBreakpoints()) {
            Watched watched = watchable.get(wanted.getDataId());
            Breakpoint breakpoint = new Breakpoint();
            breakpoint.setVerified(watched != null);
            if (watched != null) {
                ModificationWatchpointRequest request = manager.createModificationWatchpointRequest(watched.field());
                request.addInstanceFilter(watched.object());
                request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                request.enable();
                watchpoints.add(request);
            }
            result.add(breakpoint);
        }
        SetDataBreakpointsResponse response = new SetDataBreakpointsResponse();
        response.setBreakpoints(result.toArray(Breakpoint[]::new));
        return CompletableFuture.completedFuture(response);
    }

    /**
     * Lines between the tabs and the preprocessed Java
     */
    static final class Lines {
        record Spot(Path file, int line) {}

        private record Tab(Path file, int start, String text) {}

        final String className;
        private final List<Tab> tabs = new ArrayList<>();
        private String combined;
        private String java;
        private TextTransform.OffsetMapper mapper;

        private Lines(String className) {
            this.className = className;
        }

        static Lines of(Path sketch) throws Exception {
            Lines lines = new Lines(Processor.sketchName(sketch));
            StringBuilder combined = new StringBuilder();
            for (Path file : Processor.tabs(sketch)) {
                String text = Files.readString(file);
                lines.tabs.add(new Tab(file.toAbsolutePath().normalize(), combined.length(), text));
                // Same as Processor.source()
                combined.append(text).append('\n');
            }
            lines.combined = combined.toString();
            PreprocessorResult result = PdePreprocessor.builderFor(lines.className)
                    .setTabSize(2)
                    .build()
                    .write(new StringWriter(), lines.combined);
            TextTransform transform = new TextTransform(lines.combined);
            transform.addAll(result.getEdits());
            lines.java = transform.apply();
            lines.mapper = transform.getMapper();
            return lines;
        }

        // 1-based lines both ways
        Integer javaLine(Path file, int line) {
            for (Tab tab : tabs) {
                if (!tab.file().equals(file)) continue;
                int offset = lineStart(tab.text(), line - 1);
                if (offset < 0) return null;
                // First code on the line, the mapping skips indentation
                while (offset < tab.text().length() && " \t".indexOf(tab.text().charAt(offset)) >= 0) offset++;
                return lineOf(java, mapper.getOutputOffset(tab.start() + offset)) + 1;
            }
            return null;
        }

        Spot pde(int javaLine) {
            int offset = lineStart(java, javaLine - 1);
            if (offset < 0) return null;
            while (offset < java.length() && " \t".indexOf(java.charAt(offset)) >= 0) offset++;
            int input = mapper.getInputOffset(offset);
            Tab tab = tabs.getFirst();
            for (Tab t : tabs) if (t.start() <= input) tab = t;
            return new Spot(
                    tab.file(),
                    lineOf(tab.text(), Math.min(input - tab.start(), tab.text().length())) + 1);
        }

        record Place(Path file, int line, int column, String text) {}

        // Tab, line, column and line text for a Java offset
        Place place(long javaOffset) {
            int input = mapper.getInputOffset((int) Math.max(0, Math.min(javaOffset, java.length())));
            Tab tab = tabs.getFirst();
            for (Tab t : tabs) if (t.start() <= input) tab = t;
            String text = tab.text();
            int local = Math.max(0, Math.min(input - tab.start(), text.length()));
            int start = text.lastIndexOf('\n', local - 1) + 1;
            int end = text.indexOf('\n', local);
            return new Place(
                    tab.file(),
                    lineOf(text, local) + 1,
                    local - start + 1,
                    text.substring(start, end < 0 ? text.length() : end));
        }

        private static int lineStart(String text, int line) {
            int offset = 0;
            for (int i = 0; i < line; i++) {
                offset = text.indexOf('\n', offset) + 1;
                if (offset == 0) return -1;
            }
            return offset;
        }

        private static int lineOf(String text, int offset) {
            int line = 0;
            for (int i = 0; i < Math.min(offset, text.length()); i++) if (text.charAt(i) == '\n') line++;
            return line;
        }
    }
}
