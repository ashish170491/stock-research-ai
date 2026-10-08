package com.ashish.stockresearch.research.report;

import com.ashish.stockresearch.glossary.GlossaryEntry;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.glossary.MetricQuestion;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.AnnualRatios;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.CalculationStatus;
import com.ashish.stockresearch.research.model.CalendarYearReturn;
import com.ashish.stockresearch.research.model.CompanyProfile;
import com.ashish.stockresearch.research.model.CompanyProfileResult;
import com.ashish.stockresearch.research.model.CurrencyInfo;
import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.FinancialSummary;
import com.ashish.stockresearch.research.model.FinancialSummaryResult;
import com.ashish.stockresearch.research.model.HeadlineFinancials;
import com.ashish.stockresearch.research.model.HistoricalPerformance;
import com.ashish.stockresearch.research.model.HistoricalPerformanceResult;
import com.ashish.stockresearch.research.model.QuarterlyResult;
import com.ashish.stockresearch.research.model.ReportedFinancials;
import com.ashish.stockresearch.research.model.ReportedValuation;
import com.ashish.stockresearch.research.model.ShareholdingResult;
import com.ashish.stockresearch.research.model.ValuationResult;
import com.ashish.stockresearch.research.model.ValuationSnapshot;
import com.ashish.stockresearch.research.sector.SectorContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Renders research data as Markdown, deterministically - both the full
 * {@link StockResearchReport} and the compact per-tool evidence the chat
 * model reads. The model never re-types a number from JSON; only a report's
 * INTERPRETATION section comes from the model, and it is labelled as
 * analysis.
 *
 * Every financial value is shown with its period, status, raw provider value
 * and unit, and source field. A value that is not VALID is never shown as a
 * usable result: a CALCULATED metric in DATA_CONFLICT or INVALID shows only
 * its status; a REPORTED value in DATA_CONFLICT is tagged for the reader and
 * withheld from the model.
 */
@Component
public class ResearchReportRenderer {

    /**
     * Which fields are in a DATA_CONFLICT, and who the facts are for: the reader sees conflicting
     * values tagged, the model sees them withheld.
     */
    /**
     * Which values to tag (or, for the model, withhold) as depending on a conflict: every value in DATA_CONFLICT,
     * and any value depending on a conflicting field - in the periods the conflict is confined to, if it is.
     */
    private record Mask(List<DataQualityIssue> conflicts, boolean forModel) {

        Mask {
            conflicts = conflicts.stream().filter(i -> i.type() == DataQualityIssue.Type.DATA_CONFLICT).toList();
        }

        boolean covers(FinancialDataPoint p) {
            if (p.status() == DataStatus.DATA_CONFLICT) {
                return true;
            }
            for (DataQualityIssue conflict : conflicts) {
                if (conflict.affectedPeriods().isEmpty()) {
                    if (p.dependsOnFields().stream().anyMatch(conflict.affectedFields()::contains)) {
                        return true;
                    }
                } else if (p.calculationStatus() == CalculationStatus.CALCULATED) {
                    if (p.inputs() != null && p.inputs().stream().anyMatch(input -> input.sourceField() != null
                            && conflict.covers(input.sourceField(), input.period()))) {
                        return true;
                    }
                } else if (p.dependsOnFields().stream().anyMatch(field -> conflict.covers(field, p.period().label()))) {
                    return true;
                }
            }
            return false;
        }
    }

    static final String CONFLICT_TAG = " [DATA_CONFLICT]";
    /** What the model sees in place of a value that depends on a conflicting field. */
    static final String WITHHELD_FOR_MODEL = "WITHHELD - depends on a field in a DATA_CONFLICT";
    static final String CALCULATION_NOTE = "CALCULATED values were computed by the application from the inputs shown "
            + "in its calculation audit and verified against the source values before display. Quote them as given; "
            + "never recalculate them, derive new figures from them, or convert their units or currency.";

    public String render(StockResearchReport report, String interpretation) {
        return render(report, interpretation, null);
    }

    /**
     * The report, with its latest figures grouped by the question each answers when a glossary is given, ahead
     * of the FACTS they are taken from.
     */
    public String render(StockResearchReport report, String interpretation, MetricGlossary glossary) {
        return render(report, interpretation, glossary, null);
    }

    /**
     * Values that depend on a value in a DATA_CONFLICT, as this renderer tags them: a VALID status alone does not
     * show that a value was calculated from a disputed field.
     */
    public static java.util.function.Predicate<FinancialDataPoint> disputed(StockResearchReport report) {
        return new Mask(report.dataQualityIssues(), false)::covers;
    }

    /**
     * @param context the context beside each ratio, rendered by Java, placed after the latest figures; null for none
     */
    public String render(StockResearchReport report, String interpretation, MetricGlossary glossary, String context) {
        Mask conflicted = new Mask(report.dataQualityIssues(), false);
        StringBuilder md = new StringBuilder();
        md.append("# Research report: ").append(report.companyName())
                .append(" (").append(report.symbol()).append(")\n\n");
        md.append("Generated ").append(report.generatedOn()).append(". Latest reported quarter: **")
                .append(report.latestReportedQuarter().label()).append("**");
        if (report.latestReportedQuarter().known()) {
            md.append(" (period ended ").append(report.latestReportedQuarter().end()).append(")");
        }
        md.append(".\n\n");
        renderConflicts(md, report.dataQualityIssues());
        if (glossary != null) {
            renderAtAGlance(md, report, glossary, conflicted);
        }
        if (context != null && !context.isBlank()) {
            md.append(context.strip()).append("\n\n");
        }

        md.append("## 1. FACTS\n\n_Values supplied by the stated source (REPORTED) or computed by the application "
                + "from them (CALCULATED). Amounts are normalised to crore by the application only after the "
                + "source's own format confirmed the raw unit; currencies are never converted._\n\n");
        renderFacts(md, report, conflicted);

        md.append("## 2. OBSERVATIONS\n\n_Patterns directly visible in the facts, stated by the application from "
                + "VALID values only. They describe what the data shows, not why._\n\n");
        if (report.observations().isEmpty()) {
            md.append("- No observations could be derived from the available data.\n");
        }
        report.observations().forEach(o -> md.append("- ").append(o.statement()).append("\n"));
        renderWithheld(md, report.withheldObservations());
        md.append("\n");

        md.append("## 3. RISKS / DATA GAPS\n\n");
        if (report.dataQualityIssues().isEmpty() && report.dataGaps().isEmpty()) {
            md.append("- No data gaps were recorded.\n");
        }
        renderIssuesAndGaps(md, report.dataQualityIssues(), report.dataGaps());
        md.append("\n");

        md.append("## 4. INTERPRETATION\n\n_What the available evidence may suggest, generated by the language model "
                + "from the sections above. This is interpretation, not fact, and not investment advice._\n\n");
        List<String> withheldTopics = report.withheldTopics();
        if (!withheldTopics.isEmpty()) {
            md.append("**Conclusions withheld.** No interpretation was generated about: ")
                    .append(String.join(", ", withheldTopics))
                    .append(". The values these would rest on are in a DATA_CONFLICT, failed verification, or are not "
                            + "primary indicators for this industry group (see OBSERVATIONS).\n\n");
        }
        md.append(interpretation == null || interpretation.isBlank()
                        ? "_No interpretation was generated._" : interpretation.strip())
                .append("\n\n");

        md.append("## 5. SOURCES\n\n");
        for (SourceReference source : report.sources()) {
            md.append("- ").append(source.source()).append(" [").append(source.sourceType()).append("] - ")
                    .append(source.covers());
            if (source.periodEnd() != null) {
                md.append("; period end ").append(source.periodEnd());
            }
            if (source.publishedAt() != null) {
                md.append("; results published ").append(source.publishedAt());
            }
            if (source.asOf() != null) {
                md.append("; as of ").append(source.asOf());
            }
            md.append("\n");
        }

        if (report.financials().success() || report.historicalPerformance().success()) {
            md.append("\n## Appendix - calculation audit\n\n_Every calculated metric with the exact inputs it used, its "
                    + "formula, result and status. VALID results were reproduced independently from these inputs and "
                    + "the inputs matched the source values._\n\n");
            renderAudit(md, calculatedPoints(report));
        }
        return md.toString();
    }

    /**
     * The latest figures, grouped by the question each answers ({@link MetricQuestion}): the same data points as
     * the FACTS below, with the same status and conflict tags, so nothing is shown here that is not shown there.
     */
    private void renderAtAGlance(StringBuilder md, StockResearchReport report, MetricGlossary glossary, Mask conflicted) {
        Map<MetricQuestion, List<FinancialDataPoint>> byQuestion = new EnumMap<>(MetricQuestion.class);
        for (FinancialDataPoint point : glancePoints(report)) {
            Optional<GlossaryEntry> entry = glossary.forMetricKey(point.metric());
            entry.ifPresent(e -> byQuestion.computeIfAbsent(e.question(), q -> new ArrayList<>()).add(point));
        }
        if (byQuestion.isEmpty()) {
            return;
        }
        md.append("## At a glance, by question\n\n_The latest figures from the FACTS below, grouped by the question "
                + "each helps answer. What each measures and how to read it is on the Terms tab._\n\n");
        md.append("| Question | Figure | Value | Period |\n|---|---|---|---|\n");
        byQuestion.forEach((question, unordered) -> {
            boolean first = true;
            // in glossary order: ROE before ROA before ROCE
            List<FinancialDataPoint> points = unordered.stream().sorted(java.util.Comparator.comparingInt(
                    point -> glossary.entries().indexOf(glossary.forMetricKey(point.metric()).orElseThrow()))).toList();
            for (FinancialDataPoint p : points) {
                // a value that is not usable shows its status only; FACTS gives the reason
                String value = conflicted.covers(p) || p.status() == DataStatus.VALID ? value(p, conflicted)
                        : p.status().name() + " (see FACTS)";
                if (report.sectorContext() != null && report.sectorContext().isNonPrimary(p.metric())) {
                    value += " (not a primary measure for " + report.sectorContext().group() + ")";
                }
                md.append("| ").append(first ? question.heading() : "").append(" | ")
                        .append(glossary.label(p.metric())).append(" (").append(p.metric()).append(") | ").append(value).append(" | ").append(p.period().label())
                        .append(" |\n");
                first = false;
            }
        });
        md.append("\n");
    }

    /** The report's latest figures: the last fiscal year's ratios, the multi-year figures, valuation and price. */
    private static List<FinancialDataPoint> glancePoints(StockResearchReport report) {
        List<FinancialDataPoint> points = new ArrayList<>();
        if (report.financials().success()) {
            FinancialSummary summary = report.financials().financialSummary();
            List<AnnualRatios> annual = summary.calculated().annual();
            if (!annual.isEmpty()) {
                points.addAll(annual.get(annual.size() - 1).points());
            }
            points.addAll(List.of(summary.calculated().netProfitMarginTtmPercent(),
                    summary.calculated().revenueCagrPercent(), summary.calculated().netProfitCagrPercent(),
                    summary.calculated().ocfToPat3yMultiple(), summary.calculated().ocfToPat5yMultiple(),
                    summary.reported().headline().ebitda(), summary.reported().headline().freeCashFlow()));
        }
        if (report.valuation().success()) {
            ValuationSnapshot valuation = report.valuation().valuation();
            points.addAll(valuation.metrics());
            points.add(valuation.reported().trailingEps());
            if (valuation.filedFiguresUsed()) {
                points.add(valuation.peOnFiledEps());
            }
        }
        if (report.historicalPerformance().success()) {
            HistoricalPerformance h = report.historicalPerformance().historicalPerformance();
            points.addAll(List.of(h.cagrPercent(), h.totalReturnPercent(), h.maxDrawdownPercent()));
        }
        return points.stream().filter(Objects::nonNull).toList();
    }

    /** What the model is given to interpret: conflicts first, then facts, observations, withheld topics and gaps. */
    public String renderEvidence(StockResearchReport report) {
        Mask conflicted = new Mask(report.dataQualityIssues(), true);
        StringBuilder md = new StringBuilder();
        md.append("Company: ").append(report.companyName()).append(" (").append(report.symbol()).append(")\n");
        md.append("Today: ").append(report.generatedOn()).append("; latest reported quarter: ")
                .append(report.latestReportedQuarter().label()).append("\n\n");
        renderConflicts(md, report.dataQualityIssues());
        // Conflicting values are left out entirely: the model reports the conflict but gets nothing to
        // draw a conclusion from, and is never asked to reconcile the two sides.
        renderFacts(md, report, conflicted);
        md.append("OBSERVATIONS\n");
        report.observations().forEach(o -> md.append("- ").append(o.statement()).append("\n"));
        if (!report.withheldTopics().isEmpty()) {
            md.append("\nNOT GENERATED - write no interpretation, trend or conclusion about: ")
                    .append(String.join(", ", report.withheldTopics()))
                    .append(". Say only that conclusions on these were withheld and why.\n");
        }
        md.append("\nDATA GAPS AND DATA-QUALITY ISSUES\n");
        report.dataQualityIssues().forEach(i -> md.append("- ").append(i.type()).append(": ").append(i.message()).append("\n"));
        report.dataGaps().forEach(g -> md.append("- ").append(g.area()).append(" / ").append(g.item())
                .append(": ").append(g.reason()).append("\n"));
        return md.toString();
    }

    // --- Compact per-tool evidence for the chat model --------------------------------------------

    public String renderCompanyProfileTool(CompanyProfileResult result, SectorContext sector) {
        StringBuilder md = toolHeader("getCompanyProfile", result.success(), result.status(), result.message());
        if (!result.success()) {
            return md.toString();
        }
        CompanyProfile p = result.companyProfile();
        md.append("Company: ").append(p.companyName()).append(" (").append(p.symbol()).append(", ")
                .append(p.exchange()).append(")\n\n");
        renderConflicts(md, p.dataQualityIssues());
        renderCompany(md, p, new Mask(p.dataQualityIssues(), true));
        renderSector(md, sector);
        md.append("This tool returns no revenue, profit, growth, return, leverage, share-price history or shareholding "
                + "data. A fundamental overview needs getFinancialSummary, getHistoricalPerformance and "
                + "getShareholding as well.\n\n");
        renderIssuesAndGaps(md, p.dataQualityIssues(), List.of());
        md.append(p.provenance().note()).append("\n");
        return md.toString();
    }

    public String renderFinancialSummaryTool(FinancialSummaryResult result) {
        StringBuilder md = toolHeader("getFinancialSummary", result.success(), result.status(), result.message());
        if (!result.success()) {
            return md.toString();
        }
        FinancialSummary summary = result.financialSummary();
        ReportedFinancials reported = summary.reported();
        md.append("Company: ").append(reported.companyName()).append(" (").append(reported.symbol()).append(")\n")
                .append("Latest reported quarter: ").append(reported.latestReportedQuarter().label())
                .append(" - never describe a later quarter as reported.\n\n");
        renderConflicts(md, summary.dataQualityIssues());
        renderFinancials(md, summary, new Mask(summary.dataQualityIssues(), true));
        md.append(CALCULATION_NOTE).append("\n\n");
        List<FinancialDataPoint> notUsable = summary.calculated().points().stream()
                .filter(p -> p.status() == DataStatus.DATA_CONFLICT || p.status() == DataStatus.INVALID).toList();
        if (!notUsable.isEmpty()) {
            md.append("NOT CALCULATED - no value exists for these; draw no observation or conclusion from them:\n");
            notUsable.forEach(p -> md.append("- ").append(p.metric()).append(" (").append(p.period().label())
                    .append("): ").append(p.status()).append("\n"));
            md.append("\n");
        }
        renderIssuesAndGaps(md, summary.dataQualityIssues(), summary.dataGaps());
        md.append("\n").append(reported.provenance().note()).append("\n");
        return md.toString();
    }

    public String renderValuationTool(ValuationResult result) {
        StringBuilder md = toolHeader("getValuation", result.success(), result.status(), result.message());
        if (!result.success()) {
            return md.toString();
        }
        ValuationSnapshot v = result.valuation();
        ReportedValuation r = v.reported();
        md.append("Company: ").append(r.companyName()).append(" (").append(r.symbol()).append(")\n\n");
        renderConflicts(md, v.dataQualityIssues());
        renderValuation(md, result, new Mask(v.dataQualityIssues(), true));
        md.append(CALCULATION_NOTE).append("\n\n");
        renderIssuesAndGaps(md, v.dataQualityIssues(), v.dataGaps());
        md.append("\n").append(r.provenance().note()).append("\n");
        return md.toString();
    }

    public String renderHistoricalPerformanceTool(HistoricalPerformanceResult result) {
        StringBuilder md = toolHeader("getHistoricalPerformance", result.success(), result.status(), result.message());
        if (!result.success()) {
            return md.toString();
        }
        HistoricalPerformance p = result.historicalPerformance();
        md.append("Symbol: ").append(p.symbol()).append(" (").append(p.exchange()).append(")\n")
                .append("This is price HISTORY; endPrice is the last close in the window, not a live quote.\n\n");
        renderHistory(md, p, new Mask(List.of(), true));
        md.append(CALCULATION_NOTE).append("\n\n");
        md.append(p.provenance().note()).append("\n");
        return md.toString();
    }

    public String renderShareholdingTool(ShareholdingResult result) {
        StringBuilder md = toolHeader("getShareholding", result.success(), result.status(), result.message());
        if (result.success()) {
            md.append("Shareholding data retrieved from ").append(result.shareholding().provenance().source())
                    .append(".\n");
        } else {
            md.append("CAPABILITY GAP: the promoter / FII / DII / public shareholding split is UNKNOWN. Report it as "
                    + "not available; never fill it with zero, an estimate or a remembered figure.\n");
        }
        return md.toString();
    }

    private StringBuilder toolHeader(String tool, boolean success, Object status, String message) {
        StringBuilder md = new StringBuilder();
        md.append("TOOL RESULT: ").append(tool).append(" - status ").append(status).append("\n");
        if (!success) {
            md.append("No data: ").append(message).append("\n");
        }
        return md;
    }

    // --- Shared sections ---------------------------------------------------------------------

    private void renderConflicts(StringBuilder md, List<DataQualityIssue> issues) {
        List<DataQualityIssue> conflicts = issues.stream()
                .filter(i -> i.type() == DataQualityIssue.Type.DATA_CONFLICT).toList();
        if (conflicts.isEmpty()) {
            return;
        }
        md.append("> **DATA CONFLICT - read before the figures below.** The application has not chosen between the "
                + "conflicting values. Nothing is calculated from them, and no observation or interpretation is "
                + "generated from them; values that depend on them are tagged" + CONFLICT_TAG + " or withheld.\n");
        for (DataQualityIssue conflict : conflicts) {
            md.append(">\n> - ").append(conflict.message()).append(" Fields: ")
                    .append(String.join(", ", conflict.affectedFields())).append("\n");
        }
        md.append("\n");
    }

    private void renderWithheld(StringBuilder md, List<WithheldObservation> withheld) {
        if (withheld.isEmpty()) {
            return;
        }
        md.append("\nNot generated - the metric is in a DATA_CONFLICT, failed verification (INVALID), or is not a "
                + "primary indicator for this industry group (SECTOR_CONTEXT):\n");
        withheld.forEach(w -> md.append("- ").append(w.subject()).append(": **").append(w.reason()).append("** - ")
                .append(w.detail()).append("\n"));
    }

    private void renderIssuesAndGaps(StringBuilder md, List<DataQualityIssue> issues, List<DataGap> gaps) {
        for (DataQualityIssue issue : issues) {
            md.append("- **").append(issue.type()).append(":** ").append(issue.message())
                    .append(" Fields: ").append(String.join(", ", issue.affectedFields())).append("\n");
        }
        for (DataGap gap : gaps) {
            md.append("- **").append(gap.area()).append(" / ").append(gap.item()).append(":** ")
                    .append(gap.reason()).append("\n");
        }
    }

    private void renderFacts(StringBuilder md, StockResearchReport report, Mask conflicted) {
        md.append("### Company\n\n");
        if (report.companyProfile().success()) {
            renderCompany(md, report.companyProfile().companyProfile(), conflicted);
        } else {
            md.append("Company profile unavailable: ").append(report.companyProfile().message()).append("\n\n");
        }

        renderSector(md, report.sectorContext());

        if (report.financials().success()) {
            renderFinancials(md, report.financials().financialSummary(), conflicted);
        } else {
            md.append("### Financials\n\nFinancial data unavailable: ").append(report.financials().message()).append("\n\n");
        }

        md.append("### Share-price history\n\n");
        if (report.historicalPerformance().success()) {
            renderHistory(md, report.historicalPerformance().historicalPerformance(), conflicted);
        } else {
            md.append("Price history unavailable: ").append(report.historicalPerformance().message()).append("\n\n");
        }

        renderValuation(md, report.valuation(), conflicted);

        md.append("### Shareholding\n\n");
        if (report.shareholding().success()) {
            md.append("Shareholding data retrieved; see structured report.\n\n");
        } else {
            md.append(ResearchReportService.SHAREHOLDING_UNAVAILABLE).append("\n\n");
        }
    }

    private void renderCompany(StringBuilder md, CompanyProfile p, Mask conflicted) {
        md.append("| Item | Value | Status | Raw value (provider unit) | As of | Source field |\n")
                .append("|---|---|---|---|---|---|\n");
        text(md, "Exchange", p.exchange(), "");
        text(md, "Sector", p.sector(), "assetProfile.sector");
        text(md, "Industry", p.industry(), "assetProfile.industry");
        text(md, "Employees", p.fullTimeEmployees() == null ? null : String.valueOf(p.fullTimeEmployees()),
                "assetProfile.fullTimeEmployees");
        companyPoint(md, "Market capitalisation", p.marketCap(), conflicted);
        companyPoint(md, "Shares outstanding", p.sharesOutstanding(), conflicted);
        md.append("\n");
        currencyLine(md, List.of(p.marketCap()));
        if (p.businessSummary() != null) {
            md.append("Business summary (Yahoo Finance text): ").append(p.businessSummary()).append("\n\n");
        }
    }

    private void renderSector(StringBuilder md, SectorContext sector) {
        md.append("### Sector context\n\n_Industry group derived by the application from the classification below; the "
                + "metric guidance is application-supplied context, not data about this company, and it supplies no "
                + "benchmarks._\n\n");
        md.append("- Industry group: **").append(sector.group()).append("** (").append(sector.basis()).append(")\n");
        md.append("- ").append(sector.note()).append("\n");
        if (!sector.primaryMetrics().isEmpty()) {
            md.append("- Primary metrics for this group, where the data supplies them: ")
                    .append(String.join(", ", sector.primaryMetrics())).append("\n");
        }
        if (!sector.lessMeaningfulMetrics().isEmpty()) {
            md.append("- NOT primary indicators of financial health for this group - no observation or conclusion is "
                    + "drawn from them: ").append(String.join(", ", sector.lessMeaningfulMetrics())).append("\n");
        }
        if (!sector.sectorMetricsNotAvailable().isEmpty()) {
            md.append("- Sector metrics NOT SUPPORTED BY CURRENT DATA SOURCE: ")
                    .append(String.join(", ", sector.sectorMetricsNotAvailable())).append("\n");
        }
        md.append("\n");
    }

    private void renderFinancials(StringBuilder md, FinancialSummary summary, Mask conflicted) {
        ReportedFinancials reported = summary.reported();
        HeadlineFinancials h = reported.headline();
        md.append("### Financials - headline figures (reporting currency: ")
                .append(reported.reportingCurrency() == null ? "UNKNOWN" : reported.reportingCurrency()).append(")\n\n");
        md.append("| Metric | Value | Period | Status | Raw value (provider unit) | Source field |\n")
                .append("|---|---|---|---|---|---|\n");
        for (FinancialDataPoint point : List.of(h.revenue(), h.netProfit(), summary.calculated().netProfitMarginTtmPercent(),
                h.netProfitMarginPercent(), h.operatingMarginPercent(), h.returnOnEquityPercent(),
                h.returnOnAssetsPercent(), h.ebitda(), h.freeCashFlow(), h.totalDebt(), h.totalCash(),
                h.debtToEquityPercent(), h.quarterlyRevenueGrowthYoyPercent(), h.quarterlyEarningsGrowthYoyPercent(),
                h.returnOnCapitalEmployedPercent())) {
            md.append("| ").append(point.metric()).append(" | ").append(value(point, conflicted)).append(" | ")
                    .append(point.period().label()).append(" | ").append(status(point)).append(" | ")
                    .append(raw(point, conflicted)).append(" | ").append(fields(point)).append(" |\n");
        }
        md.append("\n");
        currencyLine(md, h.points());

        if (!reported.recentQuarters().isEmpty()) {
            md.append("### Reported quarters (source fields: earnings.financialsChart / earnings.earningsChart)\n\n")
                    .append("| Quarter | Period end | Results published | Revenue | Net profit | EPS |\n")
                    .append("|---|---|---|---|---|---|\n");
            for (QuarterlyResult q : reported.recentQuarters()) {
                md.append("| ").append(q.period().label()).append(" | ").append(q.period().end()).append(" | ")
                        .append(q.revenue().source() != null && q.revenue().source().publishedAt() != null
                                ? q.revenue().source().publishedAt() : "UNKNOWN")
                        .append(" | ").append(value(q.revenue(), conflicted)).append(" | ")
                        .append(value(q.netProfit(), conflicted)).append(" | ")
                        .append(value(q.earningsPerShare(), conflicted)).append(" |\n");
            }
            md.append("\n");
            currencyLine(md, reported.recentQuarters().stream().flatMap(q -> q.points().stream()).toList());
        }

        List<AnnualFinancials> annual = reported.annualHistory();
        if (!annual.isEmpty()) {
            boolean filed = annual.stream().map(AnnualFinancials::netProfit).anyMatch(point -> point.source() != null
                    && point.source().sourceType() == com.ashish.stockresearch.research.model.SourceType.OFFICIAL_FILING);
            md.append(filed ? "### Fiscal years (as the company filed them with NSE; source fields: nse-xbrl)\n\n"
                            : "### Fiscal years (source fields: fundamentals-timeseries)\n\n")
                    .append("| Fiscal year | Period end | Revenue | Net profit | Shareholders' equity | Total assets | Total debt "
                            + "| EBIT | Current liabilities | Operating cash flow |\n")
                    .append("|---|---|---|---|---|---|---|---|---|---|\n");
            for (AnnualFinancials year : annual) {
                md.append("| ").append(year.period().label()).append(" | ").append(year.period().end()).append(" | ")
                        .append(value(year.revenue(), conflicted)).append(" | ")
                        .append(value(year.netProfit(), conflicted)).append(" | ")
                        .append(value(year.shareholdersEquity(), conflicted)).append(" | ")
                        .append(value(year.totalAssets(), conflicted)).append(" | ")
                        .append(value(year.totalDebt(), conflicted)).append(" | ")
                        .append(value(year.ebit(), conflicted)).append(" | ")
                        .append(value(year.currentLiabilities(), conflicted)).append(" | ")
                        .append(value(year.operatingCashFlow(), conflicted)).append(" |\n");
            }
            md.append("\n");
            currencyLine(md, annual.stream().flatMap(y -> y.points().stream()).toList());
            md.append("### Calculated per fiscal year (CALCULATED by the application)\n\n")
                    .append("| Fiscal year | Revenue YoY | Net profit YoY | Net margin | Operating margin | ROE | ROA | Debt / equity "
                            + "| ROCE |\n")
                    .append("|---|---|---|---|---|---|---|---|---|\n");
            for (AnnualRatios r : summary.calculated().annual()) {
                md.append("| ").append(r.period().label());
                for (FinancialDataPoint p : r.points()) {
                    md.append(" | ").append(cell(p));
                }
                md.append(" |\n");
            }
            md.append("\nROE, ROA and ROCE use average opening and closing balances where the prior year is available; "
                    + "ROCE is EBIT over capital employed (total assets minus current liabilities). "
                    + "Debt / equity is a multiple (x), not a percentage. DATA_CONFLICT and INVALID mean the metric was "
                    + "not calculated or failed verification; n/a means an input is unavailable.\n\n");
            md.append("### Multi-year growth and cash conversion (CALCULATED)\n\n| Metric | Value | Period | Status | Calculation |\n")
                    .append("|---|---|---|---|---|\n");
            for (FinancialDataPoint p : List.of(summary.calculated().revenueCagrPercent(),
                    summary.calculated().netProfitCagrPercent(), summary.calculated().ocfToPat3yMultiple(),
                    summary.calculated().ocfToPat5yMultiple())) {
                md.append("| ").append(p.metric()).append(" | ").append(cell(p)).append(" | ")
                        .append(p.period().label()).append(" | ").append(status(p)).append(" | ")
                        .append(p.calculation() == null ? "" : p.calculation())
                        .append(p.status() == DataStatus.UNAVAILABLE ? " - " + p.statusReason() : "").append(" |\n");
            }
            md.append("\nOperating cash flow to net profit is a multiple (x) of the years' summed figures; it needs every "
                    + "year in its window, so with fewer years it is n/a rather than a ratio over a shorter period.\n\n");
        }
    }

    /** Point-in-time multiples and the price and per-share figures they were checked against. */
    private void renderValuation(StringBuilder md, ValuationResult result, Mask conflicted) {
        md.append("### Valuation (point in time)\n\n");
        if (!result.success()) {
            md.append("Valuation unavailable: ").append(result.message()).append("\n\n");
            return;
        }
        ValuationSnapshot v = result.valuation();
        ReportedValuation r = v.reported();
        md.append("_Measured against the share price ").append(v.asOf() != null ? "on " + v.asOf() : "at an unstated date")
                .append("; these change with the price. Facts about the price, not judgements: no fair value, price "
                        + "target or recommendation, and no benchmark is supplied to call any of them high or low._\n\n");
        md.append("| Metric | Value | As of | Status | Raw value (provider unit) | Source field |\n")
                .append("|---|---|---|---|---|---|\n");
        List<FinancialDataPoint> rows = new ArrayList<>(List.of(r.price(), v.trailingPe(), r.trailingEps(),
                v.priceToBook(), r.bookValuePerShare(), v.dividendYieldPercent(), r.dividendPerShare(),
                v.earningsYieldPercent()));
        if (v.filedFiguresUsed()) {
            rows.addAll(List.of(v.peOnFiledEps(), v.filedShareCount(), r.sharesOutstanding()));
        }
        for (FinancialDataPoint p : rows) {
            md.append("| ").append(p.metric()).append(" | ").append(value(p, conflicted)).append(" | ")
                    .append(p.period().label()).append(" | ").append(status(p)).append(" | ")
                    .append(raw(p, conflicted)).append(" | ").append(fields(p)).append(" |\n");
        }
        md.append("\nTrailing P/E, price-to-book and dividend yield are REPORTED by the provider, not calculated by the "
                + "application; each was checked against the share price and per-share figure shown, and DATA_CONFLICT "
                + "means they disagree and the multiple is not usable. Earnings yield is CALCULATED, as 100 / trailing "
                + "P/E.");
        if (v.filedFiguresUsed()) {
            md.append(" P/E on filed EPS is CALCULATED as the share price over the latest fiscal year's basic EPS as "
                    + "the company filed it, and only while the filing's share count matches today's; it is over a "
                    + "fiscal year, not the trailing twelve months.");
        }
        md.append("\n\n");

    }

    private void renderHistory(StringBuilder md, HistoricalPerformance p, Mask conflicted) {
        md.append("Window: ").append(p.window().start()).append(" to ").append(p.window().end())
                .append(" (requested ").append(p.requestedYears()).append(" years, covered ")
                .append(p.actualYears().display()).append("). Basis: ").append(p.basis())
                .append(". Prices in INR per share.\n\n");
        md.append("| Metric | Value | Date / period | Status |\n|---|---|---|---|\n");
        for (FinancialDataPoint point : List.of(p.startPrice(), p.endPrice(), p.totalReturnPercent(), p.cagrPercent(),
                p.periodHigh(), p.periodLow(), p.maxDrawdownPercent())) {
            md.append("| ").append(point.metric()).append(" | ").append(value(point, conflicted)).append(" | ")
                    .append(point.period().label()).append(" | ").append(status(point)).append(" |\n");
        }
        md.append("\nMaximum drawdown peak ").append(p.drawdownPeakDate()).append(", trough ")
                .append(p.drawdownTroughDate()).append(".\n\n");
        md.append("| Year | From | To | Close | Return |\n|---|---|---|---|---|\n");
        for (CalendarYearReturn year : p.calendarYearReturns()) {
            md.append("| ").append(year.year()).append(year.yearToDate() ? " (YTD, partial)" : "").append(" | ")
                    .append(year.fromDate() == null ? "-" : year.fromDate()).append(" | ").append(year.toDate())
                    .append(" | ").append(year.closingPrice().toPlainString()).append(" | ")
                    .append(year.returnPercent() == null ? "n/a (no prior year-end in window)"
                            : year.returnPercent().toPlainString() + "%")
                    .append(" |\n");
        }
        md.append("\n");
    }

    private void renderAudit(StringBuilder md, List<FinancialDataPoint> points) {
        md.append("| Metric | Period | Inputs | Formula | Result | Status |\n|---|---|---|---|---|---|\n");
        for (FinancialDataPoint p : points) {
            String inputs = p.inputs() != null
                    ? String.join("; ", p.inputs().stream().map(ResearchReportRenderer::auditInput).toList())
                    : p.inputFields() != null ? "fields: " + String.join(", ", p.inputFields()) : "-";
            md.append("| ").append(p.metric()).append(" | ").append(p.period().label()).append(" | ").append(inputs)
                    .append(" | ").append(p.calculation() == null ? "-" : p.calculation()).append(" | ")
                    .append(p.available() ? p.display() : "not produced").append(" | ").append(p.status())
                    .append(p.available() ? "" : " - " + p.statusReason()).append(" |\n");
        }
        md.append("\n");
    }

    private static String auditInput(CalculationInput input) {
        return input.name() + " = " + input.value().toPlainString() + (input.unit() == null ? "" : " " + input.unit())
                + (input.sourceField() == null ? "" : " (" + input.sourceField() + ")");
    }

    private List<FinancialDataPoint> calculatedPoints(StockResearchReport report) {
        List<FinancialDataPoint> points = new ArrayList<>();
        if (report.financials().success()) {
            report.financials().financialSummary().calculated().points().stream()
                    .filter(p -> p.calculationStatus() == CalculationStatus.CALCULATED).forEach(points::add);
        }
        if (report.valuation().success()) {
            Stream.of(report.valuation().valuation().earningsYieldPercent(), report.valuation().valuation().peOnFiledEps(),
                            report.valuation().valuation().filedShareCount())
                    .filter(p -> p.calculationStatus() == CalculationStatus.CALCULATED).forEach(points::add);
        }
        if (report.historicalPerformance().success()) {
            HistoricalPerformance h = report.historicalPerformance().historicalPerformance();
            Stream.of(h.totalReturnPercent(), h.cagrPercent(), h.maxDrawdownPercent())
                    .filter(p -> p.calculationStatus() == CalculationStatus.CALCULATED).forEach(points::add);
        }
        return points;
    }

    /** One line stating the original and normalised currency of the amounts just shown, and how they were normalised. */
    private void currencyLine(StringBuilder md, List<FinancialDataPoint> points) {
        List<String> currencies = points.stream().map(FinancialDataPoint::currency).filter(Objects::nonNull)
                .map(ResearchReportRenderer::describe).distinct().toList();
        if (!currencies.isEmpty()) {
            md.append("Currency: ").append(String.join("; ", currencies)).append(".\n\n");
        }
    }

    private static String describe(CurrencyInfo currency) {
        return "source %s, shown in %s (%s)".formatted(currency.originalCurrency(), currency.normalizedCurrency(),
                currency.conversion() == CurrencyInfo.Conversion.NONE ? "as reported"
                        : "scaled only - no currency conversion");
    }

    private void companyPoint(StringBuilder md, String label, FinancialDataPoint p, Mask conflicted) {
        md.append("| ").append(label).append(" | ").append(value(p, conflicted)).append(" | ").append(status(p))
                .append(" | ").append(raw(p, conflicted)).append(" | ")
                .append(p.source() != null && p.source().asOf() != null ? p.source().asOf().toString() : "UNKNOWN")
                .append(" | ").append(fields(p)).append(" |\n");
    }

    private void text(StringBuilder md, String label, String value, String field) {
        md.append("| ").append(label).append(" | ").append(value == null ? "UNAVAILABLE" : value)
                .append(" | REPORTED | - | - | ").append(field).append(" |\n");
    }

    private String value(FinancialDataPoint p, Mask conflicted) {
        if (!conflicted.covers(p)) {
            return switch (p.status()) {
                case VALID -> p.display();
                case UNAVAILABLE -> "UNAVAILABLE - " + p.statusReason();
                case DATA_CONFLICT, INVALID -> p.status().name();
            };
        }
        if (conflicted.forModel()) {
            return WITHHELD_FOR_MODEL;
        }
        return p.value() != null ? p.display() + CONFLICT_TAG : "DATA_CONFLICT";
    }

    /** A calculated table cell: the result if VALID, otherwise only why there is none. */
    private String cell(FinancialDataPoint p) {
        return switch (p.status()) {
            case VALID -> p.display();
            case UNAVAILABLE -> "n/a";
            case DATA_CONFLICT, INVALID -> p.status().name();
        };
    }

    private String raw(FinancialDataPoint p, Mask conflicted) {
        return conflicted.forModel() && conflicted.covers(p) ? "-" : raw(p);
    }

    private String status(FinancialDataPoint p) {
        return p.available() ? p.calculationStatus().name() : p.status().name();
    }

    private String raw(FinancialDataPoint p) {
        if (p.rawValue() == null) {
            return "-";
        }
        return p.rawValue().toPlainString() + " (" + (p.providerUnit() == null ? "meaning unverified" : p.providerUnit()) + ")";
    }

    private String fields(FinancialDataPoint p) {
        return String.join(", ", p.dependsOnFields());
    }
}
