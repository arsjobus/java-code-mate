# Extensions

Extensions are intentionally constrained.

## Goals

Extensions may add useful capabilities without turning the editor into an uncontrolled marketplace or execution environment.

Possible extension categories:
- Language support
- Themes
- Formatters
- Debuggers
- Build tools
- Tool integrations

## Capability model

Future extensions should declare capabilities such as:
- Read project files
- Write project files
- Execute processes
- Access network

Capabilities should be visible to the user and, where practical, independently controllable.

## No marketplace dependency

The core editor must remain fully usable without an extension marketplace.

## No extension requirement

A project must never require a proprietary editor extension merely to remain buildable or readable.
