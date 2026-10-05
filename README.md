# Code Mate

A local-first Java code editor built around the principles of intentional computing.

The editor is designed to be a tool for making software, not a platform for consuming information. It should remain useful offline, avoid unnecessary accounts and cloud dependencies, and keep automation and AI explicitly under the programmer's control.

## Current status

This is the initial project foundation (pre-v0.1).

Included:
- Maven build
- JavaFX application
- Basic editor window
- File open/save support
- Project documentation and design principles

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
    └── main/resources/styles/editor.css
```

## Philosophy

The editor should stay quiet, local, understandable, durable, and human-directed. See `PRINCIPLES.md` and `QUICK_RULES.md`.
