package com.ashish.stockresearch.tool;

import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.model.CompanyProfileResult;
import com.ashish.stockresearch.research.model.FinancialSummaryResult;
import com.ashish.stockresearch.research.model.HistoricalPerformanceResult;
import com.ashish.stockresearch.research.model.ShareholdingResult;
import com.ashish.stockresearch.service.ResearchReportWriter;
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
    private final ResearchReportWriter researchReportWriter;

    public StockResearchTools(StockResearchService stockResearchService,
                              ResearchReportWriter researchReportWriter) {
        this.stockResearchService = stockResearchService;
        this.researchReportWriter = researchReportWriter;
    }

    /**
     * The model decides to call this; it does not get to retype the result.
     * {@code returnDirect} sends the finished report - Java-rendered facts plus
     * a screened, evidence-only interpretation - straight back to the user,
     * because a small model transcribing tables drops sections, shifts periods
     * and adds claims of its own.
     */
    @Tool(description = """
            Produce a complete, verified research report on an Indian listed company: company profile \
            and market capitalisation, financial statements, reported quarters with their period-end and \
            publication dates, growth / CAGR / margin / ROE / ROA figures calculated by the application, \
            five-year share-price performance with calculated CAGR and maximum drawdown, shareholding \
            availability, observations, data gaps, an interpretation and sources. \
            Use this for broad requests - "analyse X", "research X", "give me a report on X" - instead of \
            calling the individual research tools. The report is returned to the user as-is. \
            Accepts an NSE/BSE trading symbol (e.g. HDFCBANK) or a company name (e.g. "HDFC Bank").""",
            returnDirect = true, resultConverter = PlainTextResultConverter.class)
    public String getStockResearchReport(
            @ToolParam(description = "Indian stock trading symbol (e.g. HDFCBANK, TCS) or company name "
                    + "(e.g. 'HDFC Bank')")
            String symbol) {
        log.info("TOOL CALLED: getStockResearchReport symbol={}", symbol);
        return researchReportWriter.write(symbol);
    }

    @Tool(description = """
            Get a company's business profile: what it actually does, its sector and industry, \
            business summary, country, website, employee headcount and market capitalisation. \
            Market cap is already normalised to crore with an as-of date - quote its 'display' field. \
            The business summary is the only basis for qualitative claims about the company. \
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
            Get a company's financials: headline TTM revenue, net profit, margins, returns, debt and cash; \
            recent reported quarters with period-end and results-publication dates; fiscal-year statements; \
            and YoY growth, CAGR, margins, ROE and ROA calculated by the application. \
            Use this for questions about financial or fundamental performance, profitability, \
            margins, growth, leverage or balance-sheet strength. \
            Every number is a data point with a unit, a period (with a fiscal label computed by the application), \
            a source and an origin (REPORTED by the source or CALCULATED by the application). \
            Amounts are already normalised to crore - quote the 'display' field; never convert units and \
            never recalculate a CALCULATED value. Use period labels exactly as given; \
            latestReportedQuarter is the latest quarter with results - never describe a later quarter as reported. \
            These figures describe past reporting periods, NOT live data. Their source is Yahoo Finance. \
            A data point with availability UNAVAILABLE is unknown, not zero - report it as not available \
            using its unavailableReason. dataGaps and dataQualityWarnings must be reported. \
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
            Prices are daily closes adjusted for splits and dividends. Returns, CAGR, drawdown (with peak \
            and trough dates) and calendar-year returns are CALCULATED by the application - quote them as \
            given and never recalculate them. A yearToDate calendar-year return is partial. \
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
