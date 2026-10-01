package com.ashish.stockresearch.research;

import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.calc.FinancialCalculator;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.CalculationInput;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FiledItem;
import com.ashish.stockresearch.research.model.FiledYear;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.ReportingPeriod;
import com.ashish.stockresearch.research.model.SourceInfo;
import com.ashish.stockresearch.research.model.StatementFormat;
import com.ashish.stockresearch.research.model.StatementScope;
import com.ashish.stockresearch.research.model.Unit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * One filed fiscal year as the statement data the application calculates from ({@link AnnualFinancials}).
 * Each figure is a filed line, as filed, except where the statements state no single line; those are
 * calculated from filed lines by {@link FinancialCalculator}, record their formula and inputs, and are
 * reproduced by {@link CalculationVerifier}:
 *
 * <ul>
 *   <li><b>net profit</b>: profit attributable to owners of the parent (consolidated) or profit for the
 *       period (standalone) - the profit that earnings per share and return on equity measure;</li>
 *   <li><b>operating income</b> (companies): revenue from operations - (total expenses - finance costs),
 *       so before interest, without other income or exceptional items;</li>
 *   <li><b>EBIT</b> (companies): profit before tax + finance costs;</li>
 *   <li><b>total debt</b> (companies): non-current + current borrowings, lease liabilities not included;
 *       a bank's borrowings as filed, its deposits never counted as debt;</li>
 *   <li><b>shareholders' equity</b>: equity attributable to owners of the parent (consolidated) or total
 *       equity (standalone); a bank's capital + reserves and surplus;</li>
 *   <li><b>dividends paid</b>: filed as the amount paid, recorded as the outflow the calculations expect.</li>
 * </ul>
 *
 * A bank's statement has no revenue from operations, operating profit, EBIT or current liabilities; for a
 * bank these are UNAVAILABLE, never approximated from other lines. An NBFC's balance sheet states its debt
 * securities, borrowings, deposits and subordinated liabilities, not current and non-current borrowings,
 * so its total debt is UNAVAILABLE rather than assembled.
 */
public final class FiledAnnualFinancials {

    private FiledAnnualFinancials() {
    }

    public static AnnualFinancials from(FiledYear year) {
        boolean bank = year.format() == StatementFormat.BANK;
        boolean consolidated = year.scope() == StatementScope.CONSOLIDATED;
        CalculationVerifier.SourceValues sources = new CalculationVerifier.SourceValues();
        year.items().values().forEach(sources::add);
        ReportingPeriod closing = year.item(FiledItem.TOTAL_ASSETS).period();

        FinancialDataPoint revenue = year.item(FiledItem.REVENUE_FROM_OPERATIONS);
        FinancialDataPoint netProfit = consolidated ? year.item(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS)
                : year.item(FiledItem.PROFIT_FOR_PERIOD);
        FinancialDataPoint operatingIncome = bank
                ? notInBankStatement("filedOperatingProfit", year.period(), "operating profit")
                : derived(year, "filedOperatingProfit", year.period(), Formula.OPERATING_PROFIT,
                "%s revenue from operations - (%s total expenses - %s finance costs)", sources,
                values -> FinancialCalculator.operatingProfit(values.get(0), values.get(1), values.get(2)),
                FiledItem.REVENUE_FROM_OPERATIONS, FiledItem.TOTAL_EXPENSES,
                FiledItem.FINANCE_COSTS);
        FinancialDataPoint equity = bank
                ? derived(year, "filedShareholdersEquity", closing, Formula.SUM,
                "%s capital + %s reserves and surplus", sources, FinancialCalculator::sum,
                FiledItem.SHARE_CAPITAL, FiledItem.RESERVES_AND_SURPLUS)
                : consolidated ? year.item(FiledItem.EQUITY_ATTRIBUTABLE_TO_OWNERS) : year.item(FiledItem.TOTAL_EQUITY);
        FinancialDataPoint debt = bank ? year.item(FiledItem.BORROWINGS)
                : derived(year, "filedTotalBorrowings", closing, Formula.SUM,
                "%s non-current borrowings + %s current borrowings (lease liabilities are not borrowings)", sources,
                FinancialCalculator::sum, FiledItem.BORROWINGS_NON_CURRENT, FiledItem.BORROWINGS_CURRENT);
        FinancialDataPoint ebit = bank
                ? notInBankStatement("filedEbit", year.period(), "EBIT")
                : derived(year, "filedEbit", year.period(), Formula.SUM, "%s profit before tax + %s finance costs",
                sources, FinancialCalculator::sum, FiledItem.PROFIT_BEFORE_TAX, FiledItem.FINANCE_COSTS);
        FinancialDataPoint currentLiabilities = bank
                ? notInBankStatement("filedCurrentLiabilities", closing, "current liabilities")
                : year.item(FiledItem.CURRENT_LIABILITIES);
        return new AnnualFinancials(year.period(), revenue, netProfit, operatingIncome, equity,
                year.item(FiledItem.TOTAL_ASSETS), debt, ebit, currentLiabilities,
                year.item(FiledItem.OPERATING_CASH_FLOW), asOutflow(year.item(FiledItem.DIVIDENDS_PAID)));
    }

    /**
     * A figure calculated from filed lines, verified, or - if a line is not VALID - not calculated, with that
     * line's status and why. {@code calculation} has one {@code %s} per input, each the year's label.
     */
    private static FinancialDataPoint derived(FiledYear year, String metric, ReportingPeriod period, Formula formula,
                                              String calculation, CalculationVerifier.SourceValues sources,
                                              Function<List<BigDecimal>, Optional<BigDecimal>> arithmetic,
                                              FiledItem... items) {
        List<FinancialDataPoint> inputs = new ArrayList<>();
        for (FiledItem item : items) {
            inputs.add(year.item(item));
        }
        String label = year.period().label();
        String described = calculation.formatted((Object[]) java.util.Collections.nCopies(items.length, label)
                .toArray(String[]::new));
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        inputs.forEach(input -> fields.addAll(input.dependsOnFields()));
        SourceInfo source = inputs.get(0).source().withField(String.join(" + ", fields));
        Unit unit = items[0].unit();

        Optional<FinancialDataPoint> worst = inputs.stream().filter(input -> !input.available())
                .max(java.util.Comparator.comparingInt(input -> rank(input.status())));
        if (worst.isPresent()) {
            FinancialDataPoint input = worst.get();
            String reason = "not calculated: %s (%s) is %s: %s".formatted(input.metric(), label, input.status(),
                    input.statusReason());
            return input.status() == DataStatus.UNAVAILABLE
                    ? FinancialDataPoint.unavailable(metric, unit, period, source, reason)
                    : FinancialDataPoint.notCalculated(metric, unit, period, source, input.status(), formula, described,
                    List.copyOf(fields), reason);
        }
        List<CalculationInput> recorded = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            recorded.add(CalculationInput.of(label + " " + items[i].label(), inputs.get(i)));
        }
        return arithmetic.apply(inputs.stream().map(FinancialDataPoint::value).toList())
                .map(value -> CalculationVerifier.verify(FinancialDataPoint.calculated(metric, value, unit, period,
                        source, formula, described, recorded), sources))
                .orElseGet(() -> FinancialDataPoint.unavailable(metric, unit, period, source,
                        "could not be calculated from the filed lines"));
    }

    /** The filing states dividends as the amount paid; the calculations take them as the outflow they are. */
    private static FinancialDataPoint asOutflow(FinancialDataPoint paid) {
        if (!paid.available()) {
            return paid;
        }
        if (paid.value().signum() < 0) {
            return FinancialDataPoint.rejected(paid.metric(), paid.unit(), paid.period(), paid.source(),
                    "dividends paid is filed as a negative amount (%s), not the amount paid NSE filings state, so "
                            .formatted(paid.display()) + "its meaning is not established");
        }
        return FinancialDataPoint.reported("filedDividendsPaidAsOutflow", paid.rawValue(), paid.providerUnit(),
                paid.value().negate(), paid.unit(), paid.period(), paid.source()).withCurrency(paid.currency());
    }

    private static FinancialDataPoint notInBankStatement(String metric, ReportingPeriod period, String what) {
        return FinancialDataPoint.unavailable(metric, Unit.INR_CRORE, period, null,
                "a bank's statement has no line for %s, and it is not approximated from other lines".formatted(what));
    }

    /** A missing line outranks a disputed one, which outranks an invalid one: the figure could not exist. */
    private static int rank(DataStatus status) {
        return switch (status) {
            case UNAVAILABLE -> 3;
            case DATA_CONFLICT -> 2;
            case INVALID -> 1;
            case VALID -> 0;
        };
    }
}
