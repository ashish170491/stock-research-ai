# Step 8: Autonomous research orchestrator

| ID | Type | Criterion |
| --- | --- | --- |
| S8-1 | CODE | `ResearchPlan` is structured output made of steps from a fixed `StepType` enum (SCREEN, DEEP_DIVE, COMPARE, SEARCH_DOCS, CRITIQUE, WRITE_MEMO). Test: unknown or invalid steps are rejected before anything runs. |
| S8-2 | CODE | Java executes the plan. The LLM never calls data providers directly in orchestrator mode. |
| S8-3 | CODE | Budgets (max steps, max LLM calls, wall-clock) are configurable and enforced. Test: exceeding one returns a partial memo that states which limit stopped it. |
| S8-4 | CODE | Human checkpoint: after SCREEN, the orchestrator returns the shortlist and waits. "continue" in the same conversation resumes from saved state (test). |
| S8-5 | CODE | The memo has these sections: criteria results, comparison, risks and gaps, open questions, sources. It passes both verifiers, and has no recommendation (INV-5). |
| S8-6 | CODE | Watchlist monitoring records which stocks entered or left each saved screen after the nightly snapshot (test with two snapshots). |
| S8-7 | CODE | Every plan step appears in the trace with its duration and LLM call count. |
| S8-8 | CODE | If a hosted model is used for planning, it's behind a Spring profile and used only by the orchestrator client. Tools, data and verification stay local. |
| S8-L1 | LIVE | "Find 3 long-term candidates in pharma from the Nifty 200" runs through the checkpoint to a memo within the configured budget. Record the total time and LLM call count. |
