package org.konpeki;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Scope;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
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

    private record JavaTab(Path file, String text, int[] lineStarts) {}

    final List<Problem> problems = new ArrayList<>();
    private final List<Tab> tabs = new ArrayList<>();
    private final List<JavaTab> javaTabs = new ArrayList<>();
    private final Map<CompilationUnitTree, JavaTab> javaUnits = new HashMap<>();
    private final String className;
    private String java;
    private TextTransform.OffsetMapper mapper;
    private CompilationUnitTree unit;
    private Trees trees;
    private Elements elements;

    private Analysis(String className) {
        this.className = className;
    }

    List<Path> files() {
        return Stream.concat(tabs.stream().map(Tab::file), javaTabs.stream().map(JavaTab::file))
                .toList();
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
        // Not a sketch folder, likely a .java file somewhere else
        if (analysis.tabs.isEmpty()) return analysis;
        for (Path file : Processor.javaTabs(dir)) {
            String text = open.containsKey(file) ? open.get(file) : Files.readString(file);
            analysis.javaTabs.add(new JavaTab(file, text, lineStarts(text)));
        }
        analysis.analyze(combined.toString(), dir, files);
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
        List<JavaFileObject> sources = new ArrayList<>(List.of(source));
        Map<JavaFileObject, JavaTab> tabSources = new HashMap<>();
        StringBuilder everything = new StringBuilder(combined);
        for (JavaTab tab : javaTabs) {
            JavaFileObject object = new SimpleJavaFileObject(tab.file.toUri(), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return tab.text;
                }
            };
            sources.add(object);
            tabSources.put(object, tab);
            everything.append(tab.text).append('\n');
        }

        String classpath = Stream.concat(
                        Stream.of(System.getProperty("java.class.path")),
                        Libraries.forSketch(dir, everything.toString(), null).stream()
                                .map(Path::toString))
                .collect(Collectors.joining(File.pathSeparator));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) ToolProvider.getSystemJavaCompiler()
                .getTask(null, files, diagnostics, List.of("-proc:none", "-cp", classpath), null, sources);
        // By URI, javac wraps the file objects it hands back
        for (CompilationUnitTree parsed : task.parse()) {
            JavaTab tab = javaTabs.stream()
                    .filter(t -> t.file.toUri().equals(parsed.getSourceFile().toUri()))
                    .findFirst()
                    .orElse(null);
            if (tab == null) unit = parsed;
            else javaUnits.put(parsed, tab);
        }
        task.analyze();
        trees = Trees.instance(task);
        elements = task.getElements();

        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.NOTE) continue;
            JavaTab tab = tabSources.get(d.getSource());
            if (d.getSource() != source && tab == null) continue;
            long start = d.getStartPosition() >= 0 ? d.getStartPosition() : d.getPosition();
            long end = Math.max(d.getEndPosition(), start);
            problems.add(new Problem(
                    tab == null ? javaSpot((int) start) : tabSpot(tab, (int) start),
                    tab == null ? javaSpot((int) end) : tabSpot(tab, (int) end),
                    message(d),
                    d.getKind() == Diagnostic.Kind.ERROR));
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

    // Semantic token legend, the indexes below refer to these
    static final List<String> TOKEN_TYPES = List.of(
            "class", "interface", "enum", "typeParameter", "parameter", "variable", "property", "enumMember", "method");
    static final List<String> TOKEN_MODIFIERS = List.of("declaration", "static", "readonly");

    boolean analysed() {
        return unit != null;
    }

    // [line, column, length, type, modifiers] for every name in a file that javac resolved
    List<int[]> semanticTokens(Path file) {
        List<int[]> tokens = new ArrayList<>();
        if (unit == null) return tokens;
        CompilationUnitTree target = unit;
        for (Map.Entry<CompilationUnitTree, JavaTab> entry : javaUnits.entrySet()) {
            if (entry.getValue().file.equals(file)) target = entry.getKey();
        }
        CompilationUnitTree scanned = target;
        SourcePositions positions = trees.getSourcePositions();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitIdentifier(IdentifierTree tree, Void unused) {
                add(positions.getStartPosition(scanned, tree), tree.getName().toString(), false);
                return super.visitIdentifier(tree, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
                String name = tree.getIdentifier().toString();
                add(positions.getEndPosition(scanned, tree) - name.length(), name, false);
                return super.visitMemberSelect(tree, unused);
            }

            @Override
            public Void visitMethod(MethodTree tree, Void unused) {
                String name = tree.getName().toString();
                if (!name.equals("<init>")) add(nameOffset(scanned, tree, name), name, true);
                return super.visitMethod(tree, unused);
            }

            @Override
            public Void visitVariable(VariableTree tree, Void unused) {
                String name = tree.getName().toString();
                add(nameOffset(scanned, tree, name), name, true);
                return super.visitVariable(tree, unused);
            }

            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                String name = tree.getSimpleName().toString();
                add(nameOffset(scanned, tree, name), name, true);
                return super.visitClass(tree, unused);
            }

            private void add(long offset, String name, boolean declaration) {
                if (offset < 0 || name.isEmpty() || name.equals("this") || name.equals("super")) return;
                Element element = trees.getElement(getCurrentPath());
                int type = element == null ? -1 : tokenType(element);
                if (type < 0) return;
                Spot spot = scanned == unit ? javaSpot((int) offset) : tabSpot(javaUnits.get(scanned), (int) offset);
                // Skips code the preprocessor added or moved, like size() going into settings()
                if (!spot.file().equals(file) || !name.equals(textAt(spot, name.length()))) return;
                int modifiers = (declaration ? 1 : 0)
                        | (element.getModifiers().contains(Modifier.STATIC) ? 2 : 0)
                        | (element.getModifiers().contains(Modifier.FINAL)
                                        && element.getKind().isVariable()
                                ? 4
                                : 0);
                tokens.add(new int[] {spot.line(), spot.column(), name.length(), type, modifiers});
            }
        }.scan(target, null);
        tokens.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        // The same name can be visited twice, e.g. a declaration's type
        List<int[]> unique = new ArrayList<>();
        for (int[] token : tokens) {
            int[] last = unique.isEmpty() ? null : unique.getLast();
            if (last == null || last[0] != token[0] || last[1] != token[1]) unique.add(token);
        }
        return unique;
    }

    private static int tokenType(Element element) {
        return switch (element.getKind()) {
            case CLASS, RECORD -> 0;
            case INTERFACE, ANNOTATION_TYPE -> 1;
            case ENUM -> 2;
            case TYPE_PARAMETER -> 3;
            case PARAMETER -> 4;
            case LOCAL_VARIABLE, EXCEPTION_PARAMETER, RESOURCE_VARIABLE, BINDING_VARIABLE -> 5;
            case FIELD -> 6;
            case ENUM_CONSTANT -> 7;
            case METHOD -> 8;
            default -> -1;
        };
    }

    private String textAt(Spot spot, int length) {
        for (Tab tab : tabs) {
            if (tab.file.equals(spot.file()) && spot.line() < tab.lineStarts.length) {
                int start = tab.lineStarts[spot.line()] + spot.column();
                return start + length <= tab.text.length() ? tab.text.substring(start, start + length) : null;
            }
        }
        for (JavaTab tab : javaTabs) {
            if (tab.file.equals(spot.file()) && spot.line() < tab.lineStarts.length) {
                int start = tab.lineStarts[spot.line()] + spot.column();
                return start + length <= tab.text.length() ? tab.text.substring(start, start + length) : null;
            }
        }
        return null;
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
        if (declaration == null) return null;
        CompilationUnitTree in = declaration.getCompilationUnit();
        if (in != unit && !javaUnits.containsKey(in)) return null;
        int offset =
                nameOffset(in, declaration.getLeaf(), element.getSimpleName().toString());
        if (offset < 0) return null;
        return in == unit ? javaSpot(offset) : tabSpot(javaUnits.get(in), offset);
    }

    // A suggestion, element is null for an array's length
    record Completion(String name, Element element, int overloads) {}

    // The text to analyse for completion, and whether it's after a dot
    record Patch(String text, boolean member) {}

    private static final String HERE = "__here", TYPE = "__type";

    // Swaps the line being typed for declarations that always parse. One holds the expression
    // before the dot, or null, and gives the scope. The other reads it as a type, for PVector.
    static Patch patch(String text, int line, int column) {
        int[] starts = lineStarts(text);
        if (line >= starts.length) return null;
        int lineEnd = line + 1 < starts.length ? starts[line + 1] - 1 : text.length();
        String current = text.substring(starts[line], lineEnd);
        String before = current.substring(0, Math.min(column, current.length()));
        if (inCommentOrString(before)) return null;
        int start = before.length();
        while (start > 0 && Character.isJavaIdentifierPart(before.charAt(start - 1))) start--;
        int dot = start;
        while (dot > 0 && Character.isWhitespace(before.charAt(dot - 1))) dot--;
        String stand = "Object " + HERE + " = null;";
        boolean member = dot > 0 && before.charAt(dot - 1) == '.';
        if (member) {
            start = receiverStart(before, dot - 1);
            String receiver = before.substring(start, dot - 1).strip();
            if (receiver.isEmpty() || receiver.matches("\\d+")) return null;
            stand = "Object " + HERE + " = (" + receiver + ");";
            if (receiver.matches("[\\w.]+")) stand += " " + receiver + " " + TYPE + ";";
        }
        String braces =
                braces(before.substring(0, start)) + " " + stand + " " + braces(current.substring(before.length()));
        return new Patch(text.substring(0, starts[line]) + braces + text.substring(lineEnd), member);
    }

    private static boolean inCommentOrString(String text) {
        boolean string = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && string) i++;
            else if (c == '"') string = !string;
            else if (!string && text.startsWith("//", i)) return true;
        }
        return string;
    }

    // Back over names, dots, calls, indexes and strings: ball.pos, balls.get(0), "a"
    private static int receiverStart(String text, int end) {
        int i = end, depth = 0;
        while (i > 0) {
            char c = text.charAt(i - 1);
            if (c == ')' || c == ']') depth++;
            else if (c == '(' || c == '[') {
                if (depth == 0) break;
                depth--;
            } else if (c == '"' && depth == 0) {
                int open = text.lastIndexOf('"', i - 2);
                if (open < 0) break;
                i = open;
                continue;
            } else if (depth == 0 && !Character.isJavaIdentifierPart(c) && c != '.') break;
            i--;
        }
        String rest = text.substring(0, i).stripTrailing();
        if (rest.endsWith("new")
                && (rest.length() == 3 || !Character.isJavaIdentifierPart(rest.charAt(rest.length() - 4))))
            return rest.length() - 3;
        return i;
    }

    private static String braces(String text) {
        return text.replaceAll("[^{}]", "");
    }

    // Names in scope at the patched line, or members of what's before the dot
    List<Completion> completions(boolean member) {
        if (unit == null) return List.of();
        TreePath[] found = new TreePath[2];
        TreePathScanner<Void, Void> finder = new TreePathScanner<>() {
            @Override
            public Void visitVariable(VariableTree tree, Void unused) {
                if (tree.getName().contentEquals(HERE)) found[0] = getCurrentPath();
                if (tree.getName().contentEquals(TYPE)) found[1] = getCurrentPath();
                return super.visitVariable(tree, unused);
            }
        };
        finder.scan(unit, null);
        for (CompilationUnitTree tab : javaUnits.keySet()) finder.scan(tab, null);

        Map<String, List<Element>> names = new LinkedHashMap<>();
        if (found[0] == null) {
            // The line couldn't be patched, this is the last good analysis
            if (member || unit.getTypeDecls().isEmpty()) return List.of();
            TreePath sketch =
                    new TreePath(new TreePath(unit), unit.getTypeDecls().getFirst());
            inScope(names, null, (TypeElement) trees.getElement(sketch), unit);
        } else {
            TreePath value = new TreePath(found[0], ((VariableTree) found[0].getLeaf()).getInitializer());
            Scope scope = trees.getScope(value);
            if (!member) inScope(names, scope, scope.getEnclosingClass(), found[0].getCompilationUnit());
            else if (!membersOf(names, scope, value, found[1])) return List.of(new Completion("length", null, 1));
        }
        List<Completion> completions = new ArrayList<>();
        names.forEach((key, list) -> completions.add(
                new Completion(list.getFirst().getSimpleName().toString(), list.getFirst(), list.size())));
        return completions;
    }

    private void inScope(Map<String, List<Element>> names, Scope scope, TypeElement inside, CompilationUnitTree in) {
        for (Scope s = scope; s != null; s = s.getEnclosingScope()) {
            for (Element e : s.getLocalElements()) add(names, e);
        }
        for (Element c = inside; c instanceof TypeElement type; c = type.getEnclosingElement()) {
            for (Element e : elements.getAllMembers(type)) {
                if (e.getEnclosingElement() instanceof TypeElement owner
                        && !owner.getQualifiedName().contentEquals("java.lang.Object")) add(names, e);
            }
        }
        // Classes in the .java tabs, and the sketch for them
        for (CompilationUnitTree tab :
                Stream.concat(Stream.of(unit), javaUnits.keySet().stream()).toList()) {
            for (Tree type : tab.getTypeDecls()) {
                Element e = trees.getElement(new TreePath(new TreePath(tab), type));
                if (e != null) add(names, e);
            }
        }
        // Types from the file's imports, which for a .pde include the ones Processing adds
        List<String> imports = new ArrayList<>(List.of("java.lang.*"));
        for (ImportTree i : in.getImports())
            if (!i.isStatic()) imports.add(i.getQualifiedIdentifier().toString());
        for (String name : imports) {
            if (name.endsWith(".*")) {
                PackageElement pkg = elements.getPackageElement(name.substring(0, name.length() - 2));
                if (pkg != null) for (Element e : pkg.getEnclosedElements()) add(names, e);
            } else {
                TypeElement type = elements.getTypeElement(name);
                if (type != null) add(names, type);
            }
        }
    }

    // False when the receiver is an array
    private boolean membersOf(Map<String, List<Element>> names, Scope scope, TreePath value, TreePath type) {
        TypeMirror mirror = trees.getTypeMirror(value);
        boolean statics = false;
        if (mirror != null && mirror.getKind() == TypeKind.ARRAY) return false;
        if (mirror == null || mirror.getKind() != TypeKind.DECLARED) {
            // PVector.random2D(), the receiver is a type
            Element named = type == null
                    ? null
                    : trees.getElement(new TreePath(type, ((VariableTree) type.getLeaf()).getType()));
            if (!(named instanceof TypeElement typeElement)) return true;
            mirror = typeElement.asType();
            statics = true;
        }
        DeclaredType declared = (DeclaredType) mirror;
        for (Element e : elements.getAllMembers((TypeElement) declared.asElement())) {
            boolean isStatic = e.getModifiers().contains(Modifier.STATIC)
                    || e.getKind().isClass()
                    || e.getKind().isInterface();
            if (isStatic == statics && trees.isAccessible(scope, e, declared)) add(names, e);
        }
        return true;
    }

    private static final Set<String> UNDOCUMENTED = Set.of("surface", "sketchPath", "dataPath");

    private void add(Map<String, List<Element>> names, Element e) {
        String name = e.getSimpleName().toString();
        ElementKind kind = e.getKind();
        if (name.isEmpty() || name.startsWith("__") || name.equals("this") || name.equals("super")) return;
        if (kind == ElementKind.CONSTRUCTOR || kind == ElementKind.STATIC_INIT || kind == ElementKind.INSTANCE_INIT)
            return;
        // Package-private types from imports
        if (!e.getModifiers().contains(Modifier.PUBLIC)
                && e.getEnclosingElement() instanceof PackageElement pkg
                && !pkg.isUnnamed()) return;
        if (!(e.getEnclosingElement() instanceof TypeElement owner)) {
            names.computeIfAbsent(name, k -> new ArrayList<>()).add(e);
            return;
        }
        // Written by the preprocessor
        if (owner.getQualifiedName().contentEquals(className) && (name.equals("main") || name.equals("settings")))
            return;
        // PApplet and PGraphics have lots of public internals, only offer what's in the reference
        if (owner.getQualifiedName().toString().matches("processing\\.core\\.(PApplet|PGraphics)")
                && !Reference.documented(e)
                && !UNDOCUMENTED.contains(name)) return;
        names.computeIfAbsent(name + (kind == ElementKind.METHOD ? "()" : ""), k -> new ArrayList<>())
                .add(e);
    }

    String signature(Element element) {
        return Reference.signature(element, className);
    }

    // The innermost name at a position: an identifier, member name or declaration name
    private TreePath pathAt(Path file, int line, int column) {
        if (unit == null) return null;
        CompilationUnitTree unit = this.unit;
        int offset = -1;
        for (Map.Entry<CompilationUnitTree, JavaTab> entry : javaUnits.entrySet()) {
            JavaTab tab = entry.getValue();
            if (tab.file.equals(file) && line < tab.lineStarts.length) {
                unit = entry.getKey();
                offset = Math.min(tab.lineStarts[line] + column, tab.text.length());
            }
        }
        if (unit == this.unit) {
            offset = sketchOffset(file, line, column);
            if (offset < 0) return null;
        }
        return pathAt(unit, offset);
    }

    private TreePath pathAt(CompilationUnitTree unit, int offset) {
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
                int start = nameOffset(unit, tree, name);
                if (start >= 0 && offset >= start && offset <= start + name.length()) found[0] = getCurrentPath();
            }
        }.scan(unit, null);
        return found[0];
    }

    // Where a declaration's name is in a unit's source, the first match after its type
    private int nameOffset(CompilationUnitTree unit, Tree tree, String name) {
        SourcePositions positions = trees.getSourcePositions();
        long from = positions.getStartPosition(unit, tree);
        Tree type =
                tree instanceof VariableTree v ? v.getType() : tree instanceof MethodTree m ? m.getReturnType() : null;
        if (type != null && positions.getEndPosition(unit, type) > from) from = positions.getEndPosition(unit, type);
        if (from < 0 || name.isEmpty()) return -1;
        String text = unit == this.unit ? java : javaUnits.get(unit).text;
        int at = text.indexOf(name, (int) from);
        long end = positions.getEndPosition(unit, tree);
        return at < 0 || (end >= 0 && at > end) ? -1 : at;
    }

    private Spot javaSpot(int javaOffset) {
        return combinedSpot(mapper.getInputOffset(Math.max(javaOffset, 0)));
    }

    private static Spot tabSpot(JavaTab tab, int offset) {
        int local = Math.max(0, Math.min(offset, tab.text.length()));
        int line = 0;
        while (line + 1 < tab.lineStarts.length && tab.lineStarts[line + 1] <= local) line++;
        return new Spot(tab.file, line, local - tab.lineStarts[line]);
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

    // Where a .pde position is in the generated Java
    private int sketchOffset(Path file, int line, int column) {
        int pde = combinedOffset(file, line, column);
        if (pde < 0) return -1;
        for (Tab tab : tabs) {
            if (!tab.file.equals(file)) continue;
            int lineStart = tab.lineStarts[line];
            int lineEnd = line + 1 < tab.lineStarts.length ? tab.lineStarts[line + 1] : tab.text.length();
            String text = tab.text.substring(lineStart, lineEnd);
            int start = Math.min(column, text.length());
            while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
            int end = start;
            while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
            String word = text.substring(start, end);
            int mapped = mapper.getOutputOffset(pde - (column - start));
            if (word.isEmpty() || java.startsWith(word, mapped)) return mapped + (column - start);

            // Processing moves size(), fullScreen(), smooth() and pixelDensity() into a settings() it
            // writes, where the mapping can't follow. Find the same statement there instead
            String statement = text.strip();
            int at = java.indexOf(statement);
            if (at >= 0) return at + column - text.indexOf(statement);
            return mapper.getOutputOffset(pde);
        }
        return -1;
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
