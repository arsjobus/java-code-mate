package com.code_mate.io;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.code_mate.io.RecoveryStore.Snapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryStoreTest {
    @TempDir Path dir;
    private RecoveryStore store;

    @BeforeEach void setUp() { store = new RecoveryStore(dir.resolve("recovery")); }

    @Test void nothingIsPendingBeforeAnythingIsSaved() {
        assertTrue(store.pending().isEmpty());
        assertFalse(Files.exists(store.directory()), "an unused store creates nothing on disk");
        store.clear();
        assertEquals(0, store.purgeOlderThan(Duration.ZERO));
    }

    @Test void aSnapshotSurvivesTheStoreThatWroteIt() throws IOException {
        Path original = dir.resolve("My Project/Main.java");
        String text = "class A {\n\n  // unsaved \uD83D\uDE00\r\n}";
        assertTrue(store.saveIfChanged("tab-1", original, text));
        List<Snapshot> found = new RecoveryStore(dir.resolve("recovery")).pending(); // a "next session"
        assertEquals(1, found.size());
        assertEquals("tab-1", found.get(0).id());
        assertEquals(original.toAbsolutePath(), found.get(0).original());
        assertEquals(text, found.get(0).text());
        assertTrue(Duration.between(found.get(0).savedAt(), Instant.now()).abs().toMinutes() < 1);
    }

    @Test void untitledDocumentsHaveNoOriginal() throws IOException {
        store.saveIfChanged("u", null, "scratch");
        assertNull(store.pending().get(0).original());
    }

    @Test void identicalTextIsNotRewritten() throws IOException {
        assertTrue(store.saveIfChanged("a", null, "v1"));
        FileTimeProbe probe = new FileTimeProbe(store.directory().resolve("a.recovery"));
        assertFalse(store.saveIfChanged("a", null, "v1"));
        assertTrue(probe.unchanged());
        assertTrue(store.saveIfChanged("a", null, "v2"));
        assertEquals("v2", store.pending().get(0).text());
    }

    @Test void aDeletedSnapshotIsRewrittenEvenIfTheTextIsUnchanged() throws IOException {
        store.saveIfChanged("a", null, "v1");
        Files.delete(store.directory().resolve("a.recovery"));
        assertTrue(store.saveIfChanged("a", null, "v1"));
    }

    @Test void discardRemovesOneSnapshotAndToleratesUnknownOnes() throws IOException {
        store.saveIfChanged("a", null, "1");
        store.saveIfChanged("b", null, "2");
        store.discard("a");
        store.discard("never-existed");
        assertEquals(List.of("b"), store.pending().stream().map(Snapshot::id).toList());
        assertTrue(store.saveIfChanged("a", null, "1"), "after a discard the same text is written again");
    }

    @Test void clearRemovesEverythingButForeignFiles() throws IOException {
        store.saveIfChanged("a", null, "1");
        store.saveIfChanged("b", null, "2");
        Path foreign = store.directory().resolve("notes.txt");
        Files.writeString(foreign, "mine");
        store.clear();
        assertTrue(store.pending().isEmpty());
        assertTrue(Files.exists(foreign));
    }

    @Test void pendingIsOrderedOldestFirst() throws Exception {
        store.saveIfChanged("zzz", null, "first");
        Thread.sleep(15);
        store.saveIfChanged("aaa", null, "second");
        assertEquals(List.of("zzz", "aaa"), store.pending().stream().map(Snapshot::id).toList());
    }

    @Test void damagedSnapshotsAreSkippedNotDeleted() throws IOException {
        store.saveIfChanged("good", null, "fine");
        Path folder = store.directory();
        Files.writeString(folder.resolve("empty.recovery"), "");
        Files.writeString(folder.resolve("nomagic.recovery"), "hello\nworld\n\nbody");
        Files.writeString(folder.resolve("noblank.recovery"), "code-mate-recovery 1\noriginal=\nsaved=1\nbytes=0");
        Files.writeString(folder.resolve("shortheader.recovery"), "code-mate-recovery 1\noriginal=\n\nbody");
        Files.writeString(folder.resolve("badnumber.recovery"), "code-mate-recovery 1\noriginal=\nsaved=x\nbytes=4\n\nbody");
        Files.writeString(folder.resolve("badlength.recovery"), "code-mate-recovery 1\noriginal=\nsaved=1\nbytes=99\n\nbody");
        Files.writeString(folder.resolve("badorder.recovery"), "code-mate-recovery 1\nsaved=1\noriginal=\nbytes=4\n\nbody");
        Files.writeString(folder.resolve("baduri.recovery"), "code-mate-recovery 1\noriginal=not a uri\nsaved=1\nbytes=4\n\nbody");
        Files.write(folder.resolve("binary.recovery"), new byte[] {(byte) 0xFF, (byte) 0xFE, 0});
        Files.createDirectory(folder.resolve("dir.recovery"));
        assertEquals(List.of("good"), store.pending().stream().map(Snapshot::id).toList());
        try (var files = Files.list(folder)) { assertEquals(11, files.count(), "damaged files stay for a human to inspect"); }
    }

    @Test void tamperedByteCountsAreDetected() throws IOException {
        store.saveIfChanged("a", null, "h\u00e9llo");
        Path f = store.directory().resolve("a.recovery");
        Files.writeString(f, Files.readString(f).replace("h\u00e9llo", "hello"), StandardCharsets.UTF_8);
        assertTrue(store.pending().isEmpty());
    }

    @Test void idsCannotEscapeTheDirectory() {
        for (String bad : new String[] {null, "", "../evil", "a/b", "a\\b", "a.b", "a b", "x".repeat(65)}) {
            assertThrows(IllegalArgumentException.class, () -> store.saveIfChanged(bad, null, "x"), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> store.discard(bad), String.valueOf(bad));
        }
        assertDoesNotThrow(() -> store.saveIfChanged(RecoveryStore.newId(), null, "ok"));
        assertNotEquals(RecoveryStore.newId(), RecoveryStore.newId());
    }

    @Test void purgeRemovesOnlyOldSnapshots() throws IOException {
        store.saveIfChanged("old", null, "1");
        store.saveIfChanged("new", null, "2");
        Files.writeString(store.directory().resolve("damaged.recovery"), "junk");
        Files.setLastModifiedTime(store.directory().resolve("old.recovery"), FileTime.from(Instant.now().minus(Duration.ofDays(40))));
        Files.setLastModifiedTime(store.directory().resolve("damaged.recovery"), FileTime.from(Instant.now().minus(Duration.ofDays(40))));
        assertEquals(2, store.purgeOlderThan(Duration.ofDays(30)));
        assertEquals(List.of("new"), store.pending().stream().map(Snapshot::id).toList());
    }

    @Test void theDirectoryIsPrivateWhereSupported() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        store.saveIfChanged("a", null, "secret");
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.directory())));
    }

    @Test void aStoreDirectoryThatIsAFileFailsLoudlyOnSaveButQuietlyOnRead() throws IOException {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "i am a file");
        RecoveryStore broken = new RecoveryStore(blocker);
        assertThrows(IOException.class, () -> broken.saveIfChanged("a", null, "x"));
        assertTrue(broken.pending().isEmpty());
        broken.clear();
        broken.discard("a");
        assertEquals(0, broken.purgeOlderThan(Duration.ZERO));
    }

    @Test void largeBuffersRoundTrip() throws IOException {
        String text = "a line of unsaved work\n".repeat(200_000);
        store.saveIfChanged("big", null, text);
        assertEquals(text, store.pending().get(0).text());
    }

    @Test void unwritableSnapshotFilesAreLeftForTheNextAttempt() throws IOException {
        store.saveIfChanged("a", null, "x");
        Path f = store.directory().resolve("a.recovery");
        Files.delete(f);
        Files.createDirectory(f); // cannot be deleted as a file, cannot be read as a snapshot
        Files.writeString(f.resolve("child"), "x");
        store.discard("a"); // must not throw
        store.clear();
        assertTrue(store.pending().isEmpty());
    }

    @Test void anUnreadableFolderMeansNothingIsPending() throws IOException {
        store.saveIfChanged("a", null, "x");
        RecoveryStore unreadable = new RecoveryStore(store.directory(), true, d -> { throw new IOException("permission denied"); });
        assertTrue(unreadable.pending().isEmpty());
        unreadable.clear();
        assertEquals(1, store.pending().size(), "nothing was deleted because nothing could be listed");
    }

    @Test void fileSystemsWithoutPosixPermissionsStillWork() throws IOException {
        RecoveryStore plain = new RecoveryStore(dir.resolve("plain"), false, Files::list);
        plain.saveIfChanged("a", null, "no permissions API");
        assertEquals("no permissions API", plain.pending().get(0).text());
    }

    private record FileTimeProbe(Path file, FileTime before) {
        FileTimeProbe(Path file) throws IOException { this(file, Files.getLastModifiedTime(file)); }
        boolean unchanged() throws IOException { return Files.getLastModifiedTime(file).equals(before); }
    }
}
