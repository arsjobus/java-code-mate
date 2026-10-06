package com.code_mate;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.prefs.Preferences;

public final class App extends Application {
    private static final int MAX_RECENT_PROJECTS = 8;
    private final TabPane tabs = new TabPane();
    private final TreeView<File> projectTree = new TreeView<>();
    private final Label status = new Label("Ready");
    private final Preferences preferences = Preferences.userNodeForPackage(App.class);
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

        Scene scene = new Scene(root, 1100, 700);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> { if (!confirmCloseDirtyTabs()) event.consume(); });
        stage.show();
    }

    private MenuBar createMenuBar(Stage stage) {
        Menu file = new Menu("File");
        MenuItem newFile = new MenuItem("New"); newFile.setOnAction(e -> newDocument());
        MenuItem openFile = new MenuItem("Open File..."); openFile.setOnAction(e -> chooseAndOpenFile(stage));
        MenuItem openDirectory = new MenuItem("Open Directory..."); openDirectory.setOnAction(e -> chooseProjectDirectory(stage));
        Menu recent = createRecentProjectsMenu();
        MenuItem save = new MenuItem("Save"); save.setOnAction(e -> saveCurrent(false));
        MenuItem saveAs = new MenuItem("Save As..."); saveAs.setOnAction(e -> saveCurrent(true));
        MenuItem close = new MenuItem("Close"); close.setOnAction(e -> closeCurrentTab());
        MenuItem exit = new MenuItem("Exit"); exit.setOnAction(e -> stage.close());
        file.getItems().addAll(newFile, openFile, openDirectory, recent, new SeparatorMenuItem(), save, saveAs, close, new SeparatorMenuItem(), exit);

        Menu edit = new Menu("Edit");
        MenuItem undo = new MenuItem("Undo"); undo.setOnAction(e -> currentEditor().ifPresent(TextArea::undo));
        MenuItem redo = new MenuItem("Redo"); redo.setOnAction(e -> currentEditor().ifPresent(TextArea::redo));
        edit.getItems().addAll(undo, redo);

        Menu view = new Menu("View");
        MenuItem focus = new MenuItem("Focus Mode");
        focus.setOnAction(e -> { Node node = projectTree.getParent(); if (node != null) { boolean visible = node.isVisible(); node.setVisible(!visible); node.setManaged(!visible); } });
        view.getItems().add(focus);
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
            TextArea editor = createEditor(); editor.setText(Files.readString(file.toPath())); editor.positionCaret(0);
            Tab tab = createTab(file.getName(), editor, file); tabs.getTabs().add(tab); tabs.getSelectionModel().select(tab);
            status.setText("Opened: " + file.getAbsolutePath());
        } catch (IOException ex) { showError("Could not open file", ex.getMessage()); }
    }

    private void newDocument() { TextArea editor = createEditor(); Tab tab = createTab("Untitled", editor, null); tabs.getTabs().add(tab); tabs.getSelectionModel().select(tab); status.setText("New document"); }

    private TextArea createEditor() { TextArea editor = new TextArea(); editor.setWrapText(false); editor.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 14px;"); return editor; }

    private Tab createTab(String title, TextArea editor, File file) {
        Tab tab = new Tab(title, editor); tab.setUserData(file);
        editor.textProperty().addListener((obs, oldText, newText) -> { if (!tab.getText().startsWith("* ")) tab.setText("* " + tab.getText()); });
        tab.setOnCloseRequest(event -> { if (!confirmClose(tab)) event.consume(); });
        return tab;
    }

    private void saveCurrent(boolean forceChoose) {
        Tab tab = tabs.getSelectionModel().getSelectedItem(); if (tab == null) return;
        File file = (File) tab.getUserData();
        if (file == null || forceChoose) {
            FileChooser chooser = new FileChooser(); chooser.setTitle("Save File");
            file = chooser.showSaveDialog(tabs.getScene().getWindow()); if (file == null) return; tab.setUserData(file);
        }
        TextArea editor = (TextArea) tab.getContent();
        try {
            Files.writeString(file.toPath(), editor.getText()); tab.setText(file.getName()); status.setText("Saved: " + file.getAbsolutePath());
            if (projectDirectory != null && file.toPath().startsWith(projectDirectory.toPath())) { projectTree.setRoot(createTreeItem(projectDirectory)); projectTree.getRoot().setExpanded(true); }
        } catch (IOException ex) { showError("Could not save file", ex.getMessage()); }
    }

    private void closeCurrentTab() { Tab tab = tabs.getSelectionModel().getSelectedItem(); if (tab != null && confirmClose(tab)) tabs.getTabs().remove(tab); }

    private boolean confirmClose(Tab tab) {
        if (!tab.getText().startsWith("* ")) return true;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved Changes"); alert.setHeaderText("Save changes to " + tab.getText().substring(2) + "?");
        alert.setContentText("Choose Save to keep your changes, Discard to close them, or Cancel.");
        ButtonType save = new ButtonType("Save"), discard = new ButtonType("Discard"), cancel = ButtonType.CANCEL;
        alert.getButtonTypes().setAll(save, discard, cancel);
        ButtonType result = alert.showAndWait().orElse(cancel);
        if (result == save) { tabs.getSelectionModel().select(tab); saveCurrent(false); return !tab.getText().startsWith("* "); }
        return result == discard;
    }

    private boolean confirmCloseDirtyTabs() { for (Tab tab : new ArrayList<>(tabs.getTabs())) if (!confirmClose(tab)) return false; return true; }

    private java.util.Optional<TextArea> currentEditor() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab != null && tab.getContent() instanceof TextArea editor) return java.util.Optional.of(editor);
        return java.util.Optional.empty();
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

    private void showError(String title, String message) { Alert alert = new Alert(Alert.AlertType.ERROR); alert.setTitle(title); alert.setHeaderText(null); alert.setContentText(message == null ? "Unknown error." : message); alert.showAndWait(); }

    public static void main(String[] args) { launch(args); }
}
