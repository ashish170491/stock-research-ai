package com.ashish.stockresearch.research.comparison;

import com.ashish.stockresearch.glossary.MetricQuestion;
import com.ashish.stockresearch.research.model.DataStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Renders a {@link ComparisonTable} as Markdown: one table per Step 11 question, each row with the glossary's
 * line on how to read it, and each company's figure beside its own industry group's median - the table says
 * so by name whenever the companies shown are not all in the same group.
 */
public final class ComparisonRenderer {

    private ComparisonRenderer() {
    }

    public static String render(ComparisonTable table) {
        StringBuilder md = new StringBuilder("## Comparison\n\n");
        for (ComparisonTable.Gap gap : table.gaps()) {
            md.append("_").append(gap.symbol()).append(" could not be compared: ").append(gap.reason()).append("_\n\n");
        }
        if (table.rows().isEmpty()) {
            md.append("No metric could be compared across ").append(String.join(", ", table.symbols())).append(".\n");
            return md.toString();
        }
        Map<MetricQuestion, List<ComparisonTable.Row>> byQuestion = table.rows().stream()
                .collect(Collectors.groupingBy(ComparisonTable.Row::question, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<MetricQuestion, List<ComparisonTable.Row>> entry : byQuestion.entrySet()) {
            md.append("### ").append(entry.getKey().heading()).append("\n\n");
            md.append("| Metric | ").append(String.join(" | ", table.symbols())).append(" |\n");
            md.append("|---|").append("---|".repeat(table.symbols().size())).append('\n');
            for (ComparisonTable.Row row : entry.getValue()) {
                md.append("| ").append(row.label()).append(" | ")
                        .append(row.cells().stream().map(ComparisonRenderer::cell).collect(Collectors.joining(" | ")))
                        .append(" |\n");
            }
            md.append('\n');
            for (ComparisonTable.Row row : entry.getValue()) {
                if (row.howToRead() != null) {
                    md.append("_").append(row.label()).append(" - ").append(row.howToRead()).append("_\n\n");
                }
                median(row).ifPresent(md::append);
            }
        }
        return md.toString();
    }

    private static String cell(ComparisonTable.Cell cell) {
        if (cell.value() == null) {
            return "UNAVAILABLE";
        }
        if (cell.value().status() != DataStatus.VALID) {
            return cell.value().status().name();
        }
        if (!cell.comparable()) {
            return "%s (NOT_COMPARABLE: %s)".formatted(cell.value().display(), cell.notComparableReason());
        }
        return cell.value().display();
    }

    /** The row's industry group median beside each company - named per company whenever the companies shown
     * are not all in the same group, since each then has its own group's figure. */
    private static Optional<String> median(ComparisonTable.Row row) {
        boolean anyMedian = row.cells().stream().anyMatch(c -> c.groupMedian() != null);
        if (!anyMedian) {
            return Optional.empty();
        }
        boolean mixedGroups = row.cells().stream().map(ComparisonTable.Cell::group).distinct().count() > 1;
        String text = row.cells().stream().map(c -> {
            String label = mixedGroups ? "%s (%s)".formatted(c.symbol(), c.group()) : c.symbol();
            String value = c.groupMedian() == null ? "no peer statistic" : c.groupMedian().display();
            return label + ": " + value;
        }).collect(Collectors.joining(", "));
        return Optional.of("_Industry group median - " + text + "._\n\n");
    }
}
