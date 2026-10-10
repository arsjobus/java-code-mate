package com.code_mate;

import com.code_mate.build.BuildSystem;
import com.code_mate.build.ManagedProcess;
import com.code_mate.build.Problem;
import com.code_mate.build.ProblemParser;
import com.code_mate.build.ProcessManager;
import com.code_mate.build.ShellCommand;
import com.code_mate.language.Languages;
import com.code_mate.language.LanguageService;
import com.code_mate.language.Lsp;
import com.code_mate.settings.EditorPreferences;
import com.code_mate.settings.Keybindings;
import com.code_mate.settings.Settings;
import com.code_mate.settings.SettingsTemplates;
import com.code_mate.settings.Themes;
import com.code_mate.settings.Themes.Theme;
import com.code_mate.ui.CompletionPopup;
import com.code_mate.ui.HoverPopup;
import com.code_mate.ui.OutputPanel;
import com.code_mate.ui.ProblemsPanel;
import com.code_mate.ui.ReferencesPanel;
import com.code_mate.ui.SettingsDialog;
import com.code_mate.ui.Shortcuts;
import com.code_mate.ui.TerminalsPanel;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CaretNode;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.reactfx.Subscription;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.prefs.Preferences;

public final class App extends Application {
    private static final int MAX_RECENT_PROJECTS = 8;
    private static final String THEME_KEY = "theme";
    private static final String DEFAULT_TREE_EXCLUDES = ".git,target";
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
    private final ProcessManager processes = new ProcessManager();
    private final OutputPanel output = new OutputPanel();
    private final ProblemsPanel problems = new ProblemsPanel();
    private final TerminalsPanel terminals = new TerminalsPanel(processes, () -> this.projectDirectory);
    private final Tab outputTab = new Tab("Output", output);
    private final Tab problemsTab = new Tab("Problems (0)", problems);
    private final Tab terminalTab = new Tab("Terminal", terminals);
    private final ReferencesPanel references = new ReferencesPanel();
    private final Tab referencesTab = new Tab("References", references);
    private final TabPane bottomTabs = new TabPane(outputTab, problemsTab, referencesTab, terminalTab);
    private final Label languageStatus = new Label();
    private final CompletionPopup completionPopup = new CompletionPopup();
    private final HoverPopup hoverPopup = new HoverPopup();
    private final Map<File, List<Lsp.Diagnostic>> diagnostics = new HashMap<>();
    private final LanguageService language = new LanguageService(this::onDiagnostics, this::onLanguageLog, () -> Platform.runLater(this::updateLanguageStatus));
    private final SplitPane editorSplit = new SplitPane(tabs);
    private File projectDirectory;
    private Scene scene;
    // v0.8 customization: plain-file settings (see CUSTOMIZATION.md). The java.util.prefs store still holds
    // recent projects, the last-used commands and, as a fallback for existing installs, the old theme choice.
    private final Settings settings = new Settings(Settings.defaultUserDirectory().resolve(Settings.FILE_NAME));
    private final Keybindings keys = new Keybindings(settings);
    private final Map<String, MenuItem> menuItems = new LinkedHashMap<>();
    private final List<String> keyProblems = new ArrayList<>();
    private final Set<String> themeSheets = new HashSet<>();
    private final Menu themeMenu = new Menu("Theme");
    private List<Theme> themes = Themes.builtIn();
    private Theme theme = themes.get(0);
    private EditorPreferences editorPrefs = EditorPreferences.from(settings);
    private Set<String> treeExcludes = parseNames(settings.get("tree.exclude", DEFAULT_TREE_EXCLUDES));
    private int zoom; // temporary font-size offset for this session; never saved

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
        bottomTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        editorSplit.setOrientation(Orientation.VERTICAL);
        problems.setOnOpen(this::openProblem);
        references.setOnOpen(r -> openFile(r.file(), r.line(), r.column()));
        tabs.getTabs().addListener((ListChangeListener<Tab>) change -> {
            while (change.next()) if (change.wasRemoved()) change.getRemoved().forEach(this::forgetTab);
        });
        problems.items().addListener((ListChangeListener<Problem>) c -> problemsTab.setText("Problems (" + problems.items().size() + ")"));
        root.setCenter(editorSplit);
        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        HBox statusBar = new HBox(status, statusSpacer, languageStatus);
        statusBar.setPadding(new Insets(4, 8, 4, 8));
        root.setBottom(statusBar);
        scene = new Scene(root, 1200, 760);
        var css = getClass().getResource("/styles/editor.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        installTheme();
        applyKeybindings();
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> { if (!confirmCloseDirtyTabs()) event.consume(); });
        stage.show();
        reportSettingsProblems(false);
    }

    /** Creates a menu item whose shortcut can be rebound: its id and default are registered with {@link #keys}. */
    private MenuItem bound(String menu, String text, String id, String defaultBinding, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        keys.register(id, menu + " > " + text.replace("...", ""), defaultBinding);
        menuItems.put(id, item);
        item.setAccelerator(Shortcuts.parse(keys.binding(id)));
        return item;
    }

    private MenuBar createMenuBar(Stage stage) {
        Menu file = new Menu("File");
        file.getItems().addAll(
            bound("File", "New", "file.new", "", this::newDocument),
            bound("File", "Open File...", "file.openFile", "", () -> chooseAndOpenFile(stage)),
            bound("File", "Open Directory...", "file.openDirectory", "", () -> chooseProjectDirectory(stage)),
            createRecentProjectsMenu(), new SeparatorMenuItem(),
            bound("File", "Save", "file.save", "", () -> saveCurrent(false)),
            bound("File", "Save As...", "file.saveAs", "", () -> saveCurrent(true)),
            bound("File", "Close", "file.close", "", this::closeCurrentTab), new SeparatorMenuItem(),
            bound("File", "Exit", "file.exit", "", stage::close));

        Menu edit = new Menu("Edit");
        edit.getItems().addAll(
            bound("Edit", "Undo", "edit.undo", "", () -> currentEditor().ifPresent(CodeArea::undo)),
            bound("Edit", "Redo", "edit.redo", "", () -> currentEditor().ifPresent(CodeArea::redo)), new SeparatorMenuItem(),
            bound("Edit", "Search", "edit.search", "Ctrl+F", this::search),
            bound("Edit", "Replace", "edit.replace", "Ctrl+H", this::replace),
            bound("Edit", "Go to Line", "edit.goToLine", "Ctrl+L", this::goToLine),
            bound("Edit", "Go to File", "edit.goToFile", "Ctrl+P", this::goToFile),
            bound("Edit", "Match Bracket", "edit.matchBracket", "Ctrl+M", this::matchBracket),
            bound("Edit", "Add Cursor", "edit.addCursor", "Ctrl+D", this::addCursor));

        Menu view = new Menu("View");
        view.getItems().addAll(
            bound("View", "Focus Mode", "view.focusMode", "", () -> { Node node = projectTree.getParent(); if (node != null) { boolean visible = node.isVisible(); node.setVisible(!visible); node.setManaged(!visible); } }),
            new SeparatorMenuItem(),
            bound("View", "Fold Selected", "view.fold", "", () -> currentEditor().ifPresent(CodeArea::foldSelectedParagraphs)),
            bound("View", "Unfold Current", "view.unfold", "", () -> currentEditor().ifPresent(a -> a.unfoldParagraphs(a.getCurrentParagraph()))),
            new SeparatorMenuItem(),
            bound("View", "Toggle Bottom Panel", "view.bottomPanel", "Ctrl+J", this::toggleBottomPanel),
            bound("View", "Output", "view.output", "", () -> showBottom(outputTab)),
            bound("View", "Problems", "view.problems", "", () -> showBottom(problemsTab)),
            bound("View", "References", "view.references", "", () -> showBottom(referencesTab)),
            bound("View", "Terminal", "view.terminal", "Ctrl+BACK_QUOTE", () -> { showBottom(terminalTab); terminals.focusInput(); }),
            bound("View", "New Terminal", "view.newTerminal", "Ctrl+Shift+BACK_QUOTE", () -> { showBottom(terminalTab); terminals.newTerminal(); }),
            new SeparatorMenuItem(),
            bound("View", "Zoom In", "view.zoomIn", "Ctrl+EQUALS", () -> zoomEditors(1)),
            bound("View", "Zoom Out", "view.zoomOut", "Ctrl+MINUS", () -> zoomEditors(-1)),
            bound("View", "Reset Zoom", "view.zoomReset", "Ctrl+DIGIT0", () -> zoomEditors(0)),
            new SeparatorMenuItem(), themeMenu);

        Menu languageMenu = new Menu("Language");
        languageMenu.getItems().addAll(
            bound("Language", "Start Language Server...", "language.startServer", "", this::startLanguageServer),
            bound("Language", "Stop Language Server", "language.stopServer", "", this::stopLanguageServer),
            new SeparatorMenuItem(),
            bound("Language", "Complete", "language.complete", "Ctrl+SPACE", this::complete),
            bound("Language", "Show Hover Info", "language.hover", "Ctrl+I", this::hover),
            bound("Language", "Signature Help", "language.signatureHelp", "Ctrl+Shift+SPACE", this::signatureHelp),
            bound("Language", "Go to Definition", "language.goToDefinition", "F12", this::goToDefinition),
            bound("Language", "Find References", "language.findReferences", "Shift+F12", this::findReferences),
            bound("Language", "Document Symbols", "language.documentSymbols", "Ctrl+Shift+O", this::documentSymbols),
            new SeparatorMenuItem(),
            bound("Language", "Rename Symbol...", "language.rename", "F2", this::renameSymbol),
            bound("Language", "Format Document", "language.format", "Ctrl+Shift+F", this::formatDocument));

        Menu run = new Menu("Run");
        run.getItems().addAll(
            bound("Run", "Build", "run.build", "Ctrl+B", this::build),
            bound("Run", "Run Command...", "run.runCommand", "F5", this::runCommandDialog),
            new SeparatorMenuItem(),
            bound("Run", "Stop All Processes", "run.stopAll", "Ctrl+PERIOD", this::stopAllProcesses),
            bound("Run", "Processes...", "run.processes", "", this::showProcesses));

        Menu settingsMenu = new Menu("Settings");
        settingsMenu.getItems().addAll(
            bound("Settings", "Settings...", "settings.open", "Ctrl+COMMA", this::openSettingsDialog),
            bound("Settings", "Project Settings...", "settings.project", "", this::openProjectSettingsDialog),
            new SeparatorMenuItem(),
            bound("Settings", "Open User Settings File", "settings.openUserFile", "", () -> openSettingsFile(Settings.Scope.USER)),
            bound("Settings", "Open Project Settings File", "settings.openProjectFile", "", () -> openSettingsFile(Settings.Scope.PROJECT)),
            bound("Settings", "Reload Settings", "settings.reload", "", this::reloadSettings));
        return new MenuBar(file, edit, view, languageMenu, run, settingsMenu);
    }

    // ---- customization (v0.8): themes, keybindings, settings ----
    // Everything is stored in plain files (see CUSTOMIZATION.md) and applied only on startup, after the Settings
    // dialog, or when a settings file is saved in the editor or reloaded explicitly.

    /** Light is the default look; other themes add style classes (and, for custom themes, a stylesheet) to the scene root. */
    private void installTheme() {
        // Dialogs open in their own scene, so they do not inherit this window's stylesheet or theme class.
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) if (change.wasAdded()) change.getAddedSubList().forEach(this::styleDialog);
        });
        refreshThemes();
        applyTheme();
    }

    /** Re-reads the themes folder and picks the configured theme (falling back to the pre-v0.8 saved choice, then light). */
    private void refreshThemes() {
        themes = Themes.available(settings.themesDirectory());
        theme = Themes.find(themes, settings.get(THEME_KEY, preferences.get(THEME_KEY, Themes.DEFAULT_ID)));
        ToggleGroup group = new ToggleGroup();
        themeMenu.getItems().clear();
        for (Theme candidate : themes) {
            RadioMenuItem item = new RadioMenuItem(candidate.name());
            item.setToggleGroup(group);
            item.setSelected(candidate.id().equals(theme.id()));
            item.setOnAction(e -> chooseTheme(candidate));
            themeMenu.getItems().add(item);
        }
    }

    private void chooseTheme(Theme chosen) {
        settings.put(Settings.Scope.USER, THEME_KEY, chosen.id());
        saveUserSettings();
        theme = chosen;
        applyTheme();
        status.setText("Theme: " + chosen.name());
    }

    private void applyTheme() {
        scene.getStylesheets().removeIf(themeSheets::contains);
        if (theme.stylesheet() != null) {
            String url = theme.stylesheet().toUri().toString();
            themeSheets.add(url);
            scene.getStylesheets().add(url); // after editor.css, so a custom theme can override it
        }
        setThemeClass(scene.getRoot());
        Window.getWindows().forEach(this::styleDialog);
    }

    private void styleDialog(Window window) {
        Scene dialogScene = window.getScene();
        if (dialogScene == null || !(dialogScene.getRoot() instanceof DialogPane pane)) return;
        syncStylesheets(pane);
        setThemeClass(pane);
    }

    /** Gives a dialog or popup the scene's stylesheets, dropping custom-theme sheets that are no longer in use. */
    private void syncStylesheets(Parent target) {
        target.getStylesheets().removeIf(sheet -> themeSheets.contains(sheet) && !scene.getStylesheets().contains(sheet));
        for (String sheet : scene.getStylesheets()) if (!target.getStylesheets().contains(sheet)) target.getStylesheets().add(sheet);
    }

    private void setThemeClass(Parent root) {
        root.getStyleClass().removeAll(Themes.ALL_CLASSES);
        root.getStyleClass().addAll(theme.styleClasses());
    }

    private void applyKeybindings() {
        keyProblems.clear();
        for (Keybindings.Action action : keys.actions()) {
            MenuItem item = menuItems.get(action.id());
            if (item == null) continue;
            String binding = keys.binding(action.id());
            var combination = Shortcuts.parse(binding);
            if (!binding.isEmpty() && combination == null) keyProblems.add("key." + action.id() + ": '" + binding + "' is not a shortcut Code Mate understands");
            item.setAccelerator(combination);
        }
    }

    /** Re-applies everything derived from settings: editor preferences, theme, shortcuts, and the project tree. */
    private void applySettings() {
        editorPrefs = EditorPreferences.from(settings);
        treeExcludes = parseNames(settings.get("tree.exclude", DEFAULT_TREE_EXCLUDES));
        refreshThemes();
        applyTheme();
        applyKeybindings();
        forEachEditor(this::applyEditorPreferences);
        refreshProjectTree();
    }

    private void reloadSettings() {
        settings.reload();
        applySettings();
        if (!reportSettingsProblems(true)) status.setText("Settings reloaded");
    }

    /** Writes problems found in the settings files to the Output panel. Returns true if there were any. */
    private boolean reportSettingsProblems(boolean reveal) {
        List<String> problems = new ArrayList<>(settings.warnings());
        problems.addAll(EditorPreferences.validate(settings));
        problems.addAll(keys.problems());
        problems.addAll(keyProblems);
        for (String problem : problems) output.append("settings: " + problem);
        if (problems.isEmpty()) return false;
        status.setText(problems.size() + " settings problem(s); see Output");
        if (reveal) showBottom(outputTab);
        return true;
    }

    private void saveUserSettings() {
        try { settings.save(Settings.Scope.USER); } catch (IOException ex) { showError("Could not save settings", ex.getMessage()); }
    }

    private void openSettingsDialog() {
        if (SettingsDialog.showUser(scene.getWindow(), settings, keys, themes, theme.id())) { applySettings(); status.setText("Settings saved"); }
    }

    private void openProjectSettingsDialog() {
        if (!requireProject("Project Settings")) return;
        if (SettingsDialog.showProject(scene.getWindow(), settings)) { applySettings(); status.setText("Project settings saved"); }
    }

    /** Opens a settings file in an editor tab, first creating it from a fully commented template if it does not exist. */
    private void openSettingsFile(Settings.Scope scope) {
        Path path = scope == Settings.Scope.USER ? settings.userFile() : settings.projectFile();
        if (path == null) { showError("Project Settings", "Open a project directory first."); return; }
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, scope == Settings.Scope.USER ? SettingsTemplates.user(keys) : SettingsTemplates.project());
                if (scope == Settings.Scope.PROJECT) refreshProjectTree();
            }
        } catch (IOException ex) { showError("Could not create settings file", ex.getMessage()); return; }
        openFile(path.toFile());
    }

    /** True for the settings files and custom theme stylesheets: saving one of them re-applies settings. */
    private boolean isSettingsPath(File file) {
        Path path = file.toPath().toAbsolutePath().normalize();
        if (path.equals(settings.userFile())) return true;
        if (settings.projectFile() != null && path.equals(settings.projectFile())) return true;
        return path.startsWith(settings.themesDirectory()) && path.toString().toLowerCase(Locale.ROOT).endsWith(".css");
    }

    private void applyEditorPreferences(CodeArea editor) {
        editor.setStyle(editorPrefs.style(zoom));
        editor.setWrapText(editorPrefs.wordWrap());
        editor.setParagraphGraphicFactory(editorPrefs.lineNumbers() ? LineNumberFactory.get(editor) : null);
    }

    private void forEachEditor(Consumer<CodeArea> action) {
        for (Tab tab : tabs.getTabs()) if (tab.getContent() instanceof VirtualizedScrollPane<?> pane && pane.getContent() instanceof CodeArea a) action.accept(a);
    }

    /** Changes the editors' font size for this session only (0 resets); the saved font size is not touched. */
    private void zoomEditors(int delta) {
        zoom = delta == 0 ? 0 : Math.max(-30, Math.min(30, zoom + delta));
        forEachEditor(this::applyEditorPreferences);
        status.setText(zoom == 0 ? "Font size: " + editorPrefs.fontSize() : "Font size: " + editorPrefs.fontSize() + (zoom > 0 ? " +" : " ") + zoom + " (this session)");
    }

    private void refreshProjectTree() {
        if (projectDirectory == null) return;
        projectTree.setRoot(createTreeItem(projectDirectory));
        projectTree.getRoot().setExpanded(true);
    }

    private static Set<String> parseNames(String commaSeparated) {
        Set<String> names = new LinkedHashSet<>();
        for (String name : commaSeparated.split(",")) if (!name.isBlank()) names.add(name.strip());
        return names;
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
        settings.setProject(projectDirectory.toPath()); // reads <project>/.code-mate/settings.conf if there is one
        applySettings();
        addRecentProject(projectDirectory);
        status.setText("Project: " + projectDirectory.getAbsolutePath());
        reportSettingsProblems(false);
    }

    private TreeItem<File> createTreeItem(File file) {
        TreeItem<File> item = new TreeItem<>(file);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                List<File> sorted = new ArrayList<>(List.of(children));
                sorted.sort(Comparator.comparing(File::isFile).thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                for (File child : sorted) if (!treeExcludes.contains(child.getName())) item.getChildren().add(createTreeItem(child));
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
            dirty.put(tab, false); language.open(file, editor.getText()); highlight(editor); status.setText("Opened: " + file.getAbsolutePath());
        } catch (IOException ex) { showError("Could not open file", ex.getMessage()); }
    }

    private void openFile(File file, int line, int column) {
        openFile(file);
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab == null || !file.equals(tab.getUserData())) return;
        currentEditor().ifPresent(a -> {
            int paragraph = Math.max(0, Math.min(line - 1, a.getParagraphs().size() - 1));
            a.moveTo(paragraph, Math.min(Math.max(0, column - 1), a.getParagraphLength(paragraph)));
            a.requestFollowCaret(); a.requestFocus();
        });
    }

    private void newDocument() {
        CodeArea editor = createEditor(); Tab tab = createTab("Untitled", editor, null);
        tabs.getTabs().add(tab); tabs.getSelectionModel().select(tab); dirty.put(tab, false); status.setText("New document");
    }

    private CodeArea createEditor() {
        CodeArea editor = new CodeArea();
        applyEditorPreferences(editor);
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
        if (e.getCode() == KeyCode.ENTER && editorPrefs.autoIndent()) {
            String line = editor.getParagraph(editor.getCurrentParagraph()).getText();
            String indent = line.replaceAll("\\S.*$", "");
            String trimmed = line.stripTrailing();
            if (trimmed.endsWith("{") || trimmed.endsWith("[") || trimmed.endsWith("(")) indent += editorPrefs.indentUnit();
            editor.insertText(editor.getCaretPosition(), "\n" + indent); e.consume();
        } else if (e.getCode() == KeyCode.TAB && editorPrefs.insertSpaces() && !e.isShiftDown() && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown() && !hasExtraCarets(editor)) {
            int tab = editorPrefs.tabSize();
            int column = editor.getSelection().getLength() == 0 ? editor.getCaretColumn() : 0; // pad to the next tab stop
            editor.replaceSelection(" ".repeat(tab - column % tab)); e.consume();
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
        subscriptions.put(tab, editor.plainTextChanges().subscribe(c -> {
            dirty.put(tab, true); updateTabTitle(tab);
            File current = (File) tab.getUserData();
            if (current != null) language.change(current, c.getPosition(), c.getRemoved().length(), c.getInserted());
            highlight(editor);
        }));
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
        File previous = file;
        if (file == null || forceChoose) {
            FileChooser chooser = new FileChooser(); chooser.setTitle("Save File");
            file = chooser.showSaveDialog(tabs.getScene().getWindow()); if (file == null) return; tab.setUserData(file);
        }
        CodeArea editor = currentEditor().orElse(null); if (editor == null) return;
        try {
            Files.writeString(file.toPath(), editor.getText()); dirty.put(tab, false); updateTabTitle(tab);
            status.setText("Saved: " + file.getAbsolutePath());
            if (previous != null && !previous.equals(file)) language.close(previous);
            language.saved(file, editor.getText());
            if (projectDirectory != null && file.toPath().startsWith(projectDirectory.toPath())) refreshProjectTree();
            if (isSettingsPath(file)) reloadSettings();
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
        for (File f : files) if (f.isDirectory() && !treeExcludes.contains(f.getName())) { File hit = findFile(f, q); if (hit != null) return hit; }
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
        StyleSpans<Collection<String>> spans = b.create();
        List<Lsp.Diagnostic> reported = diagnostics.get(fileOf(a));
        if (reported != null && !reported.isEmpty() && text.length() > 0) {
            spans = spans.overlay(diagnosticSpans(a, reported, text.length()), (base, extra) -> { List<String> merged = new ArrayList<>(base); merged.addAll(extra); return merged; });
        }
        a.setStyleSpans(0, spans);
    }

    /** Underline styles for the given diagnostics, one span per non-overlapping range. */
    private StyleSpans<Collection<String>> diagnosticSpans(CodeArea a, List<Lsp.Diagnostic> reported, int length) {
        List<int[]> ranges = new ArrayList<>(); // {start, end, severity}
        for (Lsp.Diagnostic d : reported) {
            int start = offsetOf(a, d.range().start()), end = offsetOf(a, d.range().end());
            if (end <= start) { if (start < length) end = start + 1; else if (start > 0) { end = start; start--; } else continue; }
            ranges.add(new int[] {start, end, d.severity()});
        }
        ranges.sort(Comparator.comparingInt(r -> r[0]));
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int at = 0;
        for (int[] r : ranges) {
            int start = Math.max(r[0], at), end = Math.min(r[1], length);
            if (end <= start) continue;
            if (start > at) b.add(Collections.emptyList(), start - at);
            b.add(Collections.singleton(r[2] == Lsp.Diagnostic.ERROR ? "diag-error" : "diag-warning"), end - start);
            at = end;
        }
        if (at < length) b.add(Collections.emptyList(), length - at);
        return b.create();
    }

    private void showBottom(Tab tab) {
        if (!editorSplit.getItems().contains(bottomTabs)) { editorSplit.getItems().add(bottomTabs); editorSplit.setDividerPositions(0.7); }
        bottomTabs.getSelectionModel().select(tab);
    }

    private void toggleBottomPanel() {
        if (editorSplit.getItems().contains(bottomTabs)) editorSplit.getItems().remove(bottomTabs);
        else showBottom(bottomTabs.getSelectionModel().getSelectedItem());
    }

    private void build() {
        if (!requireProject("Build")) return;
        String customBuild = settings.get("build.command", "").trim();
        if (!customBuild.isEmpty()) { problems.clear(); output.append("Using build.command from settings"); runCommand("Build", customBuild); return; }
        Optional<BuildSystem> system = BuildSystem.detect(projectDirectory);
        if (system.isEmpty()) { showError("Build", "No supported build file found (pom.xml, build.gradle, Makefile, Cargo.toml, package.json). Use Run > Run Command... instead."); return; }
        problems.clear();
        runCommand(system.get().name() + " build", system.get().buildCommand());
    }

    private void runCommandDialog() {
        if (!requireProject("Run Command")) return;
        String key = "run.command." + Integer.toHexString(projectDirectory.getAbsolutePath().hashCode());
        String configured = settings.get("run.command", "").trim(); // a run.command in settings is the project's own default
        String suggestion = !configured.isEmpty() ? configured : preferences.get(key, BuildSystem.detect(projectDirectory).map(BuildSystem::suggestedRunCommand).orElse(""));
        TextInputDialog d = new TextInputDialog(suggestion);
        d.setTitle("Run Command"); d.setHeaderText("Command to run in " + projectDirectory.getAbsolutePath()); d.setContentText("Command:");
        d.showAndWait().filter(s -> !s.isBlank()).ifPresent(command -> { preferences.put(key, command.trim()); runCommand("Run", command.trim()); });
    }

    private boolean requireProject(String title) {
        if (projectDirectory != null) return true;
        showError(title, "Open a project directory first.");
        return false;
    }

    private void runCommand(String name, String commandLine) {
        long unsaved = tabs.getTabs().stream().filter(t -> Boolean.TRUE.equals(dirty.get(t))).count();
        showBottom(outputTab);
        output.append("$ " + commandLine + "    (in " + projectDirectory.getAbsolutePath() + ")");
        if (unsaved > 0) output.append("Note: " + unsaved + " unsaved file(s) are not included; the command uses files on disk.");
        ProblemParser parser = new ProblemParser(); // stateful (multi-line diagnostics); only touched on the FX thread
        try {
            processes.start(name, ShellCommand.wrap(commandLine), projectDirectory, new ProcessManager.Listener() {
                @Override public void onLine(ManagedProcess p, String line) {
                    Platform.runLater(() -> { output.append(line); parser.accept(line).ifPresent(problems::add); });
                }
                @Override public void onExit(ManagedProcess p, int code) {
                    Platform.runLater(() -> { output.append("[" + name + " exited with code " + code + "]"); status.setText(name + " exited with code " + code); });
                }
            });
            status.setText("Running: " + commandLine);
        } catch (IOException ex) { showError("Could not start command", ex.getMessage()); }
    }

    private void openProblem(Problem problem) {
        File file = new File(problem.file());
        if (!file.isAbsolute() && projectDirectory != null) file = new File(projectDirectory, problem.file());
        if (file.isFile()) openFile(file, problem.line(), problem.column()); else status.setText("File not found: " + problem.file());
    }

    private void stopAllProcesses() {
        int count = processes.running().size();
        processes.stopAll();
        status.setText(count == 0 ? "No running processes" : "Stopping " + count + " process(es)");
    }

    private void showProcesses() {
        List<ManagedProcess> running = processes.running();
        if (running.isEmpty()) { status.setText("No running processes"); return; }
        ChoiceDialog<ManagedProcess> d = new ChoiceDialog<>(running.get(running.size() - 1), running);
        d.setTitle("Processes"); d.setHeaderText("Select a process to stop"); d.setContentText("Process:");
        d.showAndWait().ifPresent(processes::stop);
    }

    // ---- language intelligence (v0.5) ----
    // Servers are started only from Language > Start Language Server..., with a command the user confirms.
    // Results are shown near the caret or in the status bar, never as unsolicited dialogs.

    private void onLanguageLog(String line) { Platform.runLater(() -> output.append(line)); }

    private void updateLanguageStatus() {
        List<String> running = language.runningLanguages();
        languageStatus.setText(running.isEmpty() ? "" : "Language servers: " + String.join(", ", running));
    }

    private void onDiagnostics(URI uri, List<Lsp.Diagnostic> reported) {
        Platform.runLater(() -> {
            File file = toFile(uri);
            if (file == null) return;
            if (reported.isEmpty()) diagnostics.remove(file); else diagnostics.put(file, reported);
            List<Problem> list = new ArrayList<>();
            for (Lsp.Diagnostic d : reported) {
                Problem.Severity severity = d.severity() == Lsp.Diagnostic.ERROR ? Problem.Severity.ERROR : Problem.Severity.WARNING;
                String text = d.source() == null ? d.message() : d.message() + "  [" + d.source() + "]";
                list.add(new Problem(severity, displayPath(file), d.range().start().line() + 1, d.range().start().character() + 1, text.replace('\n', ' ')));
            }
            problems.replace("language:" + file.getPath(), list);
            CodeArea editor = editorFor(file);
            if (editor != null) highlight(editor);
        });
    }

    private String displayPath(File file) {
        if (projectDirectory != null && file.toPath().startsWith(projectDirectory.toPath())) return projectDirectory.toPath().relativize(file.toPath()).toString();
        return file.getPath();
    }

    private void forgetTab(Tab tab) {
        Subscription subscription = subscriptions.remove(tab);
        if (subscription != null) subscription.unsubscribe();
        dirty.remove(tab);
        File file = (File) tab.getUserData();
        if (file != null) language.close(file);
    }

    private static File toFile(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
        try { return new File(uri); } catch (IllegalArgumentException ex) { return null; }
    }

    private File fileOf(CodeArea editor) {
        for (Tab tab : tabs.getTabs()) if (tab.getContent() instanceof VirtualizedScrollPane<?> pane && pane.getContent() == editor) return (File) tab.getUserData();
        return null;
    }

    private CodeArea editorFor(File file) {
        for (Tab tab : tabs.getTabs())
            if (file.equals(tab.getUserData()) && tab.getContent() instanceof VirtualizedScrollPane<?> pane && pane.getContent() instanceof CodeArea a) return a;
        return null;
    }

    private int offsetOf(CodeArea a, Lsp.Position position) {
        int line = Math.max(0, Math.min(position.line(), a.getParagraphs().size() - 1));
        int column = Math.max(0, Math.min(position.character(), a.getParagraphLength(line)));
        return a.getAbsolutePosition(line, column);
    }

    /** The caret, nudged left when it sits just after an identifier so that the identifier is the symbol under it. */
    private int symbolOffset(CodeArea a) {
        int caret = a.getCaretPosition();
        String text = a.getText();
        boolean onWord = caret < text.length() && Character.isJavaIdentifierPart(text.charAt(caret));
        if (!onWord && caret > 0 && Character.isJavaIdentifierPart(text.charAt(caret - 1))) return caret - 1;
        return caret;
    }

    private String wordAt(CodeArea a, int offset) {
        String text = a.getText();
        if (offset < 0 || offset >= text.length() || !Character.isJavaIdentifierPart(text.charAt(offset))) return "";
        int start = offset, end = offset;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        return text.substring(start, end);
    }

    private void popupStyler(Parent root) {
        syncStylesheets(root);
        setThemeClass(root);
    }

    /** Runs a language action against the current editor, which must hold a saved file with a recognized extension. */
    private void withLanguageEditor(java.util.function.BiConsumer<CodeArea, File> action) {
        CodeArea editor = currentEditor().orElse(null);
        if (editor == null) { status.setText("Open a file first"); return; }
        File file = fileOf(editor);
        if (file == null) { status.setText("Save the file first; language features need a file on disk"); return; }
        if (Languages.idFor(file) == null) { status.setText("No language support for this file type"); return; }
        action.accept(editor, file);
    }

    private void languageError(Throwable error) {
        Throwable t = error;
        while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
        status.setText(t.getMessage() == null ? "Language server error" : t.getMessage());
    }

    private void startLanguageServer() {
        withLanguageEditor((editor, file) -> {
            String id = Languages.idFor(file);
            if (language.isRunning(id)) { status.setText("The " + id + " language server is already running"); return; }
            File root = projectDirectory != null ? projectDirectory : file.getAbsoluteFile().getParentFile();
            String key = "language.server." + id;
            TextInputDialog d = new TextInputDialog(settings.get(key, preferences.get(key, Languages.suggestedCommand(id))));
            d.setTitle("Start Language Server");
            d.setHeaderText("Command for the " + id + " language server.\nIt runs in " + root.getAbsolutePath() + " and is started only if you confirm.");
            d.setContentText("Command:");
            d.showAndWait().filter(s -> !s.isBlank()).ifPresent(command -> {
                preferences.put(key, command.trim());
                try {
                    status.setText("Starting " + id + " language server...");
                    language.start(id, command.trim(), root).whenComplete((v, err) -> Platform.runLater(() -> {
                        if (err == null) status.setText("Language server ready: " + id);
                        else { languageError(err); status.setText("Language server failed to start: " + status.getText()); showBottom(outputTab); }
                    }));
                } catch (IOException ex) { showError("Could not start language server", ex.getMessage()); }
            });
        });
    }

    private void stopLanguageServer() {
        List<String> running = language.runningLanguages();
        if (running.isEmpty()) { status.setText("No language servers running"); return; }
        String current = currentEditor().map(this::fileOf).map(Languages::idFor).orElse(null);
        String target = running.contains(current) ? current : running.size() == 1 ? running.get(0) : null;
        if (target == null) {
            ChoiceDialog<String> d = new ChoiceDialog<>(running.get(0), running);
            d.setTitle("Stop Language Server"); d.setHeaderText("Select a language server to stop"); d.setContentText("Language:");
            target = d.showAndWait().orElse(null);
        }
        if (target != null) { language.stop(target); status.setText("Stopping " + target + " language server"); }
    }

    private void complete() {
        withLanguageEditor((editor, file) -> {
            int requestCaret = editor.getCaretPosition();
            language.completion(file, requestCaret).whenComplete((items, err) -> Platform.runLater(() -> {
                if (err != null) { languageError(err); return; }
                if (items.isEmpty()) { status.setText("No completions"); return; }
                if (editor.getCaretPosition() != requestCaret) return; // the user moved on
                showCompletions(editor, items, requestCaret);
            }));
        });
    }

    private record Candidate(Lsp.CompletionItem item, int start, int end) {}

    private void showCompletions(CodeArea editor, List<Lsp.CompletionItem> items, int requestCaret) {
        String text = editor.getText();
        int wordStart = requestCaret;
        while (wordStart > 0 && Character.isJavaIdentifierPart(text.charAt(wordStart - 1))) wordStart--;
        List<CompletionPopup.Entry> entries = new ArrayList<>();
        int popupStart = wordStart;
        for (Lsp.CompletionItem item : items.stream().limit(500).toList()) {
            int start = item.edit() != null ? offsetOf(editor, item.edit().range().start()) : wordStart;
            int end = item.edit() != null ? offsetOf(editor, item.edit().range().end()) : requestCaret;
            if (item.edit() != null) popupStart = start;
            entries.add(new CompletionPopup.Entry(item.label(), item.detail(), item.filter(), new Candidate(item, start, end)));
        }
        completionPopup.show(editor, entries, popupStart, entry -> {
            Candidate c = (Candidate) entry.payload();
            int shift = editor.getCaretPosition() - requestCaret; // characters typed (or deleted) while the list was open
            int end = Math.max(c.start(), Math.min(c.end() + shift, editor.getLength()));
            editor.replaceText(Math.min(c.start(), editor.getLength()), end, c.item().text());
        }, this::popupStyler);
    }

    private void hover() {
        withLanguageEditor((editor, file) -> language.hover(file, symbolOffset(editor)).whenComplete((text, err) -> Platform.runLater(() -> {
            if (err != null) { languageError(err); return; }
            if (text.isBlank()) { status.setText("No hover information"); return; }
            hoverPopup.show(editor, text, this::popupStyler);
        })));
    }

    private void signatureHelp() {
        withLanguageEditor((editor, file) -> {
            int requestCaret = editor.getCaretPosition();
            language.signatureHelp(file, requestCaret).whenComplete((text, err) -> Platform.runLater(() -> {
                if (err != null) { languageError(err); return; }
                if (text.isBlank()) { status.setText("No signature help here"); return; }
                hoverPopup.show(editor, text, this::popupStyler);
            }));
        });
    }

    /** Lists the file's outline in the References panel; double-click jumps to a symbol. Read-only. */
    private void documentSymbols() {
        withLanguageEditor((editor, file) -> language.documentSymbols(file).whenComplete((symbols, err) -> {
            Platform.runLater(() -> {
                if (err != null) { languageError(err); return; }
                List<ReferencesPanel.Reference> entries = new ArrayList<>();
                for (Lsp.Symbol symbol : symbols) {
                    int line = Math.max(0, Math.min(symbol.range().start().line(), editor.getParagraphs().size() - 1));
                    String label = "  ".repeat(symbol.depth()) + symbol.kindName() + " " + symbol.name() + (symbol.detail() == null || symbol.detail().isBlank() ? "" : "  " + symbol.detail());
                    entries.add(new ReferencesPanel.Reference(file, line + 1, symbol.range().start().character() + 1, label));
                }
                references.show("Symbols in " + file.getName() + " (" + entries.size() + ")", entries);
                showBottom(referencesTab);
                status.setText(entries.isEmpty() ? "No symbols found" : entries.size() + " symbol(s)");
            });
        }));
    }

    /**
     * Asks the server for formatting edits and applies them to the editor. Nothing is written to disk, and the
     * result is one undoable step per edit. If the text changed while the server was working, the result is discarded.
     */
    private void formatDocument() {
        withLanguageEditor((editor, file) -> {
            String snapshot = editor.getText();
            language.formatting(file, editorPrefs.tabSize(), editorPrefs.insertSpaces()).whenComplete((edits, err) -> Platform.runLater(() -> {
                if (err != null) { languageError(err); return; }
                if (edits.isEmpty()) { status.setText("Nothing to format"); return; }
                if (!snapshot.equals(editor.getText())) { status.setText("The file changed while formatting; nothing was applied"); return; }
                List<int[]> spans = new ArrayList<>(); // {start, end, edit index}
                for (int i = 0; i < edits.size(); i++) spans.add(new int[] {offsetOf(editor, edits.get(i).range().start()), offsetOf(editor, edits.get(i).range().end()), i});
                spans.sort((a, b) -> Integer.compare(b[0], a[0])); // bottom to top so earlier offsets stay valid
                for (int[] span : spans) replaceMinimal(editor, span[0], span[1], edits.get(span[2]).newText());
                status.setText("Formatted " + file.getName() + " (" + edits.size() + " edit(s), unsaved)");
            }));
        });
    }

    private void goToDefinition() {
        withLanguageEditor((editor, file) -> language.definition(file, symbolOffset(editor)).whenComplete((locations, err) -> {
            List<ReferencesPanel.Reference> found = err == null ? toReferences(locations) : List.of();
            Platform.runLater(() -> {
                if (err != null) { languageError(err); return; }
                if (found.isEmpty()) { status.setText(locations.isEmpty() ? "No definition found" : "The definition is not in a local file"); return; }
                if (found.size() == 1) { openFile(found.get(0).file(), found.get(0).line(), found.get(0).column()); return; }
                references.show("Definitions (" + found.size() + ")", found); showBottom(referencesTab);
            });
        }));
    }

    private void findReferences() {
        withLanguageEditor((editor, file) -> {
            String word = wordAt(editor, symbolOffset(editor));
            language.references(file, symbolOffset(editor)).whenComplete((locations, err) -> {
                List<ReferencesPanel.Reference> found = err == null ? toReferences(locations) : List.of();
                Platform.runLater(() -> {
                    if (err != null) { languageError(err); return; }
                    references.show("References" + (word.isEmpty() ? "" : " to " + word) + " (" + found.size() + ")", found);
                    showBottom(referencesTab);
                    status.setText(found.isEmpty() ? "No references found" : found.size() + " reference(s)");
                });
            });
        });
    }

    /** Converts server locations to panel entries, reading each file once for the line previews. Runs off the FX thread. */
    private List<ReferencesPanel.Reference> toReferences(List<Lsp.Location> locations) {
        Map<File, List<String>> lines = new HashMap<>();
        List<ReferencesPanel.Reference> out = new ArrayList<>();
        for (Lsp.Location location : locations) {
            File file = toFile(location.uri());
            if (file == null) continue;
            List<String> content = lines.computeIfAbsent(file, f -> {
                try { return Files.readAllLines(f.toPath()); } catch (IOException | RuntimeException ex) { return List.of(); }
            });
            int line = location.range().start().line();
            out.add(new ReferencesPanel.Reference(file, line + 1, location.range().start().character() + 1, line < content.size() ? content.get(line) : ""));
        }
        return out;
    }

    private void renameSymbol() {
        withLanguageEditor((editor, file) -> {
            int offset = symbolOffset(editor);
            String word = wordAt(editor, offset);
            if (word.isEmpty()) { status.setText("Put the caret on the symbol to rename"); return; }
            TextInputDialog d = new TextInputDialog(word);
            d.setTitle("Rename Symbol"); d.setHeaderText("Rename " + word + "\nYou will see what changes before anything is applied."); d.setContentText("New name:");
            d.showAndWait().map(String::trim).filter(n -> !n.isEmpty() && !n.equals(word)).ifPresent(newName ->
                language.rename(file, offset, newName).whenComplete((edit, err) -> Platform.runLater(() -> {
                    if (err != null) { languageError(err); return; }
                    applyRename(word, newName, edit);
                })));
        });
    }

    /** Shows the server's proposed edits and, on approval, applies them to editor tabs. Nothing is written to disk. */
    private void applyRename(String oldName, String newName, Lsp.WorkspaceEdit edit) {
        if (edit.unsupported()) { showError("Rename", "The language server also asked to create, rename or delete files, which Code Mate never does automatically. Nothing was changed."); return; }
        Map<File, List<Lsp.TextEdit>> byFile = new LinkedHashMap<>();
        int skipped = 0;
        for (Map.Entry<URI, List<Lsp.TextEdit>> e : edit.changes().entrySet()) {
            File file = toFile(e.getKey());
            if (file == null) skipped += e.getValue().size(); else byFile.put(file, e.getValue());
        }
        int total = byFile.values().stream().mapToInt(List::size).sum();
        if (total == 0) { status.setText("The server proposed no changes"); return; }
        StringBuilder summary = new StringBuilder("Rename " + oldName + " to " + newName + ": " + total + " edit(s) in " + byFile.size() + " file(s).\n\n");
        int shown = 0;
        for (Map.Entry<File, List<Lsp.TextEdit>> e : byFile.entrySet()) {
            if (shown++ == 15) { summary.append("  ...\n"); break; }
            summary.append("  ").append(displayPath(e.getKey())).append("  (").append(e.getValue().size()).append(")\n");
        }
        if (skipped > 0) summary.append("\n").append(skipped).append(" edit(s) in non-file locations will be skipped.\n");
        summary.append("\nFiles that are not open will be opened in tabs. The changes stay unsaved, so you can review and undo them.");
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        ButtonType apply = new ButtonType("Apply"), cancel = ButtonType.CANCEL;
        confirm.setTitle("Rename Symbol"); confirm.setHeaderText("Apply rename?"); confirm.setContentText(summary.toString());
        confirm.getButtonTypes().setAll(apply, cancel);
        if (confirm.showAndWait().orElse(cancel) != apply) { status.setText("Rename cancelled"); return; }
        for (Map.Entry<File, List<Lsp.TextEdit>> e : byFile.entrySet()) {
            openFile(e.getKey());
            CodeArea editor = editorFor(e.getKey());
            if (editor == null) continue;
            List<int[]> spans = new ArrayList<>(); // {start, end, edit index}
            List<Lsp.TextEdit> edits = e.getValue();
            for (int i = 0; i < edits.size(); i++) spans.add(new int[] {offsetOf(editor, edits.get(i).range().start()), offsetOf(editor, edits.get(i).range().end()), i});
            spans.sort((a, b) -> Integer.compare(b[0], a[0])); // bottom to top so earlier offsets stay valid
            for (int[] span : spans) replaceMinimal(editor, span[0], span[1], edits.get(span[2]).newText());
        }
        status.setText("Renamed " + oldName + " to " + newName + " (" + total + " edit(s), unsaved)");
    }

    /** Replaces a range but leaves untouched the leading and trailing text that is identical, so whole-file edits stay small. */
    private void replaceMinimal(CodeArea editor, int start, int end, String replacement) {
        String old = editor.getText(start, end);
        int prefix = 0, max = Math.min(old.length(), replacement.length());
        while (prefix < max && old.charAt(prefix) == replacement.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < max - prefix && old.charAt(old.length() - 1 - suffix) == replacement.charAt(replacement.length() - 1 - suffix)) suffix++;
        if (prefix == old.length() && prefix == replacement.length()) return;
        editor.replaceText(start + prefix, end - suffix, replacement.substring(prefix, replacement.length() - suffix));
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

    @Override public void stop() { language.stopAll(); processes.stopAll(); subscriptions.values().forEach(Subscription::unsubscribe); }

    public static void main(String[] args) { launch(args); }
}
