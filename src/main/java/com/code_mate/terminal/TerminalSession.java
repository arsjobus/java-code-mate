package com.code_mate.terminal;

import com.code_mate.build.ManagedProcess;
import com.code_mate.build.ProcessManager;
import com.code_mate.build.ShellCommand;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One persistent shell behind a terminal tab, with no JavaFX dependency.
 *
 * <ul>
 *   <li><b>Foreground binding:</b> while the shell has a child process (e.g. a server), the session is
 *       "busy". Typed lines go to the shell's stdin, which the child inherits, and {@link #interrupt()}
 *       sends it Ctrl+C (SIGINT) without killing the shell.</li>
 *   <li><b>Virtualenv awareness:</b> a PTY-less shell cannot show a prompt, so once the shell has been idle
 *       after a command the session sends one read-only probe (a single printf of $VIRTUAL_ENV) and hides
 *       its output. The probe is only sent while idle, so it can never be consumed by a running program.</li>
 * </ul>
 *
 * Listener callbacks may arrive on any thread. {@link #tick()} should be called a few times per second.
 */
public final class TerminalSession {
    public interface Listener {
        void onOutput(String line);
        void onStateChanged();
    }

    static final String MARKER = "__code_mate_env__";
    private static final String PROBE = ShellCommand.isWindows() ? "echo " + MARKER + "%VIRTUAL_ENV%" : "printf '" + MARKER + "%s\\n' \"$VIRTUAL_ENV\"";
    private static final long IDLE_AFTER_MILLIS = 600;

    private final String name;
    private final ProcessManager processes;
    private final Supplier<File> directory;
    private final Listener listener;
    private ManagedProcess shell;
    private boolean busy;
    private boolean probePending;
    private long lastSubmit;
    private String lastCommand = "";
    private String venvPath;

    public TerminalSession(String name, ProcessManager processes, Supplier<File> directory, Listener listener) {
        this.name = name; this.processes = processes; this.directory = directory; this.listener = listener;
    }

    /** Sends a line to the shell, starting it first if needed. While a command runs, the line becomes that command's input. */
    public synchronized void submit(String line) throws IOException {
        ensureShell();
        if (!busy) lastCommand = line.strip();
        lastSubmit = System.currentTimeMillis();
        probePending = true;
        shell.send(line);
    }

    /** Ctrl+C: interrupts the running command, if any. Returns false when nothing was running. */
    public synchronized boolean interrupt() {
        return shell != null && shell.isAlive() && processes.interrupt(shell) > 0;
    }

    /** Ctrl+D on an empty prompt: exits the shell, but only when no command is running. */
    public synchronized boolean exitIfIdle() throws IOException {
        if (shell == null || !shell.isAlive() || busy) return false;
        shell.send("exit");
        return true;
    }

    /** Refreshes busy state and, once idle after a command, probes for an active virtualenv. */
    public synchronized void tick() {
        boolean nowBusy = shell != null && shell.isAlive() && !shell.children().isEmpty();
        if (nowBusy != busy) { busy = nowBusy; listener.onStateChanged(); }
        if (!busy && probePending && shell != null && shell.isAlive() && System.currentTimeMillis() - lastSubmit >= IDLE_AFTER_MILLIS) {
            probePending = false;
            try { shell.send(PROBE); } catch (IOException ignored) { /* shell is going away */ }
        }
    }

    public synchronized boolean isBusy() { return busy; }

    /** The command line the shell is currently running, or empty when idle. */
    public synchronized String foreground() { return busy ? lastCommand : ""; }

    /** Name of the active virtualenv directory (e.g. ".venv"), if one is active in this shell. */
    public synchronized Optional<String> venvName() {
        if (venvPath == null) return Optional.empty();
        String leaf = new File(venvPath).getName();
        return Optional.of(leaf.isBlank() ? venvPath : leaf);
    }

    public synchronized void close() {
        if (shell != null && shell.isAlive()) processes.stop(shell);
    }

    private void ensureShell() throws IOException {
        if (shell != null && shell.isAlive()) return;
        File dir = directory.get();
        if (dir == null) dir = new File(System.getProperty("user.home"));
        listener.onOutput("[starting shell in " + dir + "]");
        busy = false; probePending = false; venvPath = null;
        // Without a PTY, Python block-buffers stdout when piped, so servers would look silent. Unbuffered output fixes that.
        shell = processes.start(name, ShellCommand.interactive(), dir, Map.of("PYTHONUNBUFFERED", "1"), new ProcessManager.Listener() {
            @Override public void onLine(ManagedProcess p, String line) { handleLine(line); }
            @Override public void onExit(ManagedProcess p, int code) { handleExit(p, code); }
        });
        listener.onStateChanged();
    }

    private synchronized void handleLine(String line) {
        int at = line.indexOf(MARKER);
        if (at < 0) { listener.onOutput(line); return; }
        String value = line.substring(at + MARKER.length()).strip();
        String venv = value.isEmpty() || value.contains("%VIRTUAL_ENV%") ? null : value;
        if (!Objects.equals(venv, venvPath)) { venvPath = venv; listener.onStateChanged(); }
    }

    private synchronized void handleExit(ManagedProcess p, int code) {
        if (p != shell) return;
        busy = false; probePending = false; venvPath = null;
        listener.onOutput("[shell exited with code " + code + "]");
        listener.onStateChanged();
    }
}
