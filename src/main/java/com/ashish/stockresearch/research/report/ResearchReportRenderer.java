package com.ashish.stockresearch.research.report;

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
import com.ashish.stockresearch.research.model.ShareholdingResult;
import com.ashish.stockresearch.research.sector.SectorContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
    private record Mask(Set<String> fields, boolean forModel) {

        boolean covers(FinancialDataPoint p) {
            return p.status() == DataStatus.DATA_CONFLICT || p.dependsOnFields().stream().anyMatch(fields::contains);
        }
    }

    static final String CONFLICT_TAG = " [DATA_CONFLICT]";
    /** What the model sees in place of a value that depends on a conflicting field. */
    static final String WITHHELD_FOR_MODEL = "WITHHELD - depends on a field in a DATA_CONFLICT";
    static final String CALCULATION_NOTE = "CALCULATED values were computed by the application from the inputs shown "
            + "in its calculation audit and verified against the source values before display. Quote them as given; "
            + "never recalculate them, derive new figures from them, or convert their units or currency.";

    public String render(StockResearchReport report, String interpretation) {
        Mask conflicted = new Mask(report.conflictedFields(), false);
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

    /** What the model is given to interpret: conflicts first, then facts, observations, withheld topics and gaps. */
    public String renderEvidence(StockResearchReport report) {
        Mask conflicted = new Mask(report.conflictedFields(), true);
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
        renderCompany(md, p, new Mask(conflictedFields(p.dataQualityIssues()), true));
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
        renderFinancials(md, summary, new Mask(conflictedFields(summary.dataQualityIssues()), true));
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

    public String renderHistoricalPerformanceTool(HistoricalPerformanceResult result) {
        StringBuilder md = toolHeader("getHistoricalPerformance", result.success(), result.status(), result.message());
        if (!result.success()) {
            return md.toString();
        }
        HistoricalPerformance p = result.historicalPerformance();
        md.append("Symbol: ").append(p.symbol()).append(" (").append(p.exchange()).append(")\n")
                .append("This is price HISTORY; endPrice is the last close in the window, not a live quote.\n\n");
        renderHistory(md, p, new Mask(Set.of(), true));
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

    private static Set<String> conflictedFields(List<DataQualityIssue> issues) {
        Set<String> fields = new LinkedHashSet<>();
        issues.stream().filter(i -> i.type() == DataQualityIssue.Type.DATA_CONFLICT)
                .forEach(i -> fields.addAll(i.affectedFields()));
        return fields;
    }

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
            md.append("### Fiscal years (source fields: fundamentals-timeseries)\n\n")
                    .append("| Fiscal year | Period end | Revenue | Net profit | Shareholders' equity | Total assets | Total debt |\n")
                    .append("|---|---|---|---|---|---|---|\n");
            for (AnnualFinancials year : annual) {
                md.append("| ").append(year.period().label()).append(" | ").append(year.period().end()).append(" | ")
                        .append(value(year.revenue(), conflicted)).append(" | ")
                        .append(value(year.netProfit(), conflicted)).append(" | ")
                        .append(value(year.shareholdersEquity(), conflicted)).append(" | ")
                        .append(value(year.totalAssets(), conflicted)).append(" | ")
                        .append(value(year.totalDebt(), conflicted)).append(" |\n");
            }
            md.append("\n");
            currencyLine(md, annual.stream().flatMap(y -> y.points().stream()).toList());
            md.append("### Calculated per fiscal year (CALCULATED by the application)\n\n")
                    .append("| Fiscal year | Revenue YoY | Net profit YoY | Net margin | Operating margin | ROE | ROA | Debt / equity |\n")
                    .append("|---|---|---|---|---|---|---|---|\n");
            for (AnnualRatios r : summary.calculated().annual()) {
                md.append("| ").append(r.period().label());
                for (FinancialDataPoint p : r.points()) {
                    md.append(" | ").append(cell(p));
                }
                md.append(" |\n");
            }
            md.append("\nROE and ROA use average opening and closing balances where the prior year is available. "
                    + "Debt / equity is a multiple (x), not a percentage. DATA_CONFLICT and INVALID mean the metric was "
                    + "not calculated or failed verification; n/a means an input is unavailable.\n\n");
            md.append("### Multi-year growth (CALCULATED)\n\n| Metric | Value | Period | Status | Calculation |\n")
                    .append("|---|---|---|---|---|\n");
            for (FinancialDataPoint p : List.of(summary.calculated().revenueCagrPercent(),
                    summary.calculated().netProfitCagrPercent())) {
                md.append("| ").append(p.metric()).append(" | ").append(cell(p)).append(" | ")
                        .append(p.period().label()).append(" | ").append(status(p)).append(" | ")
                        .append(p.calculation() == null ? "" : p.calculation()).append(" |\n");
            }
            md.append("\n");
        }
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
