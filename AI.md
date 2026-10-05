# AI

AI is an optional tool, not the center of the editor.

## Principles

- User invokes AI explicitly.
- Local models are preferred where practical.
- No ambient code surveillance.
- No automatic code changes.
- Proposed changes are reviewable.
- Provider choice remains with the user.
- AI failures must not prevent ordinary editing.

## Architecture

```text
AI Action
   |
   v
AI Service Interface
   |
   +-- Local Ollama
   +-- Other local provider
   +-- External provider (optional)
```

The editor should not couple its core document model to a specific AI vendor.

## Proposed change workflow

```text
Request
  -> Context selection
  -> AI response
  -> Diff/proposal
  -> Human review
  -> Apply or reject
```

The AI may explain, analyze, draft, refactor, or propose. Applying a change remains a user action.

## Future safeguards

- Context preview
- Token/context budget
- Provider indicator
- Network indicator
- Per-action permission
- File-scope restrictions
- Undoable application
