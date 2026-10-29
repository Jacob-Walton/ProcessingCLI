package org.konpeki;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Processing style formatting, spacing and indentation only
 */
final class Format {
    private Format() {}

    enum Kind {
        SPACE,
        NEWLINE,
        COMMENT,
        LINE_COMMENT,
        STRING,
        CHAR,
        NUMBER,
        WORD,
        HEX,
        OP
    }

    record Token(Kind kind, String text) {
        boolean is(String s) {
            return text.equals(s);
        }

        boolean code() {
            return kind != Kind.SPACE && kind != Kind.NEWLINE && kind != Kind.COMMENT && kind != Kind.LINE_COMMENT;
        }

        boolean wordLike() {
            return kind == Kind.WORD
                    || kind == Kind.NUMBER
                    || kind == Kind.STRING
                    || kind == Kind.CHAR
                    || kind == Kind.HEX;
        }
    }

    private static final List<String> OPS = List.of(
            ">>>=", "<<=", ">>=", ">>>", "...", "->", "::", "++", "--", "&&", "||", "==", "!=", "<=", ">=", "+=", "-=",
            "*=", "/=", "%=", "&=", "|=", "^=", "<<", ">>");
    private static final Set<String> CONTROL = Set.of("if", "for", "while", "switch", "catch", "synchronized", "try");
    // Followed by a new expression
    private static final Set<String> KEYWORDS =
            Set.of("return", "case", "throw", "assert", "yield", "else", "do", "new", "instanceof");
    // Left as written
    private static final Set<String> AS_WRITTEN = Set.of("+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>");
    private static final Set<String> NOT_BINARY = Set.of("(", ")", "[", "]", "{", "}", ",", ";", ".", "::", "@", "...");

    // Null if it would change the code
    static String format(String text, String unit) {
        List<Token> tokens = tokens(text);
        String spaced = space(tokens);
        if (!sameCode(tokens, tokens(spaced))) return null;
        String formatted = indent(blankLines(spaced), unit);
        return sameCode(tokens, tokens(formatted)) ? formatted : null;
    }

    static List<Token> tokens(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            int start = i;
            Kind kind;
            if (c == ' ' || c == '\t' || c == '\f') {
                while (i < n && " \t\f".indexOf(text.charAt(i)) >= 0) i++;
                kind = Kind.SPACE;
            } else if (c == '\r' || c == '\n') {
                i += text.startsWith("\r\n", i) ? 2 : 1;
                kind = Kind.NEWLINE;
            } else if (text.startsWith("//", i)) {
                while (i < n && text.charAt(i) != '\n' && text.charAt(i) != '\r') i++;
                kind = Kind.LINE_COMMENT;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                kind = Kind.COMMENT;
            } else if (text.startsWith("\"\"\"", i)) {
                int end = i + 3;
                while (end < n && !text.startsWith("\"\"\"", end)) end += text.charAt(end) == '\\' ? 2 : 1;
                i = Math.min(end + 3, n);
                kind = Kind.STRING;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && text.charAt(i) != c && text.charAt(i) != '\n') i += text.charAt(i) == '\\' ? 2 : 1;
                i = Math.min(i + 1, n);
                kind = c == '"' ? Kind.STRING : Kind.CHAR;
            } else if (c == '#' && i + 1 < n && Character.digit(text.charAt(i + 1), 16) >= 0) {
                i++;
                while (i < n && Character.digit(text.charAt(i), 16) >= 0) i++;
                kind = Kind.HEX;
            } else if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(text.charAt(i + 1)))) {
                boolean hex = text.startsWith("0x", i) || text.startsWith("0X", i);
                while (i < n) {
                    char d = text.charAt(i);
                    boolean exponent = !hex && (d == '+' || d == '-') && "eE".indexOf(text.charAt(i - 1)) >= 0;
                    if (!Character.isLetterOrDigit(d) && d != '.' && d != '_' && !exponent) break;
                    i++;
                }
                kind = Kind.NUMBER;
            } else if (Character.isJavaIdentifierStart(c)) {
                while (i < n && Character.isJavaIdentifierPart(text.charAt(i))) i++;
                kind = Kind.WORD;
            } else {
                int at = i;
                i += OPS.stream()
                        .filter(op -> text.startsWith(op, at))
                        .findFirst()
                        .map(String::length)
                        .orElse(1);
                kind = Kind.OP;
            }
            tokens.add(new Token(kind, text.substring(start, i)));
        }
        return tokens;
    }

    // Same tokens, comment whitespace aside
    private static boolean sameCode(List<Token> a, List<Token> b) {
        List<String> x = significant(a), y = significant(b);
        return x.equals(y);
    }

    private static List<String> significant(List<Token> tokens) {
        return tokens.stream()
                .filter(t -> t.kind != Kind.SPACE && t.kind != Kind.NEWLINE)
                .map(t -> t.kind == Kind.COMMENT || t.kind == Kind.LINE_COMMENT
                        ? t.text.replaceAll("\\s+", " ").strip()
                        : t.text)
                .toList();
    }

    private static final int NORMAL = 0, GENERIC = 1, KEEP = 2;

    private static String space(List<Token> tokens) {
        List<Integer> code = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) if (tokens.get(i).code()) code.add(i);
        int[] generic = generics(tokens, code);
        boolean[] initializer = initializers(tokens, code);

        StringBuilder out = new StringBuilder();
        int lineStart = -1;
        Token lineFirst = null;
        int pendingTernary = 0, newlines = 0;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind == Kind.SPACE) continue;
            if (t.kind == Kind.NEWLINE) {
                // At most one blank line in a row, none at the start
                if (lineStart >= 0 || (!out.isEmpty() && newlines < 2)) out.append(t.text);
                newlines = lineStart >= 0 ? 1 : newlines + 1;
                lineStart = -1;
                lineFirst = null;
                pendingTernary = 0;
                continue;
            }
            if (lineStart >= 0) {
                int p = i - 1;
                while (tokens.get(p).kind == Kind.SPACE) p--;
                String original = i - 1 > p ? tokens.get(i - 1).text : "";
                Token prev = tokens.get(p);
                String gap = gap(tokens, p, i, generic, initializer, lineFirst, pendingTernary > 0);
                // At least one space before //
                if (t.kind == Kind.LINE_COMMENT && original.isEmpty()) gap = " ";
                out.append(gap == null ? original : gap);
                if (prev.is("?") && generic[p] == NORMAL) pendingTernary++;
                if (prev.is(":") && pendingTernary > 0) pendingTernary--;
            } else {
                lineFirst = t;
                // For indent() to move block comments
                if (i > 0 && tokens.get(i - 1).kind == Kind.SPACE) out.append(tokens.get(i - 1).text);
            }
            lineStart = i;
            out.append(t.text);
        }
        return out.toString();
    }

    // Generic < and >, after a lowercase name they may be comparisons
    private static int[] generics(List<Token> tokens, List<Integer> code) {
        int[] mark = new int[tokens.size()];
        for (int k = 0; k < code.size(); k++) {
            if (!tokens.get(code.get(k)).is("<")) continue;
            int depth = 1, j = k + 1;
            for (; j < code.size() && j - k < 50 && depth > 0; j++) {
                Token t = tokens.get(code.get(j));
                switch (t.text) {
                    case "<" -> depth++;
                    case ">" -> depth--;
                    case ">>" -> depth -= 2;
                    case ">>>" -> depth -= 3;
                    case ".", ",", "?", "[", "]", "&" -> {}
                    default -> {
                        if (t.kind != Kind.WORD) depth = -100;
                    }
                }
            }
            if (depth != 0) continue;
            Token before = k > 0 ? tokens.get(code.get(k - 1)) : null;
            boolean type = before != null && before.kind == Kind.WORD && Character.isUpperCase(before.text.charAt(0));
            for (int m = k; m < j; m++) mark[code.get(m)] = Math.max(mark[code.get(m)], type ? GENERIC : KEEP);
        }
        return mark;
    }

    // Array initializers like {1, 2, 3}
    private static boolean[] initializers(List<Token> tokens, List<Integer> code) {
        boolean[] mark = new boolean[tokens.size()];
        List<Integer> open = new ArrayList<>();
        for (int k = 0; k < code.size(); k++) {
            int i = code.get(k);
            Token t = tokens.get(i);
            if (t.is("{")) {
                Token before = k > 0 ? tokens.get(code.get(k - 1)) : null;
                mark[i] = before != null
                        && (before.is("=")
                                || before.is("]")
                                || before.is(",")
                                || before.is("{") && mark[code.get(k - 1)]);
                open.add(i);
            } else if (t.is("}") && !open.isEmpty()) {
                mark[i] = mark[open.removeLast()];
            }
        }
        return mark;
    }

    // Null keeps what was written
    private static String gap(
            List<Token> tokens,
            int pi,
            int ni,
            int[] generic,
            boolean[] initializer,
            Token lineFirst,
            boolean ternary) {
        Token p = tokens.get(pi), n = tokens.get(ni);
        // { x++; }, array initializers stay as written
        if (p.is("{") && n.is("}")) return null;
        if (p.is("{") && n.kind != Kind.LINE_COMMENT) return initializer[pi] ? null : " ";
        if (n.is("}")) return initializer[ni] ? null : " ";
        if (n.kind == Kind.LINE_COMMENT) return null;
        if (p.kind == Kind.COMMENT || n.kind == Kind.COMMENT || p.kind == Kind.LINE_COMMENT) return null;
        if (generic[pi] == KEEP || generic[ni] == KEEP) return null;

        boolean pAngle = generic[pi] == GENERIC && (p.text.startsWith("<") || p.text.startsWith(">"));
        boolean nAngle = generic[ni] == GENERIC && (n.text.startsWith("<") || n.text.startsWith(">"));
        if (generic[pi] == GENERIC && generic[ni] == GENERIC) {
            if (p.is(",")) return " ";
            if (pAngle || nAngle) return "";
            return p.kind == Kind.WORD && n.kind == Kind.WORD || p.is("?") ? " " : "";
        }
        if (nAngle) return "";
        if (pAngle) {
            if (n.kind == Kind.WORD || n.is("{")) return " ";
            return NOT_BINARY.contains(n.text) || n.is("::") ? "" : " ";
        }

        Token before = previousCode(tokens, pi);
        boolean pUnary = unary(p, before);
        boolean nUnary = unary(n, p.code() ? p : previousCode(tokens, ni));
        boolean nPostfix = (n.is("++") || n.is("--")) && !nUnary;

        if (n.is(",") || n.is(";") || n.is(")") || n.is("]")) return "";
        if (p.is("(") || p.is("[")) return "";
        if (p.is(".") || n.is(".") || p.is("::") || n.is("::") || p.is("@")) return "";
        if (n.is("...")) return "";
        if (p.is("...") || p.is(",") || p.is(";")) return " ";
        if (pUnary || nPostfix) return "";
        if (n.is(":") || p.is(":")) return colon(p, n, lineFirst, ternary);
        if (binary(p)) return AS_WRITTEN.contains(p.text) ? null : " ";
        if (n.is("(")) {
            if (p.kind == Kind.WORD) return CONTROL.contains(p.text) || KEYWORDS.contains(p.text) ? " " : "";
            return p.is("]") ? "" : null;
        }
        if (n.is("[")) return p.kind == Kind.WORD || p.is(")") || p.is("]") ? "" : null;
        if (n.is("{")) return " ";
        if (binary(n) && !nUnary) return AS_WRITTEN.contains(n.text) ? null : " ";
        if (nUnary) return p.wordLike() ? " " : null;
        if (p.is("}")) return n.kind == Kind.WORD ? " " : null;
        if (p.wordLike() && n.wordLike()) return " ";
        if (p.is("]") && n.kind == Kind.WORD) return " ";
        return null;
    }

    // case 1: and labels, otherwise spaced
    private static String colon(Token p, Token n, Token lineFirst, boolean ternary) {
        boolean caseColon = !ternary
                && lineFirst != null
                && (lineFirst.is("case") || lineFirst.is("default") || lineFirst == p && p.kind == Kind.WORD);
        return n.is(":") && caseColon ? "" : " ";
    }

    private static boolean binary(Token t) {
        return t.kind == Kind.OP
                && !NOT_BINARY.contains(t.text)
                && !Set.of("!", "~", "++", "--", ":").contains(t.text);
    }

    // -x, !x, ++x
    private static boolean unary(Token t, Token before) {
        if (t.is("!") || t.is("~")) return true;
        if (!t.is("-") && !t.is("+") && !t.is("++") && !t.is("--")) return false;
        return before == null || !endsOperand(before);
    }

    private static boolean endsOperand(Token t) {
        if (t.kind == Kind.WORD) return !KEYWORDS.contains(t.text);
        return t.wordLike() || t.is(")") || t.is("]") || t.is("++") || t.is("--") || t.is(">") || t.is(">>");
    }

    private static Token previousCode(List<Token> tokens, int i) {
        for (int j = i - 1; j >= 0; j--) if (tokens.get(j).code()) return tokens.get(j);
        return null;
    }

    // No trailing spaces, and one newline at the end
    private static String blankLines(String text) {
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        String trimmed = text.replaceAll("[ \t]+(?=\r?\n|$)", "").stripTrailing();
        return trimmed.isEmpty() ? "" : trimmed + newline;
    }

    // --check only lists what would change
    static int run(List<String> args) throws IOException {
        boolean check = args.contains("--check");
        List<Path> files = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("--check")) continue;
            Path path = Path.of(arg);
            if (Files.isDirectory(path)) {
                try (Stream<Path> s = Files.list(path)) {
                    s.filter(p -> p.toString().endsWith(".pde") || p.toString().endsWith(".java"))
                            .sorted()
                            .forEach(files::add);
                }
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            } else {
                System.err.println("Not found: " + path);
                return 2;
            }
        }
        if (files.isEmpty()) {
            System.err.println("Usage: ProcessingCLI format [--check] FILE|SKETCH...");
            return 2;
        }
        boolean changed = false, failed = false;
        for (Path file : files) {
            String text = Files.readString(file);
            String formatted = format(text, "  ");
            if (formatted == null) {
                System.err.println("Couldn't format safely, left as it is: " + file);
                failed = true;
                continue;
            }
            if (formatted.equals(text)) continue;
            changed = true;
            if (!check) Files.writeString(file, formatted);
            System.out.println((check ? "Not formatted: " : "Formatted ") + file);
        }
        return failed || check && changed ? 1 : 0;
    }

    static String indent(String text, String unit) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean comment = false, hanging = false;
        // Block comments move with their first line
        int shift = 0;
        String commentIndent = "";
        // Switch body depths
        List<Integer> switches = new ArrayList<>();
        boolean switchNext = false;
        String[] lines = text.split("\r?\n", -1);
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n].strip();
            String code = comment ? "" : code(line);
            if (line.isEmpty()) {
                // Blank lines lose their indentation
            } else if (comment && line.startsWith("*")) {
                out.append(commentIndent).append(' ').append(line);
            } else if (comment) {
                int leading = lines[n].length() - lines[n].stripLeading().length();
                out.append(" ".repeat(Math.max(0, leading + shift))).append(line);
            } else {
                int level = depth;
                if (startsWithClosing(code)) level--;
                boolean caseLine = code.matches("(case\\b.*|default\\s*)(:|->).*");
                int base = level;
                for (int body : switches) if (base > body || base == body && !caseLine) level++;
                if (hanging && !code.startsWith("{")) level++;
                commentIndent = unit.repeat(Math.max(level, 0));
                shift = commentIndent.length()
                        - (lines[n].length() - lines[n].stripLeading().length());
                out.append(commentIndent).append(line);
            }
            if (n < lines.length - 1) out.append(newline);

            if (!comment) {
                depth += balance(code);
                // An if, for, while or else without braces indents the next line
                if (!code.isEmpty()) hanging = code.matches("(\\}\\s*)?((if|for|while|else\\s+if)\\b.*\\)|else)");
                if (code.matches("(\\}\\s*)?switch\\b.*")) switchNext = true;
                if (switchNext && code.endsWith("{")) {
                    switches.add(depth);
                    switchNext = false;
                }
                while (!switches.isEmpty() && depth < switches.getLast()) switches.removeLast();
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
