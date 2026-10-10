# Bugs

Known issues for the initial foundation.

## Current

- [ ] The editor currently has no full project/file-tree model.
- [ ] Syntax highlighting is not implemented.
- [ ] The integrated terminal is line-oriented (no PTY): full-screen programs and job control do not work.
- [ ] Windows terminals: Ctrl+C terminates the command rather than interrupting it, and virtualenv detection is untested.
- [ ] Build output is parsed for javac, Maven, gcc/clang, tsc/MSVC, Kotlin, Go, rustc and Python tracebacks; other tools show their output but no problems.
- [ ] Background jobs are only recognized when the line ends in a lone `&` (not `a & b`) and not on Windows.
- [ ] Language servers are started manually per language (Language > Start Language Server...); there is no project-level server configuration yet.
- [ ] LSP: no `workspace/applyEdit`, code actions, range formatting or workspace symbols; servers that rename files as part of a rename are refused.
- [ ] LSP: completion snippets are flattened to plain text, and results pointing outside `file:` URIs (e.g. jar contents) are not opened.
- [ ] LSP: diagnostic underlines can sit a few characters off while typing, until the server publishes fresh diagnostics.
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
