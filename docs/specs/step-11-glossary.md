# Step 11: Glossary and question-based grouping

| ID | Type | Criterion |
| --- | --- | --- |
| S11-1 | CODE | `MetricGlossary` has an entry for every `ScreeningMetric` and for every metric the research report renders (EPS and the P/E on filed EPS included). Test: no `ScreeningMetric` is without an entry. |
| S11-2 | CODE | Each entry states what the metric measures, how this application calculates it (its inputs and their source, consistent with the `Formula` and calculation actually used), how to read it, what to read it with, and its sector notes. Sector notes come from `IndustryGroup` (its non-primary metrics and notes), not a second copy. Test: the bank note on ROCE is `IndustryGroup.BANK`'s. |
| S11-3 | CODE | No entry gives a benchmark or ideal value, or calls a value good or bad. Test: every entry's text is free of "good", "bad", "ideal", "healthy", "strong", "weak", "cheap", "expensive", "attractive", "should be", "better", "worse", and of any number other than 100 in a formula. |
| S11-4 | CODE | A preset threshold on a metric is shown in that metric's entry as the screen's setting ("the quality-compounder screen uses ROE 3y avg ≥ 15.00%; this is the screen's setting, which you can change"), read from `ScreeningPresets` at runtime. Test: changing the preset's threshold changes the text. |
| S11-5 | CODE | Every metric belongs to one of five questions (profitable, growing, cash, financially stretched, price), and the research report and screening output group metrics by them. |
| S11-6 | CODE | Report and screening answers end with an "Explain the terms" section rendered by Java after the answer checks, listing only the terms that appear in that answer. It is never passed to a model. |
| S11-7 | CODE | "What is ROCE?" is routed to the glossary and answered with the entry; no model call generates the definition (test with a scripted `ChatModel`: only the routing call is made). An unknown term is said to be unknown, with the list of terms. |
| S11-L1 | LIVE | "What is ROCE?" returns the glossary entry, with its bank note, in under 5 s. |
