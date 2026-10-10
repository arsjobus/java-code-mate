package com.code_mate.language;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Plain value types for the parts of the Language Server Protocol the editor uses, with parsers for server replies. */
public final class Lsp {
    private Lsp() {}

    /** Zero-based line and UTF-16 column, which matches Java string offsets. */
    public record Position(int line, int character) {
        Map<String, Object> toJson() { return Json.obj("line", line, "character", character); }
        static Position from(Object json) { return new Position(Json.integer(json, "line", 0), Json.integer(json, "character", 0)); }
    }

    public record Range(Position start, Position end) {
        Map<String, Object> toJson() { return Json.obj("start", start.toJson(), "end", end.toJson()); }
        static Range from(Object json) {
            Object s = Json.get(json, "start"), e = Json.get(json, "end");
            if (s == null || e == null) return null;
            return new Range(Position.from(s), Position.from(e));
        }
    }

    public record Location(URI uri, Range range) {}

    public record TextEdit(Range range, String newText) {}

    /** Edits grouped by document. {@code unsupported} is true when the server also asked for file create/rename/delete. */
    public record WorkspaceEdit(Map<URI, List<TextEdit>> changes, boolean unsupported) {}

    public record Diagnostic(Range range, int severity, String message, String source) {
        public static final int ERROR = 1, WARNING = 2, INFORMATION = 3, HINT = 4;
    }

    /** A completion candidate. {@code edit} is non-null when the server specified exactly what to replace. */
    public record CompletionItem(String label, String detail, int kind, String insertText, String filterText, TextEdit edit, boolean snippet) {
        public String filter() { return filterText != null ? filterText : label; }
        /** The text to insert, with snippet placeholders flattened because the editor has no snippet mode. */
        public String text() {
            String raw = edit != null ? edit.newText() : insertText != null ? insertText : label;
            return snippet ? flattenSnippet(raw) : raw;
        }
    }

    public static Map<String, Object> textDocument(URI uri) { return Json.obj("uri", uri.toString()); }

    public static Map<String, Object> positionParams(URI uri, Position position) {
        return Json.obj("textDocument", textDocument(uri), "position", position.toJson());
    }

    public static List<Location> locations(Object result) {
        List<Location> out = new ArrayList<>();
        if (result == null) return out;
        List<Object> items = Json.asArray(result);
        if (items == null) items = List.of(result);
        for (Object item : items) {
            String uri = Json.string(item, "targetUri") != null ? Json.string(item, "targetUri") : Json.string(item, "uri"); // LocationLink or Location
            Object rangeJson = Json.get(item, "targetSelectionRange") != null ? Json.get(item, "targetSelectionRange") : Json.get(item, "range");
            Range range = Range.from(rangeJson);
            if (uri == null || range == null) continue;
            try { out.add(new Location(URI.create(uri), range)); } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    public static List<CompletionItem> completions(Object result) {
        List<Object> items = Json.asArray(result);
        if (items == null) items = Json.asArray(Json.get(result, "items")); // CompletionList
        List<CompletionItem> out = new ArrayList<>();
        if (items == null) return out;
        for (Object item : items) {
            String label = Json.string(item, "label");
            if (label == null) continue;
            Object editJson = Json.get(item, "textEdit");
            TextEdit edit = null;
            if (editJson != null) {
                Range range = Range.from(Json.get(editJson, "range") != null ? Json.get(editJson, "range") : Json.get(editJson, "insert")); // TextEdit or InsertReplaceEdit
                String newText = Json.string(editJson, "newText");
                if (range != null && newText != null) edit = new TextEdit(range, newText);
            }
            out.add(new CompletionItem(label, Json.string(item, "detail"), Json.integer(item, "kind", 0), Json.string(item, "insertText"),
                Json.string(item, "filterText"), edit, Json.integer(item, "insertTextFormat", 1) == 2));
        }
        return out;
    }

    /** Flattens Hover contents (MarkupContent, MarkedString, or an array of them) to readable text. */
    public static String hoverText(Object result) {
        if (result == null) return "";
        return stripMarkdown(markedText(Json.get(result, "contents"))).trim();
    }

    private static String markedText(Object contents) {
        if (contents == null) return "";
        if (contents instanceof String s) return s;
        List<Object> list = Json.asArray(contents);
        if (list != null) {
            StringBuilder sb = new StringBuilder();
            for (Object part : list) { String text = markedText(part); if (!text.isBlank()) { if (sb.length() > 0) sb.append("\n\n"); sb.append(text); } }
            return sb.toString();
        }
        String value = Json.string(contents, "value");
        return value == null ? "" : value;
    }

    private static String stripMarkdown(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) continue;
            sb.append(line.replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1")).append('\n');
        }
        return sb.toString().replaceAll("\n{3,}", "\n\n");
    }

    public static WorkspaceEdit workspaceEdit(Object json) {
        Map<URI, List<TextEdit>> changes = new java.util.LinkedHashMap<>();
        boolean unsupported = false;
        Object plain = Json.get(json, "changes");
        if (Json.asObject(plain) != null) for (Map.Entry<String, Object> e : Json.asObject(plain).entrySet()) addEdits(changes, e.getKey(), e.getValue());
        List<Object> documentChanges = Json.asArray(Json.get(json, "documentChanges"));
        if (documentChanges != null) for (Object change : documentChanges) {
            if (Json.get(change, "kind") != null) { unsupported = true; continue; } // create / rename / delete resource operations
            String uri = Json.string(Json.get(change, "textDocument"), "uri");
            if (uri != null) addEdits(changes, uri, Json.get(change, "edits"));
        }
        return new WorkspaceEdit(changes, unsupported);
    }

    private static void addEdits(Map<URI, List<TextEdit>> changes, String uri, Object edits) {
        List<Object> list = Json.asArray(edits);
        if (list == null) return;
        URI key;
        try { key = URI.create(uri); } catch (IllegalArgumentException ex) { return; }
        for (Object edit : list) {
            Range range = Range.from(Json.get(edit, "range")); String text = Json.string(edit, "newText");
            if (range != null && text != null) changes.computeIfAbsent(key, k -> new ArrayList<>()).add(new TextEdit(range, text));
        }
    }

    public static List<Diagnostic> diagnostics(Object list) {
        List<Diagnostic> out = new ArrayList<>();
        List<Object> items = Json.asArray(list);
        if (items == null) return out;
        for (Object item : items) {
            Range range = Range.from(Json.get(item, "range")); String message = Json.string(item, "message");
            if (range != null && message != null) out.add(new Diagnostic(range, Json.integer(item, "severity", Diagnostic.ERROR), message, Json.string(item, "source")));
        }
        return out;
    }

    /** Removes snippet syntax ({@code $1}, {@code ${1:name}}, {@code $0}) leaving the default text. */
    static String flattenSnippet(String snippet) {
        String s = snippet.replaceAll("\\$\\{\\d+:([^}]*)}", "$1").replaceAll("\\$\\{\\d+\\|([^,}|]*)[^}]*}", "$1").replaceAll("\\$\\{?\\d+}?", "");
        return s.replace("\\$", "$").replace("\\}", "}");
    }
}
