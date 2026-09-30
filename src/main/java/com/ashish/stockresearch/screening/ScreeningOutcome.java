package com.ashish.stockresearch.screening;

import java.util.List;

/**
 * What a screen request produced: a result over a completed snapshot, or why there is none.
 *
 * @param notInSnapshot universe members with no stored snapshot in the run, with the reason
 */
public record ScreeningOutcome(
        boolean success,
        Status status,
        String message,
        Universe universe,
        SnapshotRun run,
        ScreeningResult result,
        List<SnapshotRun.Failure> notInSnapshot,
        long elapsedMillis
) {

    public enum Status { OK, UNKNOWN_PRESET, UNKNOWN_UNIVERSE, UNKNOWN_INDUSTRY_GROUP, NO_SNAPSHOT }

    static ScreeningOutcome failure(Status status, String message) {
        return new ScreeningOutcome(false, status, message, null, null, null, List.of(), 0);
    }
}
