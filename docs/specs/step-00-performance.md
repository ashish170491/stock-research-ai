# Step 0: Performance and housekeeping

| ID | Type | Criterion |
| --- | --- | --- |
| S0-1 | CODE | `RequestRouter`'s ChatClient sets its own `OllamaChatOptions` with thinking disabled, `temperature` 0 and `numCtx` ≤ 4096. |
| S0-2 | CODE | `ResearchReportWriter`'s interpretation ChatClient sets `OllamaChatOptions` with thinking disabled. |
| S0-3 | CODE | `stripThinking` is still applied as a safety net. |
| S0-4 | AUTO | `application.yml` sets `spring.threads.virtual.enabled: true`. |
| S0-5 | CODE | `ResearchReportService.build()` fetches profile, financials, history and shareholding concurrently (virtual-thread executor or equivalent), with a bounded wait. |
| S0-6 | CODE | A test proves that one capability throwing or timing out still produces a report, with that section as a data gap. |
| S0-7 | CODE | `spring-boot-starter-cache` and Caffeine are in `pom.xml`, caching is enabled, and there are separate caches with TTLs: quote ≈1m, price history ≈6h, financials and profile ≈24h, symbol resolution ≈7d, configured in `application.yml`. |
| S0-8 | CODE | A test proves a second identical lookup doesn't call the HTTP layer (mock verifies one interaction). |
| S0-9 | CODE | Yahoo calls go through a rate limiter or semaphore (≤ 2 requests/s, configurable), with a test or clear code path. |
| S0-10 | AUTO | `grep -rn "qwen3:" src/main/java` returns nothing: the model name comes from configuration. |
| S0-11 | CODE | Ollama `ResourceAccessException` handling exists in one component and is used by all ChatClient callers. |
| S0-12 | AUTO | Sample outputs (`hdfc.txt`, `icicibank.txt`, `infosys.txt`, `techm.txt`) aren't in the project root. `REVIEW_BUNDLE.md` is removed or git-ignored. |
| S0-L1 | LIVE | A routed QUOTE (`price of Infosys`) responds in < 3 s. |
| S0-L2 | LIVE | A FULL_RESEARCH request (`fundamental overview of TCS`) responds in < 60 s on the developer's machine. Record the measured time. |
| S0-L3 | LIVE | Repeating S0-L2 immediately makes no new Yahoo HTTP requests (check logs at DEBUG for the Yahoo client), and the data-fetch part is near zero. |
| S0-L4 | LIVE | While a request runs, `ollama ps` shows the model at 100% GPU. Record the output. |
