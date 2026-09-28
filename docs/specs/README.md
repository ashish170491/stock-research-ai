# Roadmap acceptance specs

One file per step of `docs/AGENT_ROADMAP.md`, plus `invariants.md`, which applies to every step.
The `roadmap-verifier` agent (run with `/verify-step N`) treats these files as the source of truth.

Check types:
- **AUTO**: a command with an unambiguous result.
- **CODE**: verified by reading code and the tests that assert it (the test must also pass).
- **LIVE**: needs Ollama and the app running (`./mvnw spring-boot:run`). Marked SKIPPED if they're down.

Editing specs: specs may change as you learn, but change them *before* implementing, in a
separate commit, never to make a failing verification pass.
