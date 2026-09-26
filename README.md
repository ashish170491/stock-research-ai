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
| `getFinancialSummary` | Revenue, profit, margins, ROE, debt, cash flow, growth | Yahoo Finance `financialData` |
| `getHistoricalPerformance` | Total return, CAGR, drawdown, yearly returns | Yahoo Finance chart API |
| `getShareholding` | Promoter / FII / DII / public split | **None - reports itself unavailable** |

`getShareholding` deliberately has no implementation. The only holder data our
provider exposes is a US-style "insiders / institutions" breakdown with no filing
date, which is not the SEBI shareholding pattern and cannot be honestly relabelled
as one. The tool reports the capability as unavailable so the model says the
figures are unknown rather than inventing them. See
`UnsupportedShareholdingProvider` for the full reasoning.

### Watching tool selection

Every tool logs its own invocation, so you can see exactly what the model chose:

```
TOOL CALLED: getCompanyProfile symbol=TCS
TOOL CALLED: getFinancialSummary symbol=TCS
```

### Data-quality rules

Every result carries its source, freshness (real-time / delayed / filing /
historical) and as-of date, and results are returned as structured JSON rather
than prose. A missing metric is `null` and is named in `unavailableMetrics` -
it is never silently replaced with zero. Read the `currency` field rather than
assuming rupees: Yahoo reports some Indian companies' accounts in USD.

## Running the tests

```bash
./mvnw test
```
