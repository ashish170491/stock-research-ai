# Step 6: Critic loop (evaluator-optimizer)

| ID | Type | Criterion |
| --- | --- | --- |
| S6-1 | CODE | A `Critique` record (structured output) has `unsupportedClaims`, `missedDataGaps`, `missingRisks` and `verdict` (enum ACCEPT/REVISE). |
| S6-2 | CODE | The loop is bounded. Test with a stub critic that always returns REVISE: the writer runs at most 2 times, and the result says the critique limit was reached. |
| S6-3 | CODE | The deterministic verifiers run after the final revision, not only on the first draft (code order and test). |
| S6-4 | CODE | The critic sees the evidence pack and draft only (no tools). Unparseable critic output is treated as ACCEPT, with a log warning. |
| S6-5 | CODE | The bear-case section is generated only from data gaps, conflicts and failed screening rules, and is verified. |
| S6-L1 | LIVE | A report for a bank with known DATA_CONFLICTs (HDFCBANK) mentions each conflict in the interpretation or risks. The critic output is visible in the trace. |
