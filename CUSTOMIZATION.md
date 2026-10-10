# Customization

Code Mate is configured with plain text files. There is no settings database, no sync and no network access:
everything below is a file you can read, edit, copy, back up or delete.

## Where settings live

| Layer | File | Notes |
| --- | --- | --- |
| User | Linux: `~/.config/code-mate/settings.conf` (or `$XDG_CONFIG_HOME/code-mate/`). macOS: `~/Library/Application Support/code-mate/settings.conf`. Windows: `%APPDATA%\code-mate\settings.conf` | Set `CODE_MATE_CONFIG_DIR` to use another folder. |
| Project | `<project>/.code-mate/settings.conf` | Overrides the user layer for that project. Commit it to share it, or ignore the folder. |

Open either file from the **Settings** menu (it is created from a fully commented template on first use). Saving a
settings file inside Code Mate applies it immediately; **Settings > Reload Settings** does the same on demand.
**Settings > Settings...** and **Project Settings...** are dialogs over the same files.

## File format

UTF-8, one `key=value` per line. Lines starting with `#` are comments. Values are taken exactly as written to the end
of the line, with no escape sequences, so `run.command=C:\tools\run.bat --fast` means what it says. Saving from a
dialog changes only the lines that changed; your comments, ordering and other entries are kept.

Problems (a line without `=`, a number out of range, an unknown action id) are listed in the Output panel and never stop
the editor from starting; the affected value falls back to its default.

## Settings

| Key | Default | Where | Meaning |
| --- | --- | --- | --- |
| `theme` | `light` | user only | `light`, `dark`, `high-contrast`, or a custom theme name (below). |
| `editor.fontFamily` | `Monospaced` | user, project | Editor font. |
| `editor.fontSize` | `14` | user, project | 8 to 48. |
| `editor.tabSize` | `4` | user, project | 1 to 16. Used for Tab, auto-indent and Format Document. |
| `editor.insertSpaces` | `true` | user, project | Tab inserts spaces up to the next tab stop; `false` inserts a tab character. |
| `editor.wordWrap` | `false` | user, project | Wrap long lines. |
| `editor.lineNumbers` | `true` | user, project | Show the line-number gutter. |
| `editor.autoIndent` | `true` | user, project | Keep indentation on Enter, and indent after an opening bracket. |
| `run.command` | detected | user, project | Pre-filled into Run > Run Command...; you still confirm it. |
| `build.command` | detected | user, project | Replaces the detected command for Run > Build. The command is printed in Output when it runs. |
| `tree.exclude` | `.git,target` | user, project | Comma-separated names hidden from the project tree and Go to File. Write `tree.exclude=` to hide nothing. |
| `language.server.<id>` | suggestion | user, project | Pre-filled into Language > Start Language Server...; servers are still started only after you confirm. |
| `key.<action id>` | see below | user only | Keybinding override. |

`theme` and `key.*` are read from the user file only. A project cannot change your theme or shortcuts by being opened;
if a project file contains them they are ignored with a warning.

`build.command`, `run.command` and `language.server.*` in a project file are commands that came with the project. Treat
them like the project's own build scripts: read them before building a project you did not write.

View > Zoom In / Zoom Out / Reset Zoom change the font size for the current session only and are never saved.

## Themes

Built in: Light, Dark and High Contrast (View > Theme). To add your own, put a `.css` file in the `themes` folder next to
the user settings file; its name (without `.css`) becomes the theme name, and you select it from View > Theme or with
`theme=<name>`. Saving a theme file inside Code Mate re-applies it.

A custom theme is an ordinary JavaFX stylesheet loaded after Code Mate's own, so it can override anything. Put
`/* base: dark */` near the top to keep the dark theme's rules underneath and only change what you want:

```css
/* base: dark */
.root.theme-custom { -fx-base: #1b2630; -fx-background: #16202a; -fx-control-inner-background: #16202a; }
.theme-custom .code-area { -fx-background-color: #101922; }
.theme-custom .code-area .keyword { -fx-fill: #ffb454; }
```

Style classes you can rely on: `.code-area` with `.keyword`, `.type`, `.string`, `.comment`, `.annotation`, `.number`,
`.lineno`, `.diag-error`, `.diag-warning`; `.problem-error`, `.problem-warning`; `.popup-pane`. See `editor.css`.

## Keybindings

Every menu item is an action with an id. Override one with `key.<id>=<shortcut>` in the user file, or use
Settings > Settings... > Keybindings. Use `none` to remove a shortcut.

A shortcut is modifiers joined by `+`, then one key: `Ctrl+Shift+F`, `Alt+Z`, `F12`. Modifiers are `Ctrl`, `Shift`, `Alt`,
`Meta` and `Shortcut` (Ctrl on Windows/Linux, Cmd on macOS). Key names are JavaFX `KeyCode` names, case-insensitive:
letters, `DIGIT0`..`DIGIT9` (or just `0`..`9`), `F1`..`F12`, `COMMA`, `PERIOD`, `MINUS`, `EQUALS`, `BACK_QUOTE`, `SPACE`,
`ENTER`, `TAB`, and so on. In the dialog, any key other than a function key must be combined with Ctrl, Alt or Meta so a
shortcut cannot swallow normal typing. Two actions with the same shortcut are reported as a problem.

Example, giving Save and Save As the usual shortcuts (File actions have none by default):

```
key.file.save=Ctrl+S
key.file.saveAs=Ctrl+Shift+S
```

Only menu actions are rebindable. Keys handled inside the text area itself (cursor movement, Ctrl+Z, copy and paste,
Ctrl+A) are not.

| Action id | Menu item | Default |
| --- | --- | --- |
| `file.new` | File > New | (none) |
| `file.openFile` | File > Open File | (none) |
| `file.openDirectory` | File > Open Directory | (none) |
| `file.save` | File > Save | (none) |
| `file.saveAs` | File > Save As | (none) |
| `file.close` | File > Close | (none) |
| `file.exit` | File > Exit | (none) |
| `edit.undo` | Edit > Undo | (none) |
| `edit.redo` | Edit > Redo | (none) |
| `edit.search` | Edit > Search | `Ctrl+F` |
| `edit.replace` | Edit > Replace | `Ctrl+H` |
| `edit.goToLine` | Edit > Go to Line | `Ctrl+L` |
| `edit.goToFile` | Edit > Go to File | `Ctrl+P` |
| `edit.matchBracket` | Edit > Match Bracket | `Ctrl+M` |
| `edit.addCursor` | Edit > Add Cursor | `Ctrl+D` |
| `view.focusMode` | View > Focus Mode | (none) |
| `view.fold` | View > Fold Selected | (none) |
| `view.unfold` | View > Unfold Current | (none) |
| `view.bottomPanel` | View > Toggle Bottom Panel | `Ctrl+J` |
| `view.output` | View > Output | (none) |
| `view.problems` | View > Problems | (none) |
| `view.references` | View > References | (none) |
| `view.terminal` | View > Terminal | `Ctrl+BACK_QUOTE` |
| `view.newTerminal` | View > New Terminal | `Ctrl+Shift+BACK_QUOTE` |
| `view.zoomIn` | View > Zoom In | `Ctrl+EQUALS` |
| `view.zoomOut` | View > Zoom Out | `Ctrl+MINUS` |
| `view.zoomReset` | View > Reset Zoom | `Ctrl+DIGIT0` |
| `language.startServer` | Language > Start Language Server | (none) |
| `language.stopServer` | Language > Stop Language Server | (none) |
| `language.complete` | Language > Complete | `Ctrl+SPACE` |
| `language.hover` | Language > Show Hover Info | `Ctrl+I` |
| `language.signatureHelp` | Language > Signature Help | `Ctrl+Shift+SPACE` |
| `language.goToDefinition` | Language > Go to Definition | `F12` |
| `language.findReferences` | Language > Find References | `Shift+F12` |
| `language.documentSymbols` | Language > Document Symbols | `Ctrl+Shift+O` |
| `language.rename` | Language > Rename Symbol | `F2` |
| `language.format` | Language > Format Document | `Ctrl+Shift+F` |
| `run.build` | Run > Build | `Ctrl+B` |
| `run.runCommand` | Run > Run Command | `F5` |
| `run.stopAll` | Run > Stop All Processes | `Ctrl+PERIOD` |
| `run.processes` | Run > Processes | (none) |
| `settings.open` | Settings > Settings | `Ctrl+COMMA` |
| `settings.project` | Settings > Project Settings | (none) |
| `settings.openUserFile` | Settings > Open User Settings File | (none) |
| `settings.openProjectFile` | Settings > Open Project Settings File | (none) |
| `settings.reload` | Settings > Reload Settings | (none) |
