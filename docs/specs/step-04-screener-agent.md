# Step 4: Screener agent (chain workflow)

| ID | Type | Criterion |
| --- | --- | --- |
| S4-1 | CODE | `Intent.SCREEN` exists. Keyword rules route "find…", "screen…", "shortlist…" and "which stocks…" (tests for each), and don't misroute existing test cases. |
| S4-2 | CODE | `ScreenerAgent` stage 1 extracts `ScreeningCriteria` via `.entity(...)` using a client with thinking disabled and temperature 0. |
| S4-3 | CODE | Stage 2 validation in Java: unknown metric → rejected with a message (test); out-of-bounds threshold → rejected or clamped with an assumption noted (test); vague terms ("low debt", "profitable") map to preset thresholds (test). |
| S4-4 | CODE | Every assumption made in stage 2 is shown to the user in the final answer (test asserts the text). |
| S4-5 | CODE | Stage 3 runs `StockScreener`. Stage 4 writes the summary from the result table only (tool-free client), screened by `NumericClaimVerifier`. |
| S4-6 | CODE | If extraction fails, the user gets a clear message or falls back to a preset. There's no exception and no unscreened answer. |
| S4-7 | CODE | Each chain stage appears in the conversation trace. |
| S4-L1 | LIVE | "Find profitable IT companies with low debt and ROE above 18%" shows its assumptions and a result table, with no removed-statement notes. |
