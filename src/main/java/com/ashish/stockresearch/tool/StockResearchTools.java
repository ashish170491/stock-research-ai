package com.ashish.stockresearch.tool;

import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.model.CompanyProfileResult;
import com.ashish.stockresearch.research.model.FinancialSummaryResult;
import com.ashish.stockresearch.research.model.HistoricalPerformanceResult;
import com.ashish.stockresearch.research.model.ShareholdingResult;
import com.ashish.stockresearch.research.model.ValuationResult;
import com.ashish.stockresearch.research.report.ResearchReportRenderer;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import com.ashish.stockresearch.research.sector.SectorContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * The fundamental-research half of the toolkit, alongside
 * {@link StockPriceTool}. Each method is a thin adapter: log the call,
 * delegate to {@link StockResearchService}, and hand the model the result
 * rendered as compact Markdown by {@link ResearchReportRenderer}.
 *
 * Why text rather than the result records as JSON: the full JSON for one
 * financial summary is ~47 KB. Several of those overflow the model's context,
 * and the model then silently loses the earliest results - a "fundamental
 * overview" answered from the company profile alone. The rendered tables
 * carry the same values, periods, statuses and sources at a fraction of the
 * size. The structured records remain the source of truth (see
 * {@code GET /api/research/report/data}).
 *
 * The descriptions below are load-bearing. They are the only thing the
 * model sees when deciding which tool to call, so each states what the tool
 * returns, when to reach for it, what it does NOT return, and what the
 * symbol argument accepts.
 *
 * Broad requests ("fundamental overview of X") never reach these tools: the
 * request router sends them straight to the Java-built research report. These
 * tools answer narrower questions.
 *
 * Every method returns a result rather than throwing, so one failing lookup
 * never aborts the others, and records what it returned on the request's
 * {@link ToolUsage}.
 */
@Component
public class StockResearchTools {

    private static final Logger log = LoggerFactory.getLogger(StockResearchTools.class);

    static final String PROFILE_TOOL = "getCompanyProfile";
    static final String FINANCIALS_TOOL = "getFinancialSummary";
    static final String HISTORY_TOOL = "getHistoricalPerformance";
    static final String SHAREHOLDING_TOOL = "getShareholding";
    static final String VALUATION_TOOL = "getValuation";

    private final StockResearchService stockResearchService;
    private final ResearchReportRenderer renderer;
    private final SectorClassifier sectorClassifier;

    public StockResearchTools(StockResearchService stockResearchService,
                              ResearchReportRenderer renderer,
                              SectorClassifier sectorClassifier) {
        this.stockResearchService = stockResearchService;
        this.renderer = renderer;
        this.sectorClassifier = sectorClassifier;
    }

    @Tool(name = PROFILE_TOOL, description = """
            Get a company's business profile: what it does, sector and industry, the application's industry \
            group with the metrics that matter for it, business summary, employee headcount and market \
            capitalisation. \
            Use this for qualitative questions about the company as a business - "what does X do", "what sector \
            is X in", "how big is X". \
            A company profile alone is NOT a fundamental overview or analysis: it returns no revenue, profit, \
            growth, margins, returns, leverage, share-price history or shareholding - use getFinancialSummary, \
            getHistoricalPerformance and getShareholding for those. \
            Accepts an NSE/BSE trading symbol (e.g. TCS, RELIANCE) or a company name \
            (e.g. "Tata Consultancy Services"); the name is resolved to the correct exchange symbol.""",
            resultConverter = PlainTextResultConverter.class)
    public String getCompanyProfile(
            @ToolParam(description = "Indian stock trading symbol (e.g. TCS, INFY) or company name "
                    + "(e.g. 'Tata Consultancy Services')")
            String symbol,
            ToolContext toolContext) {
        log.info("TOOL CALLED: getCompanyProfile symbol={}", symbol);
        CompanyProfileResult result = stockResearchService.getCompanyProfile(symbol);
        SectorContext sector = result.success()
                ? sectorClassifier.classify(result.companyProfile().sector(), result.companyProfile().industry())
                : SectorContext.of(IndustryGroup.UNKNOWN, "The company profile could not be retrieved");
        return recorded(toolContext, PROFILE_TOOL, symbol, result.status().name(),
                renderer.renderCompanyProfileTool(result, sector));
    }

    @Tool(name = FINANCIALS_TOOL, description = """
            Get a company's financials: headline TTM revenue, net profit, margins, returns, debt and cash; \
            recent reported quarters with period-end and results-publication dates; fiscal-year statements; \
            and YoY growth, CAGR, margins, ROE, ROA, ROCE, debt-to-equity and operating cash flow to net profit \
            calculated and verified by the application. \
            Normally needed for any fundamental overview or analysis, and for questions about financial \
            performance, profitability, margins, growth, leverage or balance sheet. \
            Every value has a period, status and source. Status VALID values may be quoted exactly as displayed. \
            DATA_CONFLICT, INVALID and UNAVAILABLE values are not usable: report the status and reason, and draw \
            no observation, trend or conclusion from them. Never recalculate a CALCULATED value, never compute \
            a new figure from these values, and never convert units or currency. Use period labels exactly as \
            given. The figures describe past reporting periods, not live data. Their source is Yahoo Finance. \
            Accepts an NSE/BSE trading symbol (e.g. TCS, RELIANCE) or a company name.""",
            resultConverter = PlainTextResultConverter.class)
    public String getFinancialSummary(
            @ToolParam(description = "Indian stock trading symbol (e.g. TCS, INFY) or company name "
                    + "(e.g. 'Tata Consultancy Services')")
            String symbol,
            ToolContext toolContext) {
        log.info("TOOL CALLED: getFinancialSummary symbol={}", symbol);
        FinancialSummaryResult result = stockResearchService.getFinancialSummary(symbol);
        return recorded(toolContext, FINANCIALS_TOOL, symbol, result.status().name(),
                renderer.renderFinancialSummaryTool(result));
    }

    @Tool(name = VALUATION_TOOL, description = """
            Get a stock's valuation multiples at one point in time: trailing P/E, price-to-book, dividend yield, \
            earnings yield (calculated by the application as 100 / P/E) and P/E on filed EPS (the share price over \
            the latest fiscal year's basic EPS as the company filed it with NSE, calculated by the application), \
            with the share price, EPS, book value and dividend per share they are measured against. The two P/Es \
            are over different periods (trailing twelve months, and a fiscal year): never present one as the other. \
            Use it for questions about P/E, price-to-book, \
            dividend yield, earnings yield or valuation multiples. \
            The values are POINT-IN-TIME: each is dated to the share price it was measured against and changes \
            with the price, so state the date and never present them as current once time has passed. \
            This tool gives NO fair value, intrinsic value, price target, or buy/sell/hold recommendation, and \
            says nothing about whether a multiple is high or low: no peer, sector or historical benchmark is \
            supplied, so make no such comparison. It returns no financial statements or growth - use \
            getFinancialSummary for those - and no live quote - use getStockQuote. \
            Each multiple was checked against the price and per-share figure it is quoted against; a \
            DATA_CONFLICT or UNAVAILABLE value is not usable: report its status and reason. Quote values exactly \
            as displayed; never recalculate them or compute a new figure from them. Their source is Yahoo Finance. \
            Accepts an NSE/BSE trading symbol (e.g. TCS, RELIANCE) or a company name.""",
            resultConverter = PlainTextResultConverter.class)
    public String getValuation(
            @ToolParam(description = "Indian stock trading symbol (e.g. TCS, INFY) or company name "
                    + "(e.g. 'Tata Consultancy Services')")
            String symbol,
            ToolContext toolContext) {
        log.info("TOOL CALLED: getValuation symbol={}", symbol);
        ValuationResult result = stockResearchService.getValuation(symbol);
        return recorded(toolContext, VALUATION_TOOL, symbol, result.status().name(),
                renderer.renderValuationTool(result));
    }

    @Tool(name = SHAREHOLDING_TOOL, description = """
            Get a company's shareholding pattern: the percentage held by the promoter group, foreign \
            institutional investors (FII), domestic institutional investors (DII) and the public. \
            Consider it for any fundamental overview or analysis, and use it for any question about who owns \
            the company, promoter stake, promoter pledging or institutional ownership. \
            Call it even though its data source may be unavailable: the result then states the capability gap, \
            which you must report - the ownership split is UNKNOWN. Never fill the gap with zero, an estimate, \
            or a figure you recall. \
            Accepts an NSE/BSE trading symbol (e.g. TCS, RELIANCE) or a company name.""",
            resultConverter = PlainTextResultConverter.class)
    public String getShareholding(
            @ToolParam(description = "Indian stock trading symbol (e.g. TCS, INFY) or company name "
                    + "(e.g. 'Tata Consultancy Services')")
            String symbol,
            ToolContext toolContext) {
        log.info("TOOL CALLED: getShareholding symbol={}", symbol);
        ShareholdingResult result = stockResearchService.getShareholding(symbol);
        return recorded(toolContext, SHAREHOLDING_TOOL, symbol, result.status().name(),
                renderer.renderShareholdingTool(result));
    }

    @Tool(name = HISTORY_TOOL, description = """
            Get a stock's past share-price performance over a multi-year window: start and end price, total \
            return, CAGR, period high and low, maximum drawdown, and year-by-year returns. \
            Normally needed when analysing a stock or giving a fundamental overview, and for questions about how \
            a stock has performed over time - "how has X done over five years", "long-term returns". \
            This is HISTORY: endPrice is the last close in the window, not the live price - use getStockQuote \
            for the current price. Returns, CAGR and drawdown are CALCULATED and verified by the application - \
            quote them as given and never recalculate them. A yearToDate calendar-year return is partial. \
            Accepts an NSE/BSE trading symbol (e.g. TCS, RELIANCE) or a company name.""",
            resultConverter = PlainTextResultConverter.class)
    public String getHistoricalPerformance(
            @ToolParam(description = "Indian stock trading symbol (e.g. TCS, INFY) or company name "
                    + "(e.g. 'Tata Consultancy Services')")
            String symbol,
            @ToolParam(description = "Lookback window in years (e.g. 5 for five-year performance). "
                    + "Defaults to 5 when omitted.", required = false)
            Integer years,
            ToolContext toolContext) {
        log.info("TOOL CALLED: getHistoricalPerformance symbol={} years={}", symbol, years);
        HistoricalPerformanceResult result = stockResearchService.getHistoricalPerformance(symbol, years);
        return recorded(toolContext, HISTORY_TOOL, symbol, result.status().name(),
                renderer.renderHistoricalPerformanceTool(result));
    }

    private static String recorded(ToolContext context, String tool, String symbol, String status, String text) {
        ToolUsage.record(context, new ToolUsage.Call(tool, symbol, status, text));
        return text;
    }
}
