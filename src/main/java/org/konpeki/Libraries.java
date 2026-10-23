package org.konpeki;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

public final class Libraries {
    private static final Pattern IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([\\w.]+?)(\\.\\*)?\\s*;", Pattern.MULTILINE);

    // Already available without any library
    private static final List<String> BUILT_IN = List.of(
            "java.", "javax.", "processing.core", "processing.data",
            "processing.event", "processing.opengl", "processing.awt");

    public static List<Path> forSketch(Path sketchDir, String source) throws IOException {
        Set<Path> jars = new LinkedHashSet<>(jarsIn(sketchDir.resolve("code")));

        Set<String> wanted = importedPackages(source);
        Path libsDir = sketchbook().resolve("libraries");
        if (!wanted.isEmpty() && Files.isDirectory(libsDir)) {
            try (DirectoryStream<Path> libs = Files.newDirectoryStream(libsDir, Files::isDirectory)) {
                for (Path lib : libs) {
                    List<Path> libJars = jarsIn(lib.resolve("library"));
                    if (providesAny(libJars, wanted)) {
                        jars.addAll(libJars);
                    }
                }
            }
        }
        return List.copyOf(jars);
    }

    private static Set<String> importedPackages(String source) {
        Set<String> packages = new HashSet<>();
        Matcher m = IMPORT.matcher(source);
        while (m.find()) {
            String name = m.group(1);
            if (BUILT_IN.stream().anyMatch(name::startsWith)) continue;
            if (m.group(2) != null) packages.add(name);
            String parent = parent(name);
            if (parent != null) {
                packages.add(parent);
                String grandparent = parent(parent);
                if (grandparent != null) packages.add(grandparent);
            }
        }
        return packages;
    }

    private static String parent(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : name.substring(0, dot);
    }

    private static boolean providesAny(List<Path> jars, Set<String> packages) {
        for (Path jar : jars) {
            try (JarFile jf = new JarFile(jar.toFile())) {
                boolean found = jf.stream()
                        .map(ZipEntry::getName)
                        .filter(n -> n.endsWith(".class") && n.lastIndexOf('/') > 0)
                        .map(n -> n.substring(0, n.lastIndexOf('/')).replace('/', '.'))
                        .anyMatch(packages::contains);
                if (found) return true;
            } catch (IOException ignored) {
                // unreadable jar, skip
            }
        }
        return false;
    }

    private static List<Path> jarsIn(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".jar") || n.endsWith(".zip");
            }).sorted().toList();
        }
    }

    static Path sketchbook() {
        String env = System.getenv("PCLI_SKETCHBOOK");
        if (env != null) return Path.of(env);

        String appData = System.getenv("APPDATA");
        if (appData != null) {
            try {
                List<String> prefs = Files.readAllLines(Path.of(appData, "Processing", "preferences.txt"));
                for (String key : List.of("sketchbook.path.four=", "sketchbook.path=")) {
                    for (String line : prefs) {
                        if (line.startsWith(key)) return Path.of(line.substring(key.length()).trim());
                    }
                }
            } catch (IOException ignored) {
                // Processing not installed
            }
        }
        return Path.of(System.getProperty("user.home"), "Documents", "Processing");
    }
}