# Bugs

Known issues for the initial foundation.

## Current

- [ ] The project tree is a snapshot of the folder: files can be created and deleted from it (right-click), but it cannot rename or move entries, and it does not notice changes made outside the editor until it is refreshed (e.g. by saving settings).
- [ ] Syntax highlighting is a lexical scanner (no parsing) for Java, Kotlin, JavaScript/TypeScript, C/C++, Go, Rust, PHP, JSON, Python, HTML and XML, chosen by file extension; other files are shown as plain text. Keywords and types come from a short built-in list per language (no contextual keywords such as `get`/`set` or Python's soft keywords `match`/`case`, and no Rust `#[...]` attributes or lifetimes). Python f-string `{...}` expressions are coloured as part of the string. In HTML, `<script>` content is highlighted as JavaScript regardless of its `type`, `<style>` content (CSS) and HTML/XML entities are left uncoloured, and unquoted attribute values are plain. It is switched off above 1 million characters.
- [ ] The integrated terminal is line-oriented (no PTY): full-screen programs and job control do not work.
- [ ] Windows terminals: Ctrl+C terminates the command rather than interrupting it, and virtualenv detection is untested.
- [ ] Build output is parsed for javac, Maven, gcc/clang, tsc/MSVC, Kotlin, Go, rustc and Python tracebacks; other tools show their output but no problems.
- [ ] Background jobs are recognized for a lone `&` anywhere on the line (not in quotes, comments or `&&`/`&>`/`>&`/`|&`), but not on Windows. For `a & b` one process per `&` is treated as the background job, so a backgrounded pipeline (`a | b & c`) is only partly tracked.
- [ ] Language servers are started manually per language (Language > Start Language Server...). A `language.server.<id>` setting (user or project) pre-fills the command, but servers are never started automatically.
- [ ] LSP: no `workspace/applyEdit`, code actions, range formatting or workspace symbols; servers that rename files as part of a rename are refused.
- [ ] LSP: completion snippets are flattened to plain text (placeholders keep their default text), and results pointing outside `file:` URIs (e.g. jar contents) are not opened.
- [ ] LSP: diagnostic underlines can sit a few characters off while typing, until the server publishes fresh diagnostics.
- [ ] Customization: only menu actions are rebindable; keys handled by the text area itself (cursor movement, Ctrl+Z, copy/paste) are not.
- [ ] Customization: with `editor.insertSpaces=false` the on-screen width of a tab character depends on JavaFX honoring `-fx-tab-size` in the code area; if it does not, tabs show at the default width.
- [ ] Customization: the Settings dialog edits files on disk. If the same settings file is open in a tab with unsaved changes, saving the dialog does not update that tab.
- [ ] Files larger than 32 MB are refused rather than opened, and files that are not UTF-8 text cannot be opened.
- [ ] Crash recovery restores the text, cursor position and relative order of unsaved tabs, but not their undo history, scroll position or selection; saved (unmodified) tabs are not part of recovery at all.
- [ ] The JavaFX user interface (`App`, `ui/*`) has no automated tests; it is covered by manual and smoke checks (see HARDENING.md).
- [ ] There is no Git integration.
- [ ] AI integration is documentation-only at this stage.
- [ ] Cross-platform packaging has not yet been established.

## Reporting

When documenting a bug, include:
- Operating system
- Java version
- Maven version
- Project/reproduction steps
- Expected behavior
- Actual behavior
- Relevant logs
