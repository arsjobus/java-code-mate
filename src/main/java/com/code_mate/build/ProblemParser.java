package com.code_mate.build;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognizes compiler diagnostics in build output. Unrecognized lines are ignored. */
public final class ProblemParser {
    // [ERROR] /path/Foo.java:[12,5] message   (maven-compiler-plugin)
    private static final Pattern MAVEN = Pattern.compile("^\\[(ERROR|WARNING)\\]\\s+(.+?):\\[(\\d+)(?:,(\\d+))?\\]\\s+(.*)$");
    // /path/Foo.java:12: error: message   or   file:12:5: warning: message   (javac, gcc, clang, ...)
    private static final Pattern GENERIC = Pattern.compile("^(.+?):(\\d+):(?:(\\d+):)?\\s+(error|warning):\\s+(.*)$");

    private ProblemParser() {}

    public static Optional<Problem> parse(String line) {
        Matcher m = MAVEN.matcher(line);
        if (m.matches()) return Optional.of(build(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)));
        m = GENERIC.matcher(line);
        if (m.matches()) return Optional.of(build(m.group(4), m.group(1), m.group(2), m.group(3), m.group(5)));
        return Optional.empty();
    }

    private static Problem build(String severity, String file, String line, String column, String message) {
        Problem.Severity s = severity.equalsIgnoreCase("error") ? Problem.Severity.ERROR : Problem.Severity.WARNING;
        return new Problem(s, file.trim(), Integer.parseInt(line), column == null ? 0 : Integer.parseInt(column), message.trim());
    }
}
