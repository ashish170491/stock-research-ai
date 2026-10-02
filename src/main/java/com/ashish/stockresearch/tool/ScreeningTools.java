package com.ashish.stockresearch.tool;

import com.ashish.stockresearch.screening.ScreeningOutcome;
import com.ashish.stockresearch.screening.ScreeningRenderer;
import com.ashish.stockresearch.screening.ScreeningService;
import com.ashish.stockresearch.screening.Universe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Screening for the chat model. The decision - which stock meets which criterion - is made by
 * {@code StockScreener} in Java; the model only asks for a screen and reports the table it gets back.
 */
@Component
public class ScreeningTools {

    private static final Logger log = LoggerFactory.getLogger(ScreeningTools.class);

    static final String SCREEN_TOOL = "screenStocks";

    private final ScreeningService screeningService;
    private final ScreeningRenderer renderer;

    public ScreeningTools(ScreeningService screeningService, ScreeningRenderer renderer) {
        this.screeningService = screeningService;
        this.renderer = renderer;
    }

    @Tool(name = SCREEN_TOOL, description = """
            Screen an index of Indian stocks against a named preset of criteria, using the application's \
            nightly fundamentals snapshot. Returns a ranked table: for every stock, whether it meets each \
            criterion (PASS), does not (FAIL), or cannot be assessed because the value is missing, disputed or \
            unchecked (INSUFFICIENT_DATA), with the value each result was decided on and the snapshot date. \
            Criteria differ by industry group: banks and NBFCs have their own, and are never screened on \
            debt-to-equity, operating margin, ROCE or cash-flow ratios; insurers and other financial companies \
            are not screened by these presets. \
            Presets: quality-compounder, reasonable-value, dividend. Universe: NIFTY50 only. \
            Use this for "which stocks meet...", "screen the Nifty 50", "which stocks meet the dividend \
            criteria". \
            It does NOT return live prices or live data (figures are as of the snapshot), full financials for a \
            company (use getFinancialSummary or getValuation), a recommendation, rating, score, fair value or \
            price target, and meeting every criterion does not make a stock a good investment. It does not \
            accept custom thresholds, and does NOT screen the Nifty 500 (its table is too large to return here; \
            the user can ask for a Nifty 500 screen in words). \
            Quote the table's results and figures exactly as given; never recalculate them, re-rank the \
            stocks, or apply thresholds of your own.""",
            resultConverter = PlainTextResultConverter.class)
    public String screenStocks(
            @ToolParam(description = "Preset name: quality-compounder, reasonable-value or dividend")
            String preset,
            @ToolParam(description = "Index universe; only NIFTY50 is available here. Defaults to NIFTY50.",
                    required = false)
            String universe,
            @ToolParam(description = "Optional industry group to limit the screen to: BANK, NBFC, INSURANCE, "
                    + "FINANCIAL_OTHER, IT_SERVICES, PHARMA, MANUFACTURING, CONSUMER or OTHER. Leave empty for all.",
                    required = false)
            String industryGroup,
            ToolContext toolContext) {
        log.info("TOOL CALLED: screenStocks preset={} universe={} industryGroup={}", preset, universe, industryGroup);
        String text;
        String status;
        if (Universe.parse(universe).filter(named -> named != Universe.NIFTY50).isPresent()) {
            // A Nifty 500 table would fill the model's context; a screen asked for in words covers it instead.
            status = ScreeningOutcome.Status.UNKNOWN_UNIVERSE.name();
            text = ("TOOL RESULT: screenStocks - status %s\nNo screen: this tool screens the Nifty 50 only. The %s is "
                    + "screened when the user asks for the screen in words, for example \"Which Nifty 500 stocks meet "
                    + "the dividend criteria?\"\n").formatted(status, Universe.parse(universe).get().displayName());
        } else {
            ScreeningOutcome outcome = screeningService.screen(preset, universe, industryGroup);
            status = outcome.status().name();
            text = renderer.render(outcome);
        }
        // No company symbol: a screen is about a universe, so it names no company for follow-up questions.
        ToolUsage.record(toolContext, new ToolUsage.Call(SCREEN_TOOL, null, status, text));
        return text;
    }
}
