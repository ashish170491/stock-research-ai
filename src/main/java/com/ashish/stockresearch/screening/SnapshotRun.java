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

    /**
     * How far back a run's stored companies have filed figures: a five-year span needs six filed fiscal years.
     *
     * @param stored             companies stored in the run
     * @param recorded           of those, the ones whose filed years were recorded (runs before it was, none)
     * @param withSixFiledYears  of those, the ones with six fiscal years read from their NSE filings
     */
    public record FiledYears(int stored, int recorded, int withSixFiledYears) {

        /** "412 of 498 stored companies (82.7%)", or that it was not recorded. */
        public String describe() {
            if (stored == 0) {
                return "no company was stored";
            }
            if (recorded == 0) {
                return "filed fiscal years were not recorded in this run";
            }
            return "%d of %d companies (%s%%) have six fiscal years read from their NSE filings".formatted(
                    withSixFiledYears, recorded, java.math.BigDecimal.valueOf(withSixFiledYears * 100L)
                            .divide(java.math.BigDecimal.valueOf(recorded), 1, java.math.RoundingMode.HALF_UP)
                            .toPlainString());
        }
    }

    /** A stock the run could not snapshot, and why. */
    public record Failure(String symbol, String reason) {
    }
}
