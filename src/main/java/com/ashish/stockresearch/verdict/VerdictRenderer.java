package com.ashish.stockresearch.verdict;

import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.glossary.MetricQuestion;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.screening.RuleOutcome;
import com.ashish.stockresearch.screening.ScreeningMetric;
import com.ashish.stockresearch.screening.ScreeningResult.RuleResult;
import com.ashish.stockresearch.screening.SnapshotMetric;
import com.ashish.stockresearch.verdict.Verdict.Pillar;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a {@link Verdict} for a beginner, in Java: a headline, a plain bar for each pillar, the five questions
 * with one or two figures each, and every check with its figure. No model writes any of it.
 */
@Component
public class VerdictRenderer {

    public static final String NOT_ADVICE = "This is general research information from fixed, published rules. "
            + "It is not personal investment advice: it does not know your goals, your time horizon or what else you "
            + "own. Consider a SEBI-registered investment adviser before you act.";

    private static final Map<MetricQuestion, List<ScreeningMetric>> FIGURES = new LinkedHashMap<>();

    static {
        FIGURES.put(MetricQuestion.PROFITABLE, List.of(ScreeningMetric.ROE_PERCENT, ScreeningMetric.ROCE_PERCENT));
        FIGURES.put(MetricQuestion.GROWING, List.of(ScreeningMetric.REVENUE_CAGR_5Y_PERCENT,
                ScreeningMetric.NET_PROFIT_CAGR_5Y_PERCENT));
        FIGURES.put(MetricQuestion.CASH, List.of(ScreeningMetric.OCF_TO_PAT_3Y_MULTIPLE));
        FIGURES.put(MetricQuestion.STRETCHED, List.of(ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE));
        FIGURES.put(MetricQuestion.PRICE, List.of(ScreeningMetric.TRAILING_PE_MULTIPLE,
                ScreeningMetric.EARNINGS_YIELD_PERCENT));
    }

    private final MetricGlossary glossary;

    public VerdictRenderer(MetricGlossary glossary) {
        this.glossary = glossary;
    }

    /** "# Name (SYMBOL)" */
    public static String title(Verdict verdict) {
        String name = verdict.snapshot().companyName() == null ? verdict.snapshot().symbol()
                : verdict.snapshot().companyName();
        return "# " + name + " (" + verdict.snapshot().symbol() + ")";
    }

    /** The rating on its own: the headline, a bar for each pillar, the rule that decided it, and its limits. */
    public String rating(Verdict verdict) {
        StringBuilder md = new StringBuilder("### Overall rating\n\n");
        md.append("**Rating: ").append(verdict.rating().label()).append("** - ").append(summary(verdict)).append("\n\n");
        md.append(bar(verdict.quality())).append("\n\n").append(bar(verdict.price())).append("\n\n");
        md.append(ruleText(verdict.rule())).append("\n\n");
        md.append("_").append(NOT_ADVICE).append(" It looks only at past figures, not news, management or the "
                + "economy._\n");
        return md.toString();
    }

    /**
     * One of the five questions: its headline figures, then each rating check that belongs to it, met or not,
     * with the figure it was decided on.
     */
    public String question(Verdict verdict, MetricQuestion question) {
        StringBuilder md = new StringBuilder("### ").append(question.heading()).append("\n\n");
        FIGURES.getOrDefault(question, List.of())
                .forEach(metric -> md.append("- ").append(figure(verdict, metric)).append("\n"));
        List<RuleResult> checks = java.util.stream.Stream.concat(verdict.quality().results().stream(),
                        verdict.price().results().stream())
                .filter(result -> glossary.forScreeningMetric(result.rule().metric())
                        .map(entry -> entry.question() == question).orElse(false))
                .toList();
        if (!checks.isEmpty()) {
            md.append("\nAgainst the rating's rules of thumb:\n");
            checks.forEach(result -> md.append("- ").append(outcome(result)).append(": ")
                    .append(result.rule().describe()).append(" - ").append(checkFigure(result)).append("\n"));
        }
        return md.toString();
    }

    public String render(Verdict verdict) {
        StringBuilder md = new StringBuilder();
        md.append(title(verdict)).append(", in plain words\n\n");
        md.append("**Rating: ").append(verdict.rating().label()).append("** - ").append(summary(verdict)).append("\n\n");
        md.append("_").append(NOT_ADVICE).append("_\n\n");
        md.append("_Want every figure? Open the Report tab for the full research report._\n\n");

        md.append("## Why this rating\n\n");
        md.append(bar(verdict.quality())).append("\n\n").append(bar(verdict.price())).append("\n\n");
        md.append(ruleText(verdict.rule())).append("\n\n");

        md.append("## The five questions\n\n");
        FIGURES.forEach((question, metrics) -> {
            md.append("**").append(question.heading()).append("**\n");
            metrics.forEach(metric -> md.append("- ").append(figure(verdict, metric)).append("\n"));
            md.append("\n");
        });

        md.append("## Every check, with its figure\n\n");
        md.append("| Check | Result | Figure |\n|---|---|---|\n");
        checks(md, verdict.quality());
        checks(md, verdict.price());
        md.append("\nThe thresholds are common rules of thumb set in the application's configuration. They are not "
                + "standards, and you can change them.\n\n");

        md.append("## What this rating does not tell you\n\n");
        md.append("- It looks at past figures. It cannot say what the company or its share price will do next.\n");
        md.append("- It does not weigh news, management, competition, regulation or the economy.\n");
        md.append("- It does not know your goals, your risk appetite or how much of your money is already in this "
                + "company or sector.\n");
        md.append("- The figures come from company filings and Yahoo Finance as of ")
                .append(verdict.snapshot().snapshotDate()).append(" and can be late or incomplete.\n");
        return md.toString();
    }

    private static String summary(Verdict verdict) {
        Pillar quality = verdict.quality();
        Pillar price = verdict.price();
        return switch (verdict.rating()) {
            case BUY -> "it meets %d of %d quality checks and %d of %d price checks.".formatted(quality.met(),
                    quality.total(), price.met(), price.total());
            case HOLD -> ("it meets %d of %d quality checks and %d of %d price checks, which is neither enough to "
                    + "buy nor weak enough to sell.").formatted(quality.met(), quality.total(), price.met(),
                    price.total());
            case SELL -> "it meets only %d of %d quality checks.".formatted(quality.met(), quality.total());
            case NO_RATING -> "%s, so the application will not guess.".formatted(capitalised(verdict.reason()));
        };
    }

    private static String bar(Pillar pillar) {
        int filled = pillar.total() == 0 ? 0 : pillar.met();
        return "**%s**  %s  %d of %d checks met%s".formatted(pillar.name(), "▰".repeat(filled)
                + "▱".repeat(Math.max(0, pillar.total() - filled)), pillar.met(), pillar.total(),
                pillar.total() > pillar.checkable()
                        ? " (%d could not be checked)".formatted(pillar.total() - pillar.checkable()) : "");
    }

    private static String ruleText(VerdictProperties rule) {
        return ("How the rating is decided: **Buy** when at least %d%% of the quality checks and %d%% of the price "
                + "checks are met; **Sell or avoid** when fewer than %d%% of the quality checks are met; **Hold** "
                + "in between; **No rating** when fewer than %d%% of a pillar's checks can be run on reliable data. A "
                + "check that cannot be run never counts as met.")
                .formatted(rule.buyQuality(), rule.buyPrice(), rule.sellBelowQuality(), rule.minimumCover());
    }

    private static String figure(Verdict verdict, ScreeningMetric metric) {
        SnapshotMetric value = verdict.snapshot().metric(metric);
        String name = capitalised(metric.description());
        if (!value.usable()) {
            return "%s: not available - %s".formatted(name, plainReason(value));
        }
        return "%s: **%s**%s".formatted(name, value.display(),
                value.periodLabel() == null ? "" : " (" + value.periodLabel() + ")");
    }

    /** Why a figure could not decide a check, in a sentence a beginner can read; the report has the detail. */
    static String plainReason(SnapshotMetric value) {
        if (value.status() == DataStatus.VALID) {
            return "it could not be cross-checked against a second source";
        }
        return switch (value.status()) {
            case UNAVAILABLE -> "the company's filing or the data source does not report it";
            case INVALID -> "the figures it is built from contradict each other, so it is withheld";
            case DATA_CONFLICT -> "two sources disagree on it, so neither is used";
            default -> "it could not be worked out reliably";
        };
    }

    private static void checks(StringBuilder md, Pillar pillar) {
        for (RuleResult result : pillar.results()) {
            md.append("| ").append(capitalised(result.rule().describe())).append(" | ").append(outcome(result))
                    .append(" | ").append(checkFigure(result).replace("|", "/")).append(" |\n");
        }
    }

    private static String outcome(RuleResult result) {
        return switch (result.outcome()) {
            case PASS -> "Met";
            case FAIL -> "Not met";
            case INSUFFICIENT_DATA -> "Could not check";
        };
    }

    private static String checkFigure(RuleResult result) {
        return result.outcome() == RuleOutcome.INSUFFICIENT_DATA ? plainReason(result.metric())
                : result.metric().display() + (result.metric().periodLabel() == null ? ""
                : " (" + result.metric().periodLabel() + ")");
    }

    private static String capitalised(String text) {
        return text == null || text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
