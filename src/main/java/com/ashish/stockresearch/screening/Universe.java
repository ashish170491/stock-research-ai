package com.ashish.stockresearch.screening;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The index universes a screen can run over. Each is the constituents file published by NSE Indices,
 * bundled as downloaded so it can be checked against the source; a comment line above the header names the
 * source and its date. A request that names no universe is screened on the Nifty 50.
 */
public enum Universe {

    /** From https://niftyindices.com/IndexConstituent/ind_nifty50list.csv, downloaded 2026-09-28. */
    NIFTY50("Nifty 50", "screening/nifty50.csv"),

    /** From https://niftyindices.com/IndexConstituent/ind_nifty500list.csv, as of 2026-10-01. */
    NIFTY500("Nifty 500", "screening/nifty500.csv");

    /** The universe screened when a request names none. */
    public static final Universe DEFAULT = NIFTY50;

    private final String displayName;
    private final String resource;

    Universe(String displayName, String resource) {
        this.displayName = displayName;
        this.resource = resource;
    }

    public String displayName() {
        return displayName;
    }

    String resource() {
        return resource;
    }

    /** "NIFTY50", "Nifty 50", "nifty-50" all name NIFTY50; "Nifty 500" names NIFTY500. */
    public static Optional<Universe> parse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return Arrays.stream(values()).filter(universe -> universe.name().equals(key)).findFirst();
    }
}
