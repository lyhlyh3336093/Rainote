# AGENTS.md

Entry point for AI agents working in this repository.

## Knowledge store

- **`docs/solutions/`** — documented solutions and learnings, organized by category (architecture-patterns, design-patterns, logic-errors, etc.). Search here before solving a problem; add here when you solve something non-trivial.
- **`CONCEPTS.md`** — shared domain vocabulary. Defines project-specific terms (NoteColumn, Double Link Column, Lookup Column, Set Operation Column, etc.) that `docs/solutions/` and conversations cite without redefinition.

## Documenting a solution

When you solve a non-trivial problem, capture it via the `ce-compound` skill: it researches, cross-references existing docs, and writes a structured doc to `docs/solutions/<category>/` with YAML frontmatter. Use `ce-compound-refresh` to audit and update stale docs.
