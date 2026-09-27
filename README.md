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

The assistant decides for itself which of these to call for a given question -
one, several, or none. There is no hard-coded workflow; Spring AI runs the
tool-calling loop and the model does the routing.

| Tool | Returns | Data source |
| --- | --- | --- |
| `getStockQuote` | Latest price, exchange, currency | Yahoo Finance chart API |
| `getCompanyProfile` | Sector, industry, business summary, employees, market cap | Yahoo Finance `assetProfile` |
| `getFinancialSummary` | TTM figures, dated quarters, fiscal years, calculated YoY / CAGR / margins / ROE / ROA | Yahoo Finance `financialData`, `earnings`, fundamentals time series |
| `getHistoricalPerformance` | Total return, CAGR, drawdown, yearly returns (calculated in Java) | Yahoo Finance chart API (daily) |
| `getStockResearchReport` | Full report, returned to the user as-is (see below) | All of the above |
| `getShareholding` | Promoter / FII / DII / public split | **None - reports itself unavailable** |

`getShareholding` deliberately has no implementation. The only holder data our
provider exposes is a US-style "insiders / institutions" breakdown with no filing
date, which is not the SEBI shareholding pattern and cannot be honestly relabelled
as one. The tool reports the capability as unavailable so the model says the
figures are unknown rather than inventing them. See
`UnsupportedShareholdingProvider` for the full reasoning.

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
- Unknown is its own state: an `UNAVAILABLE` point cannot hold a value and must
  give a reason. It is never zero and never an estimate.
- Cross-checks flag problems instead of hiding them: market cap vs price x
  shares (catches 10x unit errors), and revenue fields that disagree.

### Research reports

`GET /api/research/report?symbol=HDFC Bank` (and the `getStockResearchReport`
chat tool) returns a report with FACTS, OBSERVATIONS, RISKS / DATA GAPS,
INTERPRETATION and SOURCES. Everything except INTERPRETATION is built and
rendered in Java from a `StockResearchReport`. The interpretation comes from a
tool-free model call that sees only the evidence, and any sentence making an
unsupported market-position claim is removed. `GET /api/research/report/data`
returns the structured report with no model involved.

## Running the tests

```bash
./mvnw test
```
