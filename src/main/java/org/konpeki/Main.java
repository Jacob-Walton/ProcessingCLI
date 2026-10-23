package org.konpeki;

import java.nio.file.Path;
import processing.utils.SketchException;

public class Main {
    static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.printf("Expected 1 arg, got %d arg(s)", args.length);
            System.exit(1);
        }

        Path dir = Path.of(args[0]);
        Processor processor = new Processor(dir);

        try {
            String code = processor.process();
            Runner.run(processor.sketchName(), code, dir);
        } catch (SketchException e) {
            System.err.println("Sketch error: " + e.getMessage());
            System.exit(1);
        }
    }
}
