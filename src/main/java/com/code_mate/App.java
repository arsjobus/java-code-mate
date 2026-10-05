package com.code_mate;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public final class App extends Application {
    private final TextArea editor = new TextArea();
    private final Label status = new Label("Ready");
    private File currentFile;

    @Override
    public void start(Stage stage) {
        stage.setTitle("Code Mate");

        editor.setWrapText(false);
        editor.setStyle("-fx-font-family: 'Monospaced'; -fx-font-size: 14px;");

        MenuBar menuBar = createMenuBar(stage);

        Label projectLabel = new Label("PROJECT");
        projectLabel.setStyle("-fx-font-weight: bold;");

        sidebar.getChildren().setAll(projectLabel,
                new Label("Open a file to begin."));
        sidebar.setSpacing(8);
        sidebar.setPadding(new Insets(10));
        sidebar.setPrefWidth(190);

        BorderPane root = new BorderPane();
        root.setTop(menuBar);
        root.setLeft(sidebar);
        root.setCenter(editor);

        HBox statusBar = new HBox(status);
        statusBar.setPadding(new Insets(4, 8, 4, 8));
        root.setBottom(statusBar);

        Scene scene = new Scene(root, 1100, 700);
        stage.setScene(scene);
        stage.show();
    }

    private MenuBar createMenuBar(Stage stage) {
        Menu file = new Menu("File");

        MenuItem newFile = new MenuItem("New");
        newFile.setOnAction(e -> {
            editor.clear();
            currentFile = null;
            status.setText("New document");
        });

        MenuItem open = new MenuItem("Open...");
        open.setOnAction(e -> openFile(stage));

        MenuItem save = new MenuItem("Save");
        save.setOnAction(e -> saveFile(stage, false));

        MenuItem saveAs = new MenuItem("Save As...");
        saveAs.setOnAction(e -> saveFile(stage, true));

        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> stage.close());

        file.getItems().addAll(newFile, open, save, saveAs,
                new SeparatorMenuItem(), exit);

        Menu edit = new Menu("Edit");
        MenuItem undo = new MenuItem("Undo");
        undo.setOnAction(e -> editor.undo());
        MenuItem redo = new MenuItem("Redo");
        redo.setOnAction(e -> editor.redo());
        edit.getItems().addAll(undo, redo);

        Menu view = new Menu("View");
        MenuItem focus = new MenuItem("Focus Mode");
        focus.setOnAction(e -> {
            boolean visible = sidebar.isVisible();
            sidebar.setVisible(!visible);
            sidebar.setManaged(!visible);
        });

        // Keep this initial foundation intentionally small.
        view.getItems().add(focus);

        return new MenuBar(file, edit, view);
    }

    private final VBox sidebar = new VBox();

    private void openFile(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open File");
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;

        try {
            editor.setText(Files.readString(file.toPath()));
            currentFile = file;
            status.setText("Opened: " + file.getName());
        } catch (IOException ex) {
            showError("Could not open file", ex.getMessage());
        }
    }

    private void saveFile(Stage stage, boolean forceChoose) {
        if (currentFile == null || forceChoose) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save File");
            currentFile = chooser.showSaveDialog(stage);
        }

        if (currentFile == null) return;

        try {
            Files.writeString(currentFile.toPath(), editor.getText());
            status.setText("Saved: " + currentFile.getName());
        } catch (IOException ex) {
            showError("Could not save file", ex.getMessage());
        }
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
