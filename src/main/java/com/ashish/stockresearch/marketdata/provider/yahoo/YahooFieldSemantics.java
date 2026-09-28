package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.research.model.ProviderUnit;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Establishes what a Yahoo raw number means from Yahoo's own formatted
 * string, never from the number's size.
 *
 * Yahoo's endpoints are undocumented, so a field's name is not evidence of
 * its unit: {@code debtToEquity} could be a multiple or a percentage, and a
 * ratio field could be a fraction or percentage points. Each field declares
 * which meanings are plausible; the one that reproduces Yahoo's formatted
 * string is accepted. If none does - or there is no formatted string - the
 * meaning is unverified and the value must be reported as UNAVAILABLE.
 *
 * <pre>
 *   raw 0.08678, fmt "8.68%"  -> FRACTION     (0.08678 x 100 = 8.68)
 *   raw 7.269,   fmt "7.27%"  -> PERCENTAGE   (7.269 = 7.27)
 *   raw 2.95e12, fmt "2.95T"  -> WHOLE_CURRENCY_UNITS
 *   raw 7.269,   fmt "7.27"   -> unverified   (no unit declared)
 * </pre>
 */
final class YahooFieldSemantics {

    /** Ratio fields: Yahoo is known to send both shapes across fields, so both are candidates. */
    static final Set<ProviderUnit> RATIO = Set.of(ProviderUnit.FRACTION, ProviderUnit.PERCENTAGE);
    static final Set<ProviderUnit> AMOUNT = Set.of(ProviderUnit.WHOLE_CURRENCY_UNITS);
    static final Set<ProviderUnit> PER_SHARE = Set.of(ProviderUnit.PER_SHARE);
    static final Set<ProviderUnit> SHARES = Set.of(ProviderUnit.SHARE_COUNT);
    /** A multiple such as P/E: fmt "12.90" for raw 12.902477. */
    static final Set<ProviderUnit> MULTIPLE = Set.of(ProviderUnit.MULTIPLE);

    private static final Pattern FORMATTED = Pattern.compile("^(-?[\\d,]*\\.?\\d+)\\s*([kKMBT%]?)$");
    /** fmt is rounded to 2 decimals in its own scale; allow that rounding plus a little. */
    private static final BigDecimal RELATIVE_TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal PERCENT_POINT_TOLERANCE = new BigDecimal("0.011");

    private YahooFieldSemantics() {
    }

    /** The meaning among {@code candidates} that reproduces Yahoo's formatted string, if exactly one does. */
    static Optional<ProviderUnit> verify(YahooValue value, Set<ProviderUnit> candidates) {
        if (value == null || value.fmt() == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMATTED.matcher(value.fmt().strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        BigDecimal shown = new BigDecimal(matcher.group(1).replace(",", ""));
        String suffix = matcher.group(2);
        BigDecimal raw = value.raw();

        if ("%".equals(suffix)) {
            boolean fraction = candidates.contains(ProviderUnit.FRACTION)
                    && closeInPoints(raw.multiply(BigDecimal.valueOf(100)), shown);
            boolean percentage = candidates.contains(ProviderUnit.PERCENTAGE) && closeInPoints(raw, shown);
            if (fraction == percentage) {
                // neither matches, or (for values near zero) both do - either way, not established
                return Optional.empty();
            }
            return Optional.of(fraction ? ProviderUnit.FRACTION : ProviderUnit.PERCENTAGE);
        }

        BigDecimal scaled = shown.multiply(multiplier(suffix));
        if (!closeRelative(raw, scaled)) {
            return Optional.empty();
        }
        for (ProviderUnit unit : candidates) {
            if (unit != ProviderUnit.FRACTION && unit != ProviderUnit.PERCENTAGE) {
                return Optional.of(unit);
            }
        }
        return Optional.empty();
    }

    private static BigDecimal multiplier(String suffix) {
        return switch (suffix) {
            case "k", "K" -> new BigDecimal("1000");
            case "M" -> new BigDecimal("1000000");
            case "B" -> new BigDecimal("1000000000");
            case "T" -> new BigDecimal("1000000000000");
            default -> BigDecimal.ONE;
        };
    }

    private static boolean closeInPoints(BigDecimal a, BigDecimal b) {
        return a.subtract(b).abs().compareTo(PERCENT_POINT_TOLERANCE) <= 0;
    }

    private static boolean closeRelative(BigDecimal raw, BigDecimal shown) {
        if (shown.signum() == 0) {
            return raw.abs().compareTo(new BigDecimal("0.01")) <= 0;
        }
        return raw.subtract(shown).abs().divide(shown.abs(), MathContext.DECIMAL64).compareTo(RELATIVE_TOLERANCE) <= 0;
    }
}
