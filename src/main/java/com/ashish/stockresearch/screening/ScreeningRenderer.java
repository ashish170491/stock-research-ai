package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.ScreeningResult.RuleResult;
import com.ashish.stockresearch.screening.ScreeningResult.StockScreening;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders a screen as compact Markdown: one table per set of criteria (non-financial companies, banks,
 * NBFCs), each stock's result for each rule with the value it was decided on, then what could not be
 * screened and why. The same text is the tool result the model reads and the page a person reads.
 */
@Component
public class ScreeningRenderer {

    private static final DateTimeFormatter FINISHED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.of(SnapshotJob.ZONE));
    private static final int MAX_REASON = 160;

    static final String NOT_A_RECOMMENDATION = "This is a screen against the criteria below: which criteria each "
            + "stock meets. It is not a recommendation, rating or judgement of any stock, the criteria are heuristics "
            + "configured in this application rather than benchmarks, and meeting every criterion does not make a "
            + "stock a good investment.";

    public String render(ScreeningOutcome outcome) {
        StringBuilder md = new StringBuilder();
        md.append("TOOL RESULT: screenStocks - status ").append(outcome.status()).append('\n');
        if (!outcome.success()) {
            return md.append("No screen: ").append(outcome.message()).append('\n').toString();
        }
        ScreeningResult result = outcome.result();
        ScreeningCriteria criteria = result.criteria();
        SnapshotRun run = outcome.run();
        md.append("Preset: ").append(criteria.name());
        if (criteria.description() != null && !criteria.description().isBlank()) {
            md.append(" - ").append(criteria.description().strip());
        }
        md.append("\nUniverse: ").append(outcome.universe().displayName()).append(" (").append(outcome.universe())
                .append("). Snapshot of ").append(run.snapshotDate()).append(" (run ").append(run.id())
                .append(", finished ").append(run.finishedAt() == null ? "at an unknown time"
                        : FINISHED.format(run.finishedAt()))
                .append("): ").append(run.stored()).append(" of ").append(run.requested()).append(" stocks captured.");
        if (!criteria.industryGroups().isEmpty()) {
            md.append(" Industry groups: ").append(criteria.industryGroups()).append(" (")
                    .append(result.filteredOut()).append(" other stocks left out).");
        }
        md.append("\nFigures are from that snapshot, not live; valuation multiples are at the snapshot's share price.\n\n")
                .append(NOT_A_RECOMMENDATION).append("\n\n")
                .append("PASS = meets the criterion. FAIL = does not meet it. INSUFFICIENT_DATA = the value is missing, "
                        + "in a DATA_CONFLICT, INVALID or not cross-checked, so the criterion cannot be assessed; it "
                        + "never counts as met. Quote results and figures as given; do not recalculate them or apply "
                        + "other thresholds.\n")
                .append("Ranking: stocks meeting every criterion first, then by the share of their criteria met, then "
                        + "fewer INSUFFICIENT_DATA, then ").append(criteria.tieBreak().metric().description())
                .append(criteria.tieBreak().higherFirst() ? " (higher first)" : " (lower first)").append(".\n");

        summary(md, result);

        Map<String, List<StockScreening>> sections = new LinkedHashMap<>();
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (int i = 0; i < result.ranked().size(); i++) {
            StockScreening stock = result.ranked().get(i);
            ranks.put(stock.symbol(), i + 1);
            sections.computeIfAbsent(section(criteria, stock.industryGroup()), key -> new ArrayList<>()).add(stock);
        }
        // cause -> the stocks it affects, so a cause shared by many stocks is stated once
        Map<String, List<String>> insufficient = new LinkedHashMap<>();
        sections.forEach((title, stocks) -> table(md, title, stocks, ranks, criteria, insufficient));
        if (result.ranked().isEmpty()) {
            md.append("\nNo stock in the snapshot could be screened with this preset.\n");
        }

        if (!insufficient.isEmpty()) {
            md.append("\n#### Why criteria could not be assessed (criteria: cause: stocks)\n");
            insufficient.forEach((cause, symbols) -> md.append("- ").append(cause).append(": ")
                    .append(String.join(", ", symbols)).append('\n'));
        }
        if (!result.notScreened().isEmpty()) {
            md.append("\n#### Not screened\n");
            result.notScreened().forEach(stock -> md.append("- ").append(stock.symbol()).append(" (")
                    .append(stock.industryGroup()).append("): ").append(stock.reason()).append('\n'));
        }
        if (!outcome.notInSnapshot().isEmpty()) {
            md.append("\n#### Not in the snapshot\n");
            outcome.notInSnapshot().forEach(failure -> md.append("- ").append(failure.symbol()).append(": ")
                    .append(shorten(failure.reason())).append('\n'));
        }
        return md.toString();
    }

    /**
     * What a summary of the screen is written from: what was screened and with which criteria, the
     * application's counts, and what was not screened - not the table, so a summary cannot tally or re-rank it.
     */
    public String renderSummary(ScreeningOutcome outcome) {
        if (!outcome.success()) {
            return render(outcome);
        }
        ScreeningResult result = outcome.result();
        ScreeningCriteria criteria = result.criteria();
        StringBuilder md = new StringBuilder("Screen: ").append(criteria.name());
        if (criteria.description() != null && !criteria.description().isBlank()) {
            md.append(" - ").append(criteria.description().strip());
        }
        md.append("\nUniverse: ").append(outcome.universe().displayName()).append(", snapshot of ")
                .append(outcome.run().snapshotDate()).append(" (figures from that snapshot, not live).\n");
        if (!criteria.industryGroups().isEmpty()) {
            md.append("Industry groups: ").append(criteria.industryGroups()).append(".\n");
        }
        Map<String, List<StockScreening>> sections = new LinkedHashMap<>();
        result.ranked().forEach(stock -> sections.computeIfAbsent(section(criteria, stock.industryGroup()),
                key -> new ArrayList<>()).add(stock));
        sections.forEach((title, stocks) -> md.append("Criteria for ").append(title).append(" (")
                .append(stocks.size()).append(" stocks): ").append(String.join("; ", stocks.get(0).results().stream()
                        .map(r -> r.rule().describe()).toList())).append(".\n"));
        summary(md, result);
        if (!result.notScreened().isEmpty()) {
            md.append("\n#### Not screened\n");
            result.notScreened().forEach(stock -> md.append("- ").append(stock.symbol()).append(" (")
                    .append(stock.industryGroup()).append("): ").append(stock.reason()).append('\n'));
        }
        return md.toString();
    }

    /** A rendered screen as a person reads it: without the lines addressed to the model. */
    public static String forReaders(String rendered) {
        return rendered.replaceFirst("^TOOL RESULT: screenStocks - status \\w+\\n", "")
                .replace(" Quote results and figures as given; do not recalculate them or apply other thresholds.", "");
    }

    /** Counted here, so an answer can quote the counts instead of tallying the table itself. */
    private static void summary(StringBuilder md, ScreeningResult result) {
        List<StockScreening> all = result.ranked().stream().filter(StockScreening::meetsAll).toList();
        long withGaps = result.ranked().stream().filter(s -> s.count(RuleOutcome.INSUFFICIENT_DATA) > 0).count();
        int screened = result.ranked().size();
        md.append("\n#### Summary (counted by the application)\n")
                .append("- ").append(all.size()).append(" of the ").append(screened)
                .append(" screened stocks meet every criterion that applies to them")
                .append(all.isEmpty() ? "." : ": " + String.join(", ", all.stream()
                        .map(s -> s.symbol() + " (" + s.industryGroup() + ")").toList()) + ".")
                .append('\n')
                .append("- ").append(withGaps).append(" of the ").append(screened)
                .append(" have at least one criterion that could not be assessed (INSUFFICIENT_DATA). That criterion "
                        + "is neither met nor failed: it is listed under Not assessed, never under Not met.\n")
                .append("- ").append(result.notScreened().size()).append(" stocks were not screened (no criteria for "
                        + "their industry group, or the group is unknown); see Not screened.\n");
    }

    static String section(ScreeningCriteria criteria, IndustryGroup group) {
        return criteria.groupRules().containsKey(group) ? group.name() : "Non-financial companies";
    }

    private static void table(StringBuilder md, String title, List<StockScreening> stocks, Map<String, Integer> ranks,
                              ScreeningCriteria criteria, Map<String, List<String>> insufficient) {
        List<Rule> rules = stocks.get(0).results().stream().map(RuleResult::rule).toList();
        md.append("\n#### ").append(title).append(" (").append(stocks.size()).append(" stocks)\n");
        md.append("Criteria: ").append(String.join("; ", rules.stream().map(Rule::describe).toList())).append(".\n\n");
        // The period most rows share is stated once, in the header; a cell states its own only when it differs.
        List<String> commonPeriods = commonPeriods(stocks);
        md.append("| Rank | Symbol | Company | Group | Met |");
        for (int column = 0; column < rules.size(); column++) {
            md.append(' ').append(rules.get(column).label());
            if (commonPeriods.get(column) != null) {
                md.append(" [").append(commonPeriods.get(column)).append(']');
            }
            md.append(" |");
        }
        md.append(" Not met | Not assessed | Tie-break: ").append(criteria.tieBreak().metric().shortLabel())
                .append(" |\n|---|---|---|---|---|");
        md.append("---|".repeat(rules.size() + 3)).append('\n');
        for (StockScreening stock : stocks) {
            md.append("| ").append(ranks.get(stock.symbol())).append(" | ").append(stock.symbol()).append(" | ")
                    .append(stock.companyName()).append(" | ").append(stock.industryGroup()).append(" | ")
                    .append(stock.count(RuleOutcome.PASS)).append(" of ").append(stock.results().size()).append(" |");
            for (int column = 0; column < stock.results().size(); column++) {
                RuleResult result = stock.results().get(column);
                md.append(' ').append(cell(result, commonPeriods.get(column))).append(" |");
            }
            reasons(stock).forEach(cause -> insufficient.computeIfAbsent(cause, key -> new ArrayList<>())
                    .add(stock.symbol()));
            md.append(' ').append(labels(stock, RuleOutcome.FAIL, false)).append(" | ")
                    .append(labels(stock, RuleOutcome.INSUFFICIENT_DATA, true)).append(" |");
            SnapshotMetric tie = stock.tieBreak();
            md.append(' ').append(tie.usable() ? tie.display() + period(tie, true) : "n/a (" + why(tie) + ")")
                    .append(" |\n");
        }
    }

    /** For each rule column, the period label most of the stocks share. */
    static List<String> commonPeriods(List<StockScreening> stocks) {
        List<String> common = new ArrayList<>();
        int columns = stocks.isEmpty() ? 0 : stocks.get(0).results().size();
        for (int column = 0; column < columns; column++) {
            int c = column;
            common.add(stocks.stream().map(stock -> stock.results().get(c).metric().periodLabel())
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.groupingBy(label -> label, LinkedHashMap::new,
                            java.util.stream.Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null));
        }
        return common;
    }

    private static String cell(RuleResult result, String commonPeriod) {
        SnapshotMetric metric = result.metric();
        boolean ownPeriod = metric.periodLabel() != null && !metric.periodLabel().equals(commonPeriod);
        return switch (result.outcome()) {
            case PASS, FAIL -> result.outcome() + " " + metric.display() + period(metric, ownPeriod);
            case INSUFFICIENT_DATA -> "INSUFFICIENT_DATA";
        };
    }

    /** The criteria with this outcome, by short label; "none" when there are none. */
    private static String labels(StockScreening stock, RuleOutcome outcome, boolean withStatus) {
        List<String> labels = stock.results().stream().filter(r -> r.outcome() == outcome)
                .map(r -> r.rule().metric().shortLabel() + (withStatus ? " (" + why(r.metric()) + ")" : ""))
                .toList();
        return labels.isEmpty() ? "none" : String.join(", ", labels);
    }

    static String why(SnapshotMetric metric) {
        return metric.status() == com.ashish.stockresearch.research.model.DataStatus.VALID ? "not cross-checked"
                : metric.status().name();
    }

    /** Each distinct cause of this stock's INSUFFICIENT_DATA results, with the criteria it affects. */
    private static List<String> reasons(StockScreening stock) {
        Map<String, List<String>> byReason = new LinkedHashMap<>();
        for (RuleResult result : stock.results()) {
            if (result.outcome() == RuleOutcome.INSUFFICIENT_DATA) {
                byReason.computeIfAbsent(root(result), key -> new ArrayList<>()).add(result.rule().metric().shortLabel());
            }
        }
        List<String> causes = new ArrayList<>();
        byReason.forEach((reason, metrics) -> causes.add(String.join(", ", metrics) + ": " + reason));
        return causes;
    }

    /** The innermost cause of a chain such as "ROE not calculated: input annualNetProfit (...) is DATA_CONFLICT". */
    static String root(RuleResult result) {
        SnapshotMetric metric = result.metric();
        String reason = metric.status() == com.ashish.stockresearch.research.model.DataStatus.VALID
                ? "not cross-checked (" + metric.uncheckedReason() + ")" : metric.statusReason();
        if (reason == null) {
            return result.reason() == null ? "no reason recorded" : shorten(result.reason());
        }
        java.util.regex.Matcher input = java.util.regex.Pattern.compile("input (\\w+) \\(([^)]*)\\) is (\\w+)")
                .matcher(reason);
        String last = null;
        while (input.find()) {
            last = "%s (%s) is %s".formatted(input.group(1), input.group(2), input.group(3));
        }
        if (last != null) {
            return last;
        }
        return shorten(reason);
    }

    private static String period(SnapshotMetric metric, boolean withPeriod) {
        return withPeriod && metric.periodLabel() != null ? " (" + metric.periodLabel() + ")" : "";
    }

    private static String shorten(String text) {
        if (text == null) {
            return "no reason recorded";
        }
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= MAX_REASON ? flat : flat.substring(0, MAX_REASON - 1) + "…";
    }
}
