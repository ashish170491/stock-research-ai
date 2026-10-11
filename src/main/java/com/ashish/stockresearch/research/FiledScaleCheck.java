package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.FiledItem;
import com.ashish.stockresearch.research.model.FiledYear;
import com.ashish.stockresearch.research.model.FiledYear.Check;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.StatementFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Catches a filing whose amounts are on the wrong scale. Companies tag their own XBRL: Marksans Pharma's FY25
 * filing states every amount about ten times too small (revenue ₹262 crore against ₹2,623 crore), which no
 * identity inside the filing reveals, because all of its figures are wrong together. The provider's revenue
 * for the same year is defined differently (it may include other operating income or exclude excise duty), so
 * the two are never expected to agree closely - but they cannot be several times apart. A filed revenue more
 * than {@value #MAX_FACTOR} times larger or smaller than the provider's fails the check, and every amount the
 * filing gives for that year is INVALID, since a scaling error touches them all. Per-share items are not amounts.
 * Not run on a bank's statement (its revenue is not the provider's), nor when either revenue is not usable.
 */
public final class FiledScaleCheck {

    public static final String NAME = "revenue is of the same size as the data provider's";
    static final int MAX_FACTOR = 2;

    private FiledScaleCheck() {
    }

    /** @param providerRevenue the provider's revenue for the same fiscal year; null when it has none */
    public static FiledYear check(FiledYear year, FinancialDataPoint providerRevenue) {
        FinancialDataPoint filed = year.item(FiledItem.REVENUE_FROM_OPERATIONS);
        if (year.format() == StatementFormat.BANK || !filed.available() || providerRevenue == null
                || !providerRevenue.available() || providerRevenue.value() == null
                || providerRevenue.value().signum() <= 0 || providerRevenue.unit() != filed.unit()) {
            return year;
        }
        BigDecimal ratio = filed.value().divide(providerRevenue.value(), 4, RoundingMode.HALF_UP);
        String figures = "filed revenue from operations %s vs the data provider's revenue %s".formatted(filed.display(),
                providerRevenue.display());
        boolean tooSmall = ratio.multiply(BigDecimal.valueOf(MAX_FACTOR)).compareTo(BigDecimal.ONE) < 0;
        boolean tooLarge = ratio.compareTo(BigDecimal.valueOf(MAX_FACTOR)) > 0;
        if (!tooSmall && !tooLarge) {
            return withCheck(year, new Check(NAME, Check.Outcome.PASSED, figures));
        }
        String reason = ("the filing's revenue is far from the data provider's for the same year (%s), which "
                + "points to a scaling error in the filing, so none of its amounts for the year are used")
                .formatted(figures);
        FiledYear rejected = year;
        for (FiledItem item : FiledItem.values()) {
            FinancialDataPoint point = year.item(item);
            if (item.unit() == com.ashish.stockresearch.research.model.Unit.INR_CRORE && point.available()) {
                rejected = rejected.with(item, FinancialDataPoint.rejected(point.metric(), point.unit(),
                        point.period(), point.source(), reason));
            }
        }
        return withCheck(rejected, new Check(NAME, Check.Outcome.FAILED, figures));
    }

    private static FiledYear withCheck(FiledYear year, Check check) {
        java.util.List<Check> checks = new java.util.ArrayList<>(year.checks());
        checks.add(check);
        return year.withChecks(checks);
    }
}
