package org.konpeki;

import java.nio.file.Path;
import processing.utils.SketchException;

public class Main {
    static void main(String[] args) throws Exception {
        Bench.mark("main");

        if (args.length != 1) {
            System.out.printf("Expected 1 arg, got %d arg(s)", args.length);
            System.exit(1);
        }

        // Warm up AWT + Processing
        Thread.ofPlatform().daemon().start(() -> {
            try {
                java.awt.Toolkit.getDefaultToolkit();
                Class.forName("processing.core.PApplet");
            } catch (Throwable ignored) {}
        });

        Runner.cleanUpInBackground();

        Path dir = Path.of(args[0]);
        Processor processor = new Processor(dir);
        String name = processor.sketchName();

        try {
            String source = processor.source();
            Path classes = Runner.cacheDir(dir);
            String stamp = Runner.stamp(name, source);
            if (!Runner.isCompiled(classes, stamp)) {
                String code = processor.process(source);
                Bench.mark("processed");
                Runner.compile(name, code, classes, stamp);
                Bench.mark("compiled");
            } else {
                Bench.mark("cache-hit");
            }
            Runner.launch(name, classes, dir);
            Bench.mark("launched");
        } catch (SketchException e) {
            System.err.println("Sketch error: " + e.getMessage());
            System.exit(1);
        }
    }
}
