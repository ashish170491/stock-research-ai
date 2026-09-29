package com.ashish.stockresearch.screening;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One run of the snapshot job over a universe.
 *
 * @param finishedAt null while RUNNING
 */
public record SnapshotRun(
        long id,
        Universe universe,
        LocalDate snapshotDate,
        Instant startedAt,
        Instant finishedAt,
        Status status,
        int requested,
        int stored,
        int failed
) {

    public enum Status {
        RUNNING,
        /** Finished with at least one stock stored; failures, if any, are recorded per stock. */
        COMPLETED,
        /** Finished with nothing stored, or stopped by an error. Screening never reads it. */
        FAILED
    }

    /** A stock the run could not snapshot, and why. */
    public record Failure(String symbol, String reason) {
    }
}
