package com.ashish.stockresearch.context;

import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.calc.FinancialCalculator;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.SourceInfo;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.screening.ScreeningMetric;
import com.ashish.stockresearch.screening.SnapshotMetric;
import com.ashish.stockresearch.screening.SnapshotRun;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Where a company's metric sits among the other companies of its industry group in one stored snapshot: how
 * many of them have a usable value, their median and range, and the company's rank. Every statistic is
 * calculated by {@link FinancialCalculator} from the peers' stored values and verified by
 * {@link CalculationVerifier}; it carries the snapshot date and lists the peers it was calculated from.
 *
 * <p>Only usable values count - VALID and checked ({@link SnapshotMetric#usable()}). With fewer usable peers
 * than the minimum, no statistic is given: the median, lowest and highest are UNAVAILABLE with the count.
 *
 * @param peers        the peers with a usable value, excluding the company itself
 * @param median       the peers' median; UNAVAILABLE with the count when there are too few
 * @param lowest       the lowest peer value; UNAVAILABLE likewise
 * @param highest      the highest peer value; UNAVAILABLE likewise
 * @param companyValue the company's own value in the same snapshot; null when the company is not in it
 * @param rankPoint    the company's place counted from the highest, among itself and the peers, calculated and
 *                     verified; null when its own value is not usable or there are too few peers
 */
public record PeerContext(
        ScreeningMetric metric,
        IndustryGroup group,
        SnapshotRun run,
        String symbol,
        List<Peer> peers,
        int minimumPeers,
        FinancialDataPoint median,
        FinancialDataPoint lowest,
        FinancialDataPoint highest,
        SnapshotMetric companyValue,
        FinancialDataPoint rankPoint
) {

    /** A peer and the value of the metric it was counted with. */
    public record Peer(String symbol, SnapshotMetric value) {
    }

    public PeerContext {
        peers = List.copyOf(peers);
    }

    public LocalDate snapshotDate() {
        return run.snapshotDate();
    }

    /** The company's verified place, counted from the highest; null when it is not ranked. */
    public Integer rank() {
        return Ranks.place(rankPoint);
    }

    public int count() {
        return peers.size();
    }

    /** True when the median and range were calculated and verified. */
    public boolean calculated() {
        return median.status() == DataStatus.VALID && lowest.status() == DataStatus.VALID
                && highest.status() == DataStatus.VALID;
    }

    /** How many companies the rank is out of: the company and its peers. */
    public int rankedOf() {
        return peers.size() + 1;
    }

    /**
     * The context of {@code metric} for {@code symbol} among the other {@code group} companies of the snapshot.
     * Empty when the metric is not a primary measure for the group: no peer statistic is calculated for it at all.
     */
    public static Optional<PeerContext> of(ScreeningMetric metric, IndustryGroup group, String symbol, SnapshotRun run,
                                           List<FundamentalsSnapshot> snapshots, int minimumPeers) {
        if (group.isNonPrimary(metric.sectorKey())) {
            return Optional.empty();
        }
        List<Peer> peers = new ArrayList<>();
        SnapshotMetric own = null;
        CalculationVerifier.SourceValues sources = new CalculationVerifier.SourceValues();
        for (FundamentalsSnapshot snapshot : snapshots) {
            if (snapshot.symbol().equalsIgnoreCase(symbol)) {
                own = snapshot.metric(metric);
                continue;
            }
            SnapshotMetric value = snapshot.metric(metric);
            if (snapshot.industryGroup() == group && value.usable()) {
                peers.add(new Peer(snapshot.symbol(), value));
                sources.add(field(snapshot.symbol(), metric), value.periodLabel(), value.value());
            }
        }
        peers.sort(java.util.Comparator.comparing(Peer::symbol));

        ReportingPeriod period = ReportingPeriod.pointInTime(run.snapshotDate());
        SourceInfo source = new SourceInfo("Fundamentals snapshot run %d (%s), %s".formatted(run.id(),
                run.universe().displayName(), run.snapshotDate()), SourceType.OTHER, "fundamentals_snapshot."
                + metric.name(), null, run.snapshotDate());
        String name = "%s peers %s".formatted(group, metric.shortLabel());
        if (peers.size() < minimumPeers) {
            String reason = ("%d %s %s with a usable %s in the snapshot of %s besides %s; at least %d are needed for a "
                    + "peer statistic").formatted(peers.size(), group, peers.size() == 1 ? "company" : "companies",
                    metric.shortLabel(), run.snapshotDate(), symbol, minimumPeers);
            FinancialDataPoint none = FinancialDataPoint.unavailable(name, metric.unit(), period, source, reason);
            return Optional.of(new PeerContext(metric, group, run, symbol, peers, minimumPeers, none, none, none, own,
                    null));
        }
        List<CalculationInput> inputs = peers.stream().map(peer -> new CalculationInput(peer.symbol(),
                field(peer.symbol(), metric), peer.value().value(), metric.unit(), peer.value().periodLabel())).toList();
        List<BigDecimal> values = peers.stream().map(peer -> peer.value().value()).toList();
        String described = "%d %s peers' %s".formatted(values.size(), group, metric.description());
        FinancialDataPoint median = statistic(name + " median", "median of " + described, Formula.MEDIAN,
                FinancialCalculator::median, values, inputs, metric, period, source, sources);
        FinancialDataPoint lowest = statistic(name + " lowest", "lowest of " + described, Formula.MINIMUM,
                FinancialCalculator::minimum, values, inputs, metric, period, source, sources);
        FinancialDataPoint highest = statistic(name + " highest", "highest of " + described, Formula.MAXIMUM,
                FinancialCalculator::maximum, values, inputs, metric, period, source, sources);
        FinancialDataPoint rank = null;
        if (own != null && own.usable()) {
            CalculationInput ranked = new CalculationInput(symbol, field(symbol, metric), own.value(), metric.unit(),
                    own.periodLabel());
            sources.add(field(symbol, metric), own.periodLabel(), own.value());
            List<CalculationInput> among = new ArrayList<>(inputs);
            among.add(ranked);
            rank = Ranks.of(name + " rank of " + symbol, ranked, among, period, source,
                    "%s's place from the highest among itself and %s".formatted(symbol, described), sources);
        }
        return Optional.of(new PeerContext(metric, group, run, symbol, peers, minimumPeers, median, lowest, highest,
                own, rank));
    }

    private static FinancialDataPoint statistic(String metricName, String calculation, Formula formula,
                                                Function<List<BigDecimal>, Optional<BigDecimal>> calculate,
                                                List<BigDecimal> values, List<CalculationInput> inputs,
                                                ScreeningMetric metric, ReportingPeriod period, SourceInfo source,
                                                CalculationVerifier.SourceValues sources) {
        return CalculationVerifier.verify(calculate.apply(values)
                .map(value -> FinancialDataPoint.calculated(metricName, value, metric.unit(), period, source, formula,
                        calculation, inputs))
                .orElseGet(() -> FinancialDataPoint.unavailable(metricName, metric.unit(), period, source,
                        "could not be calculated")), sources);
    }

    /** The stored value a peer statistic's input is reconciled against. */
    static String field(String symbol, ScreeningMetric metric) {
        return "fundamentals_snapshot.%s.%s".formatted(symbol, metric.name());
    }
}
