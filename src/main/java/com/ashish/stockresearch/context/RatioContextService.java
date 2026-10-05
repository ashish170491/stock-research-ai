package com.ashish.stockresearch.context;

import com.ashish.stockresearch.research.model.AnnualRatios;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.FinancialSummary;
import com.ashish.stockresearch.research.report.StockResearchReport;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.screening.FundamentalsSnapshotRepository;
import com.ashish.stockresearch.screening.ScreeningMetric;
import com.ashish.stockresearch.screening.SnapshotBuilder;
import com.ashish.stockresearch.screening.SnapshotRun;
import com.ashish.stockresearch.screening.Universe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Builds the context beside a research report's ratios: the company's own fiscal years from the report, and its
 * industry peers from the latest completed snapshot of the widest universe stored. Reads the snapshot database
 * only - never a data provider - so it adds milliseconds to a report.
 */
@Service
public class RatioContextService {

    /** The yearly ratios, in the order the report's questions take them. */
    private static final Map<String, Function<AnnualRatios, FinancialDataPoint>> YEARLY = new LinkedHashMap<>();

    static {
        YEARLY.put("returnOnEquityPercent", AnnualRatios::returnOnEquityPercent);
        YEARLY.put("returnOnAssetsPercent", AnnualRatios::returnOnAssetsPercent);
        YEARLY.put("returnOnCapitalEmployedPercent", AnnualRatios::returnOnCapitalEmployedPercent);
        YEARLY.put("operatingMarginPercent", AnnualRatios::operatingMarginPercent);
        YEARLY.put("netProfitMarginPercent", AnnualRatios::netProfitMarginPercent);
        YEARLY.put("debtToEquityMultiple", AnnualRatios::debtToEquityMultiple);
    }

    /** Figures with peers but no yearly history in the report: cash conversion, then the price measures. */
    private static final List<String> POINT_IN_TIME = List.of("ocfToPat3yMultiple", "ocfToPat5yMultiple",
            "trailingPe", "priceToBook", "earningsYieldPercent", "dividendYieldPercent");

    /** Groups whose members are not one industry, so they are not one another's peers. */
    private static final List<IndustryGroup> NO_PEERS = List.of(IndustryGroup.OTHER, IndustryGroup.UNKNOWN);

    private final FundamentalsSnapshotRepository repository;
    private final int minimumPeers;

    public RatioContextService(FundamentalsSnapshotRepository repository,
                               @Value("${app.research.context.minimum-peers:5}") int minimumPeers) {
        this.repository = repository;
        this.minimumPeers = minimumPeers;
    }

    /**
     * @param disputed values that depend on a value in a DATA_CONFLICT, as the report masks them; such a year is
     *                 shown with its status and left out of the range
     */
    public ReportContext build(StockResearchReport report, Predicate<FinancialDataPoint> disputed) {
        IndustryGroup group = report.sectorContext() == null ? IndustryGroup.UNKNOWN : report.sectorContext().group();
        String symbol = report.symbol();
        if (!report.financials().success() && !report.valuation().success()) {
            return ReportContext.none(symbol, group, "No financials or valuation were retrieved, so no context is given.");
        }

        Optional<SnapshotRun> run = Optional.empty();
        String peerNote = null;
        if (NO_PEERS.contains(group)) {
            peerNote = group == IndustryGroup.UNKNOWN
                    ? "The company's industry group is not known, so it has no peers to compare with."
                    : "OTHER is not one industry but the companies no other group takes, so they are not peers and no "
                    + "peer figures are given.";
        } else {
            run = latestSnapshot();
            if (run.isEmpty()) {
                peerNote = "No completed fundamentals snapshot exists yet, so no peer figures are given.";
            }
        }
        List<FundamentalsSnapshot> snapshots = run.map(r -> repository.snapshots(r.id())).orElse(List.of());

        List<ReportContext.Ratio> ratios = new ArrayList<>();
        List<String> notPrimary = new ArrayList<>();
        List<AnnualRatios> annual = report.financials().success()
                ? report.financials().financialSummary().calculated().annual() : List.of();
        boolean filed = filed(report);
        List<String> keys = new ArrayList<>(YEARLY.keySet());
        keys.addAll(POINT_IN_TIME);
        for (String key : keys) {
            if (!present(report, key)) {
                continue;
            }
            if (group.isNonPrimary(key)) {
                notPrimary.add(key);
                continue;
            }
            Optional<OwnHistory> history = YEARLY.containsKey(key)
                    ? OwnHistory.of(key, annual.stream().map(YEARLY.get(key)).toList(), filed, disputed)
                    : Optional.empty();
            Optional<PeerContext> peers = run.flatMap(r -> ScreeningMetric.forReportMetric(key)
                    .flatMap(metric -> PeerContext.of(metric, group, symbol, r, snapshots, minimumPeers)));
            // a ratio with no VALID year and no peers has nothing to place it beside (a bank's net margin)
            boolean anyYear = history.filter(h -> !h.usableYears().isEmpty()).isPresent();
            if (anyYear || peers.isPresent()) {
                ratios.add(new ReportContext.Ratio(key, history, peers));
            }
        }
        return new ReportContext(symbol, group, run.orElse(null), ratios, notPrimary, peerNote);
    }

    /** The latest completed snapshot of the widest universe that has one: more companies make more peers. */
    private Optional<SnapshotRun> latestSnapshot() {
        Universe[] universes = Universe.values();
        for (int i = universes.length - 1; i >= 0; i--) {
            Optional<SnapshotRun> run = repository.latestCompletedRun(universes[i]);
            if (run.isPresent()) {
                return run;
            }
        }
        return Optional.empty();
    }

    /** True when every fiscal year the report has was read from the company's NSE filings. */
    private static boolean filed(StockResearchReport report) {
        if (!report.financials().success()) {
            return false;
        }
        int years = report.financials().financialSummary().reported().annualHistory().size();
        return years > 0 && SnapshotBuilder.filedFiscalYears(report.financials()) == years;
    }

    /** Whether the report has the figure at all. */
    private static boolean present(StockResearchReport report, String key) {
        if (YEARLY.containsKey(key)) {
            return report.financials().success() && !report.financials().financialSummary().calculated().annual().isEmpty();
        }
        if (key.startsWith("ocfToPat")) {
            if (!report.financials().success()) {
                return false;
            }
            FinancialSummary summary = report.financials().financialSummary();
            return (key.equals("ocfToPat3yMultiple") ? summary.calculated().ocfToPat3yMultiple()
                    : summary.calculated().ocfToPat5yMultiple()) != null;
        }
        return report.valuation().success() && report.valuation().valuation().metrics().stream()
                .anyMatch(point -> point != null && key.equals(point.metric()));
    }
}
