package com.ashish.stockresearch.research.model;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * One fiscal year of statement data - the inputs for the year-on-year,
 * CAGR, margin, ROE, ROA, debt-to-equity, ROCE, cash-conversion and dividend-cover calculations.
 *
 * @param ebit               earnings before interest and tax, for ROCE
 * @param currentLiabilities closing current liabilities; total assets minus these is capital employed
 * @param operatingCashFlow  cash generated from operations, for cash conversion (OCF / net profit)
 * @param cashDividendsPaid  dividends paid in cash in the year, as the cash-flow statement reports it: an
 *                           outflow, so negative (or zero); compared with the same year's operating cash flow
 */
public record AnnualFinancials(
        ReportingPeriod period,
        FinancialDataPoint revenue,
        FinancialDataPoint netProfit,
        FinancialDataPoint operatingIncome,
        FinancialDataPoint shareholdersEquity,
        FinancialDataPoint totalAssets,
        FinancialDataPoint totalDebt,
        FinancialDataPoint ebit,
        FinancialDataPoint currentLiabilities,
        FinancialDataPoint operatingCashFlow,
        FinancialDataPoint cashDividendsPaid
) {

    public List<FinancialDataPoint> points() {
        return List.of(revenue, netProfit, operatingIncome, shareholdersEquity, totalAssets, totalDebt, ebit,
                currentLiabilities, operatingCashFlow, cashDividendsPaid);
    }

    public AnnualFinancials map(UnaryOperator<FinancialDataPoint> f) {
        return new AnnualFinancials(period, f.apply(revenue), f.apply(netProfit), f.apply(operatingIncome),
                f.apply(shareholdersEquity), f.apply(totalAssets), f.apply(totalDebt), f.apply(ebit),
                f.apply(currentLiabilities), f.apply(operatingCashFlow), f.apply(cashDividendsPaid));
    }
}
