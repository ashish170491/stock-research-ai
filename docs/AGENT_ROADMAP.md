# stock-research-ai: code review and agent roadmap

_Reviewed 27 Sep 2026 against commit `020a400` plus the uncommitted changes in the working tree._

How to use this file: work through the steps in order. Each step lists **what you'll learn**, **what to build**, a **prompt you can paste into Claude Code**, and a **done when** check. Keep this file in `docs/` so Claude Code can read it as well (for example, "read docs/AGENT_ROADMAP.md and do Step 3").

---

## Part 1: Code review

### What you already have

This is more than foundational work. You have about 8.5k lines of main code, 32 test classes, and all tests passing. The evidence layer is the strongest part, and it's better designed than most production LLM finance apps:

| Area | What's good |
| --- | --- |
| **Deterministic evidence layer** | Every number is a `FinancialDataPoint` with a unit, period, source and status. Java does all the maths (`FinancialCalculator`), and `CalculationVerifier` recomputes each result. The LLM never does arithmetic. **Keep this principle for every agent you build.** |
| **Guardrails after generation** | `NumericClaimVerifier` and `UnsupportedClaimFilter` check the model's output against what the tools returned. Most tutorials skip this. |
| **Routing** | `RequestRouter` tries cheap keyword rules first, falls back to an LLM classifier using structured output (`.entity(RoutedRequest.class)`), and always has a safe fallback. This is the "Routing" agentic pattern. |
| **Tool design** | The tool descriptions say what each tool does *not* return. Results are rendered as compact Markdown rather than 47 KB of JSON, and `ToolContext` carries per-request state without thread-locals. |
| **Honesty about data gaps** | `UnsupportedShareholdingProvider` refuses to relabel Yahoo's US-style holder data as a SEBI shareholding pattern. `DATA_CONFLICT` never picks a side. |
| **Observability** | The `ToolCallTracingManager` decorator plus `ConversationTraceAdvisor` let you see exactly what the model asked for and what it got back. |

### What to fix, in priority order

1. **Latency is the biggest blocker for agents.** Your logs show a single model call taking **115 to 295 seconds**. For example: `DONE in 150.00s | 1 model round | prompt=3184 completion=95`, which is 150 seconds for 95 output tokens. An agent makes 5 to 15 calls, so at this speed a run would take 10 to 30 minutes. There are three likely causes:
   - **Qwen3 thinking is on by default.** Ollama 0.12+ turns thinking on automatically for qwen3, so the model writes a hidden `<think>` block on every call. You strip that block afterwards (`stripThinking`), but you still wait for it to be generated. Turn thinking off for the router and the interpretation client, and leave it on only where reasoning helps (see Step 0).
   - **Calls queue up in Ollama.** Two requests finished 9 seconds apart, each after about 150 seconds, which suggests one waited behind the other. By default Ollama serves one request at a time per model.
   - **Memory pressure on a MacBook Air.** An 8B model with `num-ctx: 16384` needs a lot of memory for the KV cache. Run `ollama ps` while a request is in flight. If it shows CPU/GPU splitting or macOS starts swapping, lower `num-ctx` for clients that don't need it (the router needs about 2k tokens).
2. **No conversation memory.** Every request starts from zero, so a follow-up like "what about its debt?" fails. See Step 1.
3. **No valuation data.** Nothing in `src/main` reads P/E, P/B or dividend yield. You can't pick stocks from fundamentals alone without asking "at what price?" See Step 2.
4. **COMPARE doesn't compare.** It writes two reports one after the other, each with its own LLM call, and never puts the companies side by side. See Step 5.
5. **No caching or throttling of Yahoo calls.** Each report makes fresh HTTP calls. That's fine for one stock, but screening 50 to 100 stocks would get you rate-limited or blocked. See Step 0 and Step 3.
6. **Sequential data fetches.** `ResearchReportService.build()` fetches profile, financials, history and shareholding one after another. Java 21 virtual threads make running them in parallel easy.
7. **Small cleanups:**
   - The Ollama error handling is duplicated in `AiService` and `ResearchReportWriter`, and the error message hardcodes `qwen3:8b`. Read the model name from config instead.
   - `GET /api/ai/chat?message=` should become `POST` with a JSON body `{conversationId, message}`. You'll need `conversationId` for memory anyway.
   - Commit the 10+ modified files that are sitting uncommitted.
   - Move `hdfc.txt`, `infosys.txt` and the other sample outputs to `docs/samples/`. Add `REVIEW_BUNDLE.md` to `.gitignore` or delete it.
8. **Yahoo data quality for Indian banks.** Your own HDFC report shows TTM revenue and the sum of four quarters differing by 60%. Yahoo's `totalRevenue` for banks mixes definitions. Your conflict detection handles it correctly, but the practical result is that bank analysis will stay thin until you add a second source (see Step 7).

### A design point for "picking good stocks"

`EVIDENCE_RULES` rule 5 bans words like "strong", "high" and "healthy" because *"no criteria or benchmarks for them are supplied."* That was the right call, and it also shows how to do stock picking honestly:

> **The LLM never judges whether a stock is good. Java scores it against criteria that you define. The LLM understands your request, orchestrates the tools and explains the results.**

Once criteria exist (for example ROE ≥ 15%), the model can truthfully say "INFY meets your ROE ≥ 15% criterion (FY26 ROE: X%)." This keeps your whole verification approach intact, and it's what the rest of this roadmap builds on.

---

## Part 2: Target architecture

```
                         ┌────────────────────────────────────────┐
 User ──POST /chat──────▶│  RequestRouter (exists)                │
  (conversationId)       │  + ChatMemory (Step 1)                 │
                         └───────┬───────────┬────────────┬───────┘
                                 │           │            │
             single-stock Q&A    │   screen  │  goal-driven research
             (exists)            ▼           ▼            ▼
                       Tool loop    ScreenerAgent     ResearchOrchestrator (Step 8)
                                    (Step 4)          plan → screen → deep-dive →
                                        │             compare → critique → memo
                                        ▼                  │
                     ┌──────────────────────────────────────────────┐
                     │ Deterministic Java core (the "judge")        │
                     │ StockScreener · FinancialCalculator ·        │
                     │ Verifiers · ComparisonBuilder                │
                     └──────────────┬───────────────────────────────┘
                                    │ reads
                     ┌──────────────▼───────────────────────────────┐
                     │ Data layer: Yahoo (cached) · Nightly snapshot│
                     │ DB · NSE shareholding · Documents vector     │
                     │ store (annual reports, concalls)             │
                     └──────────────────────────────────────────────┘
```

The Spring AI concepts you'll learn, mapped to steps:

| Concept | Step |
| --- | --- |
| Chat options per client, thinking control | 0 |
| Advisors and `ChatMemory` | 1 |
| Tool design (new tools) | 2 |
| Deterministic tools that an LLM calls | 3 |
| Structured output → Chain workflow | 4 |
| Parallelization and Orchestrator-Workers | 5 |
| Evaluator-Optimizer (critic loop) | 6 |
| RAG: `DocumentReader`, `VectorStore`, `QuestionAnswerAdvisor` | 7 |
| Autonomous agent with a plan, a budget and human checkpoints | 8 |
| MCP server (let Claude use your tools) | 9 |
| Evaluation and observability | 10 |

These names follow Anthropic's "Building effective agents" patterns. The Spring AI reference docs have an "Agentic patterns" page with examples of each, which is worth reading before Steps 4 to 6.

---

## Part 3: Step-by-step plan

### Step 0: Performance and housekeeping (1 to 2 evenings)

**Learn:** per-client `ChatOptions`, thinking control, caching, virtual threads.

**Build:**
- Give each `ChatClient` its own options via `.defaultOptions(OllamaChatOptions.builder()...build())`:
  - Router: thinking off, `temperature 0`, `numCtx 4096`.
  - Interpretation: thinking off.
  - Tool loop: keep thinking on at first, then measure with it off.
  - In Spring AI the builder methods are `enableThinking()` and `disableThinking()` on `OllamaChatOptions`. Check the exact names in 2.0.1 in your IDE.
- Enable `spring.threads.virtual.enabled: true`. Make `ResearchReportService.build()` fetch its four inputs in parallel using `Executors.newVirtualThreadPerTaskExecutor()`.
- Add `spring-boot-starter-cache` and Caffeine. Cache TTLs:
  - Quote: 1 minute.
  - Price history: 6 hours.
  - Financials and profile: 24 hours.
  - Symbol resolution: 7 days.
- Add a simple throttle (a semaphore or Resilience4j `RateLimiter`) in front of `YahooQuoteSummaryClient`.
- Centralise the Ollama error handling and read the model name from properties.

**Claude Code prompt:**
```
Read docs/AGENT_ROADMAP.md Step 0. Then:
1) Give RequestRouter's and ResearchReportWriter's ChatClients their own
   OllamaChatOptions with thinking disabled (router: temperature 0, numCtx 4096).
   Keep stripThinking as a safety net.
2) Enable virtual threads and fetch profile/financials/history/shareholding in
   parallel in ResearchReportService.build(), preserving the independent-failure
   behaviour. Add a test proving one failing capability still yields a report.
3) Add Spring Cache + Caffeine with per-cache TTLs (quote 1m, history 6h,
   financials/profile 24h, symbol resolution 7d) and a max 2 req/s limiter on
   Yahoo calls.
4) Extract the duplicated Ollama ResourceAccessException handling into one
   component whose message uses the configured model name.
Run ./mvnw test. Don't change any verifier or evidence rule.
```

**Done when:** a routed QUOTE takes under 3 seconds, a FULL_RESEARCH report takes well under a minute, and the second request for the same stock makes no Yahoo calls.

---

### Step 1: Conversation memory (1 evening)

**Learn:** the advisor chain, `ChatMemory`, `MessageWindowChatMemory`, conversation IDs, and advisor ordering relative to your trace advisor.

**Build:**
- Change the endpoint to `POST /api/ai/chat` with body `{ "conversationId": "...", "message": "..." }`.
- Add `MessageChatMemoryAdvisor.builder(chatMemory).build()` to the tool and general clients. Pass the ID per call:
  ```java
  .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
  ```
- Fix the router's blind spot: "what about its debt?" names no company. Keep a small per-conversation `ResearchSession` (last companies discussed) and let the router fill in the company when none is named. Don't send the whole chat history to the router. It only needs the last company, and a short prompt keeps it fast.
- Start with the in-memory repository. You can switch to `JdbcChatMemoryRepository` (H2 or Postgres) later.

**Claude Code prompt:**
```
Implement docs/AGENT_ROADMAP.md Step 1: POST /api/ai/chat with
{conversationId, message}; MessageChatMemoryAdvisor (window 20) on the tool and
general clients; a ResearchSession per conversation remembering the last
resolved companies so that "what about its debt?" after a question on TCS
routes as SPECIFIC_QUESTION for TCS. Memory must not bypass NumericClaimVerifier:
figures quoted from earlier turns still need to be in this turn's evidence, so
include prior-turn tool evidence in the ResearchSession. Add tests.
```

**Done when:** "Research Infosys" → "how has it performed over 5 years?" → "and its debt?" all answer about Infosys.

---

### Step 2: Valuation and Indian-market quality tools (2 to 3 evenings)

**Learn:** designing a new tool end to end with the same evidence discipline.

**Build:**
- Add the Yahoo `summaryDetail` and `defaultKeyStatistics` modules to the existing `YahooQuoteSummaryClient`. Produce a `ValuationSnapshot` containing:
  - Trailing P/E, P/B and dividend yield, each as a `FinancialDataPoint` with an as-of date, because valuation changes daily.
  - Earnings yield, calculated in Java as 1/PE.
  - Cross-check P/E against price ÷ EPS. If they disagree, mark it `DATA_CONFLICT`.
- New tool `getValuation(symbol)`. Its description must say the values are point-in-time, and that the tool gives no fair value or price target.
- Add earnings-quality metrics to `FinancialCalculator`: **operating cash flow ÷ net profit** (averaged over 3 to 5 years) and **ROCE** for non-financials. Indian investors watch both closely. Your ROCE is currently `null`.

**Why these metrics:** in the Indian market, the common failure isn't a bad P/E. It's reported profits that never turn into cash, or a debt-funded ROE. OCF/PAT and ROCE catch both.

**Claude Code prompt:**
```
Implement docs/AGENT_ROADMAP.md Step 2. Add a ValuationSnapshot (trailing PE,
PB, dividend yield, earnings yield) from Yahoo summaryDetail +
defaultKeyStatistics, as FinancialDataPoints with as-of dates, a PE vs
price/EPS cross-check (DATA_CONFLICT on >5% mismatch), and a getValuation tool
following the style of StockResearchTools. Add OCF/PAT (3y and 5y) and ROCE to
FinancialCalculator with CalculationVerifier coverage, marked not-primary for
BANK/NBFC/INSURANCE in SectorClassifier. Add fixture-based tests using the
existing yahoo fixtures pattern.
```

---

### Step 3: Deterministic screening engine, the "judge" (3 to 4 evenings)

**Learn:** the most important lesson for finance agents. The LLM calls a deterministic tool, and the decision is made in code you can test.

**Build:**
- **Universe:** bundle `nifty50.csv`, `niftynext50.csv` and `midcap150.csv` (symbol and name, from NSE index constituents) next to `equity-list.csv`. Add a user `watchlist`.
- **Nightly snapshot, not live calls:** a `@Scheduled` job at about 18:30 IST, after market close and the end-of-day data update. It fetches metrics for the universe (throttled) and stores them in H2 or Postgres (`fundamentals_snapshot`). Screening reads the snapshot, so it takes milliseconds and never calls Yahoo during a chat.
- `ScreeningCriteria` record: a list of `Rule(metric, operator, threshold)` plus industry-group filters.
- `StockScreener.screen(criteria, universe)` returns, for each stock, the result of each rule: `PASS`, `FAIL`, or `INSUFFICIENT_DATA` (anything not VALID). **A missing or conflicting value never passes.**
- Built-in presets in `application.yml`. These are common heuristics, not advice; tune them to your own style:

| Preset | Non-financials | Banks | NBFCs |
| --- | --- | --- | --- |
| `quality-compounder` | ROE ≥ 15% (3y avg), ROCE ≥ 15%, revenue 5y CAGR ≥ 10%, profit 5y CAGR ≥ 10%, OCF/PAT ≥ 0.8, D/E ≤ 0.5 | ROA ≥ 1%, ROE ≥ 14% | ROA ≥ 2%, ROE ≥ 15% |
| `reasonable-value` | quality-compounder rules, plus P/E ≤ own 5y median (needs snapshot history) or earnings yield ≥ a threshold you set | P/B ≤ threshold | P/B ≤ threshold |
| `dividend` | dividend yield ≥ 2%, payout sustainable (dividend ≤ OCF), 5y profit CAGR > 0 | same | same |

- Tool `screenStocks(preset or criteria, universe)` returns a ranked table: count of passes, then a tie-break metric.
- Update `EVIDENCE_RULES` rule 5 so the model may say "meets / does not meet the supplied criterion *X*", and still may not use unqualified labels.

**Indian-market checks to add as the data becomes available.** Most of these need NSE/BSE data (Step 7):
- Promoter holding and **promoter pledge**. A high or rising pledge is a classic red flag.
- Exclude stocks on NSE's ASM/GSM surveillance lists.
- A minimum liquidity filter, such as average daily traded value.
- For banks: GNPA/NNPA, CASA ratio, NIM and capital adequacy. Yahoo doesn't provide these, so they stay "INSUFFICIENT_DATA" until you have investor presentations.

**Claude Code prompt:**
```
Implement docs/AGENT_ROADMAP.md Step 3. Create package
com.ashish.stockresearch.screening with: universe CSVs (Nifty 50 first), a
FundamentalsSnapshot entity + repository (H2 file DB via spring-boot-starter-data-jdbc),
a throttled @Scheduled SnapshotJob (18:30 Asia/Kolkata, also triggerable via
POST /api/admin/snapshot), ScreeningCriteria/Rule records, StockScreener
returning per-rule PASS/FAIL/INSUFFICIENT_DATA (non-VALID never passes),
presets from application.yml, and a screenStocks @Tool. Industry-group-aware:
bank presets must not use D/E or operating margin. Pure-Java unit tests for
StockScreener with hand-built snapshots.
```

**Done when:** `screenStocks(quality-compounder, NIFTY50)` returns in under 1 second from the snapshot, with a readable pass/fail table for each stock.

---

### Step 4: Screener agent, structured output as a chain (2 evenings)

**Learn:** the **Chain** (prompt-chaining) workflow: an LLM turns free text into a validated object, Java runs it, and a second LLM call explains the result.

**Flow:** "Find profitable IT companies with low debt and ROE above 18%" goes through three stages:
1. LLM → `ScreeningCriteria` via `.entity(ScreeningCriteria.class)`, with thinking off and temperature 0.
2. **Validate in Java.** Only whitelisted metric names are allowed, and thresholds must be within sane bounds. "Low debt" has no number, so map it to your preset's D/E ≤ 0.5 and **echo the interpretation back** to the user ("I read 'low debt' as D/E ≤ 0.5").
3. `StockScreener` runs, then an LLM writes the summary from the result table only. The output passes through your verifiers.

- Add intent `SCREEN` to `RequestRouter`, with keyword rules for "find", "screen", "shortlist" and "which stocks".

**Claude Code prompt:**
```
Implement docs/AGENT_ROADMAP.md Step 4: Intent.SCREEN, a ScreenerAgent that
(1) extracts ScreeningCriteria via structured output with thinking disabled,
(2) validates against a metric whitelist and bounds and maps vague words to
preset thresholds, reporting every assumption, (3) runs StockScreener,
(4) writes a summary from the result table only, screened by
NumericClaimVerifier. Log each chain stage in the conversation trace.
```

---

### Step 5: Parallel deep-dive and a real comparison (2 evenings)

**Learn:** the **Parallelization** and **Orchestrator-Workers** patterns.

**Build:**
- `ComparisonBuilder` (Java) puts two to five companies side by side on the same metrics and periods. It must refuse to compare figures from different periods or currencies. The LLM writes the narrative from that table only. This replaces the current COMPARE behaviour.
- `ShortlistDeepDive`: for the top N screener results, fetch data in parallel on virtual threads. Run the LLM interpretation calls **one after another** unless you raise `OLLAMA_NUM_PARALLEL`. On a MacBook Air, sequential is realistic.

**Claude Code prompt:**
```
Implement docs/AGENT_ROADMAP.md Step 5: a ComparisonBuilder producing an
aligned multi-company table (same metric, same period label, same currency,
otherwise the cell is marked NOT_COMPARABLE), a comparison renderer, and a
tool-free LLM narrative over that table. Replace AiService's COMPARE branch.
Add ShortlistDeepDive running data fetches in parallel on virtual threads.
```

---

### Step 6: Critic loop, evaluator-optimizer (1 to 2 evenings)

**Learn:** the **Evaluator-Optimizer** pattern: a second model call reviews the first against a checklist, and the first revises its text.

**Build:** a `ResearchCritic` that receives the evidence pack and the draft interpretation. It returns a structured `Critique`:
- `unsupportedClaims`
- `missedDataGaps`
- `missingRisks`
- `verdict: ACCEPT | REVISE`

If the verdict is REVISE, the writer gets one revision round (at most two in total). The deterministic verifiers still run last. The critic adds judgement ("you didn't mention that the ROE figure is in DATA_CONFLICT"); it doesn't replace the verifiers.

Also add a **bear-case prompt** that lists what could go wrong, drawn *only* from data gaps, conflicts and failed screening rules. This is good discipline for any investor.

---

### Step 7: RAG over annual reports and concall transcripts (3 to 5 evenings)

**Learn:** `DocumentReader`, `TokenTextSplitter`, embeddings, `VectorStore`, `QuestionAnswerAdvisor` or `RetrievalAugmentationAdvisor`, and metadata filters.

**Why:** most of what matters in the Indian market isn't in Yahoo. That includes management guidance, capex plans, related-party transactions, contingent liabilities, auditor remarks, and bank NPA/CASA/NIM figures. It lives in PDFs: annual reports, investor presentations and earnings-call transcripts, all available from NSE, BSE and company websites.

**Build:**
- Add the `spring-ai-pdf-document-reader` and `spring-ai-advisors-vector-store` dependencies.
- Pull an Ollama embedding model (`nomic-embed-text` or `mxbai-embed-large`).
- Start with `SimpleVectorStore`, persisted to a file. Move to PGVector later.
- `POST /api/documents` uploads a PDF with metadata `{symbol, docType, fiscalYear, page}`.
- Tool `searchCompanyDocuments(symbol, question)` retrieves the top-k chunks filtered by `symbol == 'X'` and returns them **with page citations**.
- Extend `NumericClaimVerifier` so document chunks count as evidence. Tag figures from documents as `SOURCE: Annual Report FY25 p.143`, not as Yahoo.

---

### Step 8: Autonomous research orchestrator (3 to 4 evenings)

**Learn:** a real agent that takes a goal, makes a plan, stays within a budget, and pauses for human checkpoints.

**Example goal:** "Find me 3 long-term candidates in pharma from the Nifty 200 and explain the trade-offs."

**Build:**
- `ResearchPlan`: the LLM produces a list of steps, and each step must be one of a **fixed set of step types**: `SCREEN`, `DEEP_DIVE`, `COMPARE`, `SEARCH_DOCS`, `CRITIQUE`, `WRITE_MEMO`. The LLM picks and orders steps and fills in their parameters. Java validates and runs them.
- Budget limits: maximum steps, maximum LLM calls, and a wall-clock limit. Every step is logged in the trace.
- Human checkpoint: after the screening step, return the shortlist and wait for "continue" (store state in the `ResearchSession`).
- The final output is an **investment research memo**: what passed and failed each criterion, a comparison, risks and gaps, open questions to check, and sources.
- Watchlist monitoring: after the nightly snapshot, re-run your saved screens and record which stocks entered or left each screen.

**Note on models:** an 8B local model can pick from fixed step types, but free-form planning will be unreliable. If planning quality is poor:
- Try `qwen3:14b` if your RAM allows it.
- Or, because Spring AI is provider-portable, run *only the orchestrator* on a hosted model behind a Spring profile. Tools, data and verification stay local.

---

### Step 9: Expose your tools over MCP (1 evening)

**Learn:** the Model Context Protocol, with Spring AI as an MCP server.

Add `spring-ai-starter-mcp-server-webmvc` and register your tool beans with a `MethodToolCallbackProvider`. Then Claude Desktop or Claude Code can call `screenStocks`, `getFinancialSummary` and the other tools directly. You get your verified Indian-market data inside any MCP client.

---

### Step 10: Evaluation and observability (ongoing)

- **Golden set:** 20 to 30 questions with expected tool calls and the facts each answer must contain. Run it as a slow test profile (`@Tag("llm")`) against the real model. You already have the fixtures pattern for this.
- Spring AI evaluators (`RelevancyEvaluator`, `FactCheckingEvaluator`) for automated grading.
- Micrometer observations (`spring.ai.chat.observations.*`) exported to Prometheus/Grafana or Zipkin. Track latency and tokens per step, which will matter from Step 8 on.

---

## Suggested CLAUDE.md for this repo

Create `CLAUDE.md` in the project root so Claude Code follows your architecture every time:

```markdown
# stock-research-ai: rules for coding agents
- Java 21, Spring Boot 4.1, Spring AI 2.0, Ollama qwen3:8b local.
- The LLM never calculates, converts units, or judges a stock. All maths lives in
  FinancialCalculator and is verified by CalculationVerifier. All pass/fail
  judgements live in StockScreener against explicit criteria.
- Every number is a FinancialDataPoint with unit, period, source and DataStatus.
  Only VALID values are used; non-VALID never passes a screening rule.
- Every model answer that states figures goes through NumericClaimVerifier and
  UnsupportedClaimFilter.
- No buy/sell/hold, price targets, or fair values anywhere in prompts or output.
- New tools: description states what the tool does NOT return; return compact
  rendered text, record the call on ToolUsage.
- Run ./mvnw test before finishing. Use fixtures, never live Yahoo, in tests.
- Roadmap: docs/AGENT_ROADMAP.md
```

---

## A note on use

Keep the "no buy/sell/hold" rule. It keeps the LLM honest, and in India, giving investment advice to others for a fee requires SEBI registration as an Investment Adviser. For your own research the app is a screening and analysis aid; the investment decision stays with you.
