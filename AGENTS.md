# ChatGPT / Codex project instructions

This file is the compatibility entry point for ChatGPT and Codex. The existing
project rules remain the sources of truth and must not be duplicated here.

Before doing any work in this repository:

1. Read and follow every instruction in the repository-root `CLAUDE.md`.
2. Read and follow every rule in `.cursor/rules/*.mdc` whose frontmatter has
   `alwaysApply: true`.
3. For any other `.cursor/rules/*.mdc`, follow its frontmatter scope whenever
   the files or task being worked on match that scope.

Treat those instructions as mandatory repository policy for planning,
implementation, review, verification, deployment, and handoff. If an
instruction conflicts with a higher-priority system, developer, or explicit
user instruction, follow the higher-priority instruction and call out the
conflict rather than silently ignoring the repository rule.

When project rules change, update their source file (`CLAUDE.md` or the relevant
`.cursor/rules/*.mdc`) rather than copying the change into this file.
