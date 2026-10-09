# Bugs

Known issues for the initial foundation.

## Current

- [ ] The editor currently has no full project/file-tree model.
- [ ] Syntax highlighting is not implemented.
- [ ] The integrated terminal is line-oriented (no PTY): full-screen programs and job control do not work.
- [ ] Windows terminals: Ctrl+C terminates the command rather than interrupting it, and virtualenv detection is untested.
- [ ] Build output is parsed for javac, Maven, gcc/clang, tsc/MSVC, Kotlin, Go, rustc and Python tracebacks; other tools show their output but no problems.
- [ ] Background jobs are only recognized when the line ends in a lone `&` (not `a & b`) and not on Windows.
- [ ] There is no LSP integration.
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
