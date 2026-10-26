package org.konpeki;

/**
 * Re-indents a sketch by its brackets, the way Processing's auto format lays it out.
 */
final class Format {
    private Format() {}

    static String indent(String text, String unit) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean comment = false, hanging = false;
        String[] lines = text.split("\r?\n", -1);
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n].strip();
            String code = comment ? "" : code(line);
            if (line.isEmpty()) {
                // Blank lines lose their indentation
            } else if (comment) {
                out.append(unit.repeat(depth))
                        .append(line.startsWith("*") ? " " : "")
                        .append(line);
            } else {
                int level = depth;
                if (startsWithClosing(code)) level--;
                // case sits level with its switch, like Processing does
                if (code.matches("(case\\b.*|default\\s*):.*")) level--;
                if (hanging && !code.startsWith("{")) level++;
                out.append(unit.repeat(Math.max(level, 0))).append(line);
            }
            if (n < lines.length - 1) out.append(newline);

            if (!comment) {
                depth += balance(code);
                // An if, for, while or else without braces indents the next line
                if (!code.isEmpty()) hanging = code.matches("(\\}\\s*)?((if|for|while|else\\s+if)\\b.*\\)|else)");
            }
            comment = endsInComment(line, comment);
        }
        return out.toString();
    }

    // The line with strings, chars and comments blanked out
    private static String code(String line) {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (line.startsWith("//", i)) break;
            if (line.startsWith("/*", i)) {
                int end = line.indexOf("*/", i + 2);
                if (end < 0) break;
                i = end + 1;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < line.length() && line.charAt(j) != c) j += line.charAt(j) == '\\' ? 2 : 1;
                code.append(c).append(c);
                i = j;
            } else {
                code.append(c);
            }
        }
        return code.toString().strip();
    }

    private static int balance(String code) {
        int balance = 0;
        for (char c : code.toCharArray()) {
            if (c == '{' || c == '(' || c == '[') balance++;
            if (c == '}' || c == ')' || c == ']') balance--;
        }
        return balance;
    }

    private static boolean startsWithClosing(String code) {
        return !code.isEmpty() && "})]".indexOf(code.charAt(0)) >= 0;
    }

    private static boolean endsInComment(String line, boolean comment) {
        String code = line.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
        for (int i = 0; i < code.length() - 1; i++) {
            if (!comment && code.startsWith("//", i)) return false;
            if (!comment && code.startsWith("/*", i)) {
                comment = true;
                i++;
            } else if (comment && code.startsWith("*/", i)) {
                comment = false;
                i++;
            }
        }
        return comment;
    }
}
