package org.konpeki;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import processing.mode.java.preproc.PdePreprocessIssue;
import processing.mode.java.preproc.PdePreprocessor;
import processing.mode.java.preproc.PreprocessorResult;
import processing.mode.java.preproc.TextTransform;
import processing.utils.SketchException;

/**
 * One snapshot of a sketch for the language server: its tabs preprocessed to Java and type checked.
 */
final class Analysis {
    // Lines and columns are 0-based, like LSP positions
    record Spot(Path file, int line, int column) {}

    record Problem(Spot start, Spot end, String message, boolean error) {}

    private record Tab(Path file, int start, String text, int[] lineStarts) {}

    final List<Problem> problems = new ArrayList<>();
    private final List<Tab> tabs = new ArrayList<>();
    private final String className;
    private String java;
    private TextTransform.OffsetMapper mapper;
    private CompilationUnitTree unit;
    private Trees trees;

    private Analysis(String className) {
        this.className = className;
    }

    List<Path> files() {
        return tabs.stream().map(Tab::file).toList();
    }

    // open holds the text of tabs with unsaved changes
    static Analysis of(Path dir, Map<Path, String> open, StandardJavaFileManager files) throws IOException {
        Analysis analysis = new Analysis(Processor.sketchName(dir));
        StringBuilder combined = new StringBuilder();
        for (Path file : Processor.tabs(dir)) {
            String text = open.get(file);
            if (text == null && !Files.isRegularFile(file)) continue;
            if (text == null) text = Files.readString(file);
            analysis.tabs.add(new Tab(file, combined.length(), text, lineStarts(text)));
            // Same as Processor.source()
            combined.append(text).append('\n');
        }
        if (!analysis.tabs.isEmpty()) analysis.analyze(combined.toString(), dir, files);
        return analysis;
    }

    private void analyze(String combined, Path dir, StandardJavaFileManager files) throws IOException {
        PreprocessorResult result;
        try {
            result = PdePreprocessor.builderFor(className).setTabSize(2).build().write(new StringWriter(), combined);
        } catch (SketchException e) {
            Spot spot = combinedSpot(combined, e.getCodeLine(), Math.max(e.getCodeColumn(), 0));
            problems.add(new Problem(spot, spot, e.getMessage(), true));
            return;
        }
        if (!result.getPreprocessIssues().isEmpty()) {
            for (PdePreprocessIssue issue : result.getPreprocessIssues()) {
                Spot spot = combinedSpot(combined, issue.getLine() - 1, issue.getCharPositionInLine());
                // The parser notices a missing ; at the next token, put it where the ; goes like the PDE does
                if (issue.getMsg().contains("Missing ';'")) spot = endOfPreviousLine(spot);
                // ANTLR lists every token it would have accepted
                String message = issue.getMsg().replaceAll(" expecting \\{.*", "");
                problems.add(new Problem(spot, spot, message, true));
            }
            return;
        }

        TextTransform transform = new TextTransform(combined);
        transform.addAll(result.getEdits());
        java = transform.apply();
        mapper = transform.getMapper();

        JavaFileObject source =
                new SimpleJavaFileObject(URI.create("string:///" + className + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return java;
                    }
                };
        String classpath = Stream.concat(
                        Stream.of(System.getProperty("java.class.path")),
                        Libraries.forSketch(dir, combined, null).stream().map(Path::toString))
                .collect(Collectors.joining(File.pathSeparator));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                .getTask(null, files, diagnostics, List.of("-proc:none", "-cp", classpath), null, List.of(source));
        unit = task.parse().iterator().next();
        task.analyze();
        trees = Trees.instance(task);

        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.NOTE || d.getSource() != source) continue;
            long start = d.getStartPosition() >= 0 ? d.getStartPosition() : d.getPosition();
            long end = Math.max(d.getEndPosition(), start);
            problems.add(new Problem(
                    javaSpot((int) start), javaSpot((int) end), message(d), d.getKind() == Diagnostic.Kind.ERROR));
        }
    }

    // Drops javac's "location: class Sketch" line, it's always the sketch
    private static String message(Diagnostic<?> d) {
        return d.getMessage(Locale.ENGLISH)
                .lines()
                .filter(l -> !l.trim().startsWith("location:"))
                .collect(Collectors.joining("\n"))
                .replaceAll("\\b(?:[a-z_]\\w*\\.)+(?=[A-Z])", "");
    }

    String hover(Path file, int line, int column) {
        TreePath path = pathAt(file, line, column);
        Element element = path == null ? null : trees.getElement(path);
        return element == null ? null : Reference.hover(element, className);
    }

    Spot definition(Path file, int line, int column) {
        TreePath path = pathAt(file, line, column);
        Element element = path == null ? null : trees.getElement(path);
        TreePath declaration = element == null ? null : trees.getPath(element);
        if (declaration == null || declaration.getCompilationUnit() != unit) return null;
        int offset = nameOffset(declaration.getLeaf(), element.getSimpleName().toString());
        return offset < 0 ? null : javaSpot(offset);
    }

    // The innermost name at a position: an identifier, member name or declaration name
    private TreePath pathAt(Path file, int line, int column) {
        if (unit == null) return null;
        int pde = combinedOffset(file, line, column);
        if (pde < 0) return null;
        int offset = mapper.getOutputOffset(pde);
        SourcePositions positions = trees.getSourcePositions();
        TreePath[] found = new TreePath[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree == null) return null;
                long start = positions.getStartPosition(unit, tree), end = positions.getEndPosition(unit, tree);
                // Generated trees have no position, keep looking inside them
                if (start >= 0 && (offset < start || offset > end)) return null;
                return super.scan(tree, unused);
            }

            @Override
            public Void visitIdentifier(IdentifierTree tree, Void unused) {
                found[0] = getCurrentPath();
                return super.visitIdentifier(tree, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
                long end = positions.getEndPosition(unit, tree);
                if (offset >= end - tree.getIdentifier().length()) found[0] = getCurrentPath();
                return super.visitMemberSelect(tree, unused);
            }

            @Override
            public Void visitMethod(MethodTree tree, Void unused) {
                onName(tree, tree.getName().toString());
                return super.visitMethod(tree, unused);
            }

            @Override
            public Void visitVariable(VariableTree tree, Void unused) {
                onName(tree, tree.getName().toString());
                return super.visitVariable(tree, unused);
            }

            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                onName(tree, tree.getSimpleName().toString());
                return super.visitClass(tree, unused);
            }

            private void onName(Tree tree, String name) {
                int start = nameOffset(tree, name);
                if (start >= 0 && offset >= start && offset <= start + name.length()) found[0] = getCurrentPath();
            }
        }.scan(unit, null);
        return found[0];
    }

    // Where a declaration's name is in the generated Java, the first match after its type
    private int nameOffset(Tree tree, String name) {
        SourcePositions positions = trees.getSourcePositions();
        long from = positions.getStartPosition(unit, tree);
        Tree type =
                tree instanceof VariableTree v ? v.getType() : tree instanceof MethodTree m ? m.getReturnType() : null;
        if (type != null && positions.getEndPosition(unit, type) > from) from = positions.getEndPosition(unit, type);
        if (from < 0 || name.isEmpty()) return -1;
        int at = java.indexOf(name, (int) from);
        long end = positions.getEndPosition(unit, tree);
        return at < 0 || (end >= 0 && at > end) ? -1 : at;
    }

    private Spot javaSpot(int javaOffset) {
        return combinedSpot(mapper.getInputOffset(Math.max(javaOffset, 0)));
    }

    private Spot combinedSpot(String combined, int line, int column) {
        int offset = 0;
        for (int i = 0; i < line && offset >= 0; i++) offset = combined.indexOf('\n', offset) + 1;
        return combinedSpot(Math.max(offset, 0) + column);
    }

    private Spot combinedSpot(int offset) {
        Tab tab = tabs.get(0);
        for (Tab t : tabs) if (t.start <= offset) tab = t;
        int local = Math.max(0, Math.min(offset - tab.start, tab.text.length()));
        int line = 0;
        while (line + 1 < tab.lineStarts.length && tab.lineStarts[line + 1] <= local) line++;
        return new Spot(tab.file, line, local - tab.lineStarts[line]);
    }

    private Spot endOfPreviousLine(Spot spot) {
        for (Tab tab : tabs) {
            if (!tab.file.equals(spot.file())) continue;
            for (int line = spot.line() - 1; line >= 0; line--) {
                int end = line + 1 < tab.lineStarts.length ? tab.lineStarts[line + 1] - 1 : tab.text.length();
                String text = tab.text.substring(tab.lineStarts[line], end).stripTrailing();
                if (!text.isBlank()) return new Spot(spot.file(), line, text.length());
            }
        }
        return spot;
    }

    private int combinedOffset(Path file, int line, int column) {
        for (Tab tab : tabs) {
            if (!tab.file.equals(file)) continue;
            if (line >= tab.lineStarts.length) return -1;
            return tab.start + Math.min(tab.lineStarts[line] + column, tab.text.length());
        }
        return -1;
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>(List.of(0));
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }
}
