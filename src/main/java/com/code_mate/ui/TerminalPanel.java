package com.code_mate.ui;

import com.code_mate.build.ManagedProcess;
import com.code_mate.build.ProcessManager;
import com.code_mate.build.ShellCommand;
import javafx.application.Platform;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A line-oriented terminal: a persistent shell fed from a text field. There is no PTY, so
 * full-screen programs (vim, top) and job control are not supported. The shell starts on
 * the first command, in the project directory when one is open. Call from the FX thread only.
 */
public final class TerminalPanel extends BorderPane {
    private static final int MAX_CHARS = 400_000;
    private static final int KEEP_CHARS = 300_000;
    private final ProcessManager processes;
    private final Supplier<File> directory;
    private final TextArea transcript = new TextArea();
    private final TextField input = new TextField();
    private final List<String> history = new ArrayList<>();
    private int historyIndex;
    private ManagedProcess shell;

    public TerminalPanel(ProcessManager processes, Supplier<File> directory) {
        this.processes = processes;
        this.directory = directory;
        transcript.setEditable(false);
        transcript.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 13px;");
        input.setPromptText("Type a command and press Enter (no PTY: interactive full-screen programs are not supported)");
        input.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 13px;");
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.UP && historyIndex > 0) { input.setText(history.get(--historyIndex)); input.end(); e.consume(); }
            else if (e.getCode() == KeyCode.DOWN && historyIndex < history.size()) {
                historyIndex++; input.setText(historyIndex == history.size() ? "" : history.get(historyIndex)); input.end(); e.consume();
            }
        });
        input.setOnAction(e -> submit());
        setCenter(transcript);
        setBottom(input);
    }

    public void focusInput() { input.requestFocus(); }

    private void submit() {
        String line = input.getText();
        input.clear();
        if (line.isBlank()) return;
        history.add(line);
        historyIndex = history.size();
        try {
            ensureShell();
            append("$ " + line);
            shell.send(line);
        } catch (IOException ex) {
            append("[could not reach shell: " + ex.getMessage() + "]");
            shell = null;
        }
    }

    private void ensureShell() throws IOException {
        if (shell != null && shell.isAlive()) return;
        File dir = directory.get();
        if (dir == null) dir = new File(System.getProperty("user.home"));
        append("[starting shell in " + dir + "]");
        shell = processes.start("Terminal", ShellCommand.interactive(), dir, new ProcessManager.Listener() {
            @Override public void onLine(ManagedProcess p, String line) { Platform.runLater(() -> append(line)); }
            @Override public void onExit(ManagedProcess p, int code) { Platform.runLater(() -> append("[shell exited with code " + code + "]")); }
        });
    }

    private void append(String line) {
        transcript.appendText(line + "\n");
        if (transcript.getLength() > MAX_CHARS) transcript.deleteText(0, transcript.getLength() - KEEP_CHARS);
    }
}
