package com.ashish.stockresearch.research.report;

import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.model.AnnualRatios;
import com.ashish.stockresearch.research.model.CalendarYearReturn;
import com.ashish.stockresearch.research.model.CompanyProfile;
import com.ashish.stockresearch.research.model.CompanyProfileResult;
import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.FinancialSummary;
import com.ashish.stockresearch.research.model.FinancialSummaryResult;
import com.ashish.stockresearch.research.model.HistoricalPerformance;
import com.ashish.stockresearch.research.model.HistoricalPerformanceResult;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.ShareholdingResult;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import com.ashish.stockresearch.research.sector.SectorContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Assembles a {@link StockResearchReport} from the four research
 * capabilities. Each capability fails independently: a missing section
 * becomes a data gap, never a reason to drop the whole report.
 *
 * Observations are generated here from calculated values using fixed,
 * neutral templates ("rose", "fell", "grew at a compound rate of"), so they
 * cannot contain a number, a cause or a label the data does not support.
 * Any observation that depends on a field in a DATA_CONFLICT is withheld -
 * the conflict is reported instead of a conclusion drawn from either side.
 */
@Service
public class ResearchReportService {

    static final int HISTORY_YEARS = 5;
    static final String SHAREHOLDING_UNAVAILABLE =
            "Shareholding data was not available from the configured provider.";

    private final StockResearchService research;
    private final FinancialDataValidator validator;
    private final SectorClassifier sectorClassifier;

    public ResearchReportService(StockResearchService research, FinancialDataValidator validator,
                                 SectorClassifier sectorClassifier) {
        this.research = research;
        this.validator = validator;
        this.sectorClassifier = sectorClassifier;
    }

    public StockResearchReport build(String symbol) {
        CompanyProfileResult profile = research.getCompanyProfile(symbol);
        FinancialSummaryResult financials = research.getFinancialSummary(symbol);
        HistoricalPerformanceResult history = research.getHistoricalPerformance(symbol, HISTORY_YEARS);
        ShareholdingResult shareholding = research.getShareholding(symbol);
        return assemble(symbol, profile, financials, history, shareholding);
    }

    StockResearchReport assemble(String requested, CompanyProfileResult profile, FinancialSummaryResult financials,
                                 HistoricalPerformanceResult history, ShareholdingResult shareholding) {
        List<Observation> candidates = new ArrayList<>();
        List<DataGap> gaps = new ArrayList<>();
        List<DataQualityIssue> issues = new ArrayList<>();
        List<SourceReference> sources = new ArrayList<>();

        SectorContext sector = SectorContext.of(IndustryGroup.UNKNOWN,
                "The company profile could not be retrieved, so the sector is not identified");
        if (profile.success()) {
            CompanyProfile p = profile.companyProfile();
            sector = sectorClassifier.classify(p.sector(), p.industry());
            issues.addAll(p.dataQualityIssues());
            if (!p.marketCap().available()) {
                gaps.add(new DataGap("Company", "marketCap", p.marketCap().unavailableReason()));
            }
            sources.add(reference(p.provenance(), "Company profile and market capitalisation"));
        } else {
            gaps.add(new DataGap("Company", "company profile", profile.message()));
        }

        ReportingPeriod latestQuarter = ReportingPeriod.unknown();
        if (financials.success()) {
            FinancialSummary f = financials.financialSummary();
            latestQuarter = f.reported().latestReportedQuarter();
            gaps.addAll(f.dataGaps());
            issues.addAll(f.dataQualityIssues());
            candidates.addAll(financialObservations(f));
            sources.add(reference(f.reported().provenance(), "Financial statements, quarterly results and ratios"));
        } else {
            gaps.add(new DataGap("Financials", "financial summary", financials.message()));
        }

        if (history.success()) {
            candidates.addAll(priceObservations(history.historicalPerformance()));
            sources.add(reference(history.historicalPerformance().provenance(), "Historical share prices"));
        } else {
            gaps.add(new DataGap("Share price history", "historical performance", history.message()));
        }

        if (shareholding.success()) {
            sources.add(reference(shareholding.shareholding().provenance(), "Shareholding pattern"));
        } else {
            gaps.add(new DataGap("Shareholding", "promoter / FII / DII / public split",
                    SHAREHOLDING_UNAVAILABLE + " (" + shareholding.status() + ")"));
        }

        Set<String> conflicted = new LinkedHashSet<>();
        issues.stream().filter(i -> i.type() == DataQualityIssue.Type.DATA_CONFLICT)
                .forEach(i -> conflicted.addAll(i.affectedFields()));
        List<Observation> observations = candidates.stream()
                .filter(o -> o.inputFields().stream().noneMatch(conflicted::contains)).toList();
        List<Observation> withheld = candidates.stream()
                .filter(o -> o.inputFields().stream().anyMatch(conflicted::contains)).toList();

        return new StockResearchReport(
                requested,
                firstNonNull(profile.success() ? profile.companyProfile().symbol() : null,
                        financials.success() ? financials.financialSummary().reported().symbol() : null, requested),
                firstNonNull(profile.success() ? profile.companyProfile().companyName() : null,
                        financials.success() ? financials.financialSummary().reported().companyName() : null, requested),
                validator.today(),
                latestQuarter,
                sector,
                profile, financials, history, shareholding,
                observations, withheld, List.copyOf(gaps), List.copyOf(issues), List.copyOf(sources));
    }

    private List<Observation> financialObservations(FinancialSummary summary) {
        List<Observation> observations = new ArrayList<>();
        List<AnnualRatios> annual = summary.calculated().annual();
        if (!annual.isEmpty()) {
            AnnualRatios latest = annual.get(annual.size() - 1);
            change(latest.revenueGrowthYoyPercent(), "Revenue", latest.period().label() + " vs the prior fiscal year")
                    .ifPresent(observations::add);
            change(latest.netProfitGrowthYoyPercent(), "Net profit", latest.period().label() + " vs the prior fiscal year")
                    .ifPresent(observations::add);
            AnnualRatios first = annual.get(0);
            if (first != latest && first.netProfitMarginPercent().available() && latest.netProfitMarginPercent().available()) {
                observations.add(new Observation(
                        "Net profit margin was %s in %s and %s in %s.".formatted(
                                first.netProfitMarginPercent().display(), first.period().label(),
                                latest.netProfitMarginPercent().display(), latest.period().label()),
                        "calculated: net profit / revenue per fiscal year",
                        fields(first.netProfitMarginPercent(), latest.netProfitMarginPercent())));
            }
            single(latest.returnOnEquityPercent(), "Return on equity for %s was %s.").ifPresent(observations::add);
            single(latest.returnOnAssetsPercent(), "Return on assets for %s was %s.").ifPresent(observations::add);
            single(latest.debtToEquityMultiple(), "Total debt was %2$s shareholders' equity at the end of %1$s.")
                    .ifPresent(observations::add);
        }
        cagr(summary.calculated().revenueCagrPercent(), "Revenue").ifPresent(observations::add);
        cagr(summary.calculated().netProfitCagrPercent(), "Net profit").ifPresent(observations::add);
        FinancialDataPoint qoq = summary.calculated().latestQuarterRevenueGrowthQoqPercent();
        List<com.ashish.stockresearch.research.model.QuarterlyResult> quarters = summary.reported().recentQuarters();
        if (qoq.available() && quarters.size() >= 2) {
            change(qoq, "Revenue quarter-on-quarter", "%s vs %s, not year-on-year".formatted(qoq.period().label(),
                    quarters.get(quarters.size() - 2).period().label())).ifPresent(observations::add);
        }
        return observations;
    }

    private List<Observation> priceObservations(HistoricalPerformance performance) {
        List<Observation> observations = new ArrayList<>();
        FinancialDataPoint cagr = performance.cagrPercent();
        if (cagr.available()) {
            observations.add(new Observation("The share price changed at a compound annual rate of %s from %s to %s (%s); "
                    .formatted(cagr.display(), performance.window().start(), performance.window().end(),
                            performance.actualYears().display())
                    + "total return %s.".formatted(performance.totalReturnPercent().display()),
                    "calculated: " + cagr.calculation(), cagr.dependsOnFields()));
        }
        FinancialDataPoint drawdown = performance.maxDrawdownPercent();
        if (drawdown.available()) {
            observations.add(new Observation("The largest peak-to-trough fall in the window was %s (peak %s, trough %s)."
                    .formatted(drawdown.display(), performance.drawdownPeakDate(), performance.drawdownTroughDate()),
                    "calculated: " + drawdown.calculation(), drawdown.dependsOnFields()));
        }
        List<CalendarYearReturn> fullYears = performance.calendarYearReturns().stream()
                .filter(year -> year.returnPercent() != null && !year.yearToDate())
                .toList();
        List<String> priceFields = cagr.dependsOnFields();
        if (fullYears.size() >= 2) {
            CalendarYearReturn best = fullYears.stream().max(Comparator.comparing(CalendarYearReturn::returnPercent)).orElseThrow();
            CalendarYearReturn worst = fullYears.stream().min(Comparator.comparing(CalendarYearReturn::returnPercent)).orElseThrow();
            observations.add(new Observation("Across full calendar years in the window, annual price returns ranged "
                    + "from %s%% (%d) to %s%% (%d).".formatted(worst.returnPercent().toPlainString(), worst.year(),
                    best.returnPercent().toPlainString(), best.year()),
                    "calculated: calendar-year returns from year-end closes", priceFields));
        }
        performance.calendarYearReturns().stream()
                .filter(CalendarYearReturn::yearToDate)
                .filter(year -> year.returnPercent() != null)
                .findFirst()
                .ifPresent(ytd -> observations.add(new Observation(
                        "%d year-to-date (to %s, a partial year) the price return was %s%%.".formatted(
                                ytd.year(), ytd.toDate(), ytd.returnPercent().toPlainString()),
                        "calculated: from the %s close to the %s close".formatted(ytd.fromDate(), ytd.toDate()),
                        priceFields)));
        return observations;
    }

    private Optional<Observation> change(FinancialDataPoint growth, String subject, String comparison) {
        if (growth == null || !growth.available()) {
            return Optional.empty();
        }
        String direction = growth.value().signum() > 0 ? "rose" : growth.value().signum() < 0 ? "fell" : "was unchanged";
        String amount = growth.value().signum() == 0 ? "" : " " + growth.value().abs().toPlainString() + "%";
        return Optional.of(new Observation("%s %s%s (%s).".formatted(subject, direction, amount, comparison),
                "calculated: " + growth.calculation(), growth.dependsOnFields()));
    }

    private Optional<Observation> single(FinancialDataPoint point, String template) {
        if (point == null || !point.available()) {
            return Optional.empty();
        }
        return Optional.of(new Observation(template.formatted(point.period().label(), point.display()),
                "calculated: " + point.calculation(), point.dependsOnFields()));
    }

    /** Neutral CAGR wording - comparing two CAGRs is not acceleration, so no such word is ever generated. */
    private Optional<Observation> cagr(FinancialDataPoint cagr, String subject) {
        if (cagr == null || !cagr.available()) {
            return Optional.empty();
        }
        return Optional.of(new Observation("%s grew at a compound annual rate of %s over %s.".formatted(
                subject, cagr.display(), cagr.period().label()),
                "calculated: " + cagr.calculation(), cagr.dependsOnFields()));
    }

    private static List<String> fields(FinancialDataPoint... points) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (FinancialDataPoint point : points) {
            fields.addAll(point.dependsOnFields());
        }
        return List.copyOf(fields);
    }

    private SourceReference reference(DataProvenance provenance, String covers) {
        return new SourceReference(provenance.source(), provenance.sourceType(), covers,
                provenance.publishedAt(), provenance.asOf(), provenance.periodEnd());
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
