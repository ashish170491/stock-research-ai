# stock-research-ai: rules for coding agents
- Java 21, Spring Boot 4.1, Spring AI 2.0, Ollama qwen3:8b local.
- The LLM never calculates, converts units, or judges a stock. All maths lives in
  FinancialCalculator and is verified by CalculationVerifier. Pass/fail judgements
  will live in StockScreener against explicit criteria (roadmap Step 3; not built
  yet). Until then, no code or prompt judges a stock.
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
  (YahooRequestThrottle). Never cache a price inside a long-lived entry.
- Run ./mvnw test before finishing. Use fixtures, never live Yahoo, in tests.
- Roadmap: docs/AGENT_ROADMAP.md
