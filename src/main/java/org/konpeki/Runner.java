package org.konpeki;

import processing.core.PApplet;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public class Runner {
    private static final Path CACHE_ROOT = Path.of(
            System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("java.io.tmpdir")),
            "ProcessingCLI", "cache");

    public static Path cacheDir(String sketchName, String source) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(sketchName.getBytes(StandardCharsets.UTF_8));
        md.update(toolStamp().getBytes(StandardCharsets.UTF_8));
        md.update(source.getBytes(StandardCharsets.UTF_8));

        return CACHE_ROOT.resolve(HexFormat.of().formatHex(md.digest(), 0, 16));
    }

    public static boolean isCompiled(Path classesDir) {
        return Files.exists(classesDir.resolve(".ok"));
    }

    public static void compile(String className, String javaSource, Path classesDir) throws Exception {
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
        Files.writeString(classesDir.resolve(".ok"), "");
    }

    public static void launch(String className, Path classesDir, Path sketchDir) throws Exception {
        // Left open on purpose
        URLClassLoader loader = new URLClassLoader(
                new URL[]{classesDir.toUri().toURL()}, Runner.class.getClassLoader());
        PApplet sketch = (PApplet) loader.loadClass(className).getDeclaredConstructor().newInstance();
        PApplet.runSketch(new String[]{
                "--sketch-path=" + sketchDir.toAbsolutePath(),
                className
        }, sketch);
    }

    private static String toolStamp() {
        try {
            Path self = Path.of(Runner.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Runtime.version() + "|" + Files.getLastModifiedTime(self);
        } catch (Exception e) {
            return Runtime.version().toString();
        }
    }
}
