package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

import java.time.Instant;
import java.util.List;

import static com.ashish.stockresearch.screening.ScreeningFixtures.DATE;
import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;
import static com.ashish.stockresearch.screening.ScreeningFixtures.unchecked;
import static com.ashish.stockresearch.screening.ScreeningFixtures.withStatus;
import static org.assertj.core.api.Assertions.assertThat;

// S3-2: the snapshot is persisted with its date, and every metric keeps its DataStatus.
class FundamentalsSnapshotRepositoryTest {

    private final EmbeddedDatabase database = SnapshotDatabase.create();
    private final FundamentalsSnapshotRepository repository = new FundamentalsSnapshotRepository(database);
    private static final Instant NOW = Instant.parse("2026-09-28T13:00:00Z");

    @AfterEach
    void close() {
        database.shutdown();
    }

    @Test
    void storesEachMetricWithItsValueStatusReasonPeriodAndCheck() {
        SnapshotRun run = repository.startRun(Universe.NIFTY50, DATE, NOW, 2);
        repository.save(run.id(), stock("TCS", IndustryGroup.IT_SERVICES)
                .with(ScreeningMetric.ROE_PERCENT, "45.12")
                .with(withStatus(ScreeningMetric.NET_PROFIT_CAGR_3Y_PERCENT, DataStatus.DATA_CONFLICT, null))
                .with(withStatus(ScreeningMetric.REVENUE_CAGR_3Y_PERCENT, DataStatus.INVALID, null))
                .with(unchecked(ScreeningMetric.EARNINGS_YIELD_PERCENT, "3.74")).build());
        repository.finishRun(run.id(), SnapshotRun.Status.COMPLETED, 1, 1, NOW.plusSeconds(60));

        FundamentalsSnapshot stored = repository.snapshots(run.id()).get(0);

        assertThat(stored.symbol()).isEqualTo("TCS");
        assertThat(stored.industryGroup()).isEqualTo(IndustryGroup.IT_SERVICES);
        assertThat(stored.snapshotDate()).isEqualTo(DATE);
        assertThat(stored.capturedAt()).isEqualTo(Instant.parse("2026-09-28T13:10:00Z"));
        SnapshotMetric roe = stored.metric(ScreeningMetric.ROE_PERCENT);
        assertThat(roe.value()).isEqualByComparingTo("45.12");
        assertThat(roe.display()).isEqualTo("45.12%");
        assertThat(roe.status()).isEqualTo(DataStatus.VALID);
        assertThat(roe.periodLabel()).isEqualTo("FY26");
        assertThat(roe.usable()).isTrue();
        assertThat(stored.metric(ScreeningMetric.NET_PROFIT_CAGR_3Y_PERCENT).status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(stored.metric(ScreeningMetric.NET_PROFIT_CAGR_3Y_PERCENT).statusReason()).isEqualTo("test: DATA_CONFLICT");
        assertThat(stored.metric(ScreeningMetric.REVENUE_CAGR_3Y_PERCENT).status()).isEqualTo(DataStatus.INVALID);
        SnapshotMetric yield = stored.metric(ScreeningMetric.EARNINGS_YIELD_PERCENT);
        assertThat(yield.status()).isEqualTo(DataStatus.VALID);
        assertThat(yield.uncheckedReason()).contains("could not be checked");
        assertThat(yield.usable()).isFalse();
        // A metric the snapshot did not record reads back as UNAVAILABLE, never as zero.
        assertThat(stored.metric(ScreeningMetric.ROCE_PERCENT).status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(stored.metric(ScreeningMetric.ROCE_PERCENT).value()).isNull();
    }

    @Test
    void screensOnlyTheNewestCompletedRun() {
        SnapshotRun older = repository.startRun(Universe.NIFTY50, DATE.minusDays(1), NOW.minusSeconds(86400), 1);
        repository.finishRun(older.id(), SnapshotRun.Status.COMPLETED, 1, 0, NOW.minusSeconds(86000));
        SnapshotRun newer = repository.startRun(Universe.NIFTY50, DATE, NOW, 1);
        repository.finishRun(newer.id(), SnapshotRun.Status.COMPLETED, 1, 0, NOW.plusSeconds(100));
        SnapshotRun failed = repository.startRun(Universe.NIFTY50, DATE, NOW.plusSeconds(200), 1);
        repository.finishRun(failed.id(), SnapshotRun.Status.FAILED, 0, 1, NOW.plusSeconds(300));
        repository.startRun(Universe.NIFTY50, DATE, NOW.plusSeconds(400), 1); // still running

        assertThat(repository.latestCompletedRun(Universe.NIFTY50)).hasValueSatisfying(run -> {
            assertThat(run.id()).isEqualTo(newer.id());
            assertThat(run.status()).isEqualTo(SnapshotRun.Status.COMPLETED);
            assertThat(run.finishedAt()).isEqualTo(NOW.plusSeconds(100));
        });
        assertThat(repository.latestRun(Universe.NIFTY50)).hasValueSatisfying(run ->
                assertThat(run.status()).isEqualTo(SnapshotRun.Status.RUNNING));
    }

    @Test
    void recordsTheCompaniesARunCouldNotCapture() {
        SnapshotRun run = repository.startRun(Universe.NIFTY50, DATE, NOW, 2);
        repository.recordFailure(run.id(), "ETERNAL", "profile: not found; financials: not found; valuation: not found");

        assertThat(repository.failures(run.id())).containsExactly(new SnapshotRun.Failure("ETERNAL",
                "profile: not found; financials: not found; valuation: not found"));
    }

    // S12-L1: the share of a run's companies with six filed fiscal years
    @Test
    void countsTheCompaniesWithSixFiledFiscalYears() {
        SnapshotRun run = repository.startRun(Universe.NIFTY500, DATE, NOW, 4);
        repository.save(run.id(), stock("TCS", IndustryGroup.IT_SERVICES).filedFiscalYears(6).build());
        repository.save(run.id(), stock("INFY", IndustryGroup.IT_SERVICES).filedFiscalYears(6).build());
        repository.save(run.id(), stock("TMCV", IndustryGroup.MANUFACTURING).filedFiscalYears(1).build());
        repository.save(run.id(), stock("EMBASSY", IndustryGroup.OTHER).filedFiscalYears(0).build());

        SnapshotRun.FiledYears filed = repository.filedYears(run.id());

        assertThat(filed).isEqualTo(new SnapshotRun.FiledYears(4, 4, 2));
        assertThat(filed.describe()).isEqualTo("2 of 4 companies (50.0%) have six fiscal years read from their NSE filings");
        assertThat(repository.snapshots(run.id())).extracting(FundamentalsSnapshot::filedFiscalYears)
                .containsExactly(0, 6, 6, 1); // by symbol
    }

    @Test
    void saysSoWhenARunDidNotRecordFiledYears() {
        SnapshotRun run = repository.startRun(Universe.NIFTY50, DATE, NOW, 1);
        repository.save(run.id(), stock("TCS", IndustryGroup.IT_SERVICES).build());

        assertThat(repository.filedYears(run.id())).isEqualTo(new SnapshotRun.FiledYears(1, 0, 0));
        assertThat(repository.filedYears(run.id()).describe()).isEqualTo("filed fiscal years were not recorded in this run");
        assertThat(repository.snapshots(run.id()).get(0).filedFiscalYears()).isNull();
    }

    @Test
    void hasNoCompletedRunBeforeTheFirstSnapshot() {
        assertThat(repository.latestCompletedRun(Universe.NIFTY50)).isEmpty();
        assertThat(repository.snapshots(1)).isEqualTo(List.of());
    }
}
