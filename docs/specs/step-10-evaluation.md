# Step 10: Evaluation and observability

| ID | Type | Criterion |
| --- | --- | --- |
| S10-1 | AUTO | A golden set exists under `src/test/resources/golden/` with ≥ 20 cases. Each has a question, expected intent, expected tools and required facts. |
| S10-2 | CODE | Golden and real-model tests are `@Tag("llm")`, excluded from the default `./mvnw test`, and runnable with a profile or `-Dgroups=llm`. |
| S10-3 | CODE | At least one Spring AI evaluator (relevancy or fact-checking) is used in the llm tests. |
| S10-4 | CODE | Micrometer/Spring AI observations are enabled, and an actuator endpoint exposes chat latency and token metrics. |
| S10-5 | CODE | The README documents how to run the llm suite and read its results. |
| S10-L1 | LIVE | The llm suite runs against local Ollama and prints a pass rate. Record it as a baseline in the report. |
