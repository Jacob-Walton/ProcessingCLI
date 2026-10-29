package org.konpeki;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Standalone app of a sketch, from ProcessingCLI's runtime and Sketch launcher
 */
final class Export {
    private static final String LAUNCHER = "Sketch";

    private Export() {}

    static Path write(Path sketch, String className, Path classes, List<Path> libs) throws Exception {
        String launcherPath = System.getProperty("jpackage.app-path");
        if (launcherPath == null) throw new IllegalStateException("Exporting needs the packaged ProcessingCLI");
        Path launcher = Path.of(launcherPath);
        Path jar = Path.of(System.getProperty("java.class.path").split(File.pathSeparator)[0]);
        Path appDir = jar.getParent();
        // root holds the launcher and app folder, Contents on macOS
        Path root = launcher.getParent();
        while (!appDir.startsWith(root)) root = root.getParent();
        boolean mac = root.getFileName().toString().equals("Contents");
        if (mac) root = root.getParent();

        String name = sketch.getFileName().toString();
        String os = System.getProperty("os.name").startsWith("Windows") ? "windows" : mac ? "macos" : "linux";
        Path out = sketch.resolve(os + "-" + System.getProperty("os.arch"));
        deleteRecursively(out);
        Path image = mac ? out.resolve(name + ".app") : out;

        // Not ProcessingCLI's own launchers and app files
        String exe = launcher.getFileName().toString();
        String ext = exe.endsWith(".exe") ? ".exe" : "";
        Path sketchLauncher = launcher.resolveSibling(LAUNCHER + ext);
        Path newAppDir = image.resolve(root.relativize(appDir));
        Path finalRoot = root;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path from : walk.toList()) {
                boolean skip = from.equals(launcher)
                        || from.equals(sketchLauncher)
                        || (from.getParent().equals(appDir) && Files.isRegularFile(from));
                if (skip) continue;
                Path to = image.resolve(finalRoot.relativize(from).toString());
                if (Files.isDirectory(from)) Files.createDirectories(to);
                else Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        Path newLauncher = image.resolve(root.relativize(launcher.getParent())).resolve(name + ext);
        Files.copy(sketchLauncher, newLauncher, StandardCopyOption.COPY_ATTRIBUTES);

        Files.copy(jar, newAppDir.resolve(jar.getFileName()));
        jarClasses(classes, newAppDir.resolve(name + ".jar"));
        List<String> classpath =
                new ArrayList<>(List.of(name + ".jar", jar.getFileName().toString()));
        for (Path lib : libs) {
            Path to = newAppDir.resolve("lib").resolve(lib.getFileName());
            Files.createDirectories(to.getParent());
            Files.copy(lib, to, StandardCopyOption.REPLACE_EXISTING);
            classpath.add("lib/" + lib.getFileName());
        }
        // For loadImage() and co.
        Path data = sketch.resolve("data");
        if (Files.isDirectory(data)) copyRecursively(data, newAppDir.resolve("data"));

        StringBuilder cfg = new StringBuilder("[Application]\n");
        for (String entry : classpath)
            cfg.append("app.classpath=$APPDIR/").append(entry).append('\n');
        cfg.append("app.mainclass=org.konpeki.Exported\n\n[JavaOptions]\n");
        for (String option : List.of(
                "--enable-native-access=ALL-UNNAMED",
                "-Djogl.disable.opengles",
                "-Djogamp.gluegen.UseTempJarCache=false",
                "-Djava.library.path=$APPDIR/natives",
                "-Dprocessingcli.sketch=" + className,
                "-Dprocessingcli.sketchPath=$APPDIR")) {
            cfg.append("java-options=").append(option).append('\n');
        }
        Files.writeString(newAppDir.resolve(name + ".cfg"), cfg);

        if (mac) {
            Path plist = image.resolve("Contents/Info.plist");
            Files.writeString(plist, Files.readString(plist).replace("ProcessingCLI", name));
            // Editing the bundle breaks its signature
            new ProcessBuilder("codesign", "--force", "--deep", "--sign", "-", image.toString())
                    .inheritIO()
                    .start()
                    .waitFor();
        }
        return out;
    }

    private static void jarClasses(Path classes, Path jar) throws IOException {
        try (OutputStream file = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(file);
                Stream<Path> walk = Files.walk(classes)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                zip.putNextEntry(new ZipEntry(classes.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, zip);
                zip.closeEntry();
            }
        }
    }

    private static void copyRecursively(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path f : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(f);
        }
    }
}
