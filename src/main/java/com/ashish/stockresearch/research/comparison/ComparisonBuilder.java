package com.ashish.stockresearch.research.comparison;

import com.ashish.stockresearch.context.PeerContext;
import com.ashish.stockresearch.context.ReportContext;
import com.ashish.stockresearch.glossary.GlossaryEntry;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.glossary.MetricQuestion;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Puts two to five companies' reports side by side on the same metrics and periods. Every figure is the one
 * already calculated and verified for that company's own report ({@link com.ashish.stockresearch.context.RatioContextService});
 * nothing is recalculated here. A cell is read against the row's reference - its first VALID cell's period and
 * currency - and anything that differs is marked NOT_COMPARABLE rather than shown as if it were.
 */
@Component
public class ComparisonBuilder {

    public static final int MIN_COMPANIES = 2;
    public static final int MAX_COMPANIES = 5;

    private final MetricGlossary glossary;

    public ComparisonBuilder(MetricGlossary glossary) {
        this.glossary = glossary;
    }

    /**
     * @param contexts each company that was built, with its ratio context (Step 13)
     * @param gaps     companies whose report could not be built at all
     */
    public ComparisonTable build(List<ReportContext> contexts, List<ComparisonTable.Gap> gaps) {
        int companies = contexts.size() + gaps.size();
        if (companies < MIN_COMPANIES || companies > MAX_COMPANIES) {
            throw new IllegalArgumentException("a comparison needs %d to %d companies, not %d"
                    .formatted(MIN_COMPANIES, MAX_COMPANIES, companies));
        }
        List<String> symbols = contexts.stream().map(ReportContext::symbol).toList();
        List<ComparisonTable.Row> rows = new ArrayList<>();
        for (String key : metricKeys(contexts)) {
            ComparisonTable.Row row = row(key, symbols, contexts);
            if (row != null) {
                rows.add(row);
            }
        }
        return new ComparisonTable(symbols, rows, gaps);
    }

    private ComparisonTable.Row row(String key, List<String> symbols, List<ReportContext> contexts) {
        List<ComparisonTable.Figure> figures = new ArrayList<>();
        List<IndustryGroup> groups = new ArrayList<>();
        List<ComparisonTable.Figure> medians = new ArrayList<>();
        for (ReportContext context : contexts) {
            Optional<ReportContext.Ratio> ratio = ratioOf(context, key);
            figures.add(figureOf(ratio));
            groups.add(context.group());
            medians.add(ratio.flatMap(ReportContext.Ratio::peers).map(PeerContext::median)
                    .map(ComparisonTable.Figure::of).orElse(null));
        }
        if (figures.stream().allMatch(Objects::isNull)) {
            return null;
        }
        String referenceLabel = figures.stream().filter(f -> f != null && f.status() == DataStatus.VALID)
                .map(ComparisonTable.Figure::periodLabel).filter(Objects::nonNull).findFirst().orElse(null);
        String referenceCurrency = figures.stream()
                .filter(f -> f != null && f.status() == DataStatus.VALID && f.currency() != null)
                .map(f -> f.currency().normalizedCurrency()).findFirst().orElse(null);
        List<ComparisonTable.Cell> cells = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i++) {
            cells.add(cell(symbols.get(i), figures.get(i), groups.get(i), medians.get(i), referenceLabel, referenceCurrency));
        }
        GlossaryEntry entry = glossary.forMetricKey(key).orElse(null);
        return new ComparisonTable.Row(key, glossary.label(key), entry == null ? null : entry.howToRead(),
                entry == null ? MetricQuestion.GENERAL : entry.question(), cells);
    }

    private static ComparisonTable.Cell cell(String symbol, ComparisonTable.Figure value, IndustryGroup group,
                                             ComparisonTable.Figure median, String referenceLabel, String referenceCurrency) {
        if (value == null) {
            return new ComparisonTable.Cell(symbol, null, false, "not available for this company", group, median);
        }
        if (value.status() != DataStatus.VALID) {
            return new ComparisonTable.Cell(symbol, value, true, null, group, median);
        }
        if (referenceLabel != null && value.periodLabel() != null && !value.periodLabel().equals(referenceLabel)) {
            return new ComparisonTable.Cell(symbol, value, false,
                    "different period (%s, not %s)".formatted(value.periodLabel(), referenceLabel), group, median);
        }
        if (referenceCurrency != null && value.currency() != null
                && !referenceCurrency.equals(value.currency().normalizedCurrency())) {
            return new ComparisonTable.Cell(symbol, value, false,
                    "different currency (%s, not %s)".formatted(value.currency().normalizedCurrency(), referenceCurrency),
                    group, median);
        }
        return new ComparisonTable.Cell(symbol, value, true, null, group, median);
    }

    /** The metrics shown: every key that appears in any company's context, in the order Step 13 takes them. */
    private static List<String> metricKeys(List<ReportContext> contexts) {
        Set<String> keys = new LinkedHashSet<>();
        for (ReportContext context : contexts) {
            context.ratios().forEach(ratio -> keys.add(ratio.metric()));
        }
        return List.copyOf(keys);
    }

    private static Optional<ReportContext.Ratio> ratioOf(ReportContext context, String key) {
        return context.ratios().stream().filter(ratio -> ratio.metric().equals(key)).findFirst();
    }

    /** The company's current value of the metric: the latest fiscal year when it has a yearly history, else
     * its own value in the peer snapshot. */
    private static ComparisonTable.Figure figureOf(Optional<ReportContext.Ratio> ratio) {
        return ratio.map(r -> r.history().map(h -> ComparisonTable.Figure.of(h.latest().point()))
                        .orElseGet(() -> r.peers().map(PeerContext::companyValue).map(ComparisonTable.Figure::of).orElse(null)))
                .orElse(null);
    }
}
