package com.javelin_ide.build;

import java.util.concurrent.TimeUnit;

/**
 * Resolves the user's real PATH.
 *
 * Apps launched from Finder, the Dock or Spotlight inherit launchd's minimal
 * PATH (/usr/bin:/bin:/usr/sbin:/sbin), so tools such as mvn, java or git
 * installed via Homebrew are not found. This asks the user's login shell for
 * its PATH once and caches the result. Falls back to the inherited PATH on any
 * failure or timeout.
 */
public final class ShellEnvironment {
    private static final long TIMEOUT_SECONDS = 5;
    private static volatile String resolvedPath;

    private ShellEnvironment() {}

    /** Resolves the PATH on a background thread so the first use does not wait. */
    public static void warmUp() {
        Thread t = new Thread(ShellEnvironment::path, "shell-env-warmup");
        t.setDaemon(true);
        t.start();
    }

    /** The user's login-shell PATH, or the inherited PATH as a fallback. */
    public static String path() {
        String p = resolvedPath;
        if (p == null) {
            synchronized (ShellEnvironment.class) {
                p = resolvedPath;
                if (p == null) {
                    p = resolve();
                    resolvedPath = p;
                }
            }
        }
        return p;
    }

    /** Applies the resolved PATH to a process about to be started. */
    public static ProcessBuilder apply(ProcessBuilder pb) {
        pb.environment().put("PATH", path());
        return pb;
    }

    private static String resolve() {
        String fallback = System.getenv("PATH");
        if (fallback == null) {
            fallback = "/usr/bin:/bin:/usr/sbin:/sbin";
        }
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return fallback;
        }
        try {
            String shell = System.getenv("SHELL");
            if (shell == null || shell.isBlank()) {
                shell = "/bin/zsh";
            }
            Process p = new ProcessBuilder(shell, "-l", "-i", "-c", "printf %s \"$PATH\"")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.getOutputStream().close();
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return fallback;
            }
            String out = new String(p.getInputStream().readAllBytes()).trim();
            // rc files may print banners; PATH is the last line
            int nl = out.lastIndexOf('\n');
            if (nl >= 0) {
                out = out.substring(nl + 1).trim();
            }
            return out.isEmpty() ? fallback : out;
        } catch (Exception e) {
            return fallback;
        }
    }
}
