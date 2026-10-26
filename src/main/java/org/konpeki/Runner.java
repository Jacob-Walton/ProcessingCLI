package org.konpeki;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import processing.core.PApplet;

public class Runner {
    private static final Path CACHE_ROOT = Path.of(
            System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("java.io.tmpdir")),
            "ProcessingCLI",
            "cache");
    private static final Duration MAX_AGE = Duration.ofDays(30);

    public static Path cacheDir(Path sketchDir) throws Exception {
        String key = sketchDir.toAbsolutePath().normalize().toString().toLowerCase();
        return CACHE_ROOT.resolve(hash(key));
    }

    public static String stamp(String sketchName, String source, List<Path> libs) throws Exception {
        StringBuilder sb =
                new StringBuilder(sketchName).append('\n').append(toolStamp()).append('\n');
        for (Path jar : libs) {
            sb.append(jar).append('@').append(Files.getLastModifiedTime(jar)).append('\n');
        }
        return hash(sb.append(source).toString());
    }

    public static boolean isCompiled(Path classesDir, String stamp) {
        try {
            return Files.readString(classesDir.resolve(".ok")).equals(stamp);
        } catch (IOException e) {
            return false;
        }
    }

    public static void compile(String className, String javaSource, Path classesDir, String stamp, List<Path> libs)
            throws Exception {
        deleteRecursively(classesDir);
        Files.createDirectories(classesDir);
        Path file = classesDir.resolve(className + ".java");
        Files.writeString(file, javaSource);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java compiler available");
        }

        String classpath = Stream.concat(
                        Stream.of(System.getProperty("java.class.path")),
                        libs.stream().map(Path::toString))
                .collect(Collectors.joining(File.pathSeparator));

        int result = compiler.run(
                null,
                null,
                null,
                "-cp",
                classpath,
                "-d",
                classesDir.toString(),
                "-proc:none",
                "-implicit:none",
                "-nowarn",
                file.toString());
        if (result != 0) {
            throw new IllegalStateException("Sketch failed to compile (see errors above)");
        }
        Files.writeString(classesDir.resolve(".ok"), stamp);
    }

    public static void launch(
            String className,
            Path classesDir,
            Path sketchDir,
            List<Path> libs,
            boolean present,
            List<String> sketchArgs,
            String preview)
            throws Exception {
        Files.setLastModifiedTime(classesDir.resolve(".ok"), FileTime.from(Instant.now()));

        List<URL> urls = new ArrayList<>();
        urls.add(classesDir.toUri().toURL());
        for (Path jar : libs) urls.add(jar.toUri().toURL());
        URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), Runner.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(loader);

        PApplet sketch =
                (PApplet) loader.loadClass(className).getDeclaredConstructor().newInstance();
        if (Bench.enabled()) {
            sketch.registerMethod("post", new Bench.FirstFrame());
        }
        if (preview != null) {
            Preview.attach(sketch, preview);
        }

        List<String> args = new ArrayList<>();
        args.add("--sketch-path=" + sketchDir.toAbsolutePath());
        if (present) args.add("--present");
        args.add(className);
        args.addAll(sketchArgs);

        // Hide calcSketchPath's NPE, PApplet's code source is null when loaded from the AOT cache
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            PApplet.runSketch(args.toArray(String[]::new), sketch);
        } finally {
            System.setErr(err);
            boolean skipping = false;
            for (String line : captured.toString().lines().toList()) {
                if (line.contains("\"jarURL\" is null")) {
                    skipping = true;
                } else if (!skipping || !line.startsWith("\tat ")) {
                    skipping = false;
                    err.println(line);
                }
            }
        }
    }

    public static void cleanUpInBackground() {
        Thread.ofPlatform().daemon().start(() -> {
            try {
                Files.createDirectories(CACHE_ROOT);
                Path marker = CACHE_ROOT.resolve(".last-clean");
                Instant now = Instant.now();
                if (Files.exists(marker)
                        && Files.getLastModifiedTime(marker).toInstant().isAfter(now.minus(Duration.ofDays(1)))) {
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
        // Packaged builds pin the jar mtime for the AOT cache, so use the build id instead
        String build = System.getProperty("processingcli.build");
        if (build != null) return Runtime.version() + "|" + build;
        try {
            Path self = Path.of(Runner.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
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
                try {
                    Files.delete(f);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
