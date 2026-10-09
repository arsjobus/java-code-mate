package com.code_mate.build;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Starts, tracks, and stops child processes. Nothing is started unless a caller asks for it,
 * and no process is left behind: {@link #stopAll()} is meant to be called on application exit.
 * Listener callbacks arrive on background threads; UI code must hop to the FX thread itself.
 */
public final class ProcessManager {
    public interface Listener {
        void onLine(ManagedProcess process, String line);
        void onExit(ManagedProcess process, int exitCode);
    }

    private static final long GRACE_MILLIS = 3000;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, ManagedProcess> running = new ConcurrentHashMap<>();

    public ManagedProcess start(String name, List<String> command, File directory, Listener listener) throws IOException {
        return start(name, command, directory, Map.of(), listener);
    }

    /** As above, with extra environment variables layered over the editor's own environment. */
    public ManagedProcess start(String name, List<String> command, File directory, Map<String, String> environment, Listener listener) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        if (directory != null) builder.directory(directory);
        Process process = builder.start();
        ManagedProcess managed = new ManagedProcess(ids.incrementAndGet(), name, command, directory, process);
        running.put(managed.id(), managed);
        Thread reader = new Thread(() -> pump(managed, listener), "process-" + managed.id());
        reader.setDaemon(true);
        reader.start();
        return managed;
    }

    private void pump(ManagedProcess managed, Listener listener) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(managed.process().getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) listener.onLine(managed, Ansi.strip(line));
        } catch (IOException ignored) {
            // The stream closes when the process is destroyed.
        }
        int code;
        try { code = managed.process().waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); code = -1; }
        running.remove(managed.id());
        listener.onExit(managed, code);
    }

    public List<ManagedProcess> running() {
        List<ManagedProcess> list = new ArrayList<>(running.values());
        list.sort((a, b) -> Long.compare(a.id(), b.id()));
        return list;
    }

    /** Asks the process and its children to exit, then force-kills whatever is left after a short grace period. */
    public void stop(ManagedProcess managed) {
        Process process = managed.process();
        if (!process.isAlive()) return;
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        Thread killer = new Thread(() -> {
            try {
                if (!process.waitFor(GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "process-killer-" + managed.id());
        killer.setDaemon(true);
        killer.start();
    }

    public void stopAll() { running().forEach(this::stop); }

    /**
     * Sends the equivalent of Ctrl+C to whatever the given shell is running: SIGINT to its direct children,
     * leaving the shell itself alive. Windows has no equivalent signal, so children are terminated instead.
     * Returns how many children were signalled.
     */
    public int interrupt(ManagedProcess shell) {
        int signalled = 0;
        for (ProcessHandle child : shell.children()) {
            if (ShellCommand.isWindows()) { if (child.destroy()) signalled++; continue; }
            try {
                Process kill = new ProcessBuilder("kill", "-INT", Long.toString(child.pid()))
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (kill.waitFor(2, TimeUnit.SECONDS) && kill.exitValue() == 0) signalled++;
            } catch (IOException ignored) {
                // No kill binary available; nothing more we can do.
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return signalled;
    }
}
