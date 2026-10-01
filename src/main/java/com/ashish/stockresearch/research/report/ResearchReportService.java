package com.ashish.stockresearch.research.report;

import com.ashish.stockresearch.trace.Progress;
import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.model.AnnualRatios;
import com.ashish.stockresearch.research.model.CalendarYearReturn;
import com.ashish.stockresearch.research.model.CompanyProfile;
import com.ashish.stockresearch.research.model.CompanyProfileResult;
import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.DataProvenance;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.FinancialSummary;
import com.ashish.stockresearch.research.model.FinancialSummaryResult;
import com.ashish.stockresearch.research.model.HistoricalPerformance;
import com.ashish.stockresearch.research.model.HistoricalPerformanceResult;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.ShareholdingResult;
import com.ashish.stockresearch.research.model.ValuationResult;
import com.ashish.stockresearch.research.model.ValuationSnapshot;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Assembles a {@link StockResearchReport} from the four research
 * capabilities. Each capability fails independently: a missing section
 * becomes a data gap, never a reason to drop the whole report.
 *
 * Observations are generated here from calculated values using fixed,
 * neutral templates ("rose", "fell", "grew at a compound rate of"), so they
 * cannot contain a number, a cause or a label the data does not support.
 * Only VALID values produce observations. A metric that is in a
 * DATA_CONFLICT or INVALID, or that is not a primary indicator for the
 * company's industry group, produces a {@link WithheldObservation} instead -
 * stated in the report, and passed on so no interpretation is drawn from it
 * either.
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

    /**
     * Fetches the four sections at once, each on its own virtual thread; they are independent
     * provider calls, so the report waits for the slowest rather than for their sum. Each section
     * still fails on its own: {@link StockResearchService} turns a provider failure into a failed
     * result, which becomes a data gap rather than failing the report.
     */
    public StockResearchReport build(String symbol) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // each fetch is a step of its own, reported from the thread that runs it
            CompletableFuture<CompanyProfileResult> profile = CompletableFuture.supplyAsync(fetch(
                    "Fetching the company profile of %s (Yahoo Finance)".formatted(symbol),
                    () -> research.getCompanyProfile(symbol), CompanyProfileResult::success,
                    CompanyProfileResult::message), executor);
            CompletableFuture<FinancialSummaryResult> financials = CompletableFuture.supplyAsync(fetch(
                    "Fetching the results %s filed with NSE, cross-checked with Yahoo Finance".formatted(symbol),
                    () -> research.getFinancialSummary(symbol), FinancialSummaryResult::success,
                    FinancialSummaryResult::message), executor);
            CompletableFuture<HistoricalPerformanceResult> history = CompletableFuture.supplyAsync(fetch(
                    "Fetching %d years of share prices of %s (Yahoo Finance)".formatted(HISTORY_YEARS, symbol),
                    () -> research.getHistoricalPerformance(symbol, HISTORY_YEARS),
                    HistoricalPerformanceResult::success, HistoricalPerformanceResult::message), executor);
            // no source supplies shareholding yet: a known gap, not a failure of this request
            CompletableFuture<ShareholdingResult> shareholding = CompletableFuture.supplyAsync(fetch(
                    "Checking for shareholding data on %s".formatted(symbol),
                    () -> research.getShareholding(symbol),
                    result -> result.success() || result.status() == ResearchStatus.CAPABILITY_NOT_SUPPORTED,
                    ShareholdingResult::message), executor);
            CompletableFuture<ValuationResult> valuation = CompletableFuture.supplyAsync(fetch(
                    "Fetching the valuation of %s (Yahoo Finance, checked against filed EPS)".formatted(symbol),
                    () -> research.getValuation(symbol), ValuationResult::success, ValuationResult::message), executor);
            CompanyProfileResult p = profile.join();
            FinancialSummaryResult f = financials.join();
            HistoricalPerformanceResult h = history.join();
            ShareholdingResult s = shareholding.join();
            ValuationResult v = valuation.join();
            return Progress.step("Checking data quality and drawing the observations",
                    () -> assemble(symbol, p, f, h, s, v));
        }
    }

    /** One fetch as a progress step, ended as failed (with the reason) when it brought back nothing usable. */
    private static <T> java.util.function.Supplier<T> fetch(String label, java.util.function.Supplier<T> work,
                                                           java.util.function.Predicate<T> success,
                                                           java.util.function.Function<T, String> message) {
        return Progress.propagate(() -> {
            Progress.Step step = Progress.start(label);
            T result = work.get();
            if (result != null && success.test(result)) {
                step.close();
            } else {
                step.failed(result == null ? "nothing was returned" : message.apply(result));
            }
            return result;
        });
    }

    StockResearchReport assemble(String requested, CompanyProfileResult profile, FinancialSummaryResult financials,
                                 HistoricalPerformanceResult history, ShareholdingResult shareholding,
                                 ValuationResult valuation) {
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
                gaps.add(new DataGap("Company", "marketCap", p.marketCap().statusReason()));
            }
            sources.add(reference(p.provenance(), "Company profile and market capitalisation"));
        } else {
            gaps.add(new DataGap("Company", "company profile", profile.message()));
        }

        ReportingPeriod latestQuarter = ReportingPeriod.unknown();
        List<WithheldObservation> withheld = new ArrayList<>();
        if (financials.success()) {
            FinancialSummary f = financials.financialSummary();
            latestQuarter = f.reported().latestReportedQuarter();
            gaps.addAll(f.dataGaps());
            issues.addAll(f.dataQualityIssues());
            currencyWarning(f).ifPresent(issues::add);
            candidates.addAll(financialObservations(f, sector, withheld));
            Optional<SourceReference> filed = filedResults(f.reported());
            sources.add(reference(f.reported().provenance(), filed.isPresent()
                    ? "Trailing-twelve-month figures, quarterly results and ratios; the cross-check of filed net profit"
                    : "Financial statements, quarterly results and ratios"));
            filed.ifPresent(sources::add);
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

        // Valuation is stated as facts about the price only: no observation is drawn from it, since
        // nothing supplies a benchmark to call a multiple high or low.
        if (valuation.success()) {
            ValuationSnapshot v = valuation.valuation();
            issues.addAll(v.dataQualityIssues());
            gaps.addAll(v.dataGaps());
            sources.add(reference(v.reported().provenance(), "Share price and valuation multiples"));
        } else {
            gaps.add(new DataGap("Valuation", "P/E, price-to-book, dividend yield and earnings yield",
                    valuation.message()));
        }

        Set<String> conflicted = new LinkedHashSet<>();
        issues.stream().filter(i -> i.type() == DataQualityIssue.Type.DATA_CONFLICT)
                .forEach(i -> conflicted.addAll(i.affectedFields()));
        // A second line of defence: an observation built from a value that depends on a conflicting field
        // from another section (e.g. a market-cap conflict) is withheld too.
        List<Observation> observations = candidates.stream()
                .filter(o -> o.inputFields().stream().noneMatch(conflicted::contains)).toList();
        candidates.stream()
                .filter(o -> o.inputFields().stream().anyMatch(conflicted::contains))
                .forEach(o -> withheld.add(new WithheldObservation(o.statement(), o.topic(),
                        WithheldObservation.Reason.DATA_CONFLICT, "depends on a field in a DATA_CONFLICT: "
                        + String.join(", ", o.inputFields().stream().filter(conflicted::contains).toList()))));

        return new StockResearchReport(
                requested,
                firstNonNull(profile.success() ? profile.companyProfile().symbol() : null,
                        financials.success() ? financials.financialSummary().reported().symbol() : null, requested),
                firstNonNull(profile.success() ? profile.companyProfile().companyName() : null,
                        financials.success() ? financials.financialSummary().reported().companyName() : null, requested),
                validator.today(),
                latestQuarter,
                sector,
                profile, financials, history, shareholding, valuation,
                observations, List.copyOf(withheld), List.copyOf(gaps), List.copyOf(issues), List.copyOf(sources));
    }

    /** Accounts in another currency than the share price: say so, since nothing combining them is calculated. */
    private Optional<DataQualityIssue> currencyWarning(FinancialSummary summary) {
        String currency = summary.reported().reportingCurrency();
        if (currency == null || "INR".equalsIgnoreCase(currency)) {
            return Optional.empty();
        }
        return Optional.of(DataQualityIssue.warning(("The source reports this company's headline financial figures in "
                + "%s, while its share price and market capitalisation are in INR. No exchange rate is sourced, so no "
                + "currency conversion is performed and no ratio combining the accounts with the price (such as "
                + "price-to-sales) is calculated; valuation multiples are checked only against the provider's rupee "
                + "per-share figures. Figures are shown in the currency the source reported them in.").formatted(currency),
                List.of("financialData.financialCurrency")));
    }

    /**
     * Neutral observations from VALID calculated values. A metric that exists but may not be used - in a
     * DATA_CONFLICT, INVALID, or not a primary indicator for the sector - is recorded as withheld.
     */
    private List<Observation> financialObservations(FinancialSummary summary, SectorContext sector,
                                                    List<WithheldObservation> withheld) {
        List<Observation> observations = new ArrayList<>();
        Templates t = new Templates(sector, observations, withheld);
        List<AnnualRatios> annual = summary.calculated().annual();
        if (!annual.isEmpty()) {
            AnnualRatios latest = annual.get(annual.size() - 1);
            String vsPrior = latest.period().label() + " vs the prior fiscal year";
            t.change(latest.revenueGrowthYoyPercent(), "Revenue", "revenue growth", vsPrior);
            t.change(latest.netProfitGrowthYoyPercent(), "Net profit", "net profit growth", vsPrior);
            AnnualRatios first = annual.get(0);
            if (first != latest) {
                t.pair(first.netProfitMarginPercent(), latest.netProfitMarginPercent(), "Net profit margin",
                        "net profit margin");
                t.pair(first.operatingMarginPercent(), latest.operatingMarginPercent(), "Operating margin",
                        "operating margin");
            }
            t.single(latest.returnOnEquityPercent(), "Return on equity for %s was %s.", "Return on equity",
                    "return on equity");
            t.single(latest.returnOnAssetsPercent(), "Return on assets for %s was %s.", "Return on assets",
                    "return on assets");
            t.single(latest.debtToEquityMultiple(), "Total debt was %2$s shareholders' equity at the end of %1$s.",
                    "Debt-to-equity", "debt-to-equity");
            t.single(latest.returnOnCapitalEmployedPercent(), "Return on capital employed for %s was %s.",
                    "Return on capital employed", "return on capital employed");
        }
        for (FinancialDataPoint cashConversion : List.of(summary.calculated().ocfToPat3yMultiple(),
                summary.calculated().ocfToPat5yMultiple())) {
            t.single(cashConversion, "Operating cash flow over %s was %s net profit over the same years.",
                    "Operating cash flow to net profit", "cash conversion");
        }
        t.cagr(summary.calculated().revenueCagrPercent(), "Revenue", "revenue growth");
        t.cagr(summary.calculated().netProfitCagrPercent(), "Net profit", "net profit growth");
        FinancialDataPoint qoq = summary.calculated().latestQuarterRevenueGrowthQoqPercent();
        List<com.ashish.stockresearch.research.model.QuarterlyResult> quarters = summary.reported().recentQuarters();
        if (quarters.size() >= 2) {
            t.change(qoq, "Revenue quarter-on-quarter", "revenue growth", "%s vs %s, not year-on-year".formatted(
                    qoq.period().label(), quarters.get(quarters.size() - 2).period().label()));
        }
        return observations;
    }

    /**
     * The fixed observation templates. Each either adds an observation built from VALID values, records why
     * it is withheld, or - when the metric is simply UNAVAILABLE - adds nothing, since that is already a
     * data gap.
     */
    private record Templates(SectorContext sector, List<Observation> observations, List<WithheldObservation> withheld) {

        void change(FinancialDataPoint growth, String subject, String topic, String comparison) {
            if (usable(subject + " (" + comparison + ")", topic, growth)) {
                String direction = growth.value().signum() > 0 ? "rose"
                        : growth.value().signum() < 0 ? "fell" : "was unchanged";
                String amount = growth.value().signum() == 0 ? "" : " " + growth.value().abs().toPlainString() + "%";
                observations.add(new Observation("%s %s%s (%s).".formatted(subject, direction, amount, comparison),
                        "calculated: " + growth.calculation(), growth.dependsOnFields(), topic));
            }
        }

        void single(FinancialDataPoint point, String template, String subject, String topic) {
            if (usable(subject + " (" + point.period().label() + ")", topic, point)) {
                observations.add(new Observation(template.formatted(point.period().label(), point.display()),
                        "calculated: " + point.calculation(), point.dependsOnFields(), topic));
            }
        }

        void pair(FinancialDataPoint first, FinancialDataPoint latest, String subject, String topic) {
            String name = "%s (%s and %s)".formatted(subject, first.period().label(), latest.period().label());
            if (usable(name, topic, first) && usable(name, topic, latest)) {
                observations.add(new Observation("%s was %s in %s and %s in %s.".formatted(subject, first.display(),
                        first.period().label(), latest.display(), latest.period().label()),
                        "calculated per fiscal year: " + latest.calculation(), fields(first, latest), topic));
            }
        }

        /** Neutral CAGR wording - comparing two CAGRs is not acceleration, so no such word is ever generated. */
        void cagr(FinancialDataPoint cagr, String subject, String topic) {
            if (usable(subject + " CAGR (" + cagr.period().label() + ")", topic, cagr)) {
                observations.add(new Observation("%s grew at a compound annual rate of %s over %s.".formatted(
                        subject, cagr.display(), cagr.period().label()),
                        "calculated: " + cagr.calculation(), cagr.dependsOnFields(), topic));
            }
        }

        /** VALID and relevant to the sector; otherwise records why not (UNAVAILABLE is already a data gap). */
        private boolean usable(String subject, String topic, FinancialDataPoint point) {
            if (point == null || point.status() == DataStatus.UNAVAILABLE) {
                return false; // already reported as a data gap
            }
            if (sector.isNonPrimary(point.metric())) {
                if (withheld.stream().noneMatch(w -> w.topic().equals(topic)
                        && w.reason() == WithheldObservation.Reason.SECTOR_CONTEXT)) {
                    withheld.add(new WithheldObservation(subject, topic, WithheldObservation.Reason.SECTOR_CONTEXT,
                            "%s is not a primary indicator of financial health for the %s industry group; no observation "
                                    .formatted(capitalise(topic), sector.group())
                                    + "or conclusion is drawn from it without sector-specific context"));
                }
                return false;
            }
            switch (point.status()) {
                case VALID -> {
                    return true;
                }
                case DATA_CONFLICT -> withheld.add(new WithheldObservation(subject, topic,
                        WithheldObservation.Reason.DATA_CONFLICT, point.statusReason()));
                case INVALID -> withheld.add(new WithheldObservation(subject, topic,
                        WithheldObservation.Reason.INVALID, point.statusReason()));
                default -> {
                }
            }
            return false;
        }

        private static String capitalise(String text) {
            return Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }
    }

    private List<Observation> priceObservations(HistoricalPerformance performance) {
        List<Observation> observations = new ArrayList<>();
        FinancialDataPoint cagr = performance.cagrPercent();
        if (cagr.available()) {
            observations.add(new Observation("The share price changed at a compound annual rate of %s from %s to %s (%s); "
                    .formatted(cagr.display(), performance.window().start(), performance.window().end(),
                            performance.actualYears().display())
                    + "total return %s.".formatted(performance.totalReturnPercent().display()),
                    "calculated: " + cagr.calculation(), cagr.dependsOnFields(), "share price performance"));
        }
        FinancialDataPoint drawdown = performance.maxDrawdownPercent();
        if (drawdown.available()) {
            observations.add(new Observation("The largest peak-to-trough fall in the window was %s (peak %s, trough %s)."
                    .formatted(drawdown.display(), performance.drawdownPeakDate(), performance.drawdownTroughDate()),
                    "calculated: " + drawdown.calculation(), drawdown.dependsOnFields(), "share price performance"));
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
                    "calculated: calendar-year returns from year-end closes", priceFields, "share price performance"));
        }
        performance.calendarYearReturns().stream()
                .filter(CalendarYearReturn::yearToDate)
                .filter(year -> year.returnPercent() != null)
                .findFirst()
                .ifPresent(ytd -> observations.add(new Observation(
                        "%d year-to-date (to %s, a partial year) the price return was %s%%.".formatted(
                                ytd.year(), ytd.toDate(), ytd.returnPercent().toPlainString()),
                        "calculated: from the %s close to the %s close".formatted(ytd.fromDate(), ytd.toDate()),
                        priceFields, "share price performance")));
        return observations;
    }

    private static List<String> fields(FinancialDataPoint... points) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (FinancialDataPoint point : points) {
            fields.addAll(point.dependsOnFields());
        }
        return List.copyOf(fields);
    }

    /** The company's filed results, when they are the source of the fiscal-year figures. */
    private static Optional<SourceReference> filedResults(com.ashish.stockresearch.research.model.ReportedFinancials reported) {
        List<com.ashish.stockresearch.research.model.FinancialDataPoint> filed = reported.annualHistory().stream()
                .map(com.ashish.stockresearch.research.model.AnnualFinancials::netProfit)
                .filter(point -> point.source() != null
                        && point.source().sourceType() == com.ashish.stockresearch.research.model.SourceType.OFFICIAL_FILING)
                .toList();
        if (filed.isEmpty()) {
            return Optional.empty();
        }
        com.ashish.stockresearch.research.model.FinancialDataPoint latest = filed.get(filed.size() - 1);
        return Optional.of(new SourceReference(latest.source().source(),
                com.ashish.stockresearch.research.model.SourceType.OFFICIAL_FILING,
                "Fiscal-year results (%s to %s), as the company filed them with NSE".formatted(
                        filed.get(0).period().label(), latest.period().label()),
                latest.source().publishedAt(), latest.source().asOf(), latest.source().asOf()));
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
