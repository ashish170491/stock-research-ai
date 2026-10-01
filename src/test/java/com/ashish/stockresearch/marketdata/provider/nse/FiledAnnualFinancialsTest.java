package com.ashish.stockresearch.marketdata.provider.nse;

import com.ashish.stockresearch.research.FiledAnnualFinancials;
import com.ashish.stockresearch.research.FiledStatementChecks;
import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.CalculationStatus;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.model.StatementScope;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.ashish.stockresearch.marketdata.provider.nse.NseXbrlReaderTest.fixture;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real filings as the statement data the calculations use: each figure is a filed line, or a sum of filed
 * lines worked out by hand from the document below.
 */
class FiledAnnualFinancialsTest {

    private final NseXbrlReader reader = new NseXbrlReader();
    private final FiledStatementChecks checks = new FiledStatementChecks();

    private AnnualFinancials annual(String file, String symbol, int fiscalYear, NseFilingListing.Format format) {
        NseFilingListing listing = new NseFilingListing(symbol, LocalDate.of(fiscalYear, 3, 31),
                StatementScope.CONSOLIDATED, true, LocalDateTime.of(fiscalYear, 4, 25, 21, 57),
                "https://nsearchives.nseindia.com/corporate/xbrl/" + symbol + ".xml", format);
        return FiledAnnualFinancials.from(checks.check(reader.read(XbrlInstance.parse(fixture(file)), listing)));
    }

    private static FinancialDataPoint valid(FinancialDataPoint point) {
        assertThat(point.status()).as(point.metric() + ": " + point.statusReason()).isEqualTo(DataStatus.VALID);
        return point;
    }

    @Test
    void takesACompanysFiledLinesAndSumsOnlyWhatNoLineStates() {
        AnnualFinancials fy26 = annual("reliance-fy26-consolidated.xml", "RELIANCE", 2026,
                NseFilingListing.Format.INTEGRATED);

        assertThat(valid(fy26.revenue()).value()).isEqualByComparingTo("1075675");
        // net profit is the owners' share, the profit EPS and ROE measure - not the ₹95,754 crore for the period
        assertThat(valid(fy26.netProfit()).value()).isEqualByComparingTo("80775");
        assertThat(fy26.netProfit().calculationStatus()).isEqualTo(CalculationStatus.REPORTED);
        assertThat(valid(fy26.shareholdersEquity()).value()).isEqualByComparingTo("904030");
        assertThat(valid(fy26.totalAssets()).value()).isEqualByComparingTo("2178140");
        assertThat(valid(fy26.currentLiabilities()).value()).isEqualByComparingTo("541254");
        assertThat(valid(fy26.operatingCashFlow()).value()).isEqualByComparingTo("192113");
        // 1,23,162 profit before tax + 27,061 finance costs
        assertThat(valid(fy26.ebit()).value()).isEqualByComparingTo("150223");
        assertThat(fy26.ebit().formula()).isEqualTo(Formula.SUM);
        assertThat(fy26.ebit().calculation()).isEqualTo("FY26 profit before tax + FY26 finance costs");
        // 10,75,675 revenue - (9,81,475 total expenses - 27,061 finance costs)
        assertThat(valid(fy26.operatingIncome()).value()).isEqualByComparingTo("121261");
        assertThat(fy26.operatingIncome().formula()).isEqualTo(Formula.OPERATING_PROFIT);
        // 2,70,751 non-current + 1,03,670 current borrowings
        assertThat(valid(fy26.totalDebt()).value()).isEqualByComparingTo("374421");
        // filed as ₹7,879 crore paid; an outflow to the calculations
        assertThat(valid(fy26.cashDividendsPaid()).value()).isEqualByComparingTo("-7879");
        assertThat(fy26.cashDividendsPaid().rawValue()).isEqualByComparingTo("78790000000");
        // every figure is the filing's, and every sum reproduces
        assertThat(fy26.points()).allMatch(point -> point.source().sourceType() == SourceType.OFFICIAL_FILING)
                .allMatch(point -> CalculationVerifier.problem(point, new CalculationVerifier.SourceValues()).isEmpty());
        assertThat(fy26.ebit().dependsOnFields())
                .containsExactly("nse-xbrl.ProfitBeforeTax", "nse-xbrl.FinanceCosts");
    }

    @Test
    void takesABanksEquityAsCapitalPlusReservesAndStatesNoOperatingFigures() {
        AnnualFinancials fy26 = annual("hdfcbank-fy26-consolidated.xml", "HDFCBANK", 2026,
                NseFilingListing.Format.INTEGRATED);

        assertThat(valid(fy26.netProfit()).value()).isEqualByComparingTo("76025.97");
        // 1,539.34 capital + 5,79,975.02 reserves and surplus
        assertThat(valid(fy26.shareholdersEquity()).value()).isEqualByComparingTo("581514.36");
        // borrowings as filed; the ₹30,99,638 crore of deposits are not debt
        assertThat(valid(fy26.totalDebt()).value()).isEqualByComparingTo("588484.55");
        for (FinancialDataPoint notInABanksStatement : new FinancialDataPoint[]{fy26.revenue(), fy26.operatingIncome(),
                fy26.ebit(), fy26.currentLiabilities()}) {
            assertThat(notInABanksStatement.status()).isEqualTo(DataStatus.UNAVAILABLE);
            assertThat(notInABanksStatement.statusReason()).contains("a bank's statement has no line for");
        }
    }

    @Test
    void neverCalculatesFromALineTheFilingContradicts() {
        // HCL Technologies' FY24 filing states profit attributable to owners as 0 beside ₹15,710 crore
        AnnualFinancials fy24 = annual("hcltech-fy24-consolidated.xml", "HCLTECH", 2024,
                NseFilingListing.Format.RESULTS);

        assertThat(fy24.netProfit().status()).isEqualTo(DataStatus.INVALID);
        assertThat(fy24.netProfit().value()).isNull();
        // a sum whose line is not filed is not a sum of the rest
        AnnualFinancials adanient = annual("adanient-fy21-consolidated-undefined-contexts.xml", "ADANIENT", 2021,
                NseFilingListing.Format.RESULTS);
        assertThat(adanient.totalDebt().status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(adanient.totalDebt().statusReason()).startsWith("not calculated: ");
    }
}
