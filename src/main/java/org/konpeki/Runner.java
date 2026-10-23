package org.konpeki;

import processing.core.PApplet;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

public class Runner {
    private static final Path CACHE_ROOT = Path.of(
            System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("java.io.tmpdir")),
            "ProcessingCLI", "cache");
    private static final Duration MAX_AGE = Duration.ofDays(30);

    public static Path cacheDir(Path sketchDir) throws Exception {
        String key = sketchDir.toAbsolutePath().normalize().toString().toLowerCase();
        return CACHE_ROOT.resolve(hash(key));
    }

    public static String stamp(String sketchName, String source) throws Exception {
        return hash(sketchName + "\n" + toolStamp() + "\n" + source);
    }

    public static boolean isCompiled(Path classesDir, String stamp) {
        try {
            return Files.readString(classesDir.resolve(".ok")).equals(stamp);
        } catch (IOException e) {
            return false;
        }
    }

    public static void compile(String className, String javaSource, Path classesDir, String stamp) throws Exception {
        deleteRecursively(classesDir);
        Files.createDirectories(classesDir);
        Path file = classesDir.resolve(className + ".java");
        Files.writeString(file, javaSource);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java compiler available: run this with a JDK, not a JRE");
        }
        int result = compiler.run(null, null, null,
                "-cp", System.getProperty("java.class.path"),
                "-d", classesDir.toString(),
                "-proc:none",
                "-implicit:none",
                "-nowarn",
                file.toString());
        if (result != 0) {
            throw new IllegalStateException("Sketch failed to compile (see errors above)");
        }
        Files.writeString(classesDir.resolve(".ok"), stamp);
    }

    public static void launch(String className, Path classesDir, Path sketchDir) throws Exception {
        // Mark as recently used
        Files.setLastModifiedTime(classesDir.resolve(".ok"), FileTime.from(Instant.now()));

        // Left open on purpose
        URLClassLoader loader = new URLClassLoader(
                new URL[]{classesDir.toUri().toURL()}, Runner.class.getClassLoader());
        PApplet sketch = (PApplet) loader.loadClass(className).getDeclaredConstructor().newInstance();
        if (Bench.ENABLED) {
            sketch.registerMethod("post", new Bench.FirstFrame());
        }
        PApplet.runSketch(new String[]{
                "--sketch-path=" + sketchDir.toAbsolutePath(),
                className
        }, sketch);
    }

    public static void cleanUpInBackground() {
        Thread.ofPlatform().daemon().start(() -> {
            try {
                Files.createDirectories(CACHE_ROOT);
                Path marker = CACHE_ROOT.resolve(".last-clean");
                Instant now = Instant.now();
                if (Files.exists(marker) && Files.getLastModifiedTime(marker).toInstant().isAfter(now.minus(Duration.ofDays(1)))) {
                    return;
                }
                Files.writeString(marker, "");

                Instant cutoff = now.minus(MAX_AGE);
                try (DirectoryStream<Path> slots = Files.newDirectoryStream(CACHE_ROOT, Files::isDirectory)) {
                    for (Path slot : slots) {
                        Path ok = slot.resolve(".ok");
                        Path ref = Files.exists(ok) ? ok : slot;
                        if (Files.getLastModifiedTime(ref).toInstant().isBefore(cutoff)) {
                            deleteRecursively(slot);
                        }
                    }
                }
            } catch (Exception ignored) {
                // Cleanup is best effort
            }
        });
    }

    private static String toolStamp() {
        try {
            Path self = Path.of(Runner.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Runtime.version() + "|" + Files.getLastModifiedTime(self);
        } catch (Exception e) {
            return Runtime.version().toString();
        }
    }

    private static String hash(String s) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes());
        return HexFormat.of().formatHex(digest, 0, 16);
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(f -> {
                try { Files.delete(f); } catch (IOException ignored) { }
            });
        }
    }
}
