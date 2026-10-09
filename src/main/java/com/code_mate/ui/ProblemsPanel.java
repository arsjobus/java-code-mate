package com.code_mate.ui;

import com.code_mate.build.Problem;
import javafx.collections.ObservableList;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;

import java.util.function.Consumer;

/** Lists diagnostics parsed from build output. Double-click opens the location. Call from the FX thread only. */
public final class ProblemsPanel extends BorderPane {
    private final ListView<Problem> list = new ListView<>();
    private Consumer<Problem> onOpen = p -> {};

    public ProblemsPanel() {
        list.setPlaceholder(new javafx.scene.control.Label("No problems"));
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(Problem problem, boolean empty) {
                super.updateItem(problem, empty);
                setText(empty || problem == null ? null : problem.toString());
                getStyleClass().removeAll("problem-error", "problem-warning");
                if (!empty && problem != null) getStyleClass().add(problem.severity() == Problem.Severity.ERROR ? "problem-error" : "problem-warning");
            }
        });
        list.setOnMouseClicked(e -> {
            Problem selected = list.getSelectionModel().getSelectedItem();
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && selected != null) onOpen.accept(selected);
        });
        setCenter(list);
    }

    public void setOnOpen(Consumer<Problem> handler) { onOpen = handler; }

    /** Adds a problem unless an identical one is already listed (build tools often repeat errors in their summary). */
    public void add(Problem problem) { if (!list.getItems().contains(problem)) list.getItems().add(problem); }

    public void clear() { list.getItems().clear(); }

    public ObservableList<Problem> items() { return list.getItems(); }
}
