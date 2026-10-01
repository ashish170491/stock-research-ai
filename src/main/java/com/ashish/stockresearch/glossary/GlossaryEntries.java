package com.ashish.stockresearch.glossary;

import com.ashish.stockresearch.screening.ScreeningMetric;

import java.util.List;
import java.util.Set;

import static com.ashish.stockresearch.glossary.MetricQuestion.CASH;
import static com.ashish.stockresearch.glossary.MetricQuestion.GENERAL;
import static com.ashish.stockresearch.glossary.MetricQuestion.GROWING;
import static com.ashish.stockresearch.glossary.MetricQuestion.PRICE;
import static com.ashish.stockresearch.glossary.MetricQuestion.PROFITABLE;
import static com.ashish.stockresearch.glossary.MetricQuestion.STRETCHED;

/**
 * The glossary's text, written once and reviewed by a person. Each calculation line describes what the
 * application actually does ({@code FinancialMetricsService}, {@code SnapshotCalculator},
 * {@code ValuationService}, {@code FiledAnnualFinancials}); change it with the code.
 *
 * <p>No entry gives a benchmark, an ideal value or a judgement, and none holds a number other than 100: a
 * usual level depends on the industry and the period, which the application shows from data, not here.
 * {@code MetricGlossaryTest} checks this.
 */
final class GlossaryEntries {

    private GlossaryEntries() {
    }

    static final List<GlossaryEntry> ALL = List.of(

            // --- Is it profitable? -------------------------------------------------------------------
            new GlossaryEntry("roe", "Return on equity (ROE)",
                    List.of("ROE", "return on equity", "average ROE"), PROFITABLE,
                    Set.of("returnOnEquityPercent", "returnOnEquity3yAveragePercent", "returnOnEquity5yAveragePercent"),
                    Set.of(ScreeningMetric.ROE_PERCENT, ScreeningMetric.ROE_3Y_AVG_PERCENT,
                            ScreeningMetric.ROE_5Y_AVG_PERCENT),
                    Set.of("returnOnEquityPercent"), List.of("return on equity"),
                    "The profit a company made for each ₹100 of its shareholders' money (the equity in its balance "
                            + "sheet) in a fiscal year.",
                    "Net profit attributable to the parent's owners, divided by the average of the opening and closing "
                            + "shareholders' equity, x 100, both from the company's filing with NSE. When the prior "
                            + "year's balance sheet is not available, closing equity is used and the calculation says "
                            + "so. The three- and five-year figures are the plain average of each year's ROE, and are "
                            + "given only when every year is on average equity.",
                    "A higher ROE means more profit for each rupee of shareholders' money. Borrowing raises ROE without "
                            + "the business earning more on what it owns, because debt adds assets but not equity. A "
                            + "share buyback, a large dividend or a write-off shrinks equity and raises ROE; a one-off "
                            + "gain raises profit for that year only. A loss gives a negative ROE. One year can be "
                            + "unusual, so the multi-year averages show whether the level held.",
                    List.of("debt-to-equity", "roa", "roce")),

            new GlossaryEntry("roa", "Return on assets (ROA)",
                    List.of("ROA", "return on assets"), PROFITABLE,
                    Set.of("returnOnAssetsPercent"), Set.of(ScreeningMetric.ROA_PERCENT),
                    Set.of("returnOnAssetsPercent"), List.of("return on assets"),
                    "The profit a company made for each ₹100 of everything it owns and controls (its total assets) in "
                            + "a fiscal year.",
                    "Net profit attributable to the parent's owners, divided by the average of the opening and closing "
                            + "total assets, x 100, from the company's filing with NSE. When the prior year's balance sheet is "
                            + "not available, closing total assets are used and the calculation says so.",
                    "Unlike ROE, borrowing does not raise ROA, so reading the two together shows how much of a "
                            + "company's return comes from debt. A company whose assets are mostly funded by borrowing "
                            + "has a small ROA by nature, even when its ROE is large. Companies with heavy plant or property hold more "
                            + "assets for each rupee of profit than service companies, so ROA is compared within an "
                            + "industry.",
                    List.of("roe", "debt-to-equity")),

            new GlossaryEntry("roce", "Return on capital employed (ROCE)",
                    List.of("ROCE", "return on capital employed", "return on capital"), PROFITABLE,
                    Set.of("returnOnCapitalEmployedPercent"), Set.of(ScreeningMetric.ROCE_PERCENT),
                    Set.of("returnOnCapitalEmployedPercent"), List.of("ROCE"),
                    "The operating profit a company made for each ₹100 of the long-term money in the business: "
                            + "shareholders' equity and borrowings together.",
                    "EBIT (profit before tax plus finance costs), divided by the average of the opening and closing "
                            + "capital employed, x 100. Capital employed is total assets minus current liabilities. All "
                            + "from the company's filing with NSE. When the prior year's balance sheet is not available, "
                            + "closing capital employed is used and the calculation says so.",
                    "ROCE measures the business before it is financed: interest is added back and borrowings count as "
                            + "capital, so a company cannot raise it simply by borrowing, as it can its ROE. An ROE far "
                            + "above ROCE usually means borrowing is lifting the ROE. Large cash balances count as "
                            + "capital employed and lower ROCE.",
                    List.of("roe", "operating-margin", "debt-to-equity")),

            new GlossaryEntry("operating-margin", "Operating margin",
                    List.of("operating margin", "EBIT margin"), PROFITABLE,
                    Set.of("operatingMarginPercent"), Set.of(ScreeningMetric.OPERATING_MARGIN_PERCENT),
                    Set.of("operatingMarginPercent"), List.of("operating margin"),
                    "The share of each ₹100 of revenue left after the costs of running the business, before interest, "
                            + "tax and other income.",
                    "Operating profit divided by revenue from operations, x 100. Operating profit is revenue from "
                            + "operations minus total expenses, with finance costs added back, from the company's "
                            + "filing; other income (interest, dividends, gains on investments) is left out.",
                    "Margins differ widely between industries: a retailer or a commodity processor works on thin "
                            + "margins and large volumes, a software company on wider ones. A margin that moves over "
                            + "the years shows whether costs rose faster or slower than revenue. A change in how excise "
                            + "duty or other levies are counted in revenue moves the margin too.",
                    List.of("net-margin", "cagr")),

            new GlossaryEntry("net-margin", "Net profit margin",
                    List.of("net profit margin", "net margin", "profit margin", "PAT margin"), PROFITABLE,
                    Set.of("netProfitMarginPercent", "netProfitMarginTtmPercent"), Set.of(),
                    Set.of("netProfitMarginPercent"), List.of(),
                    "The share of each ₹100 of revenue left as profit for the owners after every cost, interest and "
                            + "tax.",
                    "Net profit attributable to the parent's owners divided by revenue from operations, x 100, for each "
                            + "fiscal year from the filings. The trailing-twelve-month figure uses Yahoo Finance's last "
                            + "four quarters.",
                    "Unlike operating margin, it includes interest, tax, other income and one-off items, so a single "
                            + "gain or write-off can move it for a year. A net margin that moves while the operating "
                            + "margin does not points to interest, tax or one-off items.",
                    List.of("operating-margin", "roe")),

            new GlossaryEntry("ebitda", "EBITDA",
                    List.of("EBITDA"), PROFITABLE,
                    Set.of("ebitda"), Set.of(), Set.of("ebitda"), List.of(),
                    "Earnings before interest, tax, depreciation and amortisation: operating profit with the cost of "
                            + "wearing out assets added back.",
                    "As Yahoo Finance reports it for the trailing twelve months; the application does not calculate "
                            + "it.",
                    "It leaves out depreciation, so it does not show the cost of replacing plant and machinery, and "
                            + "companies with heavy assets look larger on EBITDA than on profit. It is not cash flow "
                            + "either: working capital and tax are not in it.",
                    List.of("operating-margin", "free-cash-flow")),

            // --- Is it growing? ----------------------------------------------------------------------
            new GlossaryEntry("cagr", "Compound annual growth rate (CAGR)",
                    List.of("CAGR", "revenue growth", "sales growth", "profit growth", "earnings growth",
                            "compound annual growth"), GROWING,
                    Set.of("revenueCagrPercent", "netProfitCagrPercent", "revenueCagr3yPercent", "revenueCagr5yPercent",
                            "netProfitCagr3yPercent", "netProfitCagr5yPercent"),
                    Set.of(ScreeningMetric.REVENUE_CAGR_3Y_PERCENT, ScreeningMetric.REVENUE_CAGR_5Y_PERCENT,
                            ScreeningMetric.NET_PROFIT_CAGR_3Y_PERCENT, ScreeningMetric.NET_PROFIT_CAGR_5Y_PERCENT),
                    Set.of("revenueCagrPercent", "netProfitCagrPercent"),
                    List.of("revenue growth", "net profit growth", "net profit (PAT) growth"),
                    "The steady yearly growth rate that would take a figure from its first year's value to its last "
                            + "year's over a span of years.",
                    "(last year's value / first year's value), raised to the power of one over the number of years, minus "
                            + "one, x 100. The screens use exactly "
                            + "the last three or five fiscal years from the filings, and every year in the span must be "
                            + "usable: a span with a missing or disputed year gives no CAGR, never one over fewer years. "
                            + "It is undefined when the first or last value is zero or a loss.",
                    "Only the first and last years enter the formula, so one unusual year at either end, such as a "
                            + "pandemic year or a one-off gain, changes the result a lot; the yearly figures show what "
                            + "happened in between. Growth from a small or loss-making base can look very high. Profit "
                            + "growing faster than revenue means margins widened over the span.",
                    List.of("growth", "operating-margin")),

            new GlossaryEntry("growth", "Year-on-year and quarter-on-quarter growth",
                    List.of("YoY", "year-on-year", "year on year", "QoQ", "quarter-on-quarter", "quarter on quarter"),
                    GROWING,
                    Set.of("revenueGrowthYoyPercent", "netProfitGrowthYoyPercent", "latestQuarterRevenueGrowthQoqPercent",
                            "latestQuarterNetProfitGrowthQoqPercent", "quarterlyRevenueGrowthYoyPercent",
                            "quarterlyEarningsGrowthYoyPercent"),
                    Set.of(), Set.of(), List.of(),
                    "The change in a figure from one period to the next, as a percentage of the earlier period.",
                    "(this period's value / the earlier period's value, minus one) x 100, not calculated when the earlier "
                            + "value is zero or a loss. Year-on-year compares a fiscal year or quarter with the one a "
                            + "year before; quarter-on-quarter compares a quarter with the one just before it.",
                    "Many businesses are seasonal (festive-season sales, monsoon-linked demand, year-end orders), so a "
                            + "quarter-on-quarter change can reflect the season rather than the business; year-on-year "
                            + "comparisons remove most of that. One period's growth says little on its own; the "
                            + "multi-year CAGR and the yearly figures show the longer run.",
                    List.of("cagr")),

            // --- Are the profits real cash? ----------------------------------------------------------
            new GlossaryEntry("ocf-to-pat", "Operating cash flow to net profit (OCF/PAT)",
                    List.of("OCF/PAT", "cash conversion", "operating cash flow to net profit"), CASH,
                    Set.of("ocfToPat3yMultiple", "ocfToPat5yMultiple"),
                    Set.of(ScreeningMetric.OCF_TO_PAT_3Y_MULTIPLE, ScreeningMetric.OCF_TO_PAT_5Y_MULTIPLE),
                    Set.of("ocfToPat3yMultiple", "ocfToPat5yMultiple"), List.of("cash conversion"),
                    "How much of the reported profit arrived as cash: the cash the business's operations produced, "
                            + "compared with its net profit over the same years.",
                    "Operating cash flow summed over the last three or five fiscal years, divided by net profit summed "
                            + "over the same years; a multiple, not a percentage. From the filings' cash-flow "
                            + "statements, and every year must be usable.",
                    "A multiple near one means profit and operating cash moved together. Well below one means profit "
                            + "was booked but the cash had not come in, often because money is tied up in unpaid "
                            + "customer bills or in stock. Depreciation is added back in operating cash flow, so "
                            + "companies with heavy assets tend to sit above one. Summing several years smooths out one "
                            + "year's swing in working capital.",
                    List.of("free-cash-flow", "dividends-to-ocf")),

            new GlossaryEntry("free-cash-flow", "Free cash flow",
                    List.of("free cash flow", "FCF"), CASH,
                    Set.of("freeCashFlow"), Set.of(), Set.of("freeCashFlow"), List.of("free cash flow"),
                    "The cash left from operations after spending on new plant, equipment and other long-term assets.",
                    "As Yahoo Finance reports it for the trailing twelve months (operating cash flow minus capital "
                            + "expenditure); the application does not calculate it.",
                    "Heavy investment in a growing business can make it negative for some years without the business "
                            + "losing money. It is the cash a company can use to pay dividends, buy back shares or repay "
                            + "debt. It swings with the timing of large projects, so one period says little.",
                    List.of("ocf-to-pat", "debt-to-equity")),

            new GlossaryEntry("dividends-to-ocf", "Dividends paid to operating cash flow",
                    List.of("dividends to operating cash flow", "Dividends/OCF", "payout"), CASH,
                    Set.of("dividendsToOcfPercent"), Set.of(ScreeningMetric.DIVIDENDS_TO_OCF_PERCENT),
                    Set.of("dividendsToOcfPercent"), List.of(),
                    "The share of the cash a company's operations produced in a fiscal year that it paid to "
                            + "shareholders as dividends.",
                    "Dividends paid divided by operating cash flow, x 100, for the latest fiscal year, from the "
                            + "filing's cash-flow statement.",
                    "Above 100 means the company paid out more cash than its operations produced that year, so the rest "
                            + "came from cash it already held, from borrowing or from selling assets. A company that pays "
                            + "out most of its operating cash keeps little for investment. One year can be unusual: a "
                            + "special dividend, or a year of low cash.",
                    List.of("dividend-yield", "free-cash-flow")),

            // --- Is it financially stretched? --------------------------------------------------------
            new GlossaryEntry("debt-to-equity", "Debt to equity (D/E)",
                    List.of("D/E", "debt to equity", "debt-to-equity", "leverage"), STRETCHED,
                    Set.of("debtToEquityMultiple", "debtToEquityPercent"), Set.of(ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE),
                    Set.of("debtToEquityMultiple", "debtToEquityPercent"), List.of("debt-to-equity"),
                    "How much the company has borrowed for each rupee of its shareholders' money, at the end of a "
                            + "fiscal year.",
                    "Total borrowings (non-current plus current) divided by shareholders' equity at the fiscal year end, "
                            + "from the company's filing; a multiple, not a percentage. Lease liabilities are not "
                            + "counted as borrowings, and cash held is not deducted. For a bank, debt is the borrowings "
                            + "line as filed; deposits are not counted as debt.",
                    "Interest is owed whatever the year brings, so more debt makes profit swing more when business "
                            + "slows, and raises ROE when it does not. How much debt a company can carry depends on how "
                            + "steady its cash is: a utility with contracted revenue carries more than a cyclical "
                            + "manufacturer.",
                    List.of("roe", "roce", "ocf-to-pat")),

            // --- How much does the market charge for it? ---------------------------------------------
            new GlossaryEntry("pe", "Price to earnings (P/E)",
                    List.of("P/E", "PE", "price to earnings", "price-to-earnings", "PE ratio"), PRICE,
                    Set.of("trailingPe"), Set.of(ScreeningMetric.TRAILING_PE_MULTIPLE), Set.of("trailingPe"), List.of(),
                    "How many rupees the market is paying for each rupee of the company's yearly profit per share.",
                    "Share price divided by earnings per share over the trailing twelve months, as Yahoo Finance "
                            + "reports it; the application checks it against the share price and EPS, and withholds it "
                            + "when they do not agree.",
                    "A higher P/E means the market pays more for each rupee of today's profit, usually because it "
                            + "expects profit to grow, sometimes because this year's profit is unusually low. A loss "
                            + "makes it meaningless, and a one-off gain makes it look low. It is read against the "
                            + "company's own past P/E and against companies in the same industry; on its own it says "
                            + "nothing about whether a share is worth its price.",
                    List.of("eps", "earnings-yield", "cagr")),

            new GlossaryEntry("pe-filed", "P/E on filed EPS",
                    List.of("P/E on filed EPS", "filed EPS"), PRICE,
                    Set.of("peOnFiledEps", "filedShareCount"), Set.of(), Set.of(), List.of(),
                    "The share price divided by the earnings per share the company itself filed for its latest fiscal "
                            + "year.",
                    "Share price divided by the basic EPS in the latest annual filing with NSE. It is not calculated "
                            + "for a loss, or when the filing's share count and Yahoo Finance's differ by more than a "
                            + "small tolerance (a split, bonus or new issue in between).",
                    "It uses a full fiscal year the company has filed, so it lags the trailing P/E, which uses the last "
                            + "four quarters; the two differ when profit has changed since the year ended.",
                    List.of("pe", "eps")),

            new GlossaryEntry("eps", "Earnings per share (EPS)",
                    List.of("EPS", "earnings per share"), PRICE,
                    Set.of("trailingEps"), Set.of(), Set.of(), List.of(),
                    "The profit attributable to shareholders divided by the number of shares: each share's portion of "
                            + "the profit.",
                    "The trailing-twelve-month EPS as Yahoo Finance reports it; the fiscal-year EPS is the basic EPS in "
                            + "the company's filing.",
                    "EPS can grow faster or slower than profit when the number of shares changes: a buyback raises it, "
                            + "a new issue lowers it. A split or bonus issue changes EPS without changing the business, "
                            + "so EPS figures are compared on the same share basis only.",
                    List.of("pe")),

            new GlossaryEntry("pb", "Price to book (P/B)",
                    List.of("P/B", "price to book", "price-to-book", "book value"), PRICE,
                    Set.of("priceToBook", "bookValuePerShare"), Set.of(ScreeningMetric.PRICE_TO_BOOK_MULTIPLE),
                    Set.of("priceToBook"), List.of(),
                    "How many rupees the market is paying for each rupee of shareholders' equity in the balance sheet "
                            + "(the book value).",
                    "Share price divided by book value per share, as Yahoo Finance reports it, checked against the "
                            + "share price.",
                    "Book value is what the accounts say the equity is worth. It is closest to what a company "
                            + "owns when its assets are carried near their value, such as loans or cash. Where a "
                            + "company's value lies in brands, software or people rather than in its balance sheet, P/B "
                            + "says little. A higher ROE usually goes with a higher P/B.",
                    List.of("roe", "pe")),

            new GlossaryEntry("earnings-yield", "Earnings yield",
                    List.of("earnings yield"), PRICE,
                    Set.of("earningsYieldPercent"), Set.of(ScreeningMetric.EARNINGS_YIELD_PERCENT), Set.of(), List.of(),
                    "The P/E turned upside down: the yearly profit per share as a percentage of the share price.",
                    "100 / trailing P/E.",
                    "A higher earnings yield means more profit for each rupee of share price, the same as a lower P/E. "
                            + "As a percentage it can be set beside interest rates on deposits or bonds, but profit is "
                            + "not paid out like interest, and it can rise or fall.",
                    List.of("pe", "dividend-yield")),

            new GlossaryEntry("dividend-yield", "Dividend yield",
                    List.of("dividend yield"), PRICE,
                    Set.of("dividendYieldPercent", "dividendPerShare"), Set.of(ScreeningMetric.DIVIDEND_YIELD_PERCENT),
                    Set.of(), List.of(),
                    "The dividends paid on one share over a year, as a percentage of the share price.",
                    "Dividend per share divided by the share price, x 100, as Yahoo Finance reports it, checked against "
                            + "both.",
                    "It rises when the price falls, so a high yield can follow a fall in the share price rather than a "
                            + "rise in dividends. A one-off special dividend lifts it for a year. Whether the dividend "
                            + "comes out of the cash the business produces is shown by dividends paid to operating cash "
                            + "flow.",
                    List.of("dividends-to-ocf", "ocf-to-pat")),

            new GlossaryEntry("price-return", "Share-price return",
                    List.of("share-price return", "price return", "total return", "price CAGR"), PRICE,
                    Set.of("priceCagrPercent", "totalReturnPercent"), Set.of(), Set.of(), List.of(),
                    "How much the share price changed over a period, in total and as a yearly rate.",
                    "In total, (end close / start close, minus one) x 100; as a yearly rate, (end close / start close) "
                            + "raised to the power of one over the number of years, minus one, x 100. Closing prices "
                            + "from Yahoo Finance; dividends are not included.",
                    "It depends heavily on the start and end dates: a span that starts at a market low shows more. It "
                            + "is the past price, not the business's results; a share can rise while profit falls, and "
                            + "the reverse.",
                    List.of("drawdown")),

            new GlossaryEntry("drawdown", "Maximum drawdown",
                    List.of("drawdown", "max drawdown", "maximum drawdown"), PRICE,
                    Set.of("maxDrawdownPercent"), Set.of(), Set.of(), List.of(),
                    "The largest fall in the share price from a high to a later low within the period.",
                    "(lowest close after a peak / that peak's close, minus one) x 100, the largest such fall in the period, "
                            + "from Yahoo Finance's closing prices.",
                    "It shows how far the price fell at its worst during the period, which a holder would have sat "
                            + "through. A longer period includes more market falls.",
                    List.of("price-return")),

            // --- Terms used in the figures -----------------------------------------------------------
            new GlossaryEntry("revenue", "Revenue from operations",
                    List.of("revenue", "revenue from operations", "sales", "turnover"), GENERAL,
                    Set.of("revenue", "annualRevenue", "filedRevenueFromOperations"), Set.of(), Set.of(), List.of(),
                    "The money a company earned from selling its goods and services in a period, before any costs.",
                    "Revenue from operations as filed with NSE for each fiscal year, and as Yahoo Finance reports it for "
                            + "the trailing twelve months and the quarters; other income is not part of it. A bank's "
                            + "statement has no such line: its income is interest earned and other income.",
                    "Yahoo Finance's revenue may be defined differently from the filed line (it can include other "
                            + "operating income, or exclude excise duty), so the application never compares the two "
                            + "or mixes them in one calculation.",
                    List.of("cagr", "operating-margin")),

            new GlossaryEntry("net-profit", "Net profit (PAT)",
                    List.of("net profit", "PAT", "profit after tax"), GENERAL,
                    Set.of("netProfit", "annualNetProfit", "filedProfitAttributableToOwners"), Set.of(), Set.of(),
                    List.of(),
                    "The profit left for the company's shareholders after every cost, interest and tax.",
                    "For consolidated results, the profit attributable to the owners of the parent company as filed "
                            + "with NSE, which leaves out the share belonging to minority holders in subsidiaries. "
                            + "Yahoo Finance's figure for the same year is the cross-check; a year on which the two "
                            + "disagree is a DATA_CONFLICT, and neither is used.",
                    "One-off items (the sale of a business, a write-off, a tax refund) can move it a lot in a single "
                            + "year; the yearly figures and the operating margin show whether the business itself "
                            + "changed.",
                    List.of("net-margin", "cagr", "eps")),

            new GlossaryEntry("ebit", "EBIT",
                    List.of("EBIT", "earnings before interest and tax"), GENERAL,
                    Set.of("ebit"), Set.of(), Set.of(), List.of(),
                    "Earnings before interest and tax: the profit the business made before paying interest on its "
                            + "borrowings and tax.",
                    "Profit before tax plus finance costs, from the company's filing with NSE. A bank's statement "
                            + "has no line for it, so a bank has no EBIT here.",
                    "It is the profit used in ROCE because it is earned for everyone who funds the business: those who "
                            + "lent it money as well as its shareholders.",
                    List.of("roce", "operating-margin")),

            new GlossaryEntry("ttm", "Trailing twelve months (TTM)",
                    List.of("TTM", "trailing twelve months", "trailing twelve-month"), GENERAL,
                    Set.of(), Set.of(), Set.of(), List.of(),
                    "The last four reported quarters taken together, whatever fiscal year they fall in.",
                    "The sum of the last four quarters, as Yahoo Finance reports it; the report names the latest "
                            + "quarter it ends with.",
                    "It is more recent than the last fiscal year, but its quarters may not have been audited, and a "
                            + "figure for a TTM is not comparable with one for a fiscal year.",
                    List.of("fiscal-year")),

            new GlossaryEntry("fiscal-year", "Fiscal year (FY)",
                    List.of("fiscal year", "financial year", "FY"), GENERAL,
                    Set.of(), Set.of(), Set.of(), List.of(),
                    "The company's accounting year. For most Indian companies it runs from April to the following "
                            + "March, and is named by the year in which it ends.",
                    "Fiscal-year figures come from the company's annual results filed with NSE, as audited.",
                    "Figures for different fiscal years, or for a fiscal year and a TTM, are compared knowing they cover "
                            + "different periods; the application never mixes years from different sources in one "
                            + "calculation.",
                    List.of("ttm", "consolidated")),

            new GlossaryEntry("consolidated", "Consolidated and standalone results",
                    List.of("consolidated", "standalone"), GENERAL,
                    Set.of(), Set.of(), Set.of(), List.of(),
                    "Consolidated results combine the company with its subsidiaries; standalone results are the "
                            + "company on its own.",
                    "The application uses consolidated results where the company files them, and never mixes "
                            + "consolidated and standalone figures in one calculation.",
                    "For a company with large subsidiaries the two can differ a great deal, so a figure is read with "
                            + "the scope it is on.",
                    List.of("fiscal-year"))
    );
}
