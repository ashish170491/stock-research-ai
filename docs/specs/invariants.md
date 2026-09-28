# Invariants (must hold after every roadmap step)

These rules protect the core design of the project: **Java calculates and judges; the LLM
orchestrates and explains.** A step that breaks any invariant is FAILED, however good its
new feature is.

Types: **AUTO** (checked by `scripts/verify-invariants.sh`), **CODE** (needs reading code),
**REVIEW** (the script lists candidates; the verifier reads each one and decides).

| ID | Type | Rule | How to check |
| --- | --- | --- | --- |
| INV-1 | AUTO | The full test suite passes. | `./mvnw -q test` exits 0. |
| INV-2 | AUTO | No test is newly disabled to make the build pass. | No added `@Disabled` in `git diff HEAD -- src/test`. |
| INV-3 | REVIEW | Every model call whose output states facts about a company goes through `NumericClaimVerifier` (and `UnsupportedClaimFilter`) before it reaches the user. Routing, symbol resolution and structured extraction (`.entity(...)`) are exempt because their output is validated in Java. | Script lists every main class calling `.content()` that doesn't reference `NumericClaimVerifier`. Read each one: is its output shown to the user as analysis? |
| INV-4 | REVIEW | Every system prompt that writes about companies includes `ResearchInstructions.EVIDENCE_RULES`. | Script lists `defaultSystem(`/`.system(` callers without `EVIDENCE_RULES`. Routing, general (non-stock) and extraction prompts are exempt. |
| INV-5 | REVIEW | No buy/sell/hold recommendation, rating, price target or fair value is produced anywhere. Prompts may mention these words only to forbid them. | Script greps prompts/templates. Each hit must be a prohibition. |
| INV-6 | CODE | The LLM never calculates. Every derived number (growth, ratio, CAGR, yield, score) is produced by `FinancialCalculator` (or a Java class it delegates to) and recorded with its formula and inputs so `CalculationVerifier` can reproduce it. | For each new derived metric in the step, find where it's computed and its verifier test. |
| INV-7 | CODE | Only `DataStatus.VALID` values feed calculations, observations, screening passes and interpretations. `UNAVAILABLE` is never zero; `DATA_CONFLICT` never picks a side. From Step 3: a non-VALID input never makes a screening rule PASS. | Read new code paths that consume `FinancialDataPoint`; check for status checks and tests covering each non-VALID status. |
| INV-8 | AUTO | Every `@Tool` method records its call on `ToolUsage`, so the answer can be verified against it. | Each class with `@Tool(` references `ToolUsage`. |
| INV-9 | CODE | Every new `@Tool` description says what the tool does NOT return, what the argument accepts, and that its figures must be quoted, not recalculated. | Read each new tool description. |
| INV-10 | AUTO | Unit tests never hit the network. Tests that use the Yahoo base URL mock it (`MockRestServiceServer` or equivalent). Tests that need a real model are tagged `@Tag("llm")` and excluded from the default build. | Script checks test files that mention a real host. |
| INV-11 | AUTO | No secrets or API keys are committed. | Script greps for key-like assignments. |
| INV-12 | CODE | Amounts in different currencies are never combined, and periods are never invented. Period labels come from `FiscalCalendar`. | Read new code that combines or compares amounts. |
