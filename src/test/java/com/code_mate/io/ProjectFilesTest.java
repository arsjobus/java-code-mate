package com.code_mate.io;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.code_mate.io.ProjectFiles.Contents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFilesTest {
    @TempDir Path dir;
    Path project;

    @BeforeEach void setUp() throws IOException {
        project = Files.createDirectory(dir.resolve("project"));
    }

    private static void link(Path link, Path target) {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException ex) { assumeTrue(false, "symbolic links are not available here"); }
    }

    // ---- names ----

    @Test void acceptsOrdinaryNames() {
        for (String ok : new String[] {"Main.java", "my file.txt", ".gitignore", "a", "naïve-ü.txt", "x".repeat(ProjectFiles.MAX_NAME_LENGTH)})
            assertNull(ProjectFiles.nameProblem(ok), ok);
    }

    @Test void rejectsBadNamesWithAReason() {
        assertEquals("Enter a name.", ProjectFiles.nameProblem(null));
        assertEquals("Enter a name.", ProjectFiles.nameProblem(""));
        assertEquals("Enter a name.", ProjectFiles.nameProblem("   "));
        assertTrue(ProjectFiles.nameProblem(".").contains("not a valid name"));
        assertTrue(ProjectFiles.nameProblem(" .. ").contains("not a valid name"));
        assertTrue(ProjectFiles.nameProblem("a/b").contains("/"));
        assertTrue(ProjectFiles.nameProblem("a\\b").contains("\\"));
        assertTrue(ProjectFiles.nameProblem("tab\there").contains("control"));
        assertTrue(ProjectFiles.nameProblem("nul\0").contains("control"));
        assertTrue(ProjectFiles.nameProblem("x".repeat(ProjectFiles.MAX_NAME_LENGTH + 1)).contains("255"));
    }

    // ---- where new things go ----

    @Test void newItemsGoInsideAFolderAndBesideAFile() throws IOException {
        Path folder = Files.createDirectory(project.resolve("src"));
        Path file = Files.createFile(folder.resolve("A.java"));
        assertEquals(folder, ProjectFiles.directoryFor(folder));
        assertEquals(folder, ProjectFiles.directoryFor(file));
        assertEquals(project, ProjectFiles.directoryFor(project));
    }

    @Test void aRootPathHasNoParentSoItIsItsOwnDirectory() {
        Path root = dir.toAbsolutePath().getRoot();
        assertEquals(root, ProjectFiles.directoryFor(root.resolve("does-not-exist-" + System.nanoTime())).getRoot());
        assertEquals(root, ProjectFiles.directoryFor(root)); // a directory
    }

    // ---- create ----

    @Test void createsAnEmptyFile() throws IOException {
        Path created = ProjectFiles.createFile(project, "Main.java");
        assertEquals(project.resolve("Main.java"), created);
        assertTrue(Files.isRegularFile(created));
        assertEquals(0, Files.size(created));
    }

    @Test void createsAFolder() throws IOException {
        Path created = ProjectFiles.createFolder(project, "src");
        assertEquals(project.resolve("src"), created);
        assertTrue(Files.isDirectory(created));
    }

    @Test void surroundingSpacesInTheNameAreIgnored() throws IOException {
        assertEquals(project.resolve("a.txt"), ProjectFiles.createFile(project, "  a.txt "));
        assertEquals(project.resolve("b"), ProjectFiles.createFolder(project, " b "));
    }

    @Test void neverOverwritesAnExistingFile() throws IOException {
        Path f = project.resolve("keep.txt");
        Files.writeString(f, "precious");
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.createFile(project, "keep.txt"));
        assertTrue(ex.getMessage().contains("keep.txt") && ex.getMessage().contains("already exists"), ex.getMessage());
        assertEquals("precious", Files.readString(f));
    }

    @Test void neverReplacesAFolderWithAFileOrTheOtherWayRound() throws IOException {
        Files.createDirectory(project.resolve("thing"));
        Files.writeString(project.resolve("other"), "x");
        assertThrows(IOException.class, () -> ProjectFiles.createFile(project, "thing"));
        assertThrows(IOException.class, () -> ProjectFiles.createFolder(project, "thing"));
        assertThrows(IOException.class, () -> ProjectFiles.createFolder(project, "other"));
        assertEquals("x", Files.readString(project.resolve("other")));
    }

    @Test void aBrokenSymbolicLinkCountsAsExisting() {
        link(project.resolve("dangling"), project.resolve("nowhere"));
        assertThrows(IOException.class, () -> ProjectFiles.createFile(project, "dangling"));
        assertThrows(IOException.class, () -> ProjectFiles.createFolder(project, "dangling"));
        assertFalse(Files.exists(project.resolve("nowhere")));
    }

    @Test void refusesInvalidNamesWithoutCreatingAnything() throws IOException {
        assertThrows(IOException.class, () -> ProjectFiles.createFile(project, ""));
        assertThrows(IOException.class, () -> ProjectFiles.createFolder(project, "../escape"));
        assertThrows(IOException.class, () -> ProjectFiles.createFile(project, "a/b"));
        try (var s = Files.list(project)) { assertEquals(0, s.count()); }
        assertFalse(Files.exists(dir.resolve("escape")));
    }

    @Test void cannotCreateInsideAFileOrAMissingFolder() throws IOException {
        Path file = Files.createFile(project.resolve("f.txt"));
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.createFile(file, "x"));
        assertTrue(ex.getMessage().contains("f.txt") && ex.getMessage().contains("not a folder"), ex.getMessage());
        assertThrows(IOException.class, () -> ProjectFiles.createFolder(project.resolve("missing"), "x"));
    }

    // ---- contents ----

    @Test void countsWhatIsInsideAFolder() throws IOException {
        Path a = Files.createDirectories(project.resolve("a/b"));
        Files.createFile(a.resolve("1.txt"));
        Files.createFile(project.resolve("a/2.txt"));
        Files.createFile(project.resolve("3.txt"));
        assertEquals(new Contents(2, 1), ProjectFiles.contents(project.resolve("a")));
        assertEquals(new Contents(3, 2), ProjectFiles.contents(project));
        assertEquals(new Contents(1, 0), ProjectFiles.contents(project.resolve("3.txt")));
        assertEquals(new Contents(1, 0), ProjectFiles.contents(a)); // a is project/a/b
        assertEquals(new Contents(0, 0), ProjectFiles.contents(Files.createDirectory(project.resolve("empty"))));
    }

    @Test void describesContentsInWords() {
        assertEquals("", new Contents(0, 0).describe());
        assertEquals("1 file", new Contents(1, 0).describe());
        assertEquals("2 files", new Contents(2, 0).describe());
        assertEquals("1 folder", new Contents(0, 1).describe());
        assertEquals("3 folders", new Contents(0, 3).describe());
        assertEquals("1 file and 1 folder", new Contents(1, 1).describe());
        assertEquals("4 files and 2 folders", new Contents(4, 2).describe());
    }

    @Test void linksInsideAFolderAreCountedNotFollowed() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside/deep"));
        Files.createFile(outside.resolve("x.txt"));
        link(project.resolve("link"), dir.resolve("outside"));
        assertEquals(new Contents(1, 0), ProjectFiles.contents(project)); // the link is one entry, its target's files are not
    }

    // ---- isInside ----

    @Test void isInsideComparesPaths() {
        assertTrue(ProjectFiles.isInside(project, project));
        assertTrue(ProjectFiles.isInside(project, project.resolve("a/b.txt")));
        assertTrue(ProjectFiles.isInside(project, project.resolve("a/../b.txt")));
        assertFalse(ProjectFiles.isInside(project, project.resolve("../other.txt")));
        assertFalse(ProjectFiles.isInside(project.resolve("src"), project.resolve("src2/A.java"))); // a prefix of the text is not a parent
        assertFalse(ProjectFiles.isInside(project.resolve("src"), project));
    }

    // ---- delete ----

    @Test void deletesAFile() throws IOException {
        Path f = Files.createFile(project.resolve("gone.txt"));
        ProjectFiles.delete(project, f);
        assertFalse(Files.exists(f));
        assertTrue(Files.isDirectory(project));
    }

    @Test void deletesAnEmptyFolder() throws IOException {
        Path d = Files.createDirectory(project.resolve("empty"));
        ProjectFiles.delete(project, d);
        assertFalse(Files.exists(d));
    }

    @Test void deletesAFolderWithEverythingInIt() throws IOException {
        Path deep = Files.createDirectories(project.resolve("a/b/c"));
        Files.writeString(deep.resolve("x.txt"), "x");
        Files.writeString(project.resolve("a/y.txt"), "y");
        Files.writeString(project.resolve("sibling.txt"), "stay");
        ProjectFiles.delete(project, project.resolve("a"));
        assertFalse(Files.exists(project.resolve("a")));
        assertEquals("stay", Files.readString(project.resolve("sibling.txt")));
    }

    @Test void deletesWhenGivenAnUnnormalizedPath() throws IOException {
        Files.createDirectories(project.resolve("a"));
        Path f = Files.createFile(project.resolve("a/f.txt"));
        ProjectFiles.delete(project, project.resolve("a/../a/f.txt"));
        assertFalse(Files.exists(f));
    }

    @Test void refusesToDeleteTheProjectFolderItself() throws IOException {
        Files.createFile(project.resolve("keep.txt"));
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.delete(project, project));
        assertTrue(ex.getMessage().contains("project folder itself"), ex.getMessage());
        assertThrows(IOException.class, () -> ProjectFiles.delete(project, project.resolve("sub/..")));
        assertTrue(Files.exists(project.resolve("keep.txt")));
    }

    @Test void refusesToDeleteAnythingOutsideTheProject() throws IOException {
        Path outside = Files.writeString(dir.resolve("outside.txt"), "safe");
        Path sibling = Files.createDirectory(dir.resolve("project-two"));
        Files.createFile(sibling.resolve("x"));
        assertThrows(IOException.class, () -> ProjectFiles.delete(project, outside));
        assertThrows(IOException.class, () -> ProjectFiles.delete(project, project.resolve("../outside.txt")));
        assertThrows(IOException.class, () -> ProjectFiles.delete(project, sibling)); // shares a name prefix, is not inside
        assertThrows(IOException.class, () -> ProjectFiles.delete(project, dir));
        assertEquals("safe", Files.readString(outside));
        assertTrue(Files.exists(sibling.resolve("x")));
    }

    @Test void reportsAFileThatIsAlreadyGone() {
        NoSuchFileException ex = assertThrows(NoSuchFileException.class, () -> ProjectFiles.delete(project, project.resolve("ghost.txt")));
        assertTrue(ex.getMessage().contains("ghost.txt"), ex.getMessage());
    }

    @Test void deletingALinkRemovesTheLinkAndNotWhatItPointsAt() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "safe");
        link(project.resolve("link"), outside);
        ProjectFiles.delete(project, project.resolve("link"));
        assertFalse(Files.exists(project.resolve("link"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("safe", Files.readString(precious));
    }

    @Test void deletingAFolderThatContainsALinkLeavesTheLinkTargetAlone() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "safe");
        Path folder = Files.createDirectory(project.resolve("folder"));
        link(folder.resolve("link"), outside);
        Files.createFile(folder.resolve("own.txt"));
        ProjectFiles.delete(project, folder);
        assertFalse(Files.exists(folder));
        assertEquals("safe", Files.readString(precious));
    }

    @Test void deletingThroughALinkThatLeadsOutsideIsRefused() throws IOException {
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("precious.txt"), "safe");
        link(project.resolve("link"), outside);
        IOException ex = assertThrows(IOException.class, () -> ProjectFiles.delete(project, project.resolve("link/precious.txt")));
        assertTrue(ex.getMessage().contains("not inside the project"), ex.getMessage());
        assertEquals("safe", Files.readString(precious));
    }

    @Test void aLinkWithinTheProjectIsFine() throws IOException {
        Path real = Files.createDirectories(project.resolve("real"));
        Path f = Files.writeString(real.resolve("f.txt"), "x");
        link(project.resolve("alias"), real);
        ProjectFiles.delete(project, project.resolve("alias/f.txt"));
        assertFalse(Files.exists(f));
    }

    @Test void createThenDeleteRoundTrip() throws IOException {
        Path folder = ProjectFiles.createFolder(project, "pkg");
        Path file = ProjectFiles.createFile(folder, "A.java");
        assertEquals(new Contents(1, 0), ProjectFiles.contents(folder));
        ProjectFiles.delete(project, file);
        assertEquals(new Contents(0, 0), ProjectFiles.contents(folder));
        ProjectFiles.delete(project, folder);
        try (var s = Files.list(project)) { assertEquals(0, s.count()); }
    }
}
