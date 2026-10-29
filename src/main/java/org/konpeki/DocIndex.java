package org.konpeki;

import com.google.gson.Gson;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Doc comments of the JDK and Processing, which class files don't have. Made by the build
 */
final class DocIndex {
    // Not subpackages
    private static final Set<String> JDK = Set.of(
            "java/lang", "java/util", "java/util/function", "java/util/stream", "java/io", "java/math", "java/time");

    private static Map<String, String> index;

    private DocIndex() {}

    // OUTPUT SOURCES..., also writes sources.zip next to OUTPUT
    public static void main(String[] args) throws Exception {
        Map<String, String> docs = new TreeMap<>();
        Path out = Path.of(args[0]);
        Files.createDirectories(out.getParent());
        ZipOutputStream sources = new ZipOutputStream(Files.newOutputStream(out.resolveSibling("sources.zip")));
        for (String source : List.of(args).subList(1, args.length)) {
            Path path = Path.of(source);
            if (!Files.isRegularFile(path)) {
                System.err.println("No sources at " + path + ", hovers won't have those docs");
                continue;
            }
            try (ZipFile zip = new ZipFile(path.toFile())) {
                List<JavaFileObject> files = new ArrayList<>();
                for (ZipEntry entry : zip.stream().toList()) {
                    String name = entry.getName();
                    // src.zip has module folders: java.base/java/lang/String.java
                    String relative = name.contains("/java/") && name.startsWith("java.")
                            ? name.substring(name.indexOf('/') + 1)
                            : name;
                    String dir = relative.contains("/") ? relative.substring(0, relative.lastIndexOf('/')) : "";
                    boolean wanted = relative.startsWith("processing/") || JDK.contains(dir);
                    if (!wanted || !name.endsWith(".java") || name.endsWith("package-info.java")) continue;
                    byte[] bytes = zip.getInputStream(entry).readAllBytes();
                    sources.putNextEntry(new ZipEntry(relative));
                    sources.write(bytes);
                    sources.closeEntry();
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    files.add(
                            new SimpleJavaFileObject(URI.create("string:///" + relative), JavaFileObject.Kind.SOURCE) {
                                @Override
                                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                                    return text;
                                }
                            });
                }
                collect(files, docs);
            }
        }
        sources.close();
        try (Writer writer =
                new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(out)), StandardCharsets.UTF_8)) {
            new Gson().toJson(docs, writer);
        }
        System.out.println(docs.size() + " doc comments in " + out);
    }

    private static void collect(List<JavaFileObject> files, Map<String, String> docs) throws Exception {
        JavacTask task = (JavacTask)
                ToolProvider.getSystemJavaCompiler().getTask(null, null, d -> {}, List.of("-proc:none"), null, files);
        DocTrees trees = DocTrees.instance(task);
        for (CompilationUnitTree unit : task.parse()) {
            String pkg = unit.getPackageName() == null ? "" : unit.getPackageName() + ".";
            for (Tree type : unit.getTypeDecls()) {
                if (type instanceof ClassTree c) collect(trees, new TreePath(new TreePath(unit), c), pkg, docs);
            }
        }
    }

    private static void collect(DocTrees trees, TreePath path, String outer, Map<String, String> docs) {
        ClassTree type = (ClassTree) path.getLeaf();
        if (!visible(type.getModifiers().getFlags(), true)) return;
        String name = outer + type.getSimpleName();
        put(docs, name, trees.getDocComment(path));
        for (Tree member : type.getMembers()) {
            TreePath memberPath = new TreePath(path, member);
            switch (member) {
                case ClassTree inner -> collect(trees, memberPath, name + ".", docs);
                case MethodTree method -> {
                    if (!visible(method.getModifiers().getFlags(), false)) continue;
                    List<String> params = method.getParameters().stream()
                            .map(p -> normalize(p.getType().toString()))
                            .toList();
                    // <init> in the tree, keyed by class name
                    String methodName = method.getName().contentEquals("<init>")
                            ? type.getSimpleName().toString()
                            : method.getName().toString();
                    put(
                            docs,
                            name + "#" + methodName + "(" + String.join(",", params) + ")",
                            trees.getDocComment(memberPath));
                }
                case VariableTree field -> {
                    if (visible(field.getModifiers().getFlags(), false)) {
                        put(docs, name + "#" + field.getName(), trees.getDocComment(memberPath));
                    }
                }
                default -> {}
            }
        }
    }

    // Non-public classes too, for what subclasses inherit
    private static boolean visible(Set<Modifier> flags, boolean type) {
        return type || !flags.contains(Modifier.PRIVATE);
    }

    private static void put(Map<String, String> docs, String key, String comment) {
        if (comment != null && !comment.isBlank()) docs.put(key, comment);
    }

    // Collection<? extends E> is Collection, Object... is Object[]
    static String normalize(String type) {
        String plain = type.replaceAll("@\\w+(\\([^)]*\\))?\\s*", "");
        int depth = 0;
        StringBuilder out = new StringBuilder();
        for (char c : plain.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (depth == 0 && !Character.isWhitespace(c)) out.append(c);
        }
        String simple = out.toString().replace("...", "[]");
        return simple.substring(
                simple.lastIndexOf('.', simple.indexOf('[') < 0 ? simple.length() : simple.indexOf('[')) + 1);
    }

    static String key(Element element) {
        Element owner = element instanceof TypeElement ? null : element.getEnclosingElement();
        if (element instanceof TypeElement type) return type.getQualifiedName().toString();
        if (!(owner instanceof TypeElement type)) return null;
        String prefix = type.getQualifiedName() + "#";
        if (element instanceof ExecutableElement method) {
            String name = method.getKind() == javax.lang.model.element.ElementKind.CONSTRUCTOR
                    ? type.getSimpleName().toString()
                    : method.getSimpleName().toString();
            List<String> params = method.getParameters().stream()
                    .map(p -> normalize(p.asType().toString()))
                    .toList();
            return prefix + name + "(" + String.join(",", params) + ")";
        }
        return prefix + element.getSimpleName();
    }

    // For Javadoc.parse
    static String comment(Element element) {
        String key = key(element);
        return key == null ? null : index().get(key);
    }

    private static synchronized Map<String, String> index() {
        if (index == null) {
            try (Reader reader = new InputStreamReader(
                    new java.util.zip.GZIPInputStream(DocIndex.class.getResourceAsStream("docs.json.gz")),
                    StandardCharsets.UTF_8)) {
                index = new Gson()
                        .fromJson(reader, new com.google.gson.reflect.TypeToken<Map<String, String>>() {}.getType());
            } catch (Exception e) {
                index = Map.of();
            }
        }
        return index;
    }
}
