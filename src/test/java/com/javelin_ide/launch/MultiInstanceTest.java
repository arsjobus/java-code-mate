package com.javelin_ide.launch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Running {@code javelin} in several folders must give several independent editor windows. The windows themselves
 * have no automated tests (see HARDENING.md), so these check the wiring that makes that safe, in the same way
 * {@code DialogOwnershipTest} checks the dialog wiring.
 */
class MultiInstanceTest {
    private static final Path APP = Path.of("src", "main", "java", "com", "javelin_ide", "App.java");
    private static final Path COMMAND = Path.of("packaging", "macos", "javelin");

    @Test void theCommandAlwaysStartsANewInstance() throws IOException {
        String script = Files.readString(COMMAND);
        assertTrue(script.contains("open -n -a"), "open -n is what gives every folder its own window");
        assertFalse(script.contains("pgrep"), "looking for a running instance is what made folders open in one window");
    }

    @Test void theAppClaimsItsRecoverySessionBeforeLookingForLeftovers() throws IOException {
        String app = Files.readString(APP);
        int method = app.indexOf("private void startCrashRecovery()");
        assertTrue(method >= 0);
        int claim = app.indexOf("recovery.startSession()", method);
        int leftovers = app.indexOf("recovery.pending()", method);
        assertTrue(claim > method && claim < leftovers, "another window's live snapshots would otherwise be offered, and discarded, here");
    }

    @Test void everyBufferOfTheAppIsSnapshottedUnderItsOwnSession() throws IOException {
        String app = Files.readString(APP);
        assertTrue(app.contains("recovery.newSessionId()"));
        assertFalse(app.contains("RecoveryStore.newId()"), "a plain id carries no session, so other windows would treat it as a leftover");
    }

    @Test void theAppDoesNotPollForFoldersMeantForAnotherWindow() throws IOException {
        String app = Files.readString(APP);
        assertFalse(app.contains("OpenRequests"), "with several windows, whichever polled first would take the folder");
        assertFalse(app.contains("requestPoll"));
    }
}
