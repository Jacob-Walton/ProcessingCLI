package org.konpeki;

import java.util.Arrays;
import java.util.stream.Stream;
import processing.core.PApplet;

/**
 * Main class of an exported sketch, see Export
 */
public final class Exported {
    private Exported() {}

    public static void main(String[] args) {
        String[] sketchArgs = Stream.concat(
                        Stream.of(
                                "--sketch-path=" + System.getProperty("processingcli.sketchPath"),
                                System.getProperty("processingcli.sketch")),
                        Arrays.stream(args))
                .toArray(String[]::new);
        PApplet.main(sketchArgs);
    }
}
