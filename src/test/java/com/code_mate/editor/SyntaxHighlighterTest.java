package com.code_mate.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.code_mate.editor.SyntaxHighlighter.Span;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyntaxHighlighterTest {
    private static List<String> styled(String text) {
        return SyntaxHighlighter.spans(text).stream().map(s -> s.style() + ":" + text.substring(s.start(), s.end())).toList();
    }

    @Test void recognisesEveryTokenKind() {
        assertEquals(List.of("keyword:public", "keyword:class", "annotation:@Override", "type:String", "number:42", "number:3.14", "comment:// hi", "string:\"s\"", "string:'c'", "comment:/* b */"),
            styled("public class @Override String 42 3.14 // hi\n\"s\" 'c' /* b */"));
    }

    @Test void identifiersThatContainKeywordsAreNotHighlighted() {
        assertEquals(List.of(), styled("classify interface_ publicity new1 integer Strings x1 1x"));
        assertEquals(List.of("keyword:new"), styled("x.new y"));
    }

    @Test void stringsAndCommentsHideTheirContents() {
        assertEquals(List.of("string:\"if (class) // not a comment\""), styled("\"if (class) // not a comment\""));
        assertEquals(List.of("comment:// \"quote\" class"), styled("// \"quote\" class"));
        assertEquals(List.of("comment:/* class \" */", "keyword:int".replace("keyword", "type")), styled("/* class \" */ int"));
    }

    @Test void escapesInsideStrings() {
        assertEquals(List.of("string:\"a\\\"b\"", "number:1"), styled("\"a\\\"b\" 1"));
        assertEquals(List.of("string:'\\''"), styled("'\\''"));
    }

    @Test void aStrayQuoteCannotColourTheRestOfTheFile() {
        assertEquals(List.of("keyword:class"), styled("it's broken\nclass".replace("it's broken\n", "\"unterminated\n")));
        assertEquals(List.of("type:int", "number:7"), styled("don't\nint 7".replace("don't", "don'").replace("don'\n", "don'\n")) .stream().filter(s -> !s.startsWith("string")).toList());
        List<String> afterApostrophe = styled("// it's fine\nint x = 'a';");
        assertEquals(List.of("comment:// it's fine", "type:int", "string:'a'"), afterApostrophe);
    }

    @Test void anUnterminatedBlockCommentRunsToTheEnd() {
        assertEquals(List.of("keyword:int".replace("keyword", "type"), "comment:/* never closed\nclass A {"), styled("int /* never closed\nclass A {"));
        assertEquals(List.of("comment:/*"), styled("/*"));
    }

    @Test void blockCommentsSpanLines() { assertEquals(List.of("comment:/* a\nb\nc */"), styled("/* a\nb\nc */")); }

    @Test void textBlocksSpanLines() {
        assertEquals(List.of("string:\"\"\"\n  class \"quoted\" \n  \"\"\"", "number:1"), styled("\"\"\"\n  class \"quoted\" \n  \"\"\" 1"));
        assertEquals(List.of("string:\"\"\"\nopen"), styled("\"\"\"\nopen"));
    }

    @Test void emptyAndPlainTextHaveNoSpans() {
        assertTrue(SyntaxHighlighter.spans("").isEmpty());
        assertTrue(SyntaxHighlighter.spans("just some prose, nothing to see").isEmpty());
    }

    @Test void spansNeverOverlapAndStayInOrder() {
        String code = "@Deprecated public static void main(String[] a) { int x = 10; // c\n String s = \"q\"; /* m */ return; }\n".repeat(50);
        int end = 0;
        for (Span s : SyntaxHighlighter.spans(code)) { assertTrue(s.start() >= end); assertTrue(s.end() > s.start()); end = s.end(); }
        assertTrue(end <= code.length());
    }

    @Test void hugeDocumentsAreNotHighlightedAtAll() {
        assertFalse(SyntaxHighlighter.tooLarge(SyntaxHighlighter.MAX_CHARS));
        assertTrue(SyntaxHighlighter.tooLarge(SyntaxHighlighter.MAX_CHARS + 1));
        String big = "int x = 1; ".repeat(SyntaxHighlighter.MAX_CHARS / 10);
        assertTrue(big.length() > SyntaxHighlighter.MAX_CHARS);
        assertTrue(SyntaxHighlighter.spans(big).isEmpty());
    }

    @Test void aMegabyteOfCodeHighlightsWithinABudget() {
        String code = "public static int compute(String name) { return name.length() + 42; } // trailing \"comment\"\n";
        String text = code.repeat(SyntaxHighlighter.MAX_CHARS / code.length());
        long start = System.nanoTime();
        List<Span> spans = SyntaxHighlighter.spans(text);
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertFalse(spans.isEmpty());
        assertTrue(ms < 3_000, "highlighting 1 MB took " + ms + " ms");
    }

    @Test void pathologicalInputStaysLinear() {
        String[] nasty = {"/*".repeat(400_000), "\"".repeat(800_000), "'a".repeat(400_000), "\"\"\"".repeat(250_000), "/".repeat(800_000), "\\\"".repeat(400_000), "@".repeat(800_000), "\"" + "\\".repeat(800_000)};
        for (String text : nasty) {
            long start = System.nanoTime();
            SyntaxHighlighter.spans(text);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms < 3_000, "pathological input took " + ms + " ms: " + text.substring(0, 4));
        }
    }

    @Test void manyUnterminatedStringsOnOneLineStayFast() {
        String line = "\"x ".repeat(100_000) + "\n";
        long start = System.nanoTime();
        SyntaxHighlighter.spans(line.repeat(5));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000);
    }
}
