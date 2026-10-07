package com.code_mate;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CaretNode;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.reactfx.Subscription;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.prefs.Preferences;

public final class App extends Application {
    private static final int MAX_RECENT_PROJECTS = 8;
    private static final Pattern TOKEN = Pattern.compile(
        "(?<comment>//[^\\n]*|/\\*[\\s\\S]*?\\*/)|" +
        "(?<string>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|" +
        "(?<annotation>@[A-Za-z_$][\\w$]*)|" +
        "(?<keyword>\\b(?:abstract|assert|break|case|catch|class|continue|default|do|else|enum|extends|final|finally|for|if|implements|import|instanceof|interface|new|package|private|protected|public|return|static|super|switch|synchronized|this|throw|throws|try|var|void|while|record|sealed|yield)\\b)|" +
        "(?<type>\\b(?:boolean|byte|char|double|float|int|long|short|String|Object|Integer|Long|Double|Float|Boolean|System|List|Map|Set|File)\\b)|" +
        "(?<number>\\b\\d+(?:\\.\\d+)?\\b)"
    );
    private final TabPane tabs = new TabPane();
    private final TreeView<File> projectTree = new TreeView<>();
    private final Label status = new Label("Ready");
    private final Preferences preferences = Preferences.userNodeForPackage(App.class);
    private final Map<Tab, Boolean> dirty = new HashMap<>();
    private final Map<Tab, Subscription> subscriptions = new HashMap<>();
    private final Map<CodeArea, List<CaretNode>> extraCarets = new HashMap<>();
    private File projectDirectory;

    @Override public void start(Stage stage) {
        stage.setTitle("Code Mate");
        projectTree.setShowRoot(true);
        projectTree.setCellFactory(tree -> new TreeCell<>() {
            @Override protected void updateItem(File file, boolean empty) {
                super.updateItem(file, empty);
                setText(empty || file == null ? null : file.getName());
            }
        });
        projectTree.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                TreeItem<File> item = projectTree.getSelectionModel().getSelectedItem();
                if (item != null && item.getValue().isFile()) openFile(item.getValue());
            }
        });

        Label projectLabel = new Label("PROJECT");
        projectLabel.setStyle("-fx-font-weight: bold;");
        VBox sidebar = new VBox(8, projectLabel, projectTree);
        sidebar.setPadding(new Insets(10));
        sidebar.setPrefWidth(250);
        VBox.setVgrow(projectTree, Priority.ALWAYS);

        BorderPane root = new BorderPane();

        root.setTop(createMenuBar(stage));
        root.setLeft(sidebar);
        root.setCenter(tabs);
        HBox statusBar = new HBox(status);
        statusBar.setPadding(new Insets(4, 8, 4, 8));
        root.setBottom(statusBar);
        Scene scene = new Scene(root, 1200, 760);
        var css = getClass().getResource("/styles/editor.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> { if (!confirmCloseDirtyTabs()) event.consume(); });
        stage.show();
    }

    private MenuBar createMenuBar(Stage stage) {
        Menu file = new Menu("File");
        MenuItem newFile = new MenuItem("New"); newFile.setOnAction(e -> newDocument());
        MenuItem openFile = new MenuItem("Open File..."); openFile.setOnAction(e -> chooseAndOpenFile(stage));
        MenuItem openDirectory = new MenuItem("Open Directory..."); openDirectory.setOnAction(e -> chooseProjectDirectory(stage));
        MenuItem save = new MenuItem("Save"); save.setOnAction(e -> saveCurrent(false));
        MenuItem saveAs = new MenuItem("Save As..."); saveAs.setOnAction(e -> saveCurrent(true));
        MenuItem close = new MenuItem("Close"); close.setOnAction(e -> closeCurrentTab());
        MenuItem exit = new MenuItem("Exit"); exit.setOnAction(e -> stage.close());
        file.getItems().addAll(newFile, openFile, openDirectory, createRecentProjectsMenu(), new SeparatorMenuItem(), save, saveAs, close, new SeparatorMenuItem(), exit);

        Menu edit = new Menu("Edit");
        MenuItem undo = new MenuItem("Undo"); undo.setOnAction(e -> currentEditor().ifPresent(CodeArea::undo));
        MenuItem redo = new MenuItem("Redo"); redo.setOnAction(e -> currentEditor().ifPresent(CodeArea::redo));
        MenuItem search = new MenuItem("Search"); search.setAccelerator(new KeyCodeCombination(KeyCode.F, KeyCodeCombination.CONTROL_DOWN)); search.setOnAction(e -> search());
        MenuItem replace = new MenuItem("Replace"); replace.setAccelerator(new KeyCodeCombination(KeyCode.H, KeyCodeCombination.CONTROL_DOWN)); replace.setOnAction(e -> replace());
        MenuItem line = new MenuItem("Go to Line"); line.setAccelerator(new KeyCodeCombination(KeyCode.L, KeyCodeCombination.CONTROL_DOWN)); line.setOnAction(e -> goToLine());
        MenuItem fileItem = new MenuItem("Go to File"); fileItem.setAccelerator(new KeyCodeCombination(KeyCode.P, KeyCodeCombination.CONTROL_DOWN)); fileItem.setOnAction(e -> goToFile());
        MenuItem bracket = new MenuItem("Match Bracket"); bracket.setAccelerator(new KeyCodeCombination(KeyCode.M, KeyCodeCombination.CONTROL_DOWN)); bracket.setOnAction(e -> matchBracket());
        MenuItem cursor = new MenuItem("Add Cursor"); cursor.setAccelerator(new KeyCodeCombination(KeyCode.D, KeyCodeCombination.CONTROL_DOWN)); cursor.setOnAction(e -> addCursor());
        edit.getItems().addAll(undo, redo, new SeparatorMenuItem(), search, replace, line, fileItem, bracket, cursor);

        Menu view = new Menu("View");
        MenuItem focus = new MenuItem("Focus Mode");
        focus.setOnAction(e -> { Node node = projectTree.getParent(); if (node != null) { boolean visible = node.isVisible(); node.setVisible(!visible); node.setManaged(!visible); } });
        MenuItem fold = new MenuItem("Fold Selected"); fold.setOnAction(e -> currentEditor().ifPresent(CodeArea::foldSelectedParagraphs));
        MenuItem unfold = new MenuItem("Unfold Current"); unfold.setOnAction(e -> currentEditor().ifPresent(a -> a.unfoldParagraphs(a.getCurrentParagraph())));
        view.getItems().addAll(focus, new SeparatorMenuItem(), fold, unfold);
        return new MenuBar(file, edit, view);
    }

    private Menu createRecentProjectsMenu() {
        Menu recent = new Menu("Recent Projects");
        List<String> projects = recentProjects();
        if (projects.isEmpty()) { MenuItem empty = new MenuItem("No recent projects"); empty.setDisable(true); recent.getItems().add(empty); return recent; }
        for (String path : projects) {
            File directory = new File(path);
            MenuItem item = new MenuItem(directory.getName().isBlank() ? directory.getAbsolutePath() : directory.getName());
            item.setOnAction(e -> openProjectDirectory(directory));
            recent.getItems().add(item);
        }
        return recent;
    }

    private void chooseProjectDirectory(Stage stage) {
        DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("Open Project Directory");
        File directory = chooser.showDialog(stage); if (directory != null) openProjectDirectory(directory);
    }

    private void openProjectDirectory(File directory) {
        if (!directory.isDirectory()) { showError("Could not open project", "The selected directory no longer exists."); return; }
        projectDirectory = directory.getAbsoluteFile();
        projectTree.setRoot(createTreeItem(projectDirectory));
        projectTree.getRoot().setExpanded(true);
        addRecentProject(projectDirectory);
        status.setText("Project: " + projectDirectory.getAbsolutePath());
    }

    private TreeItem<File> createTreeItem(File file) {
        TreeItem<File> item = new TreeItem<>(file);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                List<File> sorted = new ArrayList<>(List.of(children));
                sorted.sort(Comparator.comparing(File::isFile).thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                for (File child : sorted) if (!child.getName().equals(".git") && !child.getName().equals("target")) item.getChildren().add(createTreeItem(child));
            }
        }
        return item;
    }

    private void chooseAndOpenFile(Stage stage) {
        FileChooser chooser = new FileChooser(); chooser.setTitle("Open File");
        File file = chooser.showOpenDialog(stage); if (file != null) openFile(file);
    }

    private void openFile(File file) {
        for (Tab tab : tabs.getTabs()) if (file.equals(tab.getUserData())) { tabs.getSelectionModel().select(tab); return; }
        try {
            CodeArea editor = createEditor(); editor.replaceText(Files.readString(file.toPath())); editor.moveTo(0);
            Tab tab = createTab(file.getName(), editor, file); tabs.getTabs().add(tab); tabs.getSelectionModel().select(tab);
            dirty.put(tab, false); highlight(editor); status.setText("Opened: " + file.getAbsolutePath());
        } catch (IOException ex) { showError("Could not open file", ex.getMessage()); }
    }

    private void newDocument() {
        CodeArea editor = createEditor(); Tab tab = createTab("Untitled", editor, null);
        tabs.getTabs().add(tab); tabs.getSelectionModel().select(tab); dirty.put(tab, false); status.setText("New document");
    }

    private CodeArea createEditor() {
        CodeArea editor = new CodeArea();
        editor.setWrapText(false);
        editor.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 14px;");
        editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        editor.setOnKeyPressed(this::handleKeyPressed);
        editor.addEventHandler(KeyEvent.KEY_TYPED, e -> {
            if (hasExtraCarets(editor) && !e.getCharacter().isEmpty() && !e.isControlDown() && !e.isAltDown()) {
                List<Integer> positions = extraCarets.get(editor).stream().map(CaretNode::getPosition).sorted(Comparator.reverseOrder()).toList();
                for (int p : positions) editor.insertText(p, e.getCharacter());
                clearExtraCarets(editor); e.consume();
            }
        });
        return editor;
    }

    private void handleKeyPressed(KeyEvent e) {
        if (!(e.getSource() instanceof CodeArea editor)) return;
        if (e.getCode() == KeyCode.ENTER) {
            String line = editor.getParagraph(editor.getCurrentParagraph()).getText();
            String indent = line.replaceAll("\\S.*$", "");
            String trimmed = line.stripTrailing();
            if (trimmed.endsWith("{") || trimmed.endsWith("[") || trimmed.endsWith("(")) indent += "    ";
            editor.insertText(editor.getCaretPosition(), "\n" + indent); e.consume();
        } else if ((e.getCode() == KeyCode.BACK_SPACE || e.getCode() == KeyCode.DELETE) && hasExtraCarets(editor)) {
            boolean back = e.getCode() == KeyCode.BACK_SPACE;
            List<Integer> positions = extraCarets.get(editor).stream().map(CaretNode::getPosition).sorted(Comparator.reverseOrder()).toList();
            for (int p : positions) {
                if (back && p > 0) editor.deleteText(p - 1, p);
                if (!back && p < editor.getLength()) editor.deleteText(p, p + 1);
            }
            clearExtraCarets(editor); e.consume();
        }
    }

    private Tab createTab(String title, CodeArea editor, File file) {
        Tab tab = new Tab(title, new VirtualizedScrollPane<>(editor)); tab.setUserData(file);
        subscriptions.put(tab, editor.plainTextChanges().subscribe(c -> { dirty.put(tab, true); updateTabTitle(tab); highlight(editor); }));
        tab.setOnCloseRequest(e -> { if (!confirmClose(tab)) e.consume(); });
        return tab;
    }

    private void updateTabTitle(Tab tab) {
        File f = (File) tab.getUserData(); String title = f == null ? "Untitled" : f.getName();
        tab.setText(Boolean.TRUE.equals(dirty.get(tab)) ? "* " + title : title);
    }

    private Optional<CodeArea> currentEditor() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab != null && tab.getContent() instanceof VirtualizedScrollPane<?> pane && pane.getContent() instanceof CodeArea a) return Optional.of(a);
        return Optional.empty();
    }

    private void saveCurrent(boolean forceChoose) {
        Tab tab = tabs.getSelectionModel().getSelectedItem(); if (tab == null) return;
        File file = (File) tab.getUserData();
        if (file == null || forceChoose) {
            FileChooser chooser = new FileChooser(); chooser.setTitle("Save File");
            file = chooser.showSaveDialog(tabs.getScene().getWindow()); if (file == null) return; tab.setUserData(file);
        }
        CodeArea editor = currentEditor().orElse(null); if (editor == null) return;
        try {
            Files.writeString(file.toPath(), editor.getText()); dirty.put(tab, false); updateTabTitle(tab);
            status.setText("Saved: " + file.getAbsolutePath());
            if (projectDirectory != null && file.toPath().startsWith(projectDirectory.toPath())) { projectTree.setRoot(createTreeItem(projectDirectory)); projectTree.getRoot().setExpanded(true); }
        } catch (IOException ex) { showError("Could not save file", ex.getMessage()); }
    }

    private void closeCurrentTab() { Tab tab = tabs.getSelectionModel().getSelectedItem(); if (tab != null && confirmClose(tab)) tabs.getTabs().remove(tab); }

    private boolean confirmClose(Tab tab) {
        if (!Boolean.TRUE.equals(dirty.get(tab))) return true;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved Changes"); alert.setHeaderText("Save changes to " + tab.getText().replaceFirst("^\\* ", "") + "?");
        ButtonType save = new ButtonType("Save"), discard = new ButtonType("Discard"), cancel = ButtonType.CANCEL;
        alert.getButtonTypes().setAll(save, discard, cancel);
        ButtonType result = alert.showAndWait().orElse(cancel);
        if (result == save) { tabs.getSelectionModel().select(tab); saveCurrent(false); return !Boolean.TRUE.equals(dirty.get(tab)); }
        return result == discard;
    }

    private boolean confirmCloseDirtyTabs() { for (Tab tab : new ArrayList<>(tabs.getTabs())) if (!confirmClose(tab)) return false; return true; }

    private void search() {
        currentEditor().ifPresent(a -> {
            TextInputDialog d = new TextInputDialog(); d.setTitle("Search"); d.setHeaderText("Find text"); d.setContentText("Search:");
            d.showAndWait().filter(s -> !s.isEmpty()).ifPresent(q -> {
                int p = a.getText().indexOf(q, a.getCaretPosition()); if (p < 0) p = a.getText().indexOf(q);
                if (p >= 0) { a.selectRange(p, p + q.length()); status.setText("Found: " + q); } else status.setText("Not found: " + q);
            });
        });
    }

    private void replace() {
        currentEditor().ifPresent(a -> {
            TextInputDialog f = new TextInputDialog(); f.setTitle("Replace"); f.setHeaderText("Find text"); f.setContentText("Find:");
            Optional<String> q = f.showAndWait(); if (q.isEmpty() || q.get().isEmpty()) return;
            TextInputDialog r = new TextInputDialog(); r.setTitle("Replace"); r.setHeaderText("Replacement text"); r.setContentText("Replace with:");
            r.showAndWait().ifPresent(v -> { a.replaceText(a.getText().replace(q.get(), v)); status.setText("Replaced: " + q.get()); });
        });
    }

    private void goToLine() {
        currentEditor().ifPresent(a -> {
            TextInputDialog d = new TextInputDialog(String.valueOf(a.getCurrentParagraph() + 1));
            d.setTitle("Go to Line"); d.setHeaderText("Line number"); d.setContentText("Line:");
            d.showAndWait().ifPresent(s -> {
                try { int n = Integer.parseInt(s.trim()); if (n < 1 || n > a.getParagraphs().size()) throw new NumberFormatException();
                    a.moveTo(n - 1, 0); a.requestFollowCaret(); status.setText("Line " + n);
                } catch (NumberFormatException ex) { showError("Invalid line", "Enter a valid line number."); }
            });
        });
    }

    private void goToFile() {
        if (projectDirectory == null) { showError("Go to File", "Open a project directory first."); return; }
        TextInputDialog d = new TextInputDialog(); d.setTitle("Go to File"); d.setHeaderText("Project file name"); d.setContentText("File:");
        d.showAndWait().filter(s -> !s.isBlank()).ifPresent(q -> { File f = findFile(projectDirectory, q.trim()); if (f != null) openFile(f); else status.setText("File not found: " + q); });
    }

    private File findFile(File dir, String q) {
        File[] files = dir.listFiles(); if (files == null) return null;
        for (File f : files) if (f.isFile() && f.getName().equals(q)) return f;
        for (File f : files) if (f.isDirectory() && !f.getName().equals(".git") && !f.getName().equals("target")) { File hit = findFile(f, q); if (hit != null) return hit; }
        return null;
    }

    private void matchBracket() {
        currentEditor().ifPresent(a -> {
            String s = a.getText(); int p = a.getCaretPosition();
            int b = p < s.length() && "([{)]}".indexOf(s.charAt(p)) >= 0 ? p : (p > 0 && "([{)]}".indexOf(s.charAt(p - 1)) >= 0 ? p - 1 : -1);
            if (b < 0) { status.setText("No bracket at caret"); return; }
            char c = s.charAt(b), open = ")]}".indexOf(c) >= 0 ? "([{".charAt(")]}".indexOf(c)) : c;
            char close = "([{".indexOf(c) >= 0 ? ")]}".charAt("([{".indexOf(c)) : c;
            int dir = "([{".indexOf(c) >= 0 ? 1 : -1, depth = 0;
            for (int i = b; i >= 0 && i < s.length(); i += dir) {
                char x = s.charAt(i);
                if (x == open) depth += dir;
                else if (x == close) depth -= dir;
                if (depth == 0) { a.selectRange(Math.min(b, i), Math.max(b, i) + 1); status.setText("Matching bracket"); return; }
            }
            status.setText("No matching bracket");
        });
    }

    private void addCursor() {
        currentEditor().ifPresent(a -> {
            CaretNode c = new CaretNode("extra-" + System.nanoTime(), a, a.getCaretPosition());
            a.addCaret(c); extraCarets.computeIfAbsent(a, k -> new ArrayList<>()).add(c); status.setText("Additional cursor added");
        });
    }

    private boolean hasExtraCarets(CodeArea a) { return extraCarets.containsKey(a) && !extraCarets.get(a).isEmpty(); }

    private void clearExtraCarets(CodeArea a) {
        List<CaretNode> list = extraCarets.remove(a); if (list != null) list.forEach(a::removeCaret);
    }

    private void highlight(CodeArea a) {
        String text = a.getText(); StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        Matcher m = TOKEN.matcher(text); int last = 0;
        while (m.find()) {
            if (m.start() > last) b.add(Collections.emptyList(), m.start() - last);
            String style = m.group("comment") != null ? "comment" : m.group("string") != null ? "string" :
                    m.group("annotation") != null ? "annotation" : m.group("keyword") != null ? "keyword" :
                    m.group("type") != null ? "type" : "number";
            b.add(Collections.singleton(style), m.end() - m.start()); last = m.end();
        }
        if (last < text.length()) b.add(Collections.emptyList(), text.length() - last);
        a.setStyleSpans(0, b.create());
    }

    private List<String> recentProjects() {
        List<String> projects = new ArrayList<>();
        for (int i = 0; i < MAX_RECENT_PROJECTS; i++) { String path = preferences.get("recent.project." + i, ""); if (!path.isBlank() && new File(path).isDirectory()) projects.add(path); }
        return projects;
    }

    private void addRecentProject(File directory) {
        List<String> projects = recentProjects(); String path = directory.getAbsolutePath(); projects.remove(path); projects.add(0, path);
        for (int i = 0; i < MAX_RECENT_PROJECTS; i++) { if (i < projects.size()) preferences.put("recent.project." + i, projects.get(i)); else preferences.remove("recent.project." + i); }
    }

    private void showError(String title, String message) { Alert a = new Alert(Alert.AlertType.ERROR); a.setTitle(title); a.setHeaderText(null); a.setContentText(message == null ? "Unknown error." : message); a.showAndWait(); }

    @Override public void stop() { subscriptions.values().forEach(Subscription::unsubscribe); }

    public static void main(String[] args) { launch(args); }
}
