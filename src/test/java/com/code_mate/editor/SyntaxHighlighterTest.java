package com.code_mate.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.code_mate.editor.SyntaxHighlighter.Language;
import com.code_mate.editor.SyntaxHighlighter.Span;
import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SyntaxHighlighterTest {
    private static List<String> styled(String text) {
        return SyntaxHighlighter.spans(text).stream().map(s -> s.style() + ":" + text.substring(s.start(), s.end())).toList();
    }

    private static List<String> styled(String text, String fileName) {
        return SyntaxHighlighter.spans(text, new File(fileName)).stream().map(s -> s.style() + ":" + text.substring(s.start(), s.end())).toList();
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

    @ParameterizedTest
    @ValueSource(strings = {"A.java", "a.JAVA", "a.kt", "a.kts", "a.js", "a.mjs", "a.cjs", "a.jsx", "a.ts", "a.tsx", "a.c", "a.h", "a.cpp", "a.cc", "a.cxx", "a.hpp", "a.hh",
        "a.go", "a.rs", "a.php", "a.json", "archive.tar.java", "dir.py/Main.java"})
    void cFamilyFilesAreHighlighted(String name) { assertTrue(SyntaxHighlighter.appliesTo(new File(name)), name); }

    @ParameterizedTest
    @ValueSource(strings = {"a.py", "a.pyi", "a.rb", "a.sh", "a.bash", "a.md", "a.txt", "a.xml", "a.yml", "a.properties", "README", "Makefile", ".bashrc", "trailing.", "a.java.bak", "dir.java/notes"})
    void otherFilesAreShownAsPlainText(String name) { assertFalse(SyntaxHighlighter.appliesTo(new File(name)), name); }

    @Test void anUntitledBufferIsHighlighted() { assertTrue(SyntaxHighlighter.appliesTo(null)); }

    @Test void proseInAPlainTextFileIsNeverColoured() {
        String prose = "don't panic: it's \"fine\" // not a comment\nclass of 42 things /* nor this */";
        assertTrue(SyntaxHighlighter.spans(prose, new File("notes.txt")).isEmpty());
        assertTrue(SyntaxHighlighter.spans(prose, new File("README")).isEmpty());
        assertFalse(SyntaxHighlighter.spans(prose, new File("Notes.java")).isEmpty());
    }

    @Test void pythonCommentsAndApostrophesAreNotTreatedAsJava() {
        String python = "# it's a comment\nclass Foo:\n    x = 'don\\'t'  # 42\n";
        assertTrue(SyntaxHighlighter.spans(python, new File("main.py")).isEmpty());
    }

    @Test void spansWithAFileMatchesPlainSpansWhenTheFileIsHighlighted() {
        String code = "public class A { int x = 1; // c\n }";
        assertEquals(SyntaxHighlighter.spans(code), SyntaxHighlighter.spans(code, new File("A.java")));
        assertEquals(SyntaxHighlighter.spans(code), SyntaxHighlighter.spans(code, null));
        assertTrue(SyntaxHighlighter.spans("", new File("a.py")).isEmpty());
    }

    @Test void theResultForAnUnhighlightedFileIsIndependentOfTheScanner() {
        List<Span> a = SyntaxHighlighter.spans("class A", new File("a.txt"));
        a.add(new Span(0, 1, "x")); // callers may mutate the returned list without affecting later calls
        assertTrue(SyntaxHighlighter.spans("class A", new File("a.txt")).isEmpty());
    }

    // --- keywords belong to the language of the file (BUGS.md: "keyword colours are Java's for every highlighted language") ---

    @ParameterizedTest
    @CsvSource({"A.java,JAVA", "a.JAVA,JAVA", "a.kt,KOTLIN", "a.kts,KOTLIN", "a.js,JAVASCRIPT", "a.mjs,JAVASCRIPT", "a.cjs,JAVASCRIPT", "a.jsx,JAVASCRIPT",
        "a.ts,TYPESCRIPT", "a.tsx,TYPESCRIPT", "a.c,C", "a.h,CPP", "a.cpp,CPP", "a.cc,CPP", "a.cxx,CPP", "a.hpp,CPP", "a.hh,CPP", "a.go,GO", "a.rs,RUST",
        "a.php,PHP", "a.json,JSON", "dir.py/Main.java,JAVA"})
    void eachExtensionMapsToItsLanguage(String name, Language expected) { assertEquals(expected, SyntaxHighlighter.languageOf(new File(name)), name); }

    @ParameterizedTest
    @CsvSource({"a.py", "a.md", "a.txt", "README", "Makefile", ".bashrc", "trailing.", "a.java.bak", "dir.java/notes"})
    void otherFilesHaveNoLanguage(String name) { assertNull(SyntaxHighlighter.languageOf(new File(name)), name); }

    @Test void anUntitledBufferIsJava() { assertEquals(Language.JAVA, SyntaxHighlighter.languageOf(null)); }

    @Test void appliesToAgreesWithLanguageOf() {
        for (String name : List.of("A.java", "a.rs", "a.json", "a.py", "README", "a.txt")) {
            File file = new File(name);
            assertEquals(SyntaxHighlighter.languageOf(file) != null, SyntaxHighlighter.appliesTo(file), name);
        }
        assertTrue(SyntaxHighlighter.appliesTo(null));
    }

    @Test void everyLanguageHasAKeywordList() {
        for (Language language : Language.values()) assertDoesNotThrow(() -> SyntaxHighlighter.spansIn("x 1 \"s\" // c", language), language.name());
    }

    @Test void rustKeywordsAndTypesAreHighlightedInRustFiles() {
        assertEquals(List.of("keyword:fn", "keyword:let", "keyword:mut", "type:u32", "number:1", "keyword:match", "keyword:Self"),
            styled("fn main() { let mut x: u32 = 1; match Self }", "main.rs"));
    }

    @Test void goKeywordsAndTypesAreHighlightedInGoFiles() {
        assertEquals(List.of("keyword:func", "keyword:var", "type:string", "keyword:go", "keyword:defer", "keyword:nil"),
            styled("func main() { var s string; go run(); defer done(); nil }", "main.go"));
    }

    @Test void kotlinKeywordsAndTypesAreHighlightedInKotlinFiles() {
        assertEquals(List.of("keyword:fun", "keyword:val", "type:Int", "number:1", "keyword:when"), styled("fun main() { val x: Int = 1; when }", "Main.kt"));
        assertEquals(List.of("keyword:val"), styled("val", "build.gradle.kts"));
    }

    @Test void typeScriptKnowsInterfacesAndPrimitiveTypesButJavaScriptDoesNot() {
        String code = "interface Foo { x: number; } type T = string;";
        assertEquals(List.of("keyword:interface", "type:number", "keyword:type", "type:string"), styled(code, "a.ts"));
        assertEquals(List.of("keyword:interface", "type:number", "keyword:type", "type:string"), styled(code, "a.tsx"));
        assertEquals(List.of(), styled(code, "a.js"));
    }

    @Test void javaScriptKeywordsAreHighlightedInJavaScriptFiles() {
        assertEquals(List.of("keyword:const", "keyword:function", "keyword:await", "keyword:typeof", "keyword:undefined", "type:Promise"),
            styled("const function await typeof undefined Promise", "a.js"));
    }

    @Test void cAndCppDifferInTheirKeywords() {
        assertEquals(List.of("keyword:struct", "type:int", "keyword:typedef"), styled("struct int typedef class public", "a.c"));
        assertEquals(List.of("keyword:class", "keyword:public", "keyword:namespace", "type:string"), styled("class public namespace string", "a.cpp"));
        assertEquals(List.of("keyword:class"), styled("class", "a.h"));
    }

    @Test void phpKeywordsAreHighlightedInPhpFiles() {
        assertEquals(List.of("keyword:function", "keyword:echo", "keyword:foreach", "type:string"), styled("function echo foreach string $class", "a.php"));
    }

    @Test void javaKeywordsAreNotHighlightedInOtherLanguages() {
        assertEquals(List.of("keyword:class", "keyword:public", "keyword:void", "keyword:new", "keyword:implements", "keyword:synchronized"),
            styled("class public void new implements synchronized", "A.java"));
        String javaOnly = "class public void new implements synchronized";
        assertEquals(List.of(), styled(javaOnly, "a.go"));
        assertEquals(List.of(), styled(javaOnly, "a.rs"));
        assertEquals(List.of(), styled(javaOnly, "a.json"));
    }

    @Test void jsonOnlyHighlightsItsThreeLiterals() {
        assertEquals(List.of("string:\"class\"", "keyword:true", "string:\"n\"", "keyword:null", "string:\"v\"", "number:1", "string:\"x\"", "keyword:false"),
            styled("{\"class\": true, \"n\": null, \"v\": 1, \"x\": false}", "a.json"));
    }

    @Test void identifiersThatContainAKeywordAreStillNotHighlighted() {
        assertEquals(List.of(), styled("fnord letter mutable implement", "a.rs"));
        assertEquals(List.of(), styled("funcs gone variable", "a.go"));
    }

    @Test void annotationsAreOnlyHighlightedWhereTheLanguageHasThem() {
        assertEquals(List.of("annotation:@Override"), styled("@Override", "A.java"));
        assertEquals(List.of("annotation:@Component"), styled("@Component", "a.ts"));
        assertEquals(List.of("annotation:@JvmStatic"), styled("@JvmStatic", "a.kt"));
        for (String name : List.of("a.c", "a.cpp", "a.go", "a.rs", "a.php", "a.json")) assertEquals(List.of(), styled("@Override @foo", name), name);
    }

    @Test void stringsAndCommentsStillHideKeywordsInEveryLanguage() {
        assertEquals(List.of("string:\"fn\"", "comment:// let mut"), styled("\"fn\" // let mut", "a.rs"));
        assertEquals(List.of("comment:/* func var */", "keyword:go"), styled("/* func var */ go", "a.go"));
    }

    @Test void plainSpansAreJavaSpans() {
        String code = "fn class val func 1";
        assertEquals(List.of("keyword:class", "number:1"), styled(code));
        assertEquals(SyntaxHighlighter.spansIn(code, Language.JAVA), SyntaxHighlighter.spans(code));
        assertEquals(SyntaxHighlighter.spans(code), SyntaxHighlighter.spans(code, null));
    }

    @Test void spansForAFileUseTheLanguageOfThatFile() {
        String code = "fn class func val";
        assertEquals(SyntaxHighlighter.spansIn(code, Language.RUST), SyntaxHighlighter.spans(code, new File("a.rs")));
        assertEquals(SyntaxHighlighter.spansIn(code, Language.GO), SyntaxHighlighter.spans(code, new File("a.go")));
        assertNotEquals(SyntaxHighlighter.spans(code, new File("a.rs")), SyntaxHighlighter.spans(code, new File("a.go")));
    }

    @Test void everyLanguageStaysWithinTheSizeLimit() {
        String big = "x ".repeat(SyntaxHighlighter.MAX_CHARS);
        for (Language language : Language.values()) assertTrue(SyntaxHighlighter.spansIn(big, language).isEmpty(), language.name());
    }
}
