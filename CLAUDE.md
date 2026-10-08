# stock-research-ai: rules for coding agents
- Java 21, Spring Boot 4.1, Spring AI 2.0, Ollama qwen3:8b local.
- The LLM never calculates, converts units, or judges a stock. All maths lives in
  FinancialCalculator and is verified by CalculationVerifier. Pass/fail judgements
  live only in StockScreener, against explicit configured criteria, reading the
  fundamentals snapshot (never a provider). No other code or prompt judges a stock.
- A screen asked for in words goes through ScreenerAgent: the model only extracts criteria
  and summarises the result. CriteriaValidator checks every metric, bound, threshold and span
  (3y / 5y) against the user's own words, and the answer shows every assumption. A metric over
  N fiscal years uses exactly N consecutive years, never fewer. The index screened (Nifty 50 or Nifty 500)
  is read from the user's words, never chosen by the model; a request that names none gets the Nifty 50.
- A snapshot stores a universe member only from its own NSE listing; a symbol that resolves to another
  listing is a recorded failure. Universe CSVs are kept as downloaded from NSE Indices.
- Every model answer about a screen also goes through ScreeningCountVerifier (AnswerChecks).
- Every number is a FinancialDataPoint with unit, period, source and DataStatus.
  Only VALID values are used; non-VALID never passes a screening rule.
- Every model answer that states figures goes through NumericClaimVerifier and
  UnsupportedClaimFilter.
- No buy/sell/hold, price targets, or fair values anywhere in prompts or output.
- What a term means comes only from MetricGlossary (fixed, reviewed text): never a model-written
  definition, and never an ideal value or a good/bad judgement. Industry notes come from
  IndustryGroup and screen thresholds from ScreeningPresets, not written a second time.
- Context beside a ratio (own fiscal years, industry peers in the stored snapshot) is calculated in
  FinancialCalculator, verified, and rendered by Java in neutral words only (median, range, rank, highest or
  lowest of the years shown). A peer figure always carries the snapshot date and peer count; fewer than the
  minimum peers, or a metric not primary for the group, gives no peer statistic. The model is never given a
  peer figure to restate (ContextRenderer.renderForModel omits it); PositionClaimFilter removes any sentence
  that still compares with peers or other companies, or places a value among years or companies, in words.
- Comparing companies (ComparisonService) aligns them on the same metric, fiscal period and currency in
  Java (ComparisonBuilder); a cell whose period or currency differs is NOT_COMPARABLE, never silently shown
  beside a figure it cannot be read against. Each company's figure sits beside its own industry group's
  median from Step 13. The model writes one narrative over that aligned table only - never a separate
  report per company - and that narrative is verified the same way a report's interpretation is.
- A report's interpretation goes through ResearchCritic (tool-free, evidence and draft only) before it is
  final: REVISE gets one revision round (at most two writer calls total), and the deterministic verifiers
  run on whichever draft is final, never only the first. An unreachable or unparseable critic is treated
  as ACCEPT, logged, never a new way for the report to fail. The report's bear case (BearCaseWriter) is
  drawn only from data gaps, data-quality issues and failed screening rules - never facts or observations -
  and is checked by the same verifiers.
- Long work reports its steps with trace.Progress (streamed by /api/ai/chat/stream). A step names
  what is being done, for which company and from which source - never a figure, and never the
  model's draft text.
- New tools: description states what the tool does NOT return; return compact
  rendered text, record the call on ToolUsage.
- Each ChatClient sets its own OllamaChatOptions. Thinking is off unless the call
  needs reasoning. Model calls go through OllamaCalls.
- Yahoo responses are cached (YahooCacheConfiguration) and throttled
  (RequestThrottle). Never cache a price inside a long-lived entry.
- NSE filings (marketdata/provider/nse) share one RequestThrottle; each XBRL document is
  stored once (NseDocumentStore). A filed figure is used only after FiledStatementChecks.
- Fiscal-year figures come from the filings (FiledAnnualHistoryService); Yahoo's net profit
  is the cross-check, and a year they disagree on is a DATA_CONFLICT for that year only.
  Years are never mixed across sources.
- Ingested documents (annual reports, concall transcripts, investor presentations) are chunked,
  embedded and searched through searchCompanyDocuments, a plain @Tool like any other - never a
  QuestionAnswerAdvisor, which would inject retrieved text before ToolUsage can record it as
  evidence. Every chunk carries its symbol, document type, fiscal year and page; a search is
  filtered to one symbol, and every result's citation is Java-rendered into the answer, not left
  to the model to repeat. A chunk's figures are attributed to the document, never to Yahoo.
- The research orchestrator (ResearchOrchestrator) turns a multi-step goal into a ResearchPlan of
  steps from a fixed StepType enum; PlanValidator rejects an unknown or malformed step before
  anything runs. Java executes every step itself - the planner is never given tools and never
  calls a data provider. A SCREEN step always pauses for a human checkpoint (the shortlist, saved
  on the ResearchSession) until the conversation replies "continue"; OrchestratorBudget stops a
  plan that exceeds its step, model-call or wall-clock limit and returns a partial memo naming
  which one. The final memo (MemoWriter) goes through the same verifiers as every other answer,
  and never a recommendation. Saved screens (watchlist/) are re-run deterministically, by preset
  and universe, never re-extracted by a model, so entered/left is comparable across snapshots.
- The same verified tools are exposed over MCP (McpToolsConfiguration, POST /mcp) for any MCP client.
  The bean is a List<McpServerFeatures.SyncToolSpecification>, never a ToolCallbackProvider or
  ToolCallback bean: Spring AI's own ToolCallingAutoConfiguration sweeps every such bean into the
  resolver its default tool-calling manager uses, which pulled a tool's full dependency chain (down to
  LlmSymbolResolver's ChatClient.Builder) into the eager construction of ollamaChatModel - a bean
  ChatClient.Builder itself depends on, and a real circular-dependency startup failure the first way
  round was tried. spring.ai.mcp.server.protocol must be set explicitly to STREAMABLE: the webmvc
  autoconfiguration that registers the /mcp endpoint checks the literal property, not its own default.
- Run ./mvnw test before finishing. Use fixtures, never live Yahoo or NSE, in tests.
- Roadmap: docs/AGENT_ROADMAP.md
