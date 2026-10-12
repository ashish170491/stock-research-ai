package com.ashish.stockresearch.moat;

import com.ashish.stockresearch.context.ContextRenderer;
import com.ashish.stockresearch.context.RatioContextService;
import com.ashish.stockresearch.glossary.GlossaryEntry;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.screening.RuleOutcome;
import com.ashish.stockresearch.screening.ScreeningMetric;
import com.ashish.stockresearch.screening.SnapshotMetric;
import com.ashish.stockresearch.screening.StockScreener;
import com.ashish.stockresearch.verdict.VerdictRenderer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Writes the lasting-edge answer for a beginner, in Java: the signs found in the company's own figures, each with
 * the figure it was decided on and its industry peers; what the market charges for the shares beside its peers;
 * what the company says about its own position, quoted and cited; and what none of this can show. What a moat and
 * a valuation premium are comes from {@link MetricGlossary}. No model writes any of it, and it never says the
 * company has a moat, or why its shares are priced as they are.
 */
@Component
public class MoatRenderer {

    public static final String HEADING = "Does it have a lasting edge (a moat)?";

    /** A sign's name in plain words, by the series or metric of the rule as configured. */
    private static final Map<ScreeningMetric.Series, String> SERIES_NAMES = new EnumMap<>(ScreeningMetric.Series.class);
    private static final Map<ScreeningMetric, String> METRIC_NAMES = new EnumMap<>(ScreeningMetric.class);

    static {
        SERIES_NAMES.put(ScreeningMetric.Series.ROCE_LOWEST, "High return on capital in every year");
        SERIES_NAMES.put(ScreeningMetric.Series.ROE_LOWEST, "High return on equity in every year");
        SERIES_NAMES.put(ScreeningMetric.Series.OPERATING_MARGIN_RANGE, "An operating margin that held steady");
        SERIES_NAMES.put(ScreeningMetric.Series.OCF_TO_PAT, "Profit that came in as cash");
        SERIES_NAMES.put(ScreeningMetric.Series.REVENUE_CAGR, "Sales that grew");
        SERIES_NAMES.put(ScreeningMetric.Series.NET_PROFIT_CAGR, "Profit that grew");
        METRIC_NAMES.put(ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE, "Little borrowing");
        METRIC_NAMES.put(ScreeningMetric.ROCE_PERCENT, "High return on capital");
        METRIC_NAMES.put(ScreeningMetric.ROE_PERCENT, "High return on equity");
        METRIC_NAMES.put(ScreeningMetric.ROA_PERCENT, "High return on assets");
    }

    /** The price measures shown beside the peers: the market's charge for earnings, then for book value. */
    private static final List<ScreeningMetric> PRICE = List.of(ScreeningMetric.TRAILING_PE_MULTIPLE,
            ScreeningMetric.PRICE_TO_BOOK_MULTIPLE);

    private final MetricGlossary glossary;
    private final RatioContextService context;

    public MoatRenderer(MetricGlossary glossary, RatioContextService context) {
        this.glossary = glossary;
        this.context = context;
    }

    public String render(MoatSigns signs, String whyNoSigns, CompanyStatements.Found statements) {
        StringBuilder md = new StringBuilder("### ").append(HEADING).append("\n\n");
        glossary.byKey("moat").map(GlossaryEntry::meaning)
                .ifPresent(meaning -> md.append("_A moat: ").append(lowerFirst(meaning)).append("_\n\n"));
        md.append(signsSection(signs, whyNoSigns));
        md.append(priceSection(signs.snapshot(), signs.group()));
        md.append(statementsSection(signs.snapshot(), statements));
        md.append(limits(signs.group()));
        return md.toString();
    }

    private String signsSection(MoatSigns signs, String whyNoSigns) {
        StringBuilder md = new StringBuilder("#### Signs in its own figures\n\n");
        if (signs.total() == 0) {
            return md.append(signs.group() == IndustryGroup.UNKNOWN
                    ? "The company's industry could not be identified, so the signs that apply to it cannot be chosen."
                    : "No signs are defined for %s companies (%s).".formatted(groupName(signs.group()), whyNoSigns))
                    .append("\n\n").toString();
        }
        md.append("**%d of %d signs found**  %s%s\n\n".formatted(signs.found(), signs.total(),
                "▰".repeat(signs.found()) + "▱".repeat(signs.total() - signs.found()),
                signs.total() > signs.checkable()
                        ? "  (%d could not be checked)".formatted(signs.total() - signs.checkable()) : ""));
        for (StockScreener.Decision sign : signs.signs()) {
            if (sign.result().outcome() == RuleOutcome.INSUFFICIENT_DATA) {
                continue;
            }
            md.append("- ").append(sign.result().outcome() == RuleOutcome.PASS ? "Found" : "Not found").append(" - ")
                    .append(name(sign)).append(": ").append(sign.result().rule().describe()).append(" - ")
                    .append(figure(sign.result().metric())).append("\n");
        }
        List<StockScreener.Decision> unchecked = signs.withOutcome(RuleOutcome.INSUFFICIENT_DATA);
        if (!unchecked.isEmpty()) {
            md.append(signs.checkable() > 0 ? "\n" : "").append("Could not be checked:\n");
            unchecked.forEach(sign -> md.append("- ").append(name(sign)).append(": ")
                    .append(VerdictRenderer.plainReason(sign.result().metric())).append("\n"));
        }
        md.append("\n");
        signs.notes().forEach(note -> md.append("_").append(note).append("_\n\n"));
        md.append(peers(signs)).append("_The thresholds are the moat-signs preset's settings (app.screening.presets in "
                + "application.yml), which you can change. They are not standards._\n\n");
        return md.toString();
    }

    /** The same figures among the company's industry peers in the stored snapshot, where there are enough of them. */
    private String peers(MoatSigns signs) {
        Set<ScreeningMetric> metrics = new LinkedHashSet<>();
        signs.signs().stream().map(sign -> sign.result().rule().metric()).forEach(metrics::add);
        List<String> lines = new ArrayList<>();
        for (ScreeningMetric metric : metrics) {
            context.peers(metric, signs.group(), signs.snapshot().symbol()).map(ContextRenderer::peerLine)
                    .filter(line -> !line.isBlank())
                    .ifPresent(line -> lines.add("- %s: %s".formatted(capitalised(metric.description()), line)));
        }
        return lines.isEmpty() ? "" : "Beside other companies in its industry:\n" + String.join("\n", lines) + "\n\n";
    }

    private String priceSection(FundamentalsSnapshot snapshot, IndustryGroup group) {
        StringBuilder md = new StringBuilder("#### What the market charges for it\n\n");
        for (ScreeningMetric metric : PRICE) {
            if (group.isNonPrimary(metric.sectorKey())) {
                continue;
            }
            md.append("- ").append(capitalised(metric.description())).append(": ")
                    .append(figure(snapshot.metric(metric))).append("\n");
            context.peers(metric, group, snapshot.symbol()).map(ContextRenderer::peerLine)
                    .filter(line -> !line.isBlank()).ifPresent(line -> md.append("  - ").append(line).append("\n"));
        }
        md.append("\n");
        glossary.byKey("valuation-premium").ifPresent(entry -> md.append("_Why investors may pay more: ")
                .append(lowerFirst(entry.howToRead())).append("_\n\n"));
        return md.toString();
    }

    private static String statementsSection(FundamentalsSnapshot snapshot, CompanyStatements.Found found) {
        StringBuilder md = new StringBuilder("#### What the company says about its position\n\n");
        String name = snapshot.companyName() == null ? snapshot.symbol() : snapshot.companyName();
        if (!found.statements().isEmpty()) {
            found.statements().forEach(statement -> md.append("> “").append(statement.quote()).append("”  \n> — ")
                    .append(statement.citation()).append("\n\n"));
            md.append(("_Quoted word for word from %s's own documents: these are the company's claims, not facts the "
                    + "application has checked._\n\n").formatted(name));
        } else if (!found.searched()) {
            md.append("The company's documents could not be searched right now, so nothing of its own is quoted. "
                    + "Please try again later.\n\n");
        } else if (found.documents()) {
            md.append(("Nothing in the documents added for %s describes its competitive position, so nothing is "
                    + "quoted.\n\n").formatted(snapshot.symbol()));
        } else {
            md.append(("No annual report, concall transcript or investor presentation has been added for %s yet, so "
                    + "there is nothing of its own to quote. Adding one (POST /api/documents) lets this section quote "
                    + "what the company says about its brands, market share and other strengths.\n\n")
                    .formatted(snapshot.symbol()));
        }
        return md.toString();
    }

    private static String limits(IndustryGroup group) {
        StringBuilder md = new StringBuilder("#### What this cannot tell you\n\n");
        md.append("- The signs show what happened in the figures, not why, and not whether it will last.\n");
        if (!group.sectorMetrics().isEmpty()) {
            md.append("- For %s companies an edge also shows in %s, which these figures do not include.\n"
                    .formatted(groupName(group), String.join(", ", group.sectorMetrics())));
        }
        md.append("- It does not weigh news, management, competition or regulation.\n\n");
        md.append("_").append(VerdictRenderer.NOT_ADVICE).append("_\n");
        return md.toString();
    }

    private static String name(StockScreener.Decision sign) {
        ScreeningMetric asked = sign.asked().metric();
        return asked.series().map(SERIES_NAMES::get)
                .or(() -> Optional.ofNullable(METRIC_NAMES.get(asked)))
                .orElseGet(() -> capitalised(asked.description()));
    }

    private static String figure(SnapshotMetric value) {
        if (!value.usable()) {
            return "not available - " + VerdictRenderer.plainReason(value);
        }
        // "FY22 to FY26 (5 years)" reads as "(FY22 to FY26, 5 years)"
        return "**%s**%s".formatted(value.display(), value.periodLabel() == null ? ""
                : " (" + value.periodLabel().replace(" (", ", ").replace(")", "") + ")");
    }

    private static String groupName(IndustryGroup group) {
        return group.name().toLowerCase(Locale.ROOT).replace('_', ' ').replace("it services", "IT services")
                .replace("nbfc", "NBFC");
    }

    private static String capitalised(String text) {
        return text == null || text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String lowerFirst(String text) {
        return text == null || text.isEmpty() ? "" : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }
}
