# Stock Research AI

## Prerequisites

- Java 21
- Maven (or use the included `./mvnw`)
- [Ollama](https://ollama.com) installed
- `qwen3:8b` model pulled (`ollama pull qwen3:8b`)

## How to start Ollama

```bash
ollama serve
```

(If you normally run `ollama run qwen3:8b` in a terminal, that already starts the server on `http://localhost:11434`.)

## How to start Spring Boot

```bash
./mvnw spring-boot:run
```

The app starts on `http://localhost:8080`. The model name is configurable via `spring.ai.ollama.chat.options.model` in `src/main/resources/application.yml`.

Note the `num-ctx: 16384` setting alongside it. Ollama's 4096-token default is not
enough to hold five tool schemas, their JSON results and the model's reasoning at
once; at the default, a question that calls several tools silently loses the
earliest results.

## How to test the endpoint

```bash
curl "http://localhost:8080/api/ai/chat?message=Explain%20Spring%20AI%20in%20two%20sentences"
```

If Ollama isn't running, the endpoint returns HTTP 503 with an explanatory message instead of a stack trace.

## Research tools

Every chat message is routed first (`agent.RequestRouter`), then answered by
the path its intent needs:

| Intent | Answered by |
| --- | --- |
| `FULL_RESEARCH` ("fundamental overview of X", "analyse X") | the verified report (`ResearchReportWriter`) |
| `QUOTE` ("price of X") | the quote service, rendered as text - no model |
| `COMPARE` ("compare X and Y") | one report per company, one after another |
| `SPECIFIC_QUESTION` | the model with the research tools below |
| `NOT_STOCK_RELATED` | the model without tools |

Keyword rules route the unambiguous shapes, and only when every company they
extract is in the NSE equity list; anything else goes to a tool-free model
call that returns a `RoutedRequest`. If that call fails or returns anything
unusable, the request is treated as a `SPECIFIC_QUESTION`.

For specific questions the model decides which tools to call - one, several,
or none - and Spring AI runs the tool-calling loop.

Tools hand the model compact Markdown tables (rendered by
`ResearchReportRenderer`), not the result records as JSON. One company's
financial summary as JSON was ~47 KB; several of those overflowed the context
and the model silently lost the earliest results - which is how "Fundamental
Overview of ICICI Bank" came back as a profile-only answer. The structured
records are still the source of truth: `GET /api/research/report/data`.

| Tool | Returns | Data source |
| --- | --- | --- |
| `getStockQuote` | Latest price, exchange, currency | Yahoo Finance chart API |
| `getCompanyProfile` | Sector, industry, business summary, employees, market cap | Yahoo Finance `assetProfile` |
| `getFinancialSummary` | TTM figures, dated quarters, fiscal years, calculated YoY / CAGR / margins / ROE / ROA | Yahoo Finance `financialData`, `earnings`, fundamentals time series |
| `getHistoricalPerformance` | Total return, CAGR, drawdown, yearly returns (calculated in Java) | Yahoo Finance chart API (daily) |
| `getShareholding` | Promoter / FII / DII / public split | **None - reports itself unavailable** |

`getShareholding` deliberately has no implementation. The only holder data our
provider exposes is a US-style "insiders / institutions" breakdown with no filing
date, which is not the SEBI shareholding pattern and cannot be honestly relabelled
as one. The tool reports the capability as unavailable so the model says the
figures are unknown rather than inventing them. See
`UnsupportedShareholdingProvider` for the full reasoning.

### Answer checks

A tool-loop answer is screened against what the tools returned before it is
sent. A statement with a figure that does not appear in the tool output
(mistyped, recalculated, converted, invented) is removed
(`NumericClaimVerifier`), as are unsupported labels, causal claims and source
upgrades (`UnsupportedClaimFilter`). The answer says what was removed.

### Symbol resolution

Company names and symbols are resolved in this order, and every candidate is
confirmed with a real chart lookup:

1. `NseSymbolDirectory` - NSE's own equity list, bundled at
   `src/main/resources/nse/equity-list.csv` (a snapshot of NSE's
   `EQUITY_L.csv`): exact symbol, then normalised name, then the best word
   overlap at or above 0.5 (a tie resolves to nothing). Offline and
   deterministic: "Bharti Airtel" -> BHARTIARTL, "Infosys" -> INFY.
2. The input as an NSE, then BSE, symbol.
3. Yahoo Finance search.
4. The model's guess (`LlmSymbolResolver`), with any `<think>` block removed.

Refresh the list by replacing the CSV with the first two columns of a newer
`EQUITY_L.csv`.

### Watching the dataflow

The app traces how a question moves through the model and the tools. One
question produces one block:

```
==============================================================================
[1ec22d] USER > Give me a fundamental overview of TCS.
[1ec22d] round 1 > model requested 2 tool call(s)
[1ec22d]   --> getCompanyProfile({"symbol":"TCS"})
[1ec22d]   --> getFinancialSummary({"symbol":"TCS"})
TOOL CALLED: getCompanyProfile symbol=TCS
TOOL CALLED: getFinancialSummary symbol=TCS
[1ec22d] round 1 < tool results in 0.81s
[1ec22d]   <-- getCompanyProfile [2678 chars] {"success":true,"status":"OK","companyProfile":{"symbol":"TCS",...
[1ec22d]   <-- getFinancialSummary [1517 chars] {"success":true,"status":"OK","financialSummary":{...,"returnOnCapitalEmployedPercent":null,...
[1ec22d] ANSWER > Here's a fundamental overview of **Tata Consultancy Services (TCS)**: ...
[1ec22d] DONE in 62.96s | 2 model round(s) | 2 tool call(s) | tokens: prompt=4062 completion=1177
==============================================================================
```

The `-->` lines are what the model decided to ask for and with which arguments;
the `<--` lines are the exact JSON it then reads. When an answer is wrong, those
two together tell you whether the data was bad or the model misread good data -
the `returnOnCapitalEmployedPercent: null` above is visibly going in, so an
answer that quotes a ROCE figure is the model inventing one, not a bad provider.

The token counts are worth watching too: the prompt above is already past
Ollama's 4096-token default, which is why `num-ctx` is raised.

For the full picture - untruncated payloads, the model's reasoning between
rounds, and the entire prompt - raise the level:

```yaml
logging:
  level:
    com.ashish.stockresearch.trace: DEBUG
```

Tune `app.trace.max-payload-chars` to change how much JSON appears at INFO, or
set `app.trace.enabled: false` to remove the tracing entirely. The tracer only
decorates Spring AI's `ToolCallingManager`, so behaviour is identical either way.

### Data-quality rules

The model never converts units, works out reporting periods, or calculates
anything:

- Every number is a `FinancialDataPoint`: value, canonical `Unit`
  (`INR_CRORE`, `PERCENT`, ...), `ReportingPeriod` (with a Java-computed label
  such as `Q1 FY27`), `SourceInfo` (source, type, field, published / as-of
  dates) and origin (`REPORTED` or `CALCULATED`, with the formula).
- Providers normalise raw rupees to crore (`FinancialUnits`) before anything
  reaches the model, and each point carries a ready-made `display` string.
- Periods come only from provider dates (`FiscalCalendar`); a period ending
  after today, or published before it ended, is rejected
  (`FinancialDataValidator`). An unstated period is `UNKNOWN`.
- YoY, CAGR, margins, ROE, ROA, drawdown and annual returns are calculated in
  `FinancialCalculator`, via `FinancialMetricsService` and
  `HistoricalPerformanceService`.
- Every point has a `DataStatus`: `VALID`, `UNAVAILABLE`, `DATA_CONFLICT` or
  `INVALID`. Only VALID values are used in a calculation, an observation or an
  interpretation. Unknown is never zero and never an estimate.
- Cross-checks run before anything is calculated: TTM vs the sum of its four
  quarters, TTM vs the latest fiscal year, each fiscal year vs Yahoo's second
  annual feed, provider ratios vs the application's own figures, and market cap
  vs price x shares. A disagreement is a `DATA_CONFLICT`; every value depending
  on those fields (including Yahoo ratios derived from its revenue) is marked,
  and no metric is calculated from it. Nothing picks one side.
- Every calculated value records its formula and the exact input values used.
  `CalculationVerifier` recomputes it independently and checks the inputs
  against the source values; one that does not reproduce is `INVALID` and
  never shown. The report ends with a calculation audit.
- Currencies are read per feed (headline, quarterly charts, each time-series
  value) and recorded on every amount. Amounts are scaled within a currency and
  never converted; figures in different currencies are never combined.
- Industry groups (BANK, NBFC, INSURANCE, IT_SERVICES, PHARMA, MANUFACTURING,
  CONSUMER, OTHER, UNKNOWN) say which metrics are primary. For a bank, EBITDA,
  free cash flow, operating margin and debt-to-equity are shown as facts but
  never turned into observations.

### Research reports

`GET /api/research/report?symbol=HDFC Bank` (and a chat request routed as
`FULL_RESEARCH`) returns a report with FACTS, OBSERVATIONS, RISKS / DATA GAPS,
INTERPRETATION and SOURCES, plus a calculation audit. Everything except
INTERPRETATION is built and rendered in Java from a `StockResearchReport`.
Observations whose values are in a DATA_CONFLICT, INVALID or not primary for
the sector are listed as not generated, and the INTERPRETATION section states
which conclusions were withheld. The interpretation comes from a tool-free
model call that sees only the evidence; sentences with figures not in the
evidence, unsupported claims, or conclusions about withheld topics are removed. `GET /api/research/report/data`
returns the structured report with no model involved.

## Running the tests

```bash
./mvnw test
```
