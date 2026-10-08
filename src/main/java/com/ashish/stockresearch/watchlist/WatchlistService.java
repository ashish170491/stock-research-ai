package com.ashish.stockresearch.watchlist;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshotRepository;
import com.ashish.stockresearch.screening.ScreeningCriteria;
import com.ashish.stockresearch.screening.ScreeningPresets;
import com.ashish.stockresearch.screening.ScreeningResult;
import com.ashish.stockresearch.screening.SnapshotRun;
import com.ashish.stockresearch.screening.StockScreener;
import com.ashish.stockresearch.screening.Universe;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Watchlist monitoring (roadmap Step 8, S8-6): after the nightly snapshot, re-runs every saved screen
 * against the two newest completed snapshots and records which stocks entered or left it. Reads the
 * database only, the same as {@link com.ashish.stockresearch.screening.ScreeningService} - no model,
 * no data provider.
 */
@Service
public class WatchlistService {

    private final SavedScreenRepository savedScreens;
    private final FundamentalsSnapshotRepository snapshots;
    private final StockScreener screener;
    private final ScreeningPresets presets;

    /** @param stillIn met every criterion in both the previous and the latest snapshot */
    public record WatchlistDiff(String name, Universe universe, LocalDate previousSnapshotDate,
                                LocalDate latestSnapshotDate, List<String> entered, List<String> left,
                                List<String> stillIn) {
    }

    public WatchlistService(SavedScreenRepository savedScreens, FundamentalsSnapshotRepository snapshots,
            StockScreener screener, ScreeningPresets presets) {
        this.savedScreens = savedScreens;
        this.snapshots = snapshots;
        this.screener = screener;
        this.presets = presets;
    }

    /** Every saved screen with two completed snapshots to diff; one with too few, or an unknown preset, is left out. */
    public List<WatchlistDiff> diffAll() {
        return savedScreens.findAll().stream().map(this::diff).flatMap(Optional::stream).toList();
    }

    public Optional<WatchlistDiff> diff(SavedScreen saved) {
        Optional<SnapshotRun> latest = snapshots.latestCompletedRun(saved.universe());
        Optional<SnapshotRun> previous = snapshots.previousCompletedRun(saved.universe());
        Optional<ScreeningCriteria> criteria = presets.find(saved.preset());
        if (latest.isEmpty() || previous.isEmpty() || criteria.isEmpty()) {
            return Optional.empty();
        }
        ScreeningCriteria applied = withIndustryGroup(criteria.get(), saved.industryGroup());
        Set<String> latestSet = meetingAll(applied, latest.get().id());
        Set<String> previousSet = meetingAll(applied, previous.get().id());
        List<String> entered = latestSet.stream().filter(symbol -> !previousSet.contains(symbol)).sorted().toList();
        List<String> left = previousSet.stream().filter(symbol -> !latestSet.contains(symbol)).sorted().toList();
        List<String> stillIn = latestSet.stream().filter(previousSet::contains).sorted().toList();
        return Optional.of(new WatchlistDiff(saved.name(), saved.universe(), previous.get().snapshotDate(),
                latest.get().snapshotDate(), entered, left, stillIn));
    }

    private Set<String> meetingAll(ScreeningCriteria criteria, long runId) {
        Set<String> symbols = new TreeSet<>();
        for (ScreeningResult.StockScreening stock : screener.screen(criteria, snapshots.snapshots(runId)).ranked()) {
            if (stock.meetsAll()) {
                symbols.add(stock.symbol());
            }
        }
        return symbols;
    }

    private static ScreeningCriteria withIndustryGroup(ScreeningCriteria criteria, String industryGroup) {
        if (industryGroup == null || industryGroup.isBlank()) {
            return criteria;
        }
        IndustryGroup group = IndustryGroup.valueOf(industryGroup.strip().toUpperCase(Locale.ROOT)
                .replaceAll("[\\s-]+", "_"));
        return criteria.withIndustryGroups(Set.of(group));
    }
}
