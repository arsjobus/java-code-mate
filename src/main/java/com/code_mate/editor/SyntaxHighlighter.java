package com.code_mate.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds the highlightable tokens (comments, strings, annotations, keywords, types, numbers) in Java-like source.
 * JavaFX-free so it can be tested on its own.
 *
 * <p>This is a hand-written scanner rather than a regular expression on purpose: Java's regex engine recurses once
 * per repetition of a group such as {@code (?:\\.|[^"\\])*}, so a long string literal (an embedded base64 blob, a
 * minified line) overflows the stack. The scanner does one pass in time linear in the text and cannot recurse.
 * Documents over {@link #MAX_CHARS} are not highlighted at all, so typing in a huge file never stalls the UI thread.
 *
 * <p>Strings and character literals end at the end of the line, so a stray quote cannot colour the rest of the
 * file; an unterminated block comment runs to the end of the text (as in most editors); text blocks
 * ({@code """}) may span lines.
 */
public final class SyntaxHighlighter {
    /** Documents longer than this get no highlighting. Roughly {@code FileIO.LARGE_FILE_BYTES} of ASCII text. */
    public static final int MAX_CHARS = 1 << 20;

    /** A highlighted range: {@code [start, end)} and the CSS style class to apply. */
    public record Span(int start, int end, String style) {}

    private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "break", "case", "catch", "class", "continue", "default", "do", "else", "enum",
        "extends", "final", "finally", "for", "if", "implements", "import", "instanceof", "interface", "new", "package", "private", "protected", "public",
        "return", "static", "super", "switch", "synchronized", "this", "throw", "throws", "try", "var", "void", "while", "record", "sealed", "yield");
    private static final Set<String> TYPES = Set.of("boolean", "byte", "char", "double", "float", "int", "long", "short", "String", "Object", "Integer", "Long",
        "Double", "Float", "Boolean", "System", "List", "Map", "Set", "File");

    private SyntaxHighlighter() {}

    /** True when {@link #spans} would produce nothing because the text is too long. */
    public static boolean tooLarge(int length) { return length > MAX_CHARS; }

    /** The tokens of the text in order, without overlaps. Empty for text over {@link #MAX_CHARS}. */
    public static List<Span> spans(String text) {
        List<Span> spans = new ArrayList<>();
        int n = text.length();
        if (tooLarge(n)) return spans;
        int i = 0;
        // Once a quote fails to close before the end of its line, no later quote of the same kind on that line can close
        // either (it would have been the closing quote, or sits inside an escape the first scan already skipped), so
        // remember where that line ends and skip them: without this a line of escaped quotes takes quadratic time.
        int doubleDeadUntil = -1, singleDeadUntil = -1;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                int end = text.indexOf('\n', i);
                i = add(spans, i, end < 0 ? n : end, "comment");
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int close = text.indexOf("*/", i + 2);
                i = add(spans, i, close < 0 ? n : close + 2, "comment");
            } else if (c == '"' && text.startsWith("\"\"\"", i)) {
                int close = text.indexOf("\"\"\"", i + 3);
                i = add(spans, i, close < 0 ? n : close + 3, "string");
            } else if (c == '"' || c == '\'') {
                if (i < (c == '"' ? doubleDeadUntil : singleDeadUntil)) { i++; continue; }
                int end = literalEnd(text, i, c);
                if (end >= 0) { i = add(spans, i, end, "string"); continue; }
                if (c == '"') doubleDeadUntil = -end - 1; else singleDeadUntil = -end - 1;
                i++;
            } else if (c == '@' && i + 1 < n && Character.isJavaIdentifierStart(text.charAt(i + 1))) {
                i = add(spans, i, identifierEnd(text, i + 1), "annotation");
            } else if (Character.isJavaIdentifierStart(c)) {
                int end = identifierEnd(text, i);
                String word = text.substring(i, end);
                if (KEYWORDS.contains(word)) add(spans, i, end, "keyword");
                else if (TYPES.contains(word)) add(spans, i, end, "type");
                i = end;
            } else if (c >= '0' && c <= '9') {
                int end = digitsEnd(text, i);
                if (end + 1 < n && text.charAt(end) == '.' && Character.isDigit(text.charAt(end + 1))) end = digitsEnd(text, end + 1);
                int word = identifierEnd(text, end); // 1x, 0x1F, 10L are one word, not a plain number
                if (word == end) add(spans, i, end, "number");
                i = word;
            } else {
                i++;
            }
        }
        return spans;
    }

    private static int add(List<Span> spans, int start, int end, String style) {
        spans.add(new Span(start, end, style));
        return end;
    }

    /** End (exclusive) of a quoted literal starting at {@code start}; when the line ends first, {@code -(index where it ended) - 1}. */
    private static int literalEnd(String text, int start, char quote) {
        int n = text.length();
        for (int i = start + 1; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n && text.charAt(i + 1) != '\n') i++;
            else if (c == quote) return i + 1;
            else if (c == '\n') return -i - 1;
        }
        return -n - 1;
    }

    private static int identifierEnd(String text, int from) {
        int i = from, n = text.length();
        while (i < n && Character.isJavaIdentifierPart(text.charAt(i)) && !Character.isIdentifierIgnorable(text.charAt(i))) i++;
        return i;
    }

    private static int digitsEnd(String text, int from) {
        int i = from, n = text.length();
        while (i < n && text.charAt(i) >= '0' && text.charAt(i) <= '9') i++;
        return i;
    }
}
