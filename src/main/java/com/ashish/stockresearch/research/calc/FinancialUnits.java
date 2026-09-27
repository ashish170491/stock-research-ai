package com.ashish.stockresearch.research.calc;

import com.ashish.stockresearch.research.model.Unit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Deterministic unit conversion and formatting for Indian financial
 * figures. This is the only place a provider's raw scale is turned into a
 * canonical {@link Unit}; the model never converts units itself.
 *
 * <pre>
 *   1 crore      = 10,000,000 rupees        (10^7)
 *   1 lakh crore = 100,000 crore            (10^12 rupees)
 * </pre>
 */
public final class FinancialUnits {

    public static final BigDecimal RUPEES_PER_CRORE = new BigDecimal("10000000");
    public static final BigDecimal CRORE_PER_LAKH_CRORE = new BigDecimal("100000");
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** The scale a provider publishes an amount in. Stated per provider field, never inferred from magnitude. */
    public enum ProviderScale {
        ONES("1"),
        THOUSANDS("1000"),
        LAKHS("100000"),
        MILLIONS("1000000"),
        CRORES("10000000"),
        BILLIONS("1000000000");

        private final BigDecimal multiplier;

        ProviderScale(String multiplier) {
            this.multiplier = new BigDecimal(multiplier);
        }

        public BigDecimal multiplier() {
            return multiplier;
        }
    }

    /** An amount after normalisation, in a canonical unit. */
    public record NormalizedAmount(BigDecimal value, Unit unit) {
    }

    private FinancialUnits() {
    }

    /** Converts a provider amount to whole units of its currency. */
    public static BigDecimal toWholeUnits(BigDecimal amount, ProviderScale scale) {
        return amount.multiply(scale.multiplier());
    }

    /** Converts a rupee amount at the given provider scale to crore, rounded to 2 decimal places. */
    public static BigDecimal toCrore(BigDecimal amount, ProviderScale scale) {
        return toWholeUnits(amount, scale).divide(RUPEES_PER_CRORE, 2, RoundingMode.HALF_UP);
    }

    /** Converts crore to lakh crore, kept at 4 decimal places so display rounding happens once. */
    public static BigDecimal croreToLakhCrore(BigDecimal crore) {
        return crore.divide(CRORE_PER_LAKH_CRORE, 4, RoundingMode.HALF_UP);
    }

    /** Converts an amount at the given provider scale to millions, rounded to 2 decimal places. */
    public static BigDecimal toMillions(BigDecimal amount, ProviderScale scale) {
        return toWholeUnits(amount, scale).divide(ONE_MILLION, 2, RoundingMode.HALF_UP);
    }

    /** Providers commonly publish ratios as fractions (0.2396); the canonical form is a percentage (23.96). */
    public static BigDecimal fractionToPercent(BigDecimal fraction) {
        return fraction.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Normalises an absolute currency amount into its canonical unit: INR to
     * {@link Unit#INR_CRORE}, USD to {@link Unit#USD_MILLION}. Any other or
     * unknown currency yields empty - an amount whose currency is not known
     * cannot be safely labelled, so it must be reported as unavailable.
     */
    public static Optional<NormalizedAmount> normalizeAmount(BigDecimal amount, ProviderScale scale, String currency) {
        if (amount == null || currency == null) {
            return Optional.empty();
        }
        return switch (currency.toUpperCase()) {
            case "INR" -> Optional.of(new NormalizedAmount(toCrore(amount, scale), Unit.INR_CRORE));
            case "USD" -> Optional.of(new NormalizedAmount(toMillions(amount, scale), Unit.USD_MILLION));
            default -> Optional.empty();
        };
    }

    /**
     * Renders a canonical value for a human reader. Rupee amounts of a lakh
     * crore or more are shown in both lakh crore and crore, so the
     * magnitude cannot be misread by a factor of ten.
     */
    public static String display(BigDecimal value, Unit unit) {
        if (value == null) {
            return "UNAVAILABLE";
        }
        String sign = value.signum() < 0 ? "-" : "";
        BigDecimal abs = value.abs();
        return switch (unit) {
            case INR_CRORE -> abs.compareTo(CRORE_PER_LAKH_CRORE) >= 0
                    ? "%s₹%s lakh crore (₹%s crore)".formatted(sign,
                            croreToLakhCrore(abs).setScale(2, RoundingMode.HALF_UP).toPlainString(),
                            indianGrouping(abs, 0))
                    : "%s₹%s crore".formatted(sign, indianGrouping(abs, 2));
            case INR_LAKH_CRORE -> "%s₹%s lakh crore".formatted(sign, abs.setScale(2, RoundingMode.HALF_UP).toPlainString());
            case INR -> "%s₹%s".formatted(sign, indianGrouping(abs, 2));
            case INR_PER_SHARE -> "%s₹%s per share".formatted(sign, indianGrouping(abs, 2));
            case USD_MILLION -> "%sUS$%s million".formatted(sign, abs.setScale(2, RoundingMode.HALF_UP).toPlainString());
            case PERCENT -> "%s%s%%".formatted(sign, abs.setScale(2, RoundingMode.HALF_UP).toPlainString());
            case MULTIPLE -> "%s%sx".formatted(sign, abs.setScale(2, RoundingMode.HALF_UP).toPlainString());
            case SHARES -> "%s%s shares".formatted(sign, indianGrouping(abs, 0));
            case YEARS -> "%s%s years".formatted(sign, abs.setScale(2, RoundingMode.HALF_UP).toPlainString());
        };
    }

    /** Indian digit grouping: 1134183 -> "11,34,183". */
    static String indianGrouping(BigDecimal nonNegative, int decimals) {
        String plain = nonNegative.setScale(decimals, RoundingMode.HALF_UP).toPlainString();
        int dot = plain.indexOf('.');
        String integer = dot < 0 ? plain : plain.substring(0, dot);
        String fraction = dot < 0 ? "" : plain.substring(dot);
        if (integer.length() <= 3) {
            return integer + fraction;
        }
        StringBuilder grouped = new StringBuilder(integer.substring(integer.length() - 3));
        String rest = integer.substring(0, integer.length() - 3);
        while (rest.length() > 2) {
            grouped.insert(0, rest.substring(rest.length() - 2) + ",");
            rest = rest.substring(0, rest.length() - 2);
        }
        grouped.insert(0, rest + ",");
        return grouped + fraction;
    }
}
