package org.konpeki;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import processing.utils.SketchException;

@Command(
        name = "ProcessingCLI",
        mixinStandardHelpOptions = true,
        version = "ProcessingCLI 1.0",
        description = "Preprocesses, compiles, and runs a Processing sketch.")
public class Main implements Callable<Integer> {

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

    private boolean launched;

    static void main(String[] args) {
        restoreJarTime();

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

    @Override
    public Integer call() throws Exception {
        if (bench) Bench.enable();
        Bench.mark("main");

        Runner.cleanUpInBackground();

        Processor processor = new Processor(sketch);
        String name = processor.sketchName();

        try {
            String source = processor.source();

            if (emitJava) {
                System.out.println(processor.process(source));
                return 0;
            }

            List<Path> libs = Libraries.forSketch(sketch, source, sketchbook);
            Path classes = Runner.cacheDir(sketch);
            String stamp = Runner.stamp(name, source, libs);
            if (noCache || !Runner.isCompiled(classes, stamp)) {
                String code = processor.process(source);
                Bench.mark("processed");
                Runner.compile(name, code, classes, stamp, libs);
                Bench.mark("compiled");
            } else {
                Bench.mark("cache-hit");
            }

            Runner.launch(name, classes, sketch, libs, present, sketchArgs);
            Bench.mark("launched");
            launched = true;
            return 0;
        } catch (SketchException e) {
            System.err.println("Sketch error: " + e.getMessage());
            return 1;
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }
}
