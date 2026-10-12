package com.code_mate.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *
 * <p>The scanner only understands C-family syntax ({@code //} and {@code /* *}{@code /} comments, double and single
 * quoted literals), so {@link #spans(String, File)} leaves files of other kinds (Python, shell, Markdown, plain
 * text, ...) unstyled instead of colouring an apostrophe in prose or a {@code #} comment as if it were Java.
 *
 * <p>Keywords and well-known types come from a per-{@link Language} list chosen by the file's extension, so
 * {@code fn} is a keyword in a Rust file, {@code func} in Go, and {@code class} is not one in Go or JSON. An untitled
 * buffer is treated as Java, as the editor is Java-oriented.
 */
public final class SyntaxHighlighter {
    /** Documents longer than this get no highlighting. Roughly {@code FileIO.LARGE_FILE_BYTES} of ASCII text. */
    public static final int MAX_CHARS = 1 << 20;

    /** A highlighted range: {@code [start, end)} and the CSS style class to apply. */
    public record Span(int start, int end, String style) {}

    /** The languages whose keywords the scanner knows. All share the same C-family lexical rules. */
    public enum Language { JAVA, KOTLIN, JAVASCRIPT, TYPESCRIPT, C, CPP, GO, RUST, PHP, JSON }

    /** What counts as a keyword or a type in one language, and whether {@code @name} is an annotation or decorator. */
    private record Lexicon(Set<String> keywords, Set<String> types, boolean annotations) {}

    private static final Map<Language, Lexicon> LEXICONS = new EnumMap<>(Language.class);

    private static Set<String> words(String list) { return list.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(list.trim().split("\\s+"))); }

    private static void define(Language language, String keywords, String types, boolean annotations) {
        LEXICONS.put(language, new Lexicon(words(keywords), words(types), annotations));
    }

    private static final String C_KEYWORDS = "auto break case const continue default do else enum extern for goto if inline register restrict return "
        + "sizeof static struct switch typedef union volatile while";
    private static final String C_TYPES = "char double float int long short signed unsigned void size_t bool FILE int8_t int16_t int32_t int64_t "
        + "uint8_t uint16_t uint32_t uint64_t";
    private static final String JS_KEYWORDS = "async await break case catch class const continue debugger default delete do else export extends false finally "
        + "for function if import in instanceof let new null return static super switch this throw true try typeof undefined var void while with yield";
    private static final String JS_TYPES = "Array Boolean Date Error JSON Map Math Number Object Promise Set String Symbol";

    static {
        define(Language.JAVA, "abstract assert break case catch class continue default do else enum extends final finally for if implements import instanceof "
            + "interface new package private protected public return static super switch synchronized this throw throws try var void while record sealed yield",
            "boolean byte char double float int long short String Object Integer Long Double Float Boolean System List Map Set File", true);
        define(Language.KOTLIN, "abstract as break by class companion const constructor continue data do else enum false final for fun if import in init inline "
            + "interface internal is lateinit null object open override package private protected public return sealed super suspend this throw true try "
            + "typealias val var when while",
            "Any Array Boolean Byte Char Double Float Int List Long Map Nothing Set Short String Unit", true);
        define(Language.JAVASCRIPT, JS_KEYWORDS, JS_TYPES, true);
        define(Language.TYPESCRIPT, JS_KEYWORDS + " abstract as declare enum implements interface keyof namespace private protected public readonly type",
            JS_TYPES + " any bigint boolean never number object string symbol unknown", true);
        define(Language.C, C_KEYWORDS, C_TYPES, false);
        define(Language.CPP, C_KEYWORDS + " catch class constexpr delete explicit false final friend mutable namespace new noexcept nullptr operator override "
            + "private protected public template this throw true try typename using virtual",
            C_TYPES + " string vector map set std", false);
        define(Language.GO, "break case chan const continue default defer else fallthrough false for func go goto if import interface map nil package range "
            + "return select struct switch true type var",
            "any bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr", false);
        define(Language.RUST, "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return "
            + "self Self static struct super trait true type unsafe use where while",
            "Box Option Result String Vec bool char f32 f64 i8 i16 i32 i64 i128 isize str u8 u16 u32 u64 u128 usize", false);
        define(Language.PHP, "abstract and as break case catch class clone const continue declare default do echo else elseif extends false final finally fn for "
            + "foreach function global if implements include instanceof interface namespace new null or private protected public require return static switch "
            + "throw trait true try use var while yield",
            "array bool callable float int iterable mixed object string void", false);
        define(Language.JSON, "true false null", "", false);
    }

    /** Extensions (lower case, no dot) of files whose syntax the scanner understands, and the language each one uses. */
    private static final Map<String, Language> EXTENSIONS = Map.ofEntries(
        Map.entry("java", Language.JAVA), Map.entry("kt", Language.KOTLIN), Map.entry("kts", Language.KOTLIN),
        Map.entry("js", Language.JAVASCRIPT), Map.entry("mjs", Language.JAVASCRIPT), Map.entry("cjs", Language.JAVASCRIPT), Map.entry("jsx", Language.JAVASCRIPT),
        Map.entry("ts", Language.TYPESCRIPT), Map.entry("tsx", Language.TYPESCRIPT),
        Map.entry("c", Language.C), Map.entry("h", Language.CPP), // a header may be C or C++; the C++ list is a superset
        Map.entry("cpp", Language.CPP), Map.entry("cc", Language.CPP), Map.entry("cxx", Language.CPP), Map.entry("hpp", Language.CPP), Map.entry("hh", Language.CPP),
        Map.entry("go", Language.GO), Map.entry("rs", Language.RUST), Map.entry("php", Language.PHP), Map.entry("json", Language.JSON));

    private SyntaxHighlighter() {}

    /**
     * The language whose keywords the file gets: {@link Language#JAVA} for an untitled buffer (the editor is
     * Java-oriented), the language of a C-family extension, and {@code null} for any other file or a file without an
     * extension, which is shown as plain text.
     */
    public static Language languageOf(File file) {
        if (file == null) return Language.JAVA;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : EXTENSIONS.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** True when the file should get syntax highlighting, that is when {@link #languageOf(File)} knows its language. */
    public static boolean appliesTo(File file) { return languageOf(file) != null; }

    /** True when {@link #spans} would produce nothing because the text is too long. */
    public static boolean tooLarge(int length) { return length > MAX_CHARS; }

    /** Like {@link #spans(String)}, but empty when {@link #appliesTo(File)} says the file is not highlightable. */
    public static List<Span> spans(String text, File file) {
        Language language = languageOf(file);
        return language == null ? new ArrayList<>() : spansIn(text, language);
    }

    /** The tokens of Java text, see {@link #spansIn(String, Language)}. */
    public static List<Span> spans(String text) { return spansIn(text, Language.JAVA); }

    /** The tokens of the text in order, without overlaps, using the keywords of the language. Empty for text over {@link #MAX_CHARS}. */
    public static List<Span> spansIn(String text, Language language) {
        Lexicon lexicon = LEXICONS.get(language);
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
            } else if (lexicon.annotations() && c == '@' && i + 1 < n && Character.isJavaIdentifierStart(text.charAt(i + 1))) {
                i = add(spans, i, identifierEnd(text, i + 1), "annotation");
            } else if (Character.isJavaIdentifierStart(c)) {
                int end = identifierEnd(text, i);
                String word = text.substring(i, end);
                if (lexicon.keywords().contains(word)) add(spans, i, end, "keyword");
                else if (lexicon.types().contains(word)) add(spans, i, end, "type");
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
