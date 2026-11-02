package org.konpeki;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.EmptyStatementTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Warnings javac doesn't give
 */
final class Lint {
    // text is the source at start
    record Finding(CompilationUnitTree unit, int start, int end, String text, String message, boolean unused) {}

    private static final Set<String> LOADERS = Set.of(
            "loadImage",
            "requestImage",
            "loadFont",
            "createFont",
            "loadShape",
            "loadShader",
            "loadStrings",
            "loadBytes",
            "loadJSONObject",
            "loadJSONArray",
            "loadTable",
            "loadXML",
            "createGraphics");

    // Documented ones only
    private static final Set<String> BUILT_INS = Set.of(
            "width",
            "height",
            "pixelWidth",
            "pixelHeight",
            "displayWidth",
            "displayHeight",
            "mouseX",
            "mouseY",
            "pmouseX",
            "pmouseY",
            "mousePressed",
            "mouseButton",
            "key",
            "keyCode",
            "keyPressed",
            "frameCount",
            "frameRate",
            "pixels",
            "focused");

    private final Trees trees;
    private final Types types;
    private final Elements elements;
    private final String className;
    private final Set<Element> used = new HashSet<>();
    private final Set<String> usedPackages = new HashSet<>();
    private final List<Finding> findings = new ArrayList<>();

    private Lint(Trees trees, Types types, Elements elements, String className) {
        this.trees = trees;
        this.types = types;
        this.elements = elements;
        this.className = className;
    }

    static List<Finding> check(
            List<CompilationUnitTree> units, Trees trees, Types types, Elements elements, String className) {
        Lint lint = new Lint(trees, types, elements, className);
        for (CompilationUnitTree unit : units) lint.uses(unit);
        for (CompilationUnitTree unit : units) lint.rules(unit);
        return lint.findings;
    }

    private void uses(CompilationUnitTree unit) {
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitImport(ImportTree tree, Void unused) {
                return null;
            }

            @Override
            public Void visitIdentifier(IdentifierTree tree, Void unused) {
                use(trees.getElement(getCurrentPath()));
                return super.visitIdentifier(tree, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
                use(trees.getElement(getCurrentPath()));
                return super.visitMemberSelect(tree, unused);
            }
        }.scan(unit, null);
    }

    private void use(Element element) {
        if (element == null) return;
        used.add(element);
        if (element instanceof TypeElement type) {
            Element owner = type.getEnclosingElement();
            if (owner instanceof PackageElement p)
                usedPackages.add(p.getQualifiedName().toString());
            if (owner instanceof TypeElement t)
                usedPackages.add(t.getQualifiedName().toString());
        }
    }

    private void rules(CompilationUnitTree unit) {
        SourcePositions positions = trees.getSourcePositions();
        String source;
        try {
            source = unit.getSourceFile().getCharContent(true).toString();
        } catch (IOException e) {
            return;
        }

        new TreePathScanner<Void, Void>() {
            private void add(Tree tree, String message, boolean unused) {
                int start = (int) positions.getStartPosition(unit, tree);
                int end = (int) positions.getEndPosition(unit, tree);
                if (start < 0 || end < start) return;
                findings.add(new Finding(unit, start, end, source.substring(start, end), message, unused));
            }

            private void addName(Tree tree, String name, String message, boolean unused) {
                int start = nameOffset(tree, name);
                if (start >= 0) findings.add(new Finding(unit, start, start + name.length(), name, message, unused));
            }

            // First match after the type
            private int nameOffset(Tree tree, String name) {
                long from = positions.getStartPosition(unit, tree);
                Tree type = tree instanceof VariableTree v
                        ? v.getType()
                        : tree instanceof MethodTree m ? m.getReturnType() : null;
                if (type != null && positions.getEndPosition(unit, type) > from) {
                    from = positions.getEndPosition(unit, type);
                }
                if (from < 0) return -1;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                                "\\b" + java.util.regex.Pattern.quote(name) + "\\b")
                        .matcher(source);
                return m.find((int) from) ? m.start() : -1;
            }

            @Override
            public Void visitImport(ImportTree tree, Void unused) {
                if (tree.isStatic() || !(tree.getQualifiedIdentifier() instanceof MemberSelectTree name)) return null;
                boolean needed = name.getIdentifier().contentEquals("*")
                        ? usedPackages.contains(name.getExpression().toString())
                        : used.contains(trees.getElement(new TreePath(getCurrentPath(), name)));
                if (!needed) add(tree, "Unused import", true);
                return null;
            }

            @Override
            public Void visitVariable(VariableTree tree, Void unused) {
                Element element = trees.getElement(getCurrentPath());
                String name = tree.getName().toString();
                if (element != null && !name.equals("_")) variable(tree, element, name);
                return super.visitVariable(tree, unused);
            }

            private void variable(VariableTree tree, Element element, String name) {
                if (BUILT_INS.contains(name) && element.getKind() != ElementKind.PARAMETER && sketchMember(element)) {
                    addName(tree, name, name + " hides Processing's " + name, false);
                }
                if (used.contains(element)) return;
                switch (element.getKind()) {
                    case LOCAL_VARIABLE, RESOURCE_VARIABLE -> addName(tree, name, name + " is never used", true);
                    case FIELD -> {
                        // Records read theirs through accessors
                        boolean component = element.getEnclosingElement().getKind() == ElementKind.RECORD;
                        if (!component && !name.equals("serialVersionUID")) {
                            addName(tree, name, name + " is never used", true);
                        }
                    }
                    case PARAMETER -> {
                        if (ownParameter()) addName(tree, name, name + " is never used", true);
                    }
                    default -> {}
                }
            }

            // Not a lambda's, an override's or a callback's
            private boolean ownParameter() {
                Tree parent = getCurrentPath().getParentPath().getLeaf();
                if (parent instanceof LambdaExpressionTree || !(parent instanceof MethodTree method)) return false;
                if (method.getBody() == null) return false;
                if (!(trees.getElement(getCurrentPath().getParentPath()) instanceof ExecutableElement executable)) {
                    return false;
                }
                String name = method.getName().toString();
                if (name.equals("main") || Analysis.CALLBACKS.contains(name)) return false;
                // A record's own constructor
                if (executable.getEnclosingElement().getKind() == ElementKind.RECORD) return false;
                return !overrides(executable);
            }

            @Override
            public Void visitMethod(MethodTree tree, Void unused) {
                Element element = trees.getElement(getCurrentPath());
                String name = tree.getName().toString();
                if (element != null && sketchMember(element) && !Analysis.CALLBACKS.contains(name)) {
                    for (String callback : Analysis.CALLBACKS) {
                        if (callback.equalsIgnoreCase(name)) {
                            addName(tree, name, "Processing calls " + callback + "(), not " + name + "()", false);
                        }
                    }
                }
                return super.visitMethod(tree, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                Element called = trees.getElement(getCurrentPath());
                if (called != null
                        && LOADERS.contains(called.getSimpleName().toString())
                        && called.getEnclosingElement() instanceof TypeElement owner
                        && owner.getQualifiedName().contentEquals("processing.core.PApplet")
                        && inDraw()) {
                    add(
                            tree.getMethodSelect(),
                            called.getSimpleName() + "() in draw() runs every frame, call it in setup()",
                            false);
                }
                return super.visitMethodInvocation(tree, unused);
            }

            private boolean inDraw() {
                for (TreePath path = getCurrentPath(); path != null; path = path.getParentPath()) {
                    if (path.getLeaf() instanceof MethodTree method) {
                        return method.getName().contentEquals("draw")
                                && method.getParameters().isEmpty()
                                && trees.getElement(path) instanceof Element e
                                && sketchMember(e);
                    }
                }
                return false;
            }

            @Override
            public Void visitBinary(BinaryTree tree, Void unused) {
                switch (tree.getKind()) {
                    case DIVIDE -> {
                        if (tree.getLeftOperand() instanceof LiteralTree left
                                && tree.getRightOperand() instanceof LiteralTree right
                                && left.getValue() instanceof Integer a
                                && right.getValue() instanceof Integer b
                                && b != 0
                                && a % b != 0) {
                            add(tree, "Integer division, " + a + "/" + b + " is " + a / b, false);
                        }
                    }
                    case EQUAL_TO, NOT_EQUAL_TO -> {
                        if (string(tree.getLeftOperand()) && string(tree.getRightOperand())) {
                            add(tree, "Compare strings with equals()", false);
                        }
                    }
                    default -> {}
                }
                return super.visitBinary(tree, unused);
            }

            private boolean string(ExpressionTree tree) {
                TypeMirror type = trees.getTypeMirror(new TreePath(getCurrentPath(), tree));
                return type != null
                        && type.getKind() == TypeKind.DECLARED
                        && types.asElement(type) instanceof TypeElement t
                        && t.getQualifiedName().contentEquals("java.lang.String");
            }

            @Override
            public Void visitIf(IfTree tree, Void unused) {
                assignment(tree.getCondition());
                empty(tree.getThenStatement(), "if");
                return super.visitIf(tree, unused);
            }

            @Override
            public Void visitWhileLoop(WhileLoopTree tree, Void unused) {
                assignment(tree.getCondition());
                empty(tree.getStatement(), "while");
                return super.visitWhileLoop(tree, unused);
            }

            @Override
            public Void visitForLoop(ForLoopTree tree, Void unused) {
                empty(tree.getStatement(), "for");
                return super.visitForLoop(tree, unused);
            }

            @Override
            public Void visitEnhancedForLoop(EnhancedForLoopTree tree, Void unused) {
                empty(tree.getStatement(), "for");
                return super.visitEnhancedForLoop(tree, unused);
            }

            private void empty(StatementTree body, String keyword) {
                if (body instanceof EmptyStatementTree) add(body, "; ends the " + keyword + " here", false);
            }

            private void assignment(ExpressionTree condition) {
                while (condition instanceof ParenthesizedTree p) condition = p.getExpression();
                if (condition instanceof AssignmentTree) add(condition, "= assigns, == compares", false);
            }
        }.scan(unit, null);
    }

    // In the sketch class, not one inside it
    private boolean sketchMember(Element element) {
        for (Element e = element; e != null; e = e.getEnclosingElement()) {
            if (e instanceof TypeElement type) return type.getQualifiedName().contentEquals(className);
        }
        return false;
    }

    private boolean overrides(ExecutableElement method) {
        if (!(method.getEnclosingElement() instanceof TypeElement owner)) return false;
        List<TypeMirror> pending = new ArrayList<>(types.directSupertypes(owner.asType()));
        while (!pending.isEmpty()) {
            TypeMirror next = pending.removeFirst();
            if (!(types.asElement(next) instanceof TypeElement type)) continue;
            for (ExecutableElement candidate : ElementFilter.methodsIn(type.getEnclosedElements())) {
                if (elements.overrides(method, candidate, owner)) return true;
            }
            pending.addAll(types.directSupertypes(next));
        }
        return false;
    }

    static int run(List<String> args) throws IOException {
        if (args.isEmpty()) {
            System.err.println("Usage: ProcessingCLI lint SKETCH...");
            return 2;
        }
        StandardJavaFileManager files =
                ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, StandardCharsets.UTF_8);
        int errors = 0, warnings = 0;
        for (String arg : args) {
            Path dir = Path.of(arg).toAbsolutePath().normalize();
            if (Files.isRegularFile(dir)) dir = dir.getParent();
            Analysis analysis = Analysis.of(dir, Map.of(), files);
            if (analysis.files().isEmpty()) {
                System.err.println("Not a sketch: " + dir);
                return 2;
            }
            List<Analysis.Problem> problems = analysis.problems.stream()
                    .sorted(java.util.Comparator.comparing(
                                    (Analysis.Problem p) -> p.start().file())
                            .thenComparingInt(p -> p.start().line())
                            .thenComparingInt(p -> p.start().column()))
                    .toList();
            for (Analysis.Problem problem : problems) {
                if (problem.error()) errors++;
                else warnings++;
                Analysis.Spot spot = problem.start();
                System.out.println(spot.file().getFileName() + ":" + (spot.line() + 1) + ": "
                        + (problem.error() ? "error" : "warning") + ": " + problem.message());
                String text = analysis.text(spot.file());
                List<String> lines = text == null ? List.of() : text.lines().toList();
                if (spot.line() < lines.size()) {
                    String line = lines.get(spot.line());
                    System.out.println(line);
                    // Keep tabs so ^ lines up
                    String before = line.substring(0, Math.min(spot.column(), line.length()));
                    System.out.println(before.replaceAll("[^\t]", " ") + "^");
                }
            }
        }
        if (errors > 0) System.out.println(errors + (errors == 1 ? " error" : " errors"));
        if (warnings > 0) System.out.println(warnings + (warnings == 1 ? " warning" : " warnings"));
        return errors + warnings > 0 ? 1 : 0;
    }
}
