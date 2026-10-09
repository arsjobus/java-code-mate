# Code Mate

A local-first Java code editor built around the principles of intentional computing.

The editor is designed to be a tool for making software, not a platform for consuming information. It should remain useful offline, avoid unnecessary accounts and cloud dependencies, and keep automation and AI explicitly under the programmer's control.

## Current status

Roadmap progress is tracked in `ROADMAP.md`; v0.4 (build and run) is the latest milestone.

Included:
- Maven build
- JavaFX application
- Basic editor window
- File open/save support
- Project documentation and design principles
- Build and run: output panel, problems panel, line-oriented terminal, process management

## Build and run (v0.4)

Open a project directory, then use the **Run** menu:

| Action | Shortcut | What it does |
| --- | --- | --- |
| Build | Ctrl+B | Runs the detected build command (Maven, Gradle, Make, Cargo, npm) in the project directory. |
| Run Command... | F5 | Shows an editable command (remembered per project), then runs it. |
| Stop All Processes | Ctrl+. | Stops every process the editor started. |
| Processes... | | Pick one running process to stop. |

Output appears in the **Output** panel. Compiler diagnostics (javac, Maven, gcc-style) are collected in
**Problems**; double-click one to jump to the line. **Terminal** (Ctrl+`) is a persistent shell fed one line
at a time. Toggle the bottom panel with Ctrl+J.

Terminals:
- **Tabs:** `+ New Terminal` or Ctrl+Shift+` opens another terminal; each tab has its own shell and environment.
- **Long-running commands:** while a command such as `uvicorn api.main:app --reload` runs, the tab stays bound to it. The
  tab title shows a dot, the header shows the command, and typed lines go to the program's stdin.
- **Standard keys:** Ctrl+C interrupts the running command (SIGINT; the shell stays alive; copies instead when text is
  selected), Ctrl+D exits an idle shell, Ctrl+L clears the transcript, Up/Down walk history. Closing a busy tab asks first.
- **Python virtualenvs:** after `. .venv/bin/activate` the prompt shows `(.venv) $` and the tab title shows the venv;
  `deactivate` removes both. To do this the terminal sends one read-only `printf` of `$VIRTUAL_ENV` to the shell after a
  command finishes (never while a command is running) and hides its output.
- **Limits:** there is no PTY, so full-screen programs (vim, top) and job control are unsupported. Python output is
  unbuffered (`PYTHONUNBUFFERED=1`) so servers show logs immediately. On Windows, Ctrl+C terminates the command instead of
  sending SIGINT, and venv detection is untested.

Nothing runs unless you ask for it, commands are echoed before they run, and all child processes are stopped when the editor exits.

## Requirements

- Java 21+
- Maven 3.9+

## Run

```bash
mvn javafx:run
```

## Build

```bash
mvn clean package
```

## Project structure

```text
intentional-code-editor/
├── pom.xml
├── README.md
├── PRINCIPLES.md
├── QUICK_RULES.md
├── ROADMAP.md
├── ARCHITECTURE.md
├── DESIGN.md
├── PRIVACY.md
├── AI.md
├── EXTENSIONS.md
├── SECURITY.md
├── BUGS.md
└── src/
    ├── main/java/com/code_mate/App.java
    ├── main/java/com/code_mate/build/   (process, build, and diagnostics services)
    ├── main/java/com/code_mate/ui/      (output, problems, and terminal panels)
    └── main/resources/styles/editor.css
```

## Philosophy

The editor should stay quiet, local, understandable, durable, and human-directed. See `PRINCIPLES.md` and `QUICK_RULES.md`.
