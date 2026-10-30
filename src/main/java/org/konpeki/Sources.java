package org.konpeki;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * JDK and Processing sources for go to definition, unpacked read-only into the cache
 */
final class Sources {
    private Sources() {}

    static Analysis.Spot locate(Element element) {
        TypeElement top = null;
        List<String> types = new ArrayList<>();
        for (Element e = element; e != null && !(e instanceof PackageElement); e = e.getEnclosingElement()) {
            if (e instanceof TypeElement type) {
                top = type;
                types.addFirst(type.getSimpleName().toString());
            }
        }
        if (top == null) return null;
        Path file = unpack(top.getQualifiedName().toString().replace('.', '/') + ".java");
        if (file == null) return null;
        try {
            String text = Files.readString(file);
            int offset = find(text, types, element);
            if (offset < 0) offset = 0;
            String before = text.substring(0, offset);
            int line = (int) before.chars().filter(c -> c == '\n').count();
            return new Analysis.Spot(file, line, offset - before.lastIndexOf('\n') - 1);
        } catch (Exception e) {
            return null;
        }
    }

    // Offset of the declaration's name
    private static int find(String text, List<String> types, Element element) {
        JavaFileObject source =
                new SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return text;
                    }
                };
        JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                .getTask(null, null, d -> {}, List.of("-proc:none"), null, List.of(source));
        try {
            CompilationUnitTree unit = task.parse().iterator().next();
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            List<? extends Tree> members = unit.getTypeDecls();
            ClassTree type = null;
            for (String name : types) {
                type = members.stream()
                        .filter(t ->
                                t instanceof ClassTree c && c.getSimpleName().contentEquals(name))
                        .map(ClassTree.class::cast)
                        .findFirst()
                        .orElse(null);
                if (type == null) return -1;
                members = type.getMembers();
            }
            if (element instanceof TypeElement)
                return nameAfter(text, positions.getStartPosition(unit, type), types.getLast());

            String name = element.getKind() == ElementKind.CONSTRUCTOR
                    ? "<init>"
                    : element.getSimpleName().toString();
            List<String> params = element instanceof ExecutableElement method
                    ? method.getParameters().stream()
                            .map(p -> DocIndex.normalize(p.asType().toString()))
                            .toList()
                    : null;
            for (Tree member : type.getMembers()) {
                boolean match =
                        switch (member) {
                            case MethodTree m ->
                                params != null
                                        && m.getName().contentEquals(name)
                                        && m.getParameters().stream()
                                                .map(p -> DocIndex.normalize(
                                                        p.getType().toString()))
                                                .toList()
                                                .equals(params);
                            case VariableTree v -> params == null && v.getName().contentEquals(name);
                            default -> false;
                        };
                if (match) {
                    String shown = name.equals("<init>") ? types.getLast() : name;
                    return nameAfter(text, positions.getStartPosition(unit, member), shown);
                }
            }
            return nameAfter(text, positions.getStartPosition(unit, type), types.getLast());
        } catch (Exception e) {
            return -1;
        }
    }

    // Skips modifiers and annotations
    private static int nameAfter(String text, long start, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(text);
        return m.find((int) Math.max(start, 0)) ? m.start() : (int) start;
    }

    // Read-only, editing it would do nothing
    // Source of a compiled class, like java.util.ArrayList$Itr
    static Path file(String className) {
        int nested = className.indexOf('$');
        String top = nested < 0 ? className : className.substring(0, nested);
        return unpack(top.replace('.', '/') + ".java");
    }

    private static synchronized Path unpack(String entry) {
        Path file = Runner.cacheRoot().resolve("sources").resolve(entry);
        if (Files.isRegularFile(file)) return file;
        try (InputStream zip = Sources.class.getResourceAsStream("sources.zip")) {
            if (zip == null) return null;
            ZipInputStream in = new ZipInputStream(zip);
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (!e.getName().equals(entry)) continue;
                Files.createDirectories(file.getParent());
                Files.write(file, in.readAllBytes());
                file.toFile().setWritable(false);
                return file;
            }
        } catch (Exception e) {
            // Falls through
        }
        return null;
    }
}
