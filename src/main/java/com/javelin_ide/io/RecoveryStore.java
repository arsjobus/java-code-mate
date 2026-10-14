package com.javelin_ide.io;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Crash recovery: keeps a copy of every unsaved buffer on the user's own disk so a crash, power cut or kill does
 * not lose their work. JavaFX-free, and local only: nothing here touches the network.
 *
 * <p>A snapshot exists only while its buffer has unsaved changes. Saving or deliberately discarding a buffer deletes
 * its snapshot, so any snapshot found at the next start belongs to a session that did not end cleanly. The directory
 * is created owner-only where the file system supports it, because snapshots hold text the user has not chosen to save.
 */
public final class RecoveryStore {
    /**
     * One recovered buffer. {@code original} is null for a document that was never saved. {@code caret} is the cursor
     * offset into {@code text} (always within it; 0 when unknown) and {@code tabIndex} the buffer's position among the
     * editor tabs when it was written (-1 when unknown, e.g. a snapshot written by an older version).
     */
    public record Snapshot(String id, Path original, Instant savedAt, String text, int caret, int tabIndex) {
        public Snapshot {
            caret = Math.max(0, Math.min(caret, text.length()));
            if (caret > 0 && caret < text.length() && Character.isLowSurrogate(text.charAt(caret))) caret--; // never inside an emoji
            tabIndex = Math.max(-1, tabIndex);
        }

        /** A snapshot with no known cursor position or tab position. */
        public Snapshot(String id, Path original, Instant savedAt, String text) { this(id, original, savedAt, text, 0, -1); }
    }

    /** The order tabs were in when the snapshots were written; snapshots without a known position come last, oldest first. */
    public static final Comparator<Snapshot> TAB_ORDER = Comparator
        .comparingInt((Snapshot s) -> s.tabIndex() < 0 ? Integer.MAX_VALUE : s.tabIndex())
        .thenComparing(Snapshot::savedAt)
        .thenComparing(Snapshot::id);

    /** What was last written for an id, so unchanged buffers (text, cursor and tab position) are not rewritten. */
    private record Written(String text, int caret, int tabIndex) {}

    private static final String EXTENSION = ".recovery";
    private static final String MAGIC = "javelin-ide-recovery 1";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private final Path directory;
    private final Map<String, Written> lastSaved = new HashMap<>();

    /** Lists a directory; injectable so a folder that cannot be read can be simulated in tests. */
    interface Lister { Stream<Path> list(Path directory) throws IOException; }

    private final boolean posix;
    private final Lister lister;

    public RecoveryStore(Path directory) {
        this(directory, FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), Files::list);
    }

    RecoveryStore(Path directory, boolean posix, Lister lister) {
        this.directory = directory.toAbsolutePath().normalize();
        this.posix = posix;
        this.lister = lister;
    }

    public Path directory() { return directory; }

    public static String newId() { return UUID.randomUUID().toString(); }

    /** Writes the buffer's current text unless it is identical to what was last written for this id. Returns whether it wrote. */
    public boolean saveIfChanged(String id, Path original, String text) throws IOException {
        return saveIfChanged(id, original, text, 0, -1);
    }

    /**
     * Like {@link #saveIfChanged(String, Path, String)}, and also remembers the cursor offset and the buffer's tab
     * position so a restore can put the cursor and the tabs back where they were. Moving the cursor or the tab counts
     * as a change. Out-of-range values are clamped when read back, never rejected: the text matters most.
     */
    public synchronized boolean saveIfChanged(String id, Path original, String text, int caret, int tabIndex) throws IOException {
        checkId(id);
        Written now = new Written(text, caret, tabIndex);
        if (now.equals(lastSaved.get(id)) && Files.exists(fileFor(id))) return false;
        ensureDirectory();
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String header = MAGIC + "\noriginal=" + (original == null ? "" : original.toAbsolutePath().toUri()) + "\nsaved=" + Instant.now().toEpochMilli()
            + "\ncaret=" + caret + "\ntab=" + tabIndex + "\nbytes=" + body.length + "\n\n";
        FileIO.writeAtomic(fileFor(id), header + text);
        lastSaved.put(id, now);
        return true;
    }

    /** Removes the snapshot for a buffer that was saved or deliberately discarded. Unknown ids are ignored. */
    public synchronized void discard(String id) {
        checkId(id);
        lastSaved.remove(id);
        deleteQuietly(fileFor(id));
    }

    /** A snapshot that cannot be deleted is simply offered again next time: never lost, never fatal. */
    private static boolean deleteQuietly(Path file) {
        try { return Files.deleteIfExists(file); } catch (IOException ignored) { return false; }
    }

    /** Removes every snapshot, e.g. after a clean exit in which the user decided what to keep. */
    public synchronized void clear() {
        lastSaved.clear();
        for (Path file : snapshotFiles()) deleteQuietly(file);
    }

    /** Snapshots left by earlier sessions, oldest first. Damaged or unrecognized files are skipped, never deleted. */
    public synchronized List<Snapshot> pending() {
        List<Snapshot> found = new ArrayList<>();
        for (Path file : snapshotFiles()) {
            Snapshot snapshot = read(file);
            if (snapshot != null) found.add(snapshot);
        }
        found.sort(Comparator.comparing(Snapshot::savedAt).thenComparing(Snapshot::id));
        return found;
    }

    /** Deletes snapshots (including damaged ones) last written longer ago than {@code age}; returns how many were removed. */
    public synchronized int purgeOlderThan(Duration age) {
        Instant cutoff = Instant.now().minus(age);
        int removed = 0;
        for (Path file : snapshotFiles()) {
            try {
                if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff) && deleteQuietly(file)) removed++;
            } catch (IOException ignored) { /* vanished meanwhile */ }
        }
        return removed;
    }

    private List<Path> snapshotFiles() {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = lister.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(EXTENSION) && Files.isRegularFile(p)).sorted().toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private static Snapshot read(Path file) {
        try {
            String raw = FileIO.readText(file);
            int blank = raw.indexOf("\n\n");
            if (blank < 0) return null;
            String[] header = raw.substring(0, blank).split("\n");
            // magic, original, saved, then optional caret and tab (absent in snapshots from older versions), then bytes
            if (header.length < 4 || header.length > 6 || !header[0].equals(MAGIC)) return null;
            String text = raw.substring(blank + 2);
            int last = header.length - 1;
            long expected = Long.parseLong(value(header[last], "bytes="));
            if (text.getBytes(StandardCharsets.UTF_8).length != expected) return null; // truncated or edited by hand
            String originalUri = value(header[1], "original=");
            Path original = originalUri.isEmpty() ? null : Path.of(URI.create(originalUri));
            Instant saved = Instant.ofEpochMilli(Long.parseLong(value(header[2], "saved=")));
            int caret = 0, tabIndex = -1, next = 3;
            if (next < last && header[next].startsWith("caret=")) caret = intOr(value(header[next++], "caret="), 0);
            if (next < last && header[next].startsWith("tab=")) tabIndex = intOr(value(header[next++], "tab="), -1);
            if (next != last) return null; // an unknown line where the byte count should be
            String name = file.getFileName().toString();
            return new Snapshot(name.substring(0, name.length() - EXTENSION.length()), original, saved, text, caret, tabIndex);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /** A damaged cursor or tab position must never cost the user their text, so it falls back instead of failing. */
    private static int intOr(String number, int fallback) {
        try { return Integer.parseInt(number.trim()); } catch (NumberFormatException ex) { return fallback; }
    }

    private static String value(String line, String prefix) {
        if (!line.startsWith(prefix)) throw new IllegalArgumentException(line);
        return line.substring(prefix.length());
    }

    private void ensureDirectory() throws IOException {
        if (Files.isDirectory(directory)) return;
        if (posix) Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        else Files.createDirectories(directory);
    }

    private Path fileFor(String id) { return directory.resolve(id + EXTENSION); }

    private static void checkId(String id) {
        if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid recovery id: " + id);
    }
}
