package org.konpeki;

import processing.core.PApplet;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

public class Runner {
    public static void run(String className, String source, Path sketchDir) throws Exception {
        Path outDir = Files.createTempDirectory("sketch");
        Path file = outDir.resolve(className + ".java");
        Files.writeString(file, source);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No Java Compiler available");
        }
        int result = compiler.run(null, null, null,
                "-cp", System.getProperty("java.class.path"),
                "-d", outDir.toString(),
                "-nowarn",
                file.toString());
        if (result != 0) {
            throw new IllegalStateException("Sketch failed to compile");
        }

        URLClassLoader loader = new URLClassLoader(
                new URL[]{outDir.toUri().toURL()}, Runner.class.getClassLoader());
        Class<?> cls = loader.loadClass(className);
        PApplet sketch = (PApplet) cls.getDeclaredConstructor().newInstance();

        PApplet.runSketch(new String[]{
                "--sketch-path=" + sketchDir.toAbsolutePath(),
                className
        }, sketch);
    }
}
