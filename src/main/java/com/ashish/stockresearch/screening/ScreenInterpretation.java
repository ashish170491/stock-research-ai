package com.ashish.stockresearch.screening;

import java.util.List;
import java.util.Optional;

/**
 * Stage 2 of a screening request in words: the validated criteria, and everything the application
 * assumed or refused while building them - all of it shown to the user with the answer.
 *
 * @param criteria   the criteria to screen with; empty when nothing usable was requested
 * @param readings   how each part of the request was read ("low debt" as D/E ≤ 0.50x, the preset's threshold)
 * @param notApplied parts of the request that were not used, each with why
 * @param refusal    why no screen was run; null when {@code criteria} is present
 * @param universe   the index to screen, as the request names it; {@link Universe#DEFAULT} when it names none
 */
public record ScreenInterpretation(Optional<ScreeningCriteria> criteria, List<String> readings,
                                   List<String> notApplied, String refusal, Universe universe) {

    public ScreenInterpretation {
        criteria = criteria == null ? Optional.empty() : criteria;
        universe = universe == null ? Universe.DEFAULT : universe;
        readings = readings == null ? List.of() : List.copyOf(readings);
        notApplied = notApplied == null ? List.of() : List.copyOf(notApplied);
    }

    static ScreenInterpretation run(ScreeningCriteria criteria, List<String> readings, List<String> notApplied,
                                    Universe universe) {
        return new ScreenInterpretation(Optional.of(criteria), readings, notApplied, null, universe);
    }

    static ScreenInterpretation refused(String why, List<String> readings, List<String> notApplied,
                                        Universe universe) {
        return new ScreenInterpretation(Optional.empty(), readings, notApplied, why, universe);
    }
}
