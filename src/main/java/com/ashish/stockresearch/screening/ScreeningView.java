package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.ScreeningResult.RuleResult;
import com.ashish.stockresearch.screening.ScreeningResult.StockScreening;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A screen shaped for the web page: the same decisions and figures as the Markdown the model reads,
 * with the counts, cells and reasons already worked out so the page only lays them out.
 *
 * @param disclaimer shown with every result
 */
public record ScreeningView(
        String status,
        String message,
        String preset,
        String presetDescription,
        String universe,
        String universeName,
        SnapshotInfo snapshot,
        Summary summary,
        String ranking,
        List<Section> sections,
        List<ScreeningResult.NotScreened> notScreened,
        List<SnapshotRun.Failure> notInSnapshot,
        long elapsedMillis,
        String disclaimer
) {

    public record SnapshotInfo(long runId, LocalDate date, Instant finishedAt, int stored, int requested) {
    }

    public record Stock(String symbol, String company, IndustryGroup group) {
    }

    /** Counted here, like the tool's summary. */
    public record Summary(int screened, List<Stock> meetEveryCriterion, int withUnassessedCriteria, int notScreened) {
    }

    /** One table: the stocks screened by the same set of rules. */
    public record Section(String title, List<Criterion> criteria, List<Row> rows) {
    }

    /** @param period the period most stocks' values cover; a cell states its own when it differs */
    public record Criterion(String label, String description, String period) {
    }

    public record Row(int rank, String symbol, String company, IndustryGroup group, long met, int total,
                      boolean meetsAll, List<Cell> cells, String tieBreak) {
    }

    /**
     * @param outcome PASS, FAIL or INSUFFICIENT_DATA
     * @param period  only when it differs from the column's
     * @param status  the value's DataStatus, or "not cross-checked"
     * @param reason  why the criterion could not be assessed; null otherwise
     * @param source  the provider and fields the value came from
     */
    public record Cell(String criterion, RuleOutcome outcome, String display, String period, String status,
                       String reason, String source, String calculation) {
    }

    public static ScreeningView from(ScreeningOutcome outcome) {
        if (!outcome.success()) {
            return new ScreeningView(outcome.status().name(), outcome.message(), null, null, null, null, null, null,
                    null, List.of(), List.of(), List.of(), outcome.elapsedMillis(), ScreeningRenderer.NOT_A_RECOMMENDATION);
        }
        ScreeningResult result = outcome.result();
        ScreeningCriteria criteria = result.criteria();
        SnapshotRun run = outcome.run();

        Map<String, List<StockScreening>> grouped = new LinkedHashMap<>();
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i < result.ranked().size(); i++) {
            StockScreening stock = result.ranked().get(i);
            ranks.put(stock.symbol(), i + 1);
            grouped.computeIfAbsent(ScreeningRenderer.section(criteria, stock.industryGroup()), key -> new ArrayList<>())
                    .add(stock);
        }
        List<Section> sections = new ArrayList<>();
        grouped.forEach((title, stocks) -> sections.add(section(title, stocks, ranks)));

        List<Stock> meetAll = result.ranked().stream().filter(StockScreening::meetsAll)
                .map(s -> new Stock(s.symbol(), s.companyName(), s.industryGroup())).toList();
        int withGaps = (int) result.ranked().stream().filter(s -> s.count(RuleOutcome.INSUFFICIENT_DATA) > 0).count();
        String ranking = "Stocks meeting every criterion first, then by the share of their criteria met, then fewer "
                + "criteria that could not be assessed, then " + criteria.tieBreak().metric().description()
                + (criteria.tieBreak().higherFirst() ? " (higher first)." : " (lower first).");
        return new ScreeningView(outcome.status().name(), null, criteria.name(), criteria.description(),
                outcome.universe().name(), outcome.universe().displayName(),
                new SnapshotInfo(run.id(), run.snapshotDate(), run.finishedAt(), run.stored(), run.requested()),
                new Summary(result.ranked().size(), meetAll, withGaps, result.notScreened().size()), ranking,
                sections, result.notScreened(), outcome.notInSnapshot(), outcome.elapsedMillis(),
                ScreeningRenderer.NOT_A_RECOMMENDATION);
    }

    private static Section section(String title, List<StockScreening> stocks, Map<String, Integer> ranks) {
        List<Rule> rules = stocks.get(0).results().stream().map(RuleResult::rule).toList();
        List<String> periods = ScreeningRenderer.commonPeriods(stocks);
        List<Criterion> criteria = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            criteria.add(new Criterion(rules.get(i).label(), rules.get(i).describe(), periods.get(i)));
        }
        List<Row> rows = new ArrayList<>();
        for (StockScreening stock : stocks) {
            List<Cell> cells = new ArrayList<>();
            for (int i = 0; i < stock.results().size(); i++) {
                RuleResult result = stock.results().get(i);
                SnapshotMetric metric = result.metric();
                boolean ownPeriod = metric.periodLabel() != null && !metric.periodLabel().equals(periods.get(i));
                boolean insufficient = result.outcome() == RuleOutcome.INSUFFICIENT_DATA;
                cells.add(new Cell(result.rule().label(), result.outcome(), metric.display(),
                        ownPeriod ? metric.periodLabel() : null, ScreeningRenderer.why(metric),
                        insufficient ? ScreeningRenderer.root(result) : null, metric.source(), metric.calculation()));
            }
            SnapshotMetric tie = stock.tieBreak();
            rows.add(new Row(ranks.get(stock.symbol()), stock.symbol(), stock.companyName(), stock.industryGroup(),
                    stock.count(RuleOutcome.PASS), stock.results().size(), stock.meetsAll(), cells,
                    tie.usable() ? tie.display() + (tie.periodLabel() != null ? " (" + tie.periodLabel() + ")" : "")
                            : "n/a (" + ScreeningRenderer.why(tie) + ")"));
        }
        return new Section(title, criteria, rows);
    }
}
