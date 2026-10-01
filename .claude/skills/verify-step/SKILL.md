---
name: verify-step
description: Verify that a step of docs/AGENT_ROADMAP.md is correctly implemented, using the roadmap-verifier subagent against docs/specs. Use when the user says "verify step N", "check step N", or asks whether a roadmap phase is done.
argument-hint: "[step-number 0-13 | invariants]"
arguments: [step]
context: fork
agent: roadmap-verifier
background: false
allowed-tools: Read Grep Glob Write Bash(git status*) Bash(git log*) Bash(git diff*) Bash(git rev-parse*) Bash(./mvnw *) Bash(bash scripts/verify-invariants.sh*) Bash(curl *) Bash(ollama ps*) Bash(ls *) Bash(wc *) Bash(date*)
---

Verify roadmap step **$step** of this project.

## Current state
- Branch and changes: !`git status --short --branch | head -30`
- Recent commits: !`git log --oneline -5`
- Spec files: !`ls docs/specs`

## Task
1. If `$step` is `invariants`, only check `docs/specs/invariants.md`: run
   `bash scripts/verify-invariants.sh`, resolve each REVIEW line, and write
   `docs/verification/invariants-<date>.md`.
2. Otherwise, zero-pad the step number (for example 3 becomes `03`). Open
   `docs/specs/step-<NN>-*.md` and the matching "Step N" section of
   `docs/AGENT_ROADMAP.md`, then follow your verification procedure exactly.
3. If the spec file doesn't exist, stop and report that. Don't invent criteria.
4. Finish with: the verdict, a numbered list of FAIL and NEEDS_REVIEW items, each
   with a fix hint, and the report path. Keep the summary under 25 lines.
