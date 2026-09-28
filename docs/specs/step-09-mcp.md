# Step 9: MCP server

| ID | Type | Criterion |
| --- | --- | --- |
| S9-1 | CODE | The Spring AI MCP server starter (webmvc) is in `pom.xml`. Server name and version are configured. |
| S9-2 | CODE | Tools are exposed through a tool-callback provider bean: at least `getStockQuote`, `getCompanyProfile`, `getFinancialSummary`, `getHistoricalPerformance`, `getValuation`, `screenStocks`. |
| S9-3 | CODE | Tool calls made over MCP still return the same verified, rendered text, and still behave safely when no `ToolUsage` is present in the context. |
| S9-4 | CODE | A test loads the context and asserts the tool list the MCP server exposes. |
| S9-L1 | LIVE | An MCP client (Claude Code `claude mcp add`, or MCP Inspector) connects, lists the tools, and gets a result from `getStockQuote`. |
