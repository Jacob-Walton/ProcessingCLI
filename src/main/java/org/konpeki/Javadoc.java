package org.konpeki;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Doc comment as Markdown
 */
record Javadoc(String description, Map<String, String> params, String returns, List<String> tags) {
    private static final Pattern INLINE = Pattern.compile("\\{@(\\w+)\\s*([^}]*)}");

    // Raw comment, as getDocComment gives it
    static Javadoc parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // Block tags run to the next @
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            if (line.strip().startsWith("@")) {
                blocks.add(current.toString());
                current = new StringBuilder(line.strip());
            } else {
                current.append('\n').append(line);
            }
        }
        blocks.add(current.toString());

        Map<String, String> params = new LinkedHashMap<>();
        String returns = null;
        List<String> tags = new ArrayList<>();
        for (String block : blocks.subList(1, blocks.size())) {
            String[] parts = block.split("\\s+", 3);
            String tag = parts[0], first = parts.length > 1 ? parts[1] : "", rest = parts.length > 2 ? parts[2] : "";
            String text = block.substring(tag.length());
            switch (tag) {
                case "@param" -> params.put(first.replaceAll("[<>]", ""), markdown(rest));
                case "@return" -> returns = markdown(text);
                case "@throws", "@exception" -> tags.add("*Throws* `" + first + "` " + markdown(rest));
                case "@deprecated" -> tags.add("**Deprecated** " + markdown(text));
                case "@see" -> tags.add("*See* " + markdown(text));
                case "@since" -> tags.add("*Since* " + markdown(text));
                default -> {}
            }
        }
        return new Javadoc(markdown(blocks.getFirst()), params, returns, tags);
    }

    String markdown() {
        StringBuilder text = new StringBuilder(description);
        if (!params.isEmpty()) {
            text.append("\n");
            params.forEach((name, about) ->
                    text.append("\n- `").append(name).append("`: ").append(about));
        }
        if (returns != null) text.append("\n\n*Returns* ").append(returns);
        for (String tag : tags) text.append("\n\n").append(tag);
        return text.toString().strip();
    }

    // Inline tags and HTML
    private static String markdown(String text) {
        Matcher m = INLINE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String body = m.group(2).strip();
            String replacement =
                    switch (m.group(1)) {
                        case "code", "literal", "value" -> "`" + body + "`";
                        // {@link Ball#move label} shows the label, or Ball.move
                        case "link", "linkplain" -> {
                            String[] target = body.split("\\s+", 2);
                            yield target.length > 1
                                    ? target[1]
                                    : "`" + target[0].replace('#', '.').replaceFirst("^\\.", "") + "`";
                        }
                        default -> body;
                    };
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        String md = out.toString()
                .replaceAll("(?i)\\s*<pre>\\s*", "\n```java\n")
                .replaceAll("(?i)\\s*</pre>\\s*", "\n```\n")
                .replaceAll("(?i)<p>|</p>|<br\\s*/?>", "\n\n")
                .replaceAll("(?i)<li>", "\n- ")
                .replaceAll("(?i)</?(b|strong)>", "**")
                .replaceAll("(?i)</?(i|em)>", "*")
                .replaceAll("(?i)</?code>", "`")
                .replaceAll("<[^>]+>", "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&");
        // Join wrapped lines
        StringBuilder joined = new StringBuilder();
        boolean code = false, joinable = false;
        for (String line : md.split("\n", -1)) {
            String trimmed = line.strip();
            boolean fence = trimmed.startsWith("```");
            if (fence) code = !code;
            if (fence || code || trimmed.isEmpty() || trimmed.startsWith("- ") || !joinable) {
                joined.append('\n').append(code && !fence ? line : trimmed);
            } else {
                joined.append(' ').append(trimmed);
            }
            joinable = !code && !fence && !trimmed.isEmpty();
        }
        return joined.toString().replaceAll("\n{3,}", "\n\n").strip();
    }
}
