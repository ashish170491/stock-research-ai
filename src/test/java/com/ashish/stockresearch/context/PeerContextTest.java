package com.ashish.stockresearch.context;

import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.calc.FinancialCalculator;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.Unit;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshot;
import com.ashish.stockresearch.screening.ScreeningFixtures;
import com.ashish.stockresearch.screening.ScreeningMetric;
import com.ashish.stockresearch.screening.SnapshotRun;
import com.ashish.stockresearch.screening.Universe;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;
import static org.assertj.core.api.Assertions.assertThat;

class PeerContextTest {

    static final SnapshotRun RUN = ContextFixtures.RUN;

    static List<FundamentalsSnapshot> snapshot() {
        return ContextFixtures.snapshot();
    }

    // S13-1
    @Test
    void givesTheUsablePeersMedianRangeAndTheCompanysRankWithTheSnapshotAndThePeers() {
        PeerContext peers = PeerContext.of(ScreeningMetric.ROE_PERCENT, IndustryGroup.BANK, "HDFCBANK", RUN, snapshot(), 5)
                .orElseThrow();

        // only VALID, checked BANK values besides HDFCBANK: not YESBANK (disputed), IDFCFIRSTB (unchecked) or TCS
        assertThat(peers.peers()).extracting(PeerContext.Peer::symbol)
                .containsExactly("AXISBANK", "FEDERALBNK", "ICICIBANK", "INDUSINDBK", "KOTAKBANK", "SBIN");
        assertThat(peers.count()).isEqualTo(6);
        assertThat(peers.snapshotDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(peers.calculated()).isTrue();
        // 8.60, 12.90, 13.40, 15.20, 17.50, 18.10: the median is the mean of 13.40 and 15.20
        assertThat(peers.median().value()).isEqualByComparingTo("14.30");
        assertThat(peers.median().display()).isEqualTo("14.30%");
        assertThat(peers.lowest().value()).isEqualByComparingTo("8.60");
        assertThat(peers.highest().value()).isEqualByComparingTo("18.10");
        // 14.03 sits below 18.10, 17.50, 15.20: the 4th highest of 7
        assertThat(peers.rank()).isEqualTo(4);
        assertThat(peers.rankedOf()).isEqualTo(7);
        // the rank too is a verified calculation, from HDFCBANK's value and the seven values it is placed among
        assertThat(peers.rankPoint().formula()).isEqualTo(Formula.RANK_FROM_HIGHEST);
        assertThat(peers.rankPoint().inputs()).hasSize(8).first().extracting(CalculationInput::name).isEqualTo("HDFCBANK");
        assertThat(CalculationVerifier.problem(peers.rankPoint(), new CalculationVerifier.SourceValues())).isEmpty();
        FinancialDataPoint tampered = FinancialDataPoint.calculated("rank", new BigDecimal("2"),
                com.ashish.stockresearch.research.model.Unit.POSITION, peers.rankPoint().period(),
                peers.rankPoint().source(), Formula.RANK_FROM_HIGHEST, "rank", peers.rankPoint().inputs());
        assertThat(CalculationVerifier.verify(tampered, new CalculationVerifier.SourceValues()).status())
                .isEqualTo(DataStatus.INVALID);
        // each statistic is a verified calculation over the six peers' stored values
        for (FinancialDataPoint statistic : List.of(peers.median(), peers.lowest(), peers.highest())) {
            assertThat(statistic.status()).isEqualTo(DataStatus.VALID);
            assertThat(statistic.inputs()).extracting(CalculationInput::name)
                    .containsExactly("AXISBANK", "FEDERALBNK", "ICICIBANK", "INDUSINDBK", "KOTAKBANK", "SBIN");
            assertThat(CalculationVerifier.problem(statistic, new CalculationVerifier.SourceValues())).isEmpty();
            assertThat(statistic.source().source()).contains("snapshot run 13").contains("2026-10-02");
        }
    }

    @Test
    void doesNotRankACompanyWhoseOwnSnapshotValueIsNotUsable() {
        List<FundamentalsSnapshot> snapshot = new ArrayList<>(snapshot());
        snapshot.set(0, stock("HDFCBANK", IndustryGroup.BANK).with(ScreeningFixtures.withStatus(
                ScreeningMetric.ROE_PERCENT, DataStatus.DATA_CONFLICT, "14.03")).build());

        PeerContext peers = PeerContext.of(ScreeningMetric.ROE_PERCENT, IndustryGroup.BANK, "HDFCBANK", RUN, snapshot, 5)
                .orElseThrow();

        assertThat(peers.calculated()).isTrue();
        assertThat(peers.rank()).isNull();
        assertThat(peers.companyValue().status()).isEqualTo(DataStatus.DATA_CONFLICT);
    }

    // S13-2
    @Test
    void givesNoStatisticOfTooFewPeers() {
        // HDFCLIFE's only peer is SBILIFE
        PeerContext insurers = PeerContext.of(ScreeningMetric.ROE_PERCENT, IndustryGroup.INSURANCE, "HDFCLIFE", RUN,
                snapshot(), 5).orElseThrow();
        // with a newcomer, there are two insurers besides it
        List<FundamentalsSnapshot> two = new ArrayList<>(snapshot());
        two.add(stock("LICI", IndustryGroup.INSURANCE).with(ScreeningMetric.ROE_PERCENT, "40").build());
        PeerContext twoInsurers = PeerContext.of(ScreeningMetric.ROE_PERCENT, IndustryGroup.INSURANCE, "HDFCLIFE", RUN,
                two, 5).orElseThrow();

        for (PeerContext context : List.of(insurers, twoInsurers)) {
            assertThat(context.calculated()).isFalse();
            assertThat(context.median().status()).isEqualTo(DataStatus.UNAVAILABLE);
            assertThat(context.median().value()).isNull();
            assertThat(context.lowest().value()).isNull();
            assertThat(context.highest().value()).isNull();
            assertThat(context.rank()).isNull();
        }
        assertThat(twoInsurers.count()).isEqualTo(2);
        assertThat(twoInsurers.median().statusReason()).isEqualTo("2 INSURANCE companies with a usable ROE in the "
                + "snapshot of 2026-10-02 besides HDFCLIFE; at least 5 are needed for a peer statistic");
        assertThat(insurers.median().statusReason()).startsWith("1 INSURANCE company with a usable ROE");
    }

    // S13-3
    @Test
    void givesNoPeerContextForAMetricThatIsNotPrimaryForTheGroup() {
        assertThat(PeerContext.of(ScreeningMetric.ROCE_PERCENT, IndustryGroup.BANK, "HDFCBANK", RUN, snapshot(), 5))
                .isEmpty();
        assertThat(PeerContext.of(ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE, IndustryGroup.BANK, "HDFCBANK", RUN,
                snapshot(), 5)).isEmpty();
        assertThat(PeerContext.of(ScreeningMetric.ROE_PERCENT, IndustryGroup.BANK, "HDFCBANK", RUN, snapshot(), 5))
                .isPresent();
    }

    @Test
    void calculatesTheMedianRangeAndRankItself() {
        List<BigDecimal> odd = List.of(new BigDecimal("3"), new BigDecimal("1"), new BigDecimal("2"));
        List<BigDecimal> even = List.of(new BigDecimal("1.00"), new BigDecimal("2.25"), new BigDecimal("4.00"),
                new BigDecimal("9.00"));

        assertThat(FinancialCalculator.median(odd)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("2"));
        assertThat(FinancialCalculator.median(even)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("3.13"));
        assertThat(FinancialCalculator.minimum(even)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1"));
        assertThat(FinancialCalculator.maximum(even)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("9"));
        assertThat(FinancialCalculator.median(List.of())).isEmpty();
        // equal values share a rank
        List<BigDecimal> ties = List.of(new BigDecimal("5"), new BigDecimal("5"), new BigDecimal("3"));
        assertThat(FinancialCalculator.rankFromHighest(ties, new BigDecimal("5"))).contains(1);
        assertThat(FinancialCalculator.rankFromHighest(ties, new BigDecimal("3"))).contains(3);
        assertThat(FinancialCalculator.rankFromHighest(ties, new BigDecimal("4"))).isEmpty();
    }

    @Test
    void theVerifierCatchesAStatisticThatDoesNotFollowFromItsInputs() {
        List<CalculationInput> inputs = List.of(
                new CalculationInput("A", null, new BigDecimal("10.00"), Unit.PERCENT, "FY26"),
                new CalculationInput("B", null, new BigDecimal("12.00"), Unit.PERCENT, "FY26"),
                new CalculationInput("C", null, new BigDecimal("20.00"), Unit.PERCENT, "FY26"));
        ReportingPeriod period = ReportingPeriod.pointInTime(LocalDate.of(2026, 10, 2));
        com.ashish.stockresearch.research.model.SourceInfo source = new com.ashish.stockresearch.research.model
                .SourceInfo("Fundamentals snapshot run 13", com.ashish.stockresearch.research.model.SourceType.OTHER,
                null, null, null);
        FinancialDataPoint right = FinancialDataPoint.calculated("median", new BigDecimal("12.00"), Unit.PERCENT, period,
                source, Formula.MEDIAN, "median", inputs);
        FinancialDataPoint wrong = FinancialDataPoint.calculated("median", new BigDecimal("14.00"), Unit.PERCENT, period,
                source, Formula.MEDIAN, "median", inputs);
        FinancialDataPoint wrongHighest = FinancialDataPoint.calculated("highest", new BigDecimal("12.00"), Unit.PERCENT,
                period, source, Formula.MAXIMUM, "highest", inputs);

        assertThat(CalculationVerifier.verify(right, new CalculationVerifier.SourceValues()).status())
                .isEqualTo(DataStatus.VALID);
        assertThat(CalculationVerifier.verify(wrong, new CalculationVerifier.SourceValues()).status())
                .isEqualTo(DataStatus.INVALID);
        assertThat(CalculationVerifier.verify(wrongHighest, new CalculationVerifier.SourceValues()).status())
                .isEqualTo(DataStatus.INVALID);
    }
}
