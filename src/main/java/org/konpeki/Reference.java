package org.konpeki;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;

final class Reference {
    record Parameter(String name, String description, List<String> type) {}

    record Entry(String name, String description, List<String> syntax, List<Parameter> parameters, String returns) {}

    // Their members are documented as plain functions and variables, like ellipse() and mouseX
    private static final Set<String> GLOBAL = Set.of("PApplet", "PGraphics", "PConstants");

    private static Map<String, Entry> entries;

    static String hover(Element element, String className, String docs) {
        return "```java\n" + signature(element, className) + "\n```" + (docs == null ? "" : "\n\n" + docs);
    }

    static String docs(Element element) {
        String key = key(element);
        Entry entry = key == null ? null : entries().get(key);
        if (entry == null) return null;
        StringBuilder text = new StringBuilder(entry.description());
        if (!entry.parameters().isEmpty()) {
            text.append("\n");
            for (Parameter p : entry.parameters()) {
                text.append("\n- `").append(p.name()).append("`: ").append(p.description());
            }
        }
        return text.append("\n\n[Reference](https://processing.org/reference/")
                .append(key)
                .append(".html)")
                .toString();
    }

    static boolean documented(Element element) {
        return entry(element) != null;
    }

    static String summary(Element element) {
        Entry entry = entry(element);
        return entry == null ? null : entry.description();
    }

    // Names from the syntax line with matching types, fill(int) is fill(rgb)
    static List<Parameter> parameters(ExecutableElement method) {
        List<Parameter> own = method.getParameters().stream()
                .map(p -> new Parameter(p.getSimpleName().toString(), null, List.of()))
                .toList();
        Entry entry = entry(method);
        if (entry == null) return own;
        Map<String, Parameter> documented =
                entry.parameters().stream().collect(Collectors.toMap(Parameter::name, p -> p, (a, b) -> a));
        boolean named = own.stream().noneMatch(p -> p.name().matches("arg\\d+"));
        List<Parameter> best = null;
        int bestScore = -1;
        for (String syntax : named ? List.<String>of() : entry.syntax()) {
            int open = syntax.indexOf('('), close = syntax.lastIndexOf(')');
            if (open < 0 || close < open) continue;
            String inside = syntax.substring(open + 1, close).strip();
            List<String> names = inside.isEmpty() ? List.of() : List.of(inside.split("\\s*,\\s*"));
            if (names.size() != own.size()) continue;
            int score = 0;
            for (int i = 0; i < names.size(); i++) {
                Parameter p = documented.get(names.get(i));
                String type = type(method.getParameters().get(i).asType());
                if (p != null && p.type() != null && p.type().contains(type)) score++;
            }
            if (score > bestScore) {
                bestScore = score;
                best = names.stream()
                        .map(n -> documented.getOrDefault(n, new Parameter(n, null, List.of())))
                        .toList();
            }
        }
        if (best != null) return best;
        // Real names, maybe described in the reference
        return own.stream().map(p -> documented.getOrDefault(p.name(), p)).toList();
    }

    private static Entry entry(Element element) {
        String key = key(element);
        return key == null ? null : entries().get(key);
    }

    // File names in the reference: ellipse_, mouseX, PVector, PVector_add_
    private static String key(Element element) {
        if (element instanceof TypeElement type) {
            return isDocumented(type) ? type.getSimpleName().toString() : null;
        }
        if (!(element.getEnclosingElement() instanceof TypeElement owner)) return null;
        String name = element.getSimpleName().toString();
        String suffix = element.getKind() == ElementKind.METHOD ? "_" : "";
        if (element.getKind() == ElementKind.CONSTRUCTOR)
            return isDocumented(owner) ? owner.getSimpleName().toString() : null;

        // setup(), draw(), mousePressed() and friends declared in the sketch
        if (isSketch(owner)) return entries().containsKey(name + suffix) ? name + suffix : null;
        if (!isDocumented(owner)) return null;
        String ownerName = owner.getSimpleName().toString();
        return GLOBAL.contains(ownerName) ? name + suffix : ownerName + "_" + name + suffix;
    }

    private static boolean isDocumented(TypeElement type) {
        String name = type.getQualifiedName().toString();
        return name.startsWith("processing.")
                || entries().containsKey(type.getSimpleName().toString());
    }

    private static boolean isSketch(TypeElement type) {
        TypeMirror superclass = type.getSuperclass();
        return superclass instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement parent
                && parent.getQualifiedName().contentEquals("processing.core.PApplet");
    }

    static String signature(Element element, String className) {
        String name = element.getSimpleName().toString();
        String text =
                switch (element) {
                    case ExecutableElement method -> {
                        String params = method.getParameters().stream()
                                .map(p -> type(p.asType())
                                        + (p.getSimpleName().toString().matches("arg\\d+")
                                                ? ""
                                                : " " + p.getSimpleName()))
                                .collect(Collectors.joining(", "));
                        yield method.getKind() == ElementKind.CONSTRUCTOR
                                ? method.getEnclosingElement().getSimpleName() + "(" + params + ")"
                                : type(method.getReturnType()) + " " + name + "(" + params + ")";
                    }
                    case VariableElement variable ->
                        type(variable.asType()) + " " + name
                                + (variable.getConstantValue() != null ? " = " + variable.getConstantValue() : "");
                    case TypeElement type ->
                        type.getKind().toString().toLowerCase().replace('_', ' ') + " " + name;
                    default -> element.toString();
                };
        // The sketch is one class, so its inner classes read as Sketch.Ball
        return text.replace(className + ".", "");
    }

    // processing.core.PVector -> PVector
    static String type(TypeMirror type) {
        return type.toString().replaceAll("\\b(?:[a-z_]\\w*\\.)+(?=[A-Z])", "");
    }

    private static synchronized Map<String, Entry> entries() {
        if (entries == null) {
            try (Reader reader = new InputStreamReader(
                    Reference.class.getResourceAsStream("reference.json"), StandardCharsets.UTF_8)) {
                entries = new Gson().fromJson(reader, new TypeToken<Map<String, Entry>>() {}.getType());
            } catch (Exception e) {
                entries = Map.of();
            }
        }
        return entries;
    }
}
