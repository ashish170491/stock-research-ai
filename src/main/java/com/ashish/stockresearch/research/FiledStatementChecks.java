package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.model.FiledItem;
import com.ashish.stockresearch.research.model.FiledYear;
import com.ashish.stockresearch.research.model.FiledYear.Check;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.StatementFormat;
import com.ashish.stockresearch.research.model.StatementScope;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks a filed year against itself before any of it is used. A statutory filing is the source of
 * record, but companies tag their own XBRL, and tags are sometimes wrong: HCL Technologies' FY24 filing
 * states profit attributable to owners as 0, Tech Mahindra's FY25 filing as ₹241 crore beside a profit
 * for the year of ₹4,253 crore. The statements' own identities catch these:
 *
 * <ul>
 *   <li>profit for the period = profit attributable to owners + to non-controlling interests
 *       (consolidated Ind-AS statements, which split it; not the banking format);</li>
 *   <li>total assets = total equity and liabilities;</li>
 *   <li>total equity = equity attributable to owners + non-controlling interest (consolidated
 *       statements under Ind-AS).</li>
 * </ul>
 *
 * The figures compared are filed at a stated precision (usually to the nearest crore), so each identity
 * may be off by the rounding of its figures, and no more. When one fails, every item in it is INVALID -
 * the filing does not say which line is wrong, so none is chosen - and the reason quotes the filed figures.
 * A check that needs an item the filing does not report is NOT_POSSIBLE, and the items stay as filed:
 * the cross-check against a second source still applies to them.
 */
@Component
public class FiledStatementChecks {

    public static final String PROFIT_SPLIT = "profit split between owners and non-controlling interests";
    public static final String BALANCE_SHEET = "balance sheet balances";
    public static final String EQUITY_SPLIT = "equity split between owners and non-controlling interests";

    /** One crore, in the crore unit the items are held in: the usual precision of a filing's figures. */
    private static final BigDecimal ONE_CRORE = BigDecimal.ONE;

    public FiledYear check(FiledYear year) {
        List<Check> checks = new ArrayList<>();
        FiledYear checked = year;
        boolean bank = year.format() == StatementFormat.BANK;
        if (year.scope() == StatementScope.CONSOLIDATED && bank) {
            // The banking format deducts minority interest and adds associates' profit after tax, with signs
            // this application has not established; a check on it could reject a correct figure.
            checks.add(new Check(PROFIT_SPLIT, Check.Outcome.NOT_POSSIBLE,
                    "not run on the banking format, whose profit after minority interest also adds associates' profit"));
        } else if (year.scope() == StatementScope.CONSOLIDATED) {
            checked = identity(checked, checks, PROFIT_SPLIT, FiledItem.PROFIT_FOR_PERIOD,
                    FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS, FiledItem.PROFIT_ATTRIBUTABLE_TO_NON_CONTROLLING_INTERESTS);
        }
        checked = identity(checked, checks, BALANCE_SHEET, FiledItem.TOTAL_EQUITY_AND_LIABILITIES,
                FiledItem.TOTAL_ASSETS);
        if (year.scope() == StatementScope.CONSOLIDATED && year.item(FiledItem.TOTAL_EQUITY).available()) {
            checked = identity(checked, checks, EQUITY_SPLIT,
                    FiledItem.TOTAL_EQUITY, FiledItem.EQUITY_ATTRIBUTABLE_TO_OWNERS, FiledItem.NON_CONTROLLING_INTEREST);
        }
        return checked.withChecks(checks);
    }

    /** {@code total} must equal the sum of {@code parts}, to within the parts' rounding. */
    private static FiledYear identity(FiledYear year, List<Check> checks, String name, FiledItem total,
                                      FiledItem... parts) {
        List<FiledItem> involved = new ArrayList<>(List.of(total));
        involved.addAll(List.of(parts));
        List<String> missing = involved.stream().filter(item -> !year.item(item).available())
                .map(FiledItem::label).toList();
        if (!missing.isEmpty()) {
            checks.add(new Check(name, Check.Outcome.NOT_POSSIBLE, "not filed or not usable: " + String.join(", ", missing)));
            return year;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (FiledItem part : parts) {
            sum = sum.add(year.item(part).value());
        }
        BigDecimal stated = year.item(total).value();
        BigDecimal allowed = ONE_CRORE.multiply(BigDecimal.valueOf(parts.length));
        StringBuilder figures = new StringBuilder(total.label() + " " + year.item(total).display() + " vs ");
        for (int i = 0; i < parts.length; i++) {
            figures.append(i == 0 ? "" : " + ").append(parts[i].label()).append(' ').append(year.item(parts[i]).display());
        }
        if (stated.subtract(sum).abs().compareTo(allowed) <= 0) {
            checks.add(new Check(name, Check.Outcome.PASSED, figures.toString()));
            return year;
        }
        String reason = "the filing contradicts itself (%s: %s), so neither line is used".formatted(name, figures);
        checks.add(new Check(name, Check.Outcome.FAILED, figures.toString()));
        FiledYear rejected = year;
        for (FiledItem item : involved) {
            FinancialDataPoint point = year.item(item);
            rejected = rejected.with(item, FinancialDataPoint.rejected(point.metric(), point.unit(), point.period(),
                    point.source(), reason));
        }
        return rejected;
    }
}
