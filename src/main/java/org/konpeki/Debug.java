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
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.ExceptionRequest;
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
import org.eclipse.lsp4j.debug.Capabilities;
import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.ContinueArguments;
import org.eclipse.lsp4j.debug.ContinueResponse;
import org.eclipse.lsp4j.debug.DisconnectArguments;
import org.eclipse.lsp4j.debug.EvaluateArguments;
import org.eclipse.lsp4j.debug.EvaluateResponse;
import org.eclipse.lsp4j.debug.ExceptionBreakpointsFilter;
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
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsArguments;
import org.eclipse.lsp4j.debug.SetExceptionBreakpointsResponse;
import org.eclipse.lsp4j.debug.Source;
import org.eclipse.lsp4j.debug.SourceBreakpoint;
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
    private boolean uncaught = true;
    private ExceptionRequest exceptions;

    // Breakpoint lines by file, and their requests
    private final Map<Path, List<Integer>> breakpoints = new HashMap<>();
    private final Map<Path, List<BreakpointRequest>> requests = new HashMap<>();
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
        ExceptionBreakpointsFilter filter = new ExceptionBreakpointsFilter();
        filter.setFilter("uncaught");
        filter.setLabel("Uncaught exceptions");
        filter.setDefault_(true);
        capabilities.setExceptionBreakpointFilters(new ExceptionBreakpointsFilter[] {filter});
        // After the response
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> client.initialized());
        return CompletableFuture.completedFuture(capabilities);
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
                    switch (event) {
                        case ClassPrepareEvent prepare -> prepared(prepare.referenceType());
                        case BreakpointEvent hit -> {
                            stopped(hit.thread(), StoppedEventArgumentsReason.BREAKPOINT, null);
                            resume = false;
                        }
                        case StepEvent step -> {
                            vm.eventRequestManager().deleteEventRequest(step.request());
                            stopped(step.thread(), StoppedEventArgumentsReason.STEP, null);
                            resume = false;
                        }
                        case ExceptionEvent thrown -> {
                            stopped(
                                    thrown.thread(),
                                    StoppedEventArgumentsReason.EXCEPTION,
                                    thrown.exception().referenceType().name());
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

    private synchronized void stopped(ThreadReference thread, String reason, String description) {
        handles.clear();
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
        List<Integer> wanted = new ArrayList<>();
        for (SourceBreakpoint b : args.getBreakpoints()) wanted.add(b.getLine());
        breakpoints.put(file, wanted);
        if (attached.isDone() && !attached.isCompletedExceptionally()) place(file);

        List<Breakpoint> result = new ArrayList<>();
        for (int line : wanted) {
            Breakpoint breakpoint = new Breakpoint();
            breakpoint.setLine(line);
            breakpoint.setVerified(lines == null || target(file, line) != null);
            result.add(breakpoint);
        }
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
        for (Path file : breakpoints.keySet()) place(file);
    }

    private void place(Path file) {
        VirtualMachine vm = attached.join();
        EventRequestManager manager = vm.eventRequestManager();
        List<BreakpointRequest> old = requests.remove(file);
        if (old != null) manager.deleteEventRequests(old);
        List<BreakpointRequest> placed = new ArrayList<>();
        for (int line : breakpoints.get(file)) {
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
                    request.enable();
                    placed.add(request);
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
        if (attached.isDone() && !attached.isCompletedExceptionally()) setExceptions(attached.join());
        return CompletableFuture.completedFuture(new SetExceptionBreakpointsResponse());
    }

    private void setExceptions(VirtualMachine vm) {
        if (exceptions != null) vm.eventRequestManager().deleteEventRequest(exceptions);
        exceptions = null;
        if (!uncaught) return;
        exceptions = vm.eventRequestManager().createExceptionRequest(null, false, true);
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
                    // Library code
                    frame.setPresentationHint(StackFramePresentationHint.SUBTLE);
                    frame.setLine(0);
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
            case CharValue c -> "'" + c.value() + "'";
            case PrimitiveValue p -> p.toString();
            case ArrayReference a -> shortType(a.type().name()).replaceFirst("\\[]", "[" + a.length() + "]");
            case ObjectReference o -> summary(o);
            default -> value.toString();
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

    // Like ball.pos.x or balls[0]
    @Override
    public synchronized CompletableFuture<EvaluateResponse> evaluate(EvaluateArguments args) {
        Value value = null;
        boolean found = false;
        if (args.getFrameId() != null && handles.get(args.getFrameId()) instanceof FrameHandle handle) {
            String[] parts = args.getExpression().strip().split("\\.|(?=\\[)");
            StackFrame frame = frame(handle);
            try {
                LocalVariable local = frame.visibleVariableByName(parts[0]);
                if (local != null) {
                    value = frame.getValue(local);
                    found = true;
                }
            } catch (AbsentInformationException ignored) {
                // No locals
            }
            for (ObjectReference self = frame.thisObject(); !found && self != null; self = outer(self)) {
                Field field = self.referenceType().fieldByName(parts[0]);
                if (field != null) {
                    value = self.getValue(field);
                    found = true;
                }
                if (isSketch(self)) break;
            }
            for (int i = 1; found && i < parts.length; i++) {
                String part = parts[i];
                if (part.startsWith("[") && value instanceof ArrayReference array) {
                    int index = Integer.parseInt(
                            part.substring(1, part.indexOf(']')).strip());
                    value = index < array.length() ? array.getValue(index) : null;
                    found = index < array.length();
                } else if (value instanceof ObjectReference object
                        && object.referenceType().fieldByName(part) instanceof Field field) {
                    value = object.getValue(field);
                } else {
                    found = false;
                }
            }
        }
        if (!found) return CompletableFuture.failedFuture(new IllegalArgumentException("Not available"));
        EvaluateResponse response = new EvaluateResponse();
        response.setResult(describe(value));
        response.setVariablesReference(expandable(value) ? handle(children(value)) : 0);
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
