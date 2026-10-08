package com.ashish.stockresearch.watchlist;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshotRepository;
import com.ashish.stockresearch.screening.ScreeningPresetsTest;
import com.ashish.stockresearch.screening.SnapshotRun;
import com.ashish.stockresearch.screening.StockScreener;
import com.ashish.stockresearch.screening.Universe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;
import static com.ashish.stockresearch.screening.ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE;
import static com.ashish.stockresearch.screening.ScreeningMetric.NET_PROFIT_CAGR_5Y_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.OCF_TO_PAT_3Y_MULTIPLE;
import static com.ashish.stockresearch.screening.ScreeningMetric.REVENUE_CAGR_5Y_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROCE_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROE_3Y_AVG_PERCENT;
import static com.ashish.stockresearch.screening.ScreeningMetric.ROE_PERCENT;
import static com.ashish.stockresearch.screening.SnapshotDatabase.create;
import static org.assertj.core.api.Assertions.assertThat;

/** Watchlist monitoring (roadmap Step 8, S8-6): which stocks entered or left a saved screen between
 * two completed snapshots, read from the database only - no model, no data provider. */
class WatchlistServiceTest {

    private static final Instant T1 = Instant.parse("2026-09-20T18:30:00Z");
    private static final Instant T2 = Instant.parse("2026-09-27T18:30:00Z");

    private final EmbeddedDatabase database = create();
    private final FundamentalsSnapshotRepository repository = new FundamentalsSnapshotRepository(database);
    private final SavedScreenRepository savedScreens = new SavedScreenRepository(database);
    private final WatchlistService watchlist = new WatchlistService(savedScreens, repository, new StockScreener(),
            ScreeningPresetsTest.presets());

    @AfterEach
    void close() {
        database.shutdown();
    }

    /** A quality-compounder pass, or (with a low ROE) a fail - the same shape ScreeningServiceTest uses. */
    private static com.ashish.stockresearch.screening.FundamentalsSnapshot company(String symbol, boolean passes) {
        return stock(symbol, IndustryGroup.IT_SERVICES)
                .with(ROE_3Y_AVG_PERCENT, passes ? "22" : "5").with(ROE_PERCENT, passes ? "22" : "5")
                .with(ROCE_PERCENT, "25").with(REVENUE_CAGR_5Y_PERCENT, "12").with(NET_PROFIT_CAGR_5Y_PERCENT, "11")
                .with(OCF_TO_PAT_3Y_MULTIPLE, "1.1").with(DEBT_TO_EQUITY_MULTIPLE, "0.1").build();
    }

    private void run(LocalDate date, Instant startedAt, boolean tcsPasses, boolean infyPasses, boolean sbinPasses) {
        SnapshotRun snapshotRun = repository.startRun(Universe.NIFTY50, date, startedAt, 3);
        repository.save(snapshotRun.id(), company("TCS", tcsPasses));
        repository.save(snapshotRun.id(), company("INFY", infyPasses));
        repository.save(snapshotRun.id(), company("SBIN", sbinPasses));
        repository.finishRun(snapshotRun.id(), SnapshotRun.Status.COMPLETED, 3, 0, startedAt.plusSeconds(60));
    }

    // S8-6
    @Test
    void recordsWhichStocksEnteredOrLeftBetweenTwoSnapshots() {
        run(LocalDate.of(2026, 9, 20), T1, true, false, true);
        run(LocalDate.of(2026, 9, 27), T2, true, true, false);
        SavedScreen saved = savedScreens.save("quality IT and banks", Universe.NIFTY50, "quality-compounder", null);

        WatchlistService.WatchlistDiff diff = watchlist.diff(saved).orElseThrow();

        assertThat(diff.name()).isEqualTo("quality IT and banks");
        assertThat(diff.previousSnapshotDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(diff.latestSnapshotDate()).isEqualTo(LocalDate.of(2026, 9, 27));
        assertThat(diff.entered()).containsExactly("INFY");
        assertThat(diff.left()).containsExactly("SBIN");
        assertThat(diff.stillIn()).containsExactly("TCS");
    }

    @Test
    void diffAllCoversEverySavedScreenWithEnoughHistory() {
        run(LocalDate.of(2026, 9, 20), T1, true, false, true);
        run(LocalDate.of(2026, 9, 27), T2, true, true, false);
        savedScreens.save("a", Universe.NIFTY50, "quality-compounder", null);
        savedScreens.save("b", Universe.NIFTY50, "quality-compounder", null);

        List<WatchlistService.WatchlistDiff> diffs = watchlist.diffAll();

        assertThat(diffs).extracting(WatchlistService.WatchlistDiff::name).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void isEmptyWithOnlyOneCompletedSnapshot() {
        run(LocalDate.of(2026, 9, 27), T2, true, false, true);
        SavedScreen saved = savedScreens.save("too new", Universe.NIFTY50, "quality-compounder", null);

        assertThat(watchlist.diff(saved)).isEmpty();
    }

    @Test
    void isEmptyForAnUnknownPreset() {
        run(LocalDate.of(2026, 9, 20), T1, true, false, true);
        run(LocalDate.of(2026, 9, 27), T2, true, true, false);
        SavedScreen saved = new SavedScreen(1, "bad", Universe.NIFTY50, "no-such-preset", null, Instant.now());

        assertThat(watchlist.diff(saved)).isEmpty();
    }

    @Test
    void savingTheSameNameAgainReplacesTheEarlierScreen() {
        savedScreens.save("my screen", Universe.NIFTY50, "quality-compounder", null);

        SavedScreen replaced = savedScreens.save("my screen", Universe.NIFTY500, "dividend", "BANK");

        assertThat(savedScreens.findAll()).hasSize(1);
        Optional<SavedScreen> found = savedScreens.findByName("my screen");
        assertThat(found).isPresent();
        assertThat(found.get().universe()).isEqualTo(Universe.NIFTY500);
        assertThat(found.get().preset()).isEqualTo("dividend");
        assertThat(found.get().industryGroup()).isEqualTo("BANK");
        assertThat(replaced.id()).isEqualTo(found.get().id());
    }
}
