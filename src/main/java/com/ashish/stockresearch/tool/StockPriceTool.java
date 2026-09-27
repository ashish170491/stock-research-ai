package com.ashish.stockresearch.tool;

import com.ashish.stockresearch.marketdata.StockMarketDataService;
import com.ashish.stockresearch.marketdata.model.StockQuoteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Exposes stock-quote retrieval as an AI tool. Contains no business logic or
 * HTTP calls of its own - it only adapts {@link StockMarketDataService} to
 * the Spring AI tool-calling contract.
 */
@Component
public class StockPriceTool {

    private static final Logger log = LoggerFactory.getLogger(StockPriceTool.class);

    private final StockMarketDataService stockMarketDataService;

    public StockPriceTool(StockMarketDataService stockMarketDataService) {
        this.stockMarketDataService = stockMarketDataService;
    }

    @Tool(description = "Get the latest available market quote for any Indian stock listed on NSE or BSE. "
            + "Use this whenever the user asks for a current, latest, or live stock price. "
            + "Not limited to a fixed list - any listed company is supported. "
            + "Note: some exchange-sounding names are themselves listed companies (e.g. 'BSE' is the ticker "
            + "for BSE Limited, the exchange operator, which trades on NSE) - always call this tool to check "
            + "rather than assuming a name cannot be a stock.")
    public StockQuoteResult getStockQuote(
            @ToolParam(description = "The Indian stock's trading symbol (e.g. RELIANCE, WIPRO) or company name "
                    + "(e.g. 'Reliance Industries', 'HDFC Bank') - it will be resolved to the correct exchange symbol")
            String symbol,
            ToolContext toolContext) {
        log.info("TOOL CALLED: getStockQuote symbol={}", symbol);
        StockQuoteResult result = stockMarketDataService.getQuote(symbol);
        ToolUsage.record(toolContext, new ToolUsage.Call("getStockQuote", symbol,
                result.success() ? "OK" : "ERROR", String.valueOf(result)));
        return result;
    }
}
