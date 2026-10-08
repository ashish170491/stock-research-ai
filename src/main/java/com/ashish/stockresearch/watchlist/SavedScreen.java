package com.ashish.stockresearch.watchlist;

import com.ashish.stockresearch.screening.Universe;

import java.time.Instant;

/**
 * A screen saved for watchlist monitoring (roadmap Step 8, S8-6): a preset and universe, re-run
 * deterministically against two completed snapshots - never read from a request's words or re-extracted
 * by a model, so the same name always means the same screen.
 *
 * @param industryGroup null for every group the preset screens
 */
public record SavedScreen(long id, String name, Universe universe, String preset, String industryGroup,
                          Instant createdAt) {
}
