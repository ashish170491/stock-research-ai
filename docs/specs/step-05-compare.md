# Step 5: Parallel deep-dive and real comparison

| ID | Type | Criterion |
| --- | --- | --- |
| S5-1 | CODE | `ComparisonBuilder` aligns 2 to 5 companies on the same metrics. A cell whose period label or currency differs from the row's is NOT_COMPARABLE (tests for both). |
| S5-2 | CODE | Non-VALID values show their status in the table and are excluded from any ranking. |
| S5-3 | CODE | `AiService`'s COMPARE branch uses `ComparisonBuilder`, no longer joins separate reports, and makes one narrative LLM call, tool-free and verified. |
| S5-4 | CODE | `ShortlistDeepDive` fetches data for N companies concurrently. LLM calls are sequential or bounded by a configurable limit. |
| S5-5 | CODE | Test: one company failing to load still gives a comparison of the rest, with a data gap. |
| S5-6 | CODE | Comparison rows are grouped by the Step 11 questions, and each row carries the glossary's line on how to read it. |
| S5-7 | CODE | Each company's figure is shown beside its own industry group's median from Step 13. Companies in different groups each get their own group's median, and the table says so. |
| S5-L1 | LIVE | "Compare TCS and Infosys" returns one aligned table plus narrative, faster than two sequential full reports took before this step. |
