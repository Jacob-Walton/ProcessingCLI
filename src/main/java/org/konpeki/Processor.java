package org.konpeki;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import processing.mode.java.preproc.PdePreprocessor;

/**
 * Pre-process a .pde file.
 */
public class Processor {
    private final Path dir;
    private final Path mainFile;
    private final List<Path> paths;

    public Processor(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            fail("Expected a folder at %s", dir);
        }
        this.dir = dir;

        this.mainFile = dir.resolve(dir.getFileName() + ".pde");
        if (!Files.isRegularFile(mainFile)) {
            fail("Expected a sketch file at %s", mainFile);
        }

        try (Stream<Path> s = Files.list(dir)) {
            this.paths = Stream.concat(
                            Stream.of(mainFile),
                            s.filter(p -> p.toString().endsWith(".pde") && !p.equals(mainFile))
                                    .sorted())
                    .toList();
        }
    }

    public String sketchName() {
        String raw = dir.getFileName().toString();
        StringBuilder sb = new StringBuilder();
        raw.codePoints().forEach(cp -> sb.appendCodePoint(Character.isJavaIdentifierPart(cp) ? cp : '_'));
        if (sb.isEmpty() || !Character.isJavaIdentifierStart(sb.codePointAt(0))) {
            sb.insert(0, '_');
        }
        String name = sb.toString();
        return SourceVersion.isName(name) ? name : name + "_";
    }

    public String source() throws IOException {
        StringBuilder source = new StringBuilder();
        for (Path p : paths) {
            source.append(Files.readString(p)).append('\n');
        }
        return source.toString();
    }

    public String process(String source) throws Exception {
        StringWriter out = new StringWriter();
        // Otherwise it reads editor.tabs.size from Processing's preferences.txt, which may not exist
        PdePreprocessor.builderFor(sketchName()).setTabSize(2).build().write(out, source);
        return out.toString();
    }

    static void fail(String format, Object... values) {
        System.err.println(format.formatted(values));
        System.exit(1);
    }
}
