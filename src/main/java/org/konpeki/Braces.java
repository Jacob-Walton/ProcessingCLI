package org.konpeki;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Unmatched braces in one tab, Processing would blame the next one
 */
final class Braces {
    record Unmatched(int offset, String message) {}

    private Braces() {}

    static Unmatched find(String text) {
        Deque<Integer> open = new ArrayDeque<>();
        List<int[]> pairs = new ArrayList<>();
        int offset = 0;
        for (Format.Token t : Format.tokens(text)) {
            if (t.is("{")) {
                open.push(offset);
            } else if (t.is("}")) {
                if (open.isEmpty()) return new Unmatched(offset, "This } has no { to close");
                pairs.add(new int[] {open.pop(), offset});
            }
            offset += t.text().length();
        }
        if (open.isEmpty()) return null;
        // Usually the first pair that isn't lined up
        for (int[] pair : pairs) {
            if (indent(text, pair[0]) != indent(text, pair[1])) return missing(pair[0]);
        }
        return missing(open.getLast());
    }

    private static Unmatched missing(int offset) {
        return new Unmatched(offset, "Missing } to close this {");
    }

    private static int indent(String text, int offset) {
        int start = text.lastIndexOf('\n', offset - 1) + 1;
        int end = start;
        while (end < text.length() && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) end++;
        return end - start;
    }
}
