# Bugs

Known issues for the initial foundation.

## Current

- [ ] The editor currently has no full project/file-tree model.
- [ ] Syntax highlighting is not implemented.
- [ ] The integrated terminal is line-oriented (no PTY): full-screen programs and job control do not work.
- [ ] Windows terminals: Ctrl+C terminates the command rather than interrupting it, and virtualenv detection is untested.
- [ ] A background job started with `&` keeps its terminal marked as running until it exits.
- [ ] Build output is only parsed for javac/Maven/gcc-style diagnostics; other tools show their output but no problems.
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
