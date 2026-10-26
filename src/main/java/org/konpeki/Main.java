package org.konpeki;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import processing.utils.SketchException;

@Command(
        name = "ProcessingCLI",
        mixinStandardHelpOptions = true,
        versionProvider = Main.Version.class,
        description = "Preprocesses, compiles, and runs a Processing sketch.")
public class Main implements Callable<Integer> {

    // jpackage sets this from the build's version
    static class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"ProcessingCLI " + System.getProperty("jpackage.app-version", "dev")};
        }
    }

    @Parameters(index = "0", paramLabel = "SKETCH", description = "Sketch folder (must contain <folder name>.pde).")
    Path sketch;

    @Parameters(
            index = "1..*",
            paramLabel = "ARGS",
            description = "Arguments passed to the sketch (its args[]). Put -- before any that start with '-'.")
    List<String> sketchArgs = new ArrayList<>();

    @Option(names = "--present", description = "Run full screen.")
    boolean present;

    @Option(names = "--emit-java", description = "Print the preprocessed Java source and exit.")
    boolean emitJava;

    @Option(names = "--no-cache", description = "Recompile even if a cached build exists.")
    boolean noCache;

    @Option(
            names = "--sketchbook",
            paramLabel = "DIR",
            description = "Sketchbook folder for contributed libraries (default: Processing's own setting).")
    Path sketchbook;

    @Option(names = "--bench", description = "Print startup timings and exit after the first frame.")
    boolean bench;

    @Option(names = "--preview", hidden = true, paramLabel = "PORT/TOKEN")
    String preview;

    private static final Pattern OPENGL = Pattern.compile("\\b(P2D|P3D|OPENGL)\\b");

    private boolean launched;

    static void main(String[] args) throws Exception {
        restoreJarTime();

        if (args.length > 0 && args[0].equals("lsp")) {
            Lsp.run();
            return;
        }

        // Warm up AWT + Processing
        Thread.ofPlatform().daemon().start(() -> {
            try {
                java.awt.Toolkit.getDefaultToolkit();
                Class.forName("processing.core.PApplet");
            } catch (Throwable ignored) {
            }
        });

        Main app = new Main();
        int code = new CommandLine(app).execute(args);

        // The preview keeps this JVM and reloads the sketch in it on each save
        if (app.preview != null && code == 0) {
            Preview.serveReloads(app::runSketch);
        }
        // On a successful launch, leave the JVM running for the sketch window
        if (code != 0 || !app.launched) {
            System.exit(code);
        }
    }

    // AOT cache only loads if the jar mtime matches the build
    private static void restoreJarTime() {
        Long time = Long.getLong("processingcli.jarTime");
        if (time == null) return;
        try {
            Path jar = Path.of(Main.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            if (Files.getLastModifiedTime(jar).toMillis() != time) {
                Files.setLastModifiedTime(jar, FileTime.fromMillis(time));
            }
        } catch (Exception ignored) {
        }
    }

    // Probing OpenGL is most of a P2D/P3D launch, so start it while the sketch compiles
    private static void warmUpOpenGL(String source) {
        if (System.getProperty("os.name").startsWith("Mac")
                || !OPENGL.matcher(source).find()) return;
        Thread.ofPlatform().daemon().start(() -> {
            try {
                Class.forName("com.jogamp.opengl.GLProfile")
                        .getMethod("initSingleton")
                        .invoke(null);
            } catch (Throwable ignored) {
            }
        });
    }

    @Override
    public Integer call() throws Exception {
        if (bench) Bench.enable();
        Bench.mark("main");

        Runner.cleanUpInBackground();

        if (emitJava) {
            Processor processor = new Processor(sketch);
            System.out.println(processor.process(processor.source()));
            return 0;
        }
        if (preview != null) {
            // A compile error waits for the next save instead of ending the preview
            Preview.start(preview);
            runSketch();
            return 0;
        }
        return runSketch() ? 0 : 1;
    }

    // Compiles if the sketch changed, then launches it
    private boolean runSketch() {
        try {
            Processor processor = new Processor(sketch);
            String name = processor.sketchName();
            String source = processor.source();
            warmUpOpenGL(source);

            List<Path> libs = Libraries.forSketch(sketch, source, sketchbook);
            Path classes = Runner.cacheDir(sketch);
            String stamp = Runner.stamp(name, source, libs);
            if (noCache || !Runner.isCompiled(classes, stamp)) {
                String code = processor.process(source);
                Bench.mark("processed");
                Runner.compile(name, Preview.hideable(code), classes, stamp, libs);
                Bench.mark("compiled");
            } else {
                Bench.mark("cache-hit");
            }

            Runner.launch(name, classes, sketch, libs, present, sketchArgs);
            Bench.mark("launched");
            launched = true;
            return true;
        } catch (SketchException e) {
            System.err.println("Sketch error: " + e.getMessage());
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
        }
        Preview.failed();
        return false;
    }
}
