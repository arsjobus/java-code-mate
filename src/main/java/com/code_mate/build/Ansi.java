package com.code_mate.build;

import java.util.regex.Pattern;

/** Removes ANSI escape sequences, since the output panels render plain text. */
public final class Ansi {
    private static final Pattern ESCAPES = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007");

    private Ansi() {}

    public static String strip(String text) {
        return ESCAPES.matcher(text).replaceAll("");
    }
}
