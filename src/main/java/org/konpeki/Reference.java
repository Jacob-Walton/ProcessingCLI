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
    record Parameter(String name, String description) {}

    record Entry(String name, String description, List<String> syntax, List<Parameter> parameters, String returns) {}

    // Their members are documented as plain functions and variables, like ellipse() and mouseX
    private static final Set<String> GLOBAL = Set.of("PApplet", "PGraphics", "PConstants");

    private static Map<String, Entry> entries;

    static String hover(Element element, String className) {
        StringBuilder text = new StringBuilder("```java\n")
                .append(signature(element, className))
                .append("\n```");
        String key = key(element);
        Entry entry = key == null ? null : entries().get(key);
        if (entry != null) {
            text.append("\n\n").append(entry.description());
            if (!entry.parameters().isEmpty()) {
                text.append("\n");
                for (Parameter p : entry.parameters()) {
                    text.append("\n- `").append(p.name()).append("`: ").append(p.description());
                }
            }
            text.append("\n\n[Reference](https://processing.org/reference/")
                    .append(key)
                    .append(".html)");
        }
        return text.toString();
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

    private static String signature(Element element, String className) {
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
    private static String type(TypeMirror type) {
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
