# Architecture

## Goals

The architecture should be modular without becoming framework-heavy. UI is a client of the editor model rather than the editor model itself.

```text
Application
    |
    +-- UI
    |
    +-- Core
    |    +-- Document model
    |    +-- Commands
    |    +-- Project model
    |    +-- Settings
    |
    +-- Services
         +-- Filesystem
         +-- Search
         +-- Build/Run
         +-- Terminal
         +-- Git
         +-- Language/LSP
         +-- AI
```

## Package layout

```text
com.intentional.editor
├── App.java
├── core
├── document
├── editor
├── filesystem
├── project
├── search
├── terminal
├── language
├── git
├── ai
├── settings
└── ui
```

The initial release deliberately keeps these packages small. Empty abstractions should not be created merely to make the diagram look complete.

## Technology choices

- Java for the application language.
- JavaFX for the desktop UI.
- Maven for dependency/build management.
- Standard filesystem APIs for ordinary files.
- LSP for language intelligence.
- Tree-sitter may be introduced when syntax parsing needs justify the dependency.
- Git remains Git rather than being replaced by a proprietary project database.

## Dependency rule

Every dependency should answer:
1. What problem does it solve?
2. Can the JDK/JavaFX solve the problem adequately?
3. Does it add meaningful complexity?
4. Does it introduce network, telemetry, account, or lock-in concerns?

Prefer fewer dependencies.
