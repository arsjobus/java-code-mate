package com.javelin_ide.editor;

/**
 * Find / Find Next / Find Previous as pure text lookups, so they can be tested without JavaFX.
 *
 * <p>Matching is case-sensitive and literal. Matches may overlap ({@code "aa"} matches twice in {@code "aaa"}).
 * Searching wraps around the document, and an empty query never matches.
 */
public final class TextSearch {
    private TextSearch() {}

    /** A match, as the {@code [start, end)} range to select. */
    public record Match(int start, int end) {}

    /**
     * The first match starting at or after {@code from}, wrapping to the start of the text; null when there is none.
     * This is what the first Find does, starting at the caret.
     */
    public static Match first(String text, String query, int from) {
        if (query.isEmpty()) return null;
        int at = text.indexOf(query, Math.max(0, Math.min(from, text.length())));
        if (at < 0) at = text.indexOf(query);
        return at < 0 ? null : new Match(at, at + query.length());
    }

    /**
     * The match after the current selection, wrapping to the start of the text. When the selection is itself a
     * match the search moves past it; otherwise (a caret, or some other selected text) it starts at the selection.
     */
    public static Match next(String text, String query, int selStart, int selEnd) {
        return first(text, query, isMatchAt(text, query, selStart, selEnd) ? selStart + 1 : selStart);
    }

    /**
     * The match before the current selection, wrapping to the end of the text; null when there is none. The
     * match found always starts before {@code selStart}, so repeating it walks backwards through the document.
     */
    public static Match previous(String text, String query, int selStart) {
        if (query.isEmpty()) return null;
        int at = selStart <= 0 ? -1 : text.lastIndexOf(query, selStart - 1);
        if (at < 0) at = text.lastIndexOf(query);
        return at < 0 ? null : new Match(at, at + query.length());
    }

    /** How many matches the text holds. */
    public static int count(String text, String query) {
        if (query.isEmpty()) return 0;
        int n = 0;
        for (int at = text.indexOf(query); at >= 0; at = text.indexOf(query, at + 1)) n++;
        return n;
    }

    /** The 1-based position of the match that starts at {@code start}, or 0 when no match starts there. */
    public static int ordinal(String text, String query, int start) {
        if (query.isEmpty() || !text.startsWith(query, start)) return 0;
        int n = 0;
        for (int at = text.indexOf(query); at >= 0 && at <= start; at = text.indexOf(query, at + 1)) n++;
        return n;
    }

    private static boolean isMatchAt(String text, String query, int selStart, int selEnd) {
        return !query.isEmpty() && selEnd - selStart == query.length() && text.startsWith(query, selStart);
    }
}
