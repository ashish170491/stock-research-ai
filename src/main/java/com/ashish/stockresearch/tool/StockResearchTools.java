package com.ashish.stockresearch.tool;

import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.model.CompanyProfileResult;
import com.ashish.stockresearch.research.model.FinancialSummaryResult;
import com.ashish.stockresearch.research.model.HistoricalPerformanceResult;
import com.ashish.stockresearch.research.model.ShareholdingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The fundamental-research half of the toolkit, alongside
 * {@link StockPriceTool}. Each method is a thin adapter: log the call,
 * delegate to {@link StockResearchService}, return the structured result.
 * No HTTP, no parsing, no formatting, and deliberately no prose - the
 * records go to the model as JSON so it can reason over the fields rather
 * than re-read a sentence we already wrote.
 *
 * The descriptions below are load-bearing. They are the only thing the
 * model sees when deciding which tool to call, so each states what the tool
 * returns, when to reach for it, and what the symbol argument accepts.
 *
 * Every method returns a result object rather than throwing, so one failing
 * lookup never aborts the others when the model calls several tools for a
 * single question.
 */
@Component
public class StockResearchTools {

    private static final Logger log = LoggerFactory.getLogger(StockResearchTools.class);

    private final StockResearchService stockResearchService;

    public StockResearchTools(StockResearchService stockResearchService) {
        this.stockResearchService = stockResearchService;
    }

    @Tool(description = """
            Get a company's business profile: what it actually does, its sector and industry, \
            business summary, country, website, employee headcount and market capitalisation. \
            Use this for qualitative questions about the company as a business - "tell me about X", \
            "what does X do", "what sector is X in", "how big is X". \
            Returns no revenue, profit or valuation ratios - use getFinancialSummary for those, \
            and getStockQuote for the current share price. \
            Accepts an NSE/BSE trading symbol (e.g. TCS, RELIANCE) or a company name \
            (e.g. "Tata Consultancy Services"); the name is resolved to the correct exchange symbol.""")
    public CompanyProfileResult getCompanyProfile(
            @ToolParam(description = "Indian stock trading symbol (e.g. TCS, INFY) or company name "
                    + "(e.g. 'Tata Consultancy Services')")
            String symbol) {
        log.info("TOOL CALLED: getCompanyProfile symbol={}", symbol);
        return stockResearchService.getCompanyProfile(symbol);
    }

    @Tool(description = """
            Get a company's headline financial figures: revenue, net profit, EBITDA, operating and \
            net margins, return on equity, return on assets, total debt, debt-to-equity, cash, \
            free cash flow, and revenue and earnings growth. \
            Use this for questions about financial or fundamental performance, profitability, \
            margins, growth, leverage or balance-sheet strength. \
            These are trailing-twelve-month figures from past company filings, NOT live data - the \
            reportingPeriod and provenance fields state the period they cover, and you must not \
            describe them as current or as today's numbers. \
            Read the currency field: it is the currency of the accounts and is not always INR. \
            Absolute amounts are whole currency units, not crore or millions - divide by 10,000,000 \
            to express a figure in crore. \
            Any metric that is null is UNAVAILABLE, not zero; the unavailableMetrics field names \
            each one - report those as not available rather than guessing or omitting them. \
            Accepts an NSE/BSE trading symbol (e.g. TCS, RELIANCE) or a company name.""")
    public FinancialSummaryResult getFinancialSummary(
            @ToolParam(description = "Indian stock trading symbol (e.g. TCS, INFY) or company name "
                    + "(e.g. 'Tata Consultancy Services')")
            String symbol) {
        log.info("TOOL CALLED: getFinancialSummary symbol={}", symbol);
        return stockResearchService.getFinancialSummary(symbol);
    }

    @Tool(description = """
            Get a company's shareholding pattern: the percentage held by the promoter group, \
            foreign institutional investors (FII), domestic institutional investors (DII) and the public. \
            Use this for any question about who owns the company, promoter stake, promoter pledging, \
            or institutional ownership. \
            Call this tool even though its data source may be unavailable: when it is, the result \
            explains that the ownership split is UNKNOWN, which you must report as such. Never fill \
            the gap with zero, an estimate, or a figure you recall - shareholding percentages change \
            every quarter and a remembered number will be wrong. \
            Accepts an NSE/BSE trading symbol (e.g. TCS, RELIANCE) or a company name.""")
    public ShareholdingResult getShareholding(
            @ToolParam(description = "Indian stock trading symbol (e.g. TCS, INFY) or company name "
                    + "(e.g. 'Tata Consultancy Services')")
            String symbol) {
        log.info("TOOL CALLED: getShareholding symbol={}", symbol);
        return stockResearchService.getShareholding(symbol);
    }

    @Tool(description = """
            Get a stock's past share-price performance over a multi-year window: start and end price, \
            total return, CAGR, period high and low, maximum drawdown, and year-by-year returns. \
            Use this for questions about how a stock has performed or moved over time - "how has X done \
            over five years", "long-term returns", "has X been volatile". \
            This is HISTORY: endPrice is the last close in the window and is not the live price, so use \
            getStockQuote when the user wants the current price. \
            Prices are adjusted for splits and dividends; returns are calculated from that series. \
            Check actualYears - a recently listed company may have a shorter history than requested. \
            Accepts an NSE/BSE trading symbol (e.g. TCS, RELIANCE) or a company name.""")
    public HistoricalPerformanceResult getHistoricalPerformance(
            @ToolParam(description = "Indian stock trading symbol (e.g. TCS, INFY) or company name "
                    + "(e.g. 'Tata Consultancy Services')")
            String symbol,
            @ToolParam(description = "Lookback window in years (e.g. 5 for five-year performance). "
                    + "Defaults to 5 when omitted.", required = false)
            Integer years) {
        log.info("TOOL CALLED: getHistoricalPerformance symbol={} years={}", symbol, years);
        return stockResearchService.getHistoricalPerformance(symbol, years);
    }
}
