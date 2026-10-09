package com.code_mate.build;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** A child process started on the user's behalf. stderr is merged into stdout. */
public final class ManagedProcess {
    private final long id;
    private final String name;
    private final List<String> command;
    private final File directory;
    private final Instant started = Instant.now();
    private final Process process;

    ManagedProcess(long id, String name, List<String> command, File directory, Process process) {
        this.id = id; this.name = name; this.command = List.copyOf(command); this.directory = directory; this.process = process;
    }

    public long id() { return id; }
    public String name() { return name; }
    public List<String> command() { return command; }
    public File directory() { return directory; }
    public Instant started() { return started; }
    public boolean isAlive() { return process.isAlive(); }
    Process process() { return process; }

    /** Direct child processes, e.g. the command a shell is currently running. */
    public List<ProcessHandle> children() { return process.children().toList(); }

    /** Writes one line to the process's stdin (used by the terminal). */
    public synchronized void send(String line) throws IOException {
        OutputStream out = process.getOutputStream();
        out.write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override public String toString() { return "#" + id + " " + name + ": " + command.get(command.size() - 1); }
}
