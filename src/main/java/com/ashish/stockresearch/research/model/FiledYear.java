package com.ashish.stockresearch.research.model;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One fiscal year of a company's results as filed with the exchange: every {@link FiledItem} the filing
 * reports for the year, each a {@link FinancialDataPoint} whose source is the filing itself.
 *
 * @param period       the fiscal year, as the filing's own reporting-period dates state it
 * @param scope        consolidated or standalone
 * @param audited      whether the filing says the results are audited
 * @param filedOn      when the filing was made; null when the source does not say
 * @param documentUrl  the filed document the figures were read from
 * @param items        every item, VALID or with the reason it is not; an item the filing does not report is
 *                     UNAVAILABLE, never zero
 * @param checks       the consistency checks run on the filing and what each found; empty until checked
 */
public record FiledYear(
        ReportingPeriod period,
        StatementScope scope,
        boolean audited,
        LocalDate filedOn,
        String documentUrl,
        Map<FiledItem, FinancialDataPoint> items,
        List<Check> checks
) {

    /** One consistency check of a filing against itself, e.g. total assets = total equity and liabilities. */
    public record Check(String name, Outcome outcome, String detail) {

        public enum Outcome {
            PASSED,
            /** The filing contradicts itself; the items involved are INVALID. */
            FAILED,
            /** An item the check needs is not filed or not usable, so it could not be run. */
            NOT_POSSIBLE
        }
    }

    public FiledYear {
        if (period == null || scope == null || documentUrl == null) {
            throw new IllegalArgumentException("a filed year needs its period, scope and document");
        }
        EnumMap<FiledItem, FinancialDataPoint> all = new EnumMap<>(FiledItem.class);
        if (items != null) {
            all.putAll(items);
        }
        for (FiledItem item : FiledItem.values()) {
            if (!all.containsKey(item)) {
                throw new IllegalArgumentException("every filed item needs a data point, UNAVAILABLE if not filed: "
                        + item);
            }
        }
        items = java.util.Collections.unmodifiableMap(all);
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public FinancialDataPoint item(FiledItem item) {
        return items.get(item);
    }

    /** The same year with one item replaced, e.g. marked INVALID by a consistency check. */
    public FiledYear with(FiledItem item, FinancialDataPoint point) {
        EnumMap<FiledItem, FinancialDataPoint> copy = new EnumMap<>(items);
        copy.put(item, point);
        return new FiledYear(period, scope, audited, filedOn, documentUrl, copy, checks);
    }

    public FiledYear withChecks(List<Check> results) {
        return new FiledYear(period, scope, audited, filedOn, documentUrl, items, results);
    }
}
