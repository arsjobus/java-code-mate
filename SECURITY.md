# Security

Security should follow the same principle as privacy: minimize unnecessary capability.

## Threats

The editor may eventually process:
- Source code
- Build scripts
- Git repositories
- Local credentials indirectly exposed through processes
- AI requests
- Third-party extensions

## Rules

- Do not execute commands without user direction.
- Show commands before destructive operations where practical.
- Never silently upload project data.
- Keep secrets out of logs.
- Do not expose credentials to AI context by default.
- Treat extensions as untrusted capability providers.
- Validate paths before filesystem operations.
- Keep dependencies current and auditable.

## Destructive operations

Deletion, overwriting, reset, checkout, and similar operations should be explicit and recoverable where practical.
