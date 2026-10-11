package com.ashish.stockresearch.focus;

import com.ashish.stockresearch.marketdata.NseSymbolDirectory;
import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.HistoricalPerformance;
import com.ashish.stockresearch.research.model.HistoricalPerformanceResult;
import com.ashish.stockresearch.service.ResearchReportWriter;
import com.ashish.stockresearch.trace.Progress;
import com.ashish.stockresearch.verdict.Verdict;
import com.ashish.stockresearch.verdict.VerdictRenderer;
import com.ashish.stockresearch.verdict.VerdictService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Answers only the topics the user chose about a company, rendered in Java from the same figures and rules as the
 * rating: no model writes any of it, except the full report when that is chosen.
 */
@Service
public class FocusService {

    private final VerdictService verdicts;
    private final VerdictRenderer renderer;
    private final StockResearchService research;
    private final ResearchReportWriter reportWriter;
    private final NseSymbolDirectory directory;
    private final com.ashish.stockresearch.context.RatioContextService context;

    public FocusService(VerdictService verdicts, VerdictRenderer renderer, StockResearchService research,
                        ResearchReportWriter reportWriter, NseSymbolDirectory directory,
                        com.ashish.stockresearch.context.RatioContextService context) {
        this.verdicts = verdicts;
        this.renderer = renderer;
        this.research = research;
        this.reportWriter = reportWriter;
        this.directory = directory;
        this.context = context;
    }

    public String answer(String company, List<Topic> topics) {
        if (topics == null || topics.isEmpty()) {
            throw new IllegalArgumentException("choose at least one topic");
        }
        String symbol = directory.resolve(company).map(NseSymbolDirectory.Listing::symbol).orElse(company);
        StringBuilder md = new StringBuilder();
        boolean needsFigures = topics.stream().anyMatch(topic -> topic == Topic.RATING || topic.question() != null);
        Verdict verdict = null;
        if (needsFigures) {
            VerdictService.Lookup lookup = Progress.step("Gathering %s's figures (company filings, Yahoo Finance)"
                    .formatted(company), () -> verdicts.lookup(symbol));
            verdict = lookup.verdict();
            md.append(verdict != null ? VerdictRenderer.title(verdict) : "# " + company).append("\n\n");
            if (verdict == null) {
                md.append(lookup.failure()).append("\n\n");
            }
        } else {
            md.append("# ").append(company).append("\n\n");
        }
        for (Topic topic : topics) {
            if (topic == Topic.RATING && verdict != null) {
                md.append(renderer.rating(verdict)).append("\n");
            } else if (topic.question() != null && verdict != null) {
                md.append(renderer.question(verdict, topic.question()));
                md.append(peerLines(verdict, topic)).append("\n");
            } else if (topic == Topic.PERFORMANCE) {
                md.append(performance(company, symbol)).append("\n");
            }
        }
        if (topics.contains(Topic.FULL_REPORT)) {
            String report = Progress.step("Building the full report on %s".formatted(company),
                    () -> reportWriter.write(symbol));
            return topics.size() == 1 ? report : md + "\n" + report;
        }
        return md.toString().stripTrailing() + "\n";
    }

    /**
     * The same figures among the company's industry peers in the stored snapshot, so a beginner can see whether a
     * value is usual for this kind of company. Not for growth, whose span may differ from the snapshot's.
     */
    private String peerLines(Verdict verdict, Topic topic) {
        if (topic == Topic.GROWING) {
            return "";
        }
        List<String> lines = new java.util.ArrayList<>();
        for (com.ashish.stockresearch.screening.ScreeningMetric metric : VerdictRenderer.figuresFor(topic.question())) {
            context.peers(metric, verdict.group(), verdict.snapshot().symbol())
                    .map(com.ashish.stockresearch.context.ContextRenderer::peerLine).filter(line -> !line.isBlank())
                    .ifPresent(line -> lines.add("- %s: %s".formatted(capitalised(metric.description()), line)));
        }
        return lines.isEmpty() ? "" : "\nBeside other companies in its industry:\n" + String.join("\n", lines) + "\n";
    }

    private static String capitalised(String text) {
        return text == null || text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private String performance(String company, String symbol) {
        HistoricalPerformanceResult result = Progress.step("Fetching %s's share-price history (Yahoo Finance)"
                .formatted(company), () -> research.getHistoricalPerformance(symbol, 5));
        StringBuilder md = new StringBuilder("### ").append(Topic.PERFORMANCE.label()).append("\n\n");
        if (!result.success() || result.historicalPerformance() == null) {
            return md.append("The share-price history could not be retrieved right now.\n").toString();
        }
        HistoricalPerformance history = result.historicalPerformance();
        line(md, "Total return", history.totalReturnPercent(), true);
        line(md, "Yearly rate (CAGR)", history.cagrPercent(), false);
        line(md, "Biggest fall from a high (max drawdown)", history.maxDrawdownPercent(), false);
        line(md, "Highest close", history.periodHigh(), false);
        line(md, "Lowest close", history.periodLow(), false);
        md.append("\n_Past share-price moves do not say what the price will do next._\n");
        return md.toString();
    }

    private static void line(StringBuilder md, String label, FinancialDataPoint point, boolean withPeriod) {
        if (point == null || point.status() != DataStatus.VALID || point.display() == null) {
            md.append("- ").append(label).append(": not available\n");
            return;
        }
        md.append("- ").append(label).append(": **").append(point.display()).append("**");
        if (withPeriod && point.period() != null) {
            md.append(" (").append(point.period().label()).append(")");
        }
        md.append("\n");
    }
}
