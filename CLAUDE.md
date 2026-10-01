# stock-research-ai: rules for coding agents
- Java 21, Spring Boot 4.1, Spring AI 2.0, Ollama qwen3:8b local.
- The LLM never calculates, converts units, or judges a stock. All maths lives in
  FinancialCalculator and is verified by CalculationVerifier. Pass/fail judgements
  live only in StockScreener, against explicit configured criteria, reading the
  fundamentals snapshot (never a provider). No other code or prompt judges a stock.
- A screen asked for in words goes through ScreenerAgent: the model only extracts criteria
  and summarises the result. CriteriaValidator checks every metric, bound and threshold
  against the user's own words, and the answer shows every assumption.
- Every model answer about a screen also goes through ScreeningCountVerifier (AnswerChecks).
- Every number is a FinancialDataPoint with unit, period, source and DataStatus.
  Only VALID values are used; non-VALID never passes a screening rule.
- Every model answer that states figures goes through NumericClaimVerifier and
  UnsupportedClaimFilter.
- No buy/sell/hold, price targets, or fair values anywhere in prompts or output.
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
- Run ./mvnw test before finishing. Use fixtures, never live Yahoo or NSE, in tests.
- Roadmap: docs/AGENT_ROADMAP.md
