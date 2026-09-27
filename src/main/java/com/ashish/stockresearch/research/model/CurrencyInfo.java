package com.ashish.stockresearch.research.model;

/**
 * The currency of a monetary data point, before and after normalisation.
 * This application scales amounts (whole units to crore or millions) but
 * never converts between currencies - no exchange rate is sourced - so the
 * original and normalised currencies are always the same, and figures in
 * different currencies are never combined.
 *
 * @param originalCurrency   ISO code the source stated for the raw value
 * @param normalizedCurrency ISO code of the normalised value
 * @param conversion         how the raw value became the normalised one
 */
public record CurrencyInfo(String originalCurrency, String normalizedCurrency, Conversion conversion) {

    public enum Conversion {
        /** The value is used exactly as the source stated it. */
        NONE,
        /** Only the scale changed within the same currency (e.g. rupees to crore); no exchange rate involved. */
        SCALED_SAME_CURRENCY
    }

    public CurrencyInfo {
        if (originalCurrency == null || normalizedCurrency == null || conversion == null) {
            throw new IllegalArgumentException("currency metadata must be complete");
        }
        if (!originalCurrency.equalsIgnoreCase(normalizedCurrency)) {
            throw new IllegalArgumentException("currency conversion is not supported: no exchange rate is sourced");
        }
    }

    public static CurrencyInfo scaled(String currency) {
        return new CurrencyInfo(currency.toUpperCase(), currency.toUpperCase(), Conversion.SCALED_SAME_CURRENCY);
    }

    public static CurrencyInfo unchanged(String currency) {
        return new CurrencyInfo(currency.toUpperCase(), currency.toUpperCase(), Conversion.NONE);
    }
}
