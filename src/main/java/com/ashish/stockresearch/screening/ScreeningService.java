package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Screens a universe's latest completed snapshot against a preset. Reads the database only - never a
 * data provider - so a screen of 50 stocks takes milliseconds.
 */
@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);

    private final FundamentalsSnapshotRepository repository;
    private final StockScreener screener;
    private final ScreeningPresets presets;
    private final Universes universes;

    public ScreeningService(FundamentalsSnapshotRepository repository, StockScreener screener,
                            ScreeningPresets presets, Universes universes) {
        this.repository = repository;
        this.screener = screener;
        this.presets = presets;
        this.universes = universes;
    }

    /**
     * @param universeName  null or blank for NIFTY50
     * @param industryGroup null or blank for every group
     */
    public ScreeningOutcome screen(String presetName, String universeName, String industryGroup) {
        Optional<ScreeningCriteria> criteria = presets.find(presetName);
        if (criteria.isEmpty()) {
            return ScreeningOutcome.failure(ScreeningOutcome.Status.UNKNOWN_PRESET,
                    "Unknown screening preset '%s'. Available presets: %s.".formatted(presetName,
                            String.join(", ", presets.names())));
        }
        Set<IndustryGroup> groups = Set.of();
        if (industryGroup != null && !industryGroup.isBlank()) {
            try {
                groups = Set.of(IndustryGroup.valueOf(industryGroup.strip().toUpperCase(Locale.ROOT)
                        .replaceAll("[\\s-]+", "_")));
            } catch (IllegalArgumentException ex) {
                return ScreeningOutcome.failure(ScreeningOutcome.Status.UNKNOWN_INDUSTRY_GROUP,
                        "Unknown industry group '%s'. Available groups: %s.".formatted(industryGroup,
                                Arrays.stream(IndustryGroup.values()).map(IndustryGroup::name)
                                        .collect(Collectors.joining(", "))));
            }
        }
        return screen(criteria.get().withIndustryGroups(groups), universeName);
    }

    /**
     * Screens with criteria built elsewhere - a preset, or criteria read from a request in words and
     * validated ({@code CriteriaValidator}). The industry-group filter is the criteria's own.
     *
     * @param universeName null or blank for NIFTY50
     */
    public ScreeningOutcome screen(ScreeningCriteria criteria, String universeName) {
        long started = System.nanoTime();
        Optional<Universe> universe = universeName == null || universeName.isBlank() ? Optional.of(Universe.NIFTY50)
                : Universe.parse(universeName);
        if (universe.isEmpty()) {
            return ScreeningOutcome.failure(ScreeningOutcome.Status.UNKNOWN_UNIVERSE,
                    "Unknown universe '%s'. Available universes: %s.".formatted(universeName,
                            Arrays.stream(Universe.values()).map(Universe::name).collect(Collectors.joining(", "))));
        }
        Set<IndustryGroup> groups = criteria.industryGroups();
        Optional<SnapshotRun> run = repository.latestCompletedRun(universe.get());
        if (run.isEmpty()) {
            return ScreeningOutcome.failure(ScreeningOutcome.Status.NO_SNAPSHOT, ("No completed snapshot of %s exists "
                    + "yet, so nothing can be screened. The snapshot runs on weekdays at 18:30 India time, or on "
                    + "demand with POST /api/admin/snapshot.").formatted(universe.get().displayName()));
        }
        List<FundamentalsSnapshot> snapshots = repository.snapshots(run.get().id());
        ScreeningResult result = screener.screen(criteria, snapshots);

        List<SnapshotRun.Failure> missing = new ArrayList<>(repository.failures(run.get().id()));
        Set<String> captured = snapshots.stream().map(FundamentalsSnapshot::symbol).collect(Collectors.toSet());
        Set<String> failed = missing.stream().map(SnapshotRun.Failure::symbol).collect(Collectors.toSet());
        universes.members(universe.get()).stream()
                .filter(member -> !captured.contains(member.symbol()) && !failed.contains(member.symbol()))
                .forEach(member -> missing.add(new SnapshotRun.Failure(member.symbol(),
                        "not in the snapshot of " + run.get().snapshotDate())));
        if (!groups.isEmpty()) {
            // The filter applies to what was captured; a stock that was not captured has no known group.
            missing.clear();
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        log.info("Screened {} ({} stocks) with {} from snapshot run {} of {} in {} ms", universe.get(),
                snapshots.size(), criteria.name(), run.get().id(), run.get().snapshotDate(), elapsed);
        return new ScreeningOutcome(true, ScreeningOutcome.Status.OK, null, universe.get(), run.get(), result,
                List.copyOf(missing), elapsed);
    }
}
