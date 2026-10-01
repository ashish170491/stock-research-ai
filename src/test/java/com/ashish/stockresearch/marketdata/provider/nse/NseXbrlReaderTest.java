package com.ashish.stockresearch.marketdata.provider.nse;

import com.ashish.stockresearch.research.FiledStatementChecks;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FiledItem;
import com.ashish.stockresearch.research.model.FiledYear;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.model.StatementScope;
import com.ashish.stockresearch.research.model.Unit;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real NSE filings, recorded: every figure asserted here is the one the company filed, checked by hand
 * against the document. Includes the two formats (the older results filing and the integrated filing from
 * FY25), the banking statement, and two filings whose own tags contradict themselves.
 */
class NseXbrlReaderTest {

    private final NseXbrlReader reader = new NseXbrlReader();
    private final FiledStatementChecks checks = new FiledStatementChecks();

    static byte[] fixture(String name) {
        try {
            return new ClassPathResource("fixtures/nse/" + name).getInputStream().readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static NseFilingListing listing(String symbol, int fiscalYear, StatementScope scope,
                                            NseFilingListing.Format format) {
        return new NseFilingListing(symbol, LocalDate.of(fiscalYear, 3, 31), scope, true,
                LocalDateTime.of(fiscalYear, 4, 25, 21, 57), "https://nsearchives.nseindia.com/corporate/xbrl/" + symbol
                + "_FY" + fiscalYear + ".xml", format);
    }

    private FiledYear read(String file, String symbol, int fiscalYear, NseFilingListing.Format format) {
        return checks.check(reader.read(XbrlInstance.parse(fixture(file)),
                listing(symbol, fiscalYear, StatementScope.CONSOLIDATED, format)));
    }

    private static BigDecimal crore(FiledYear year, FiledItem item) {
        FinancialDataPoint point = year.item(item);
        assertThat(point.status()).as(item + ": " + point.statusReason()).isEqualTo(DataStatus.VALID);
        assertThat(point.unit()).isEqualTo(item.unit());
        return point.value();
    }

    @Test
    void readsTheFullYearOfAnIntegratedFilingWithEveryItemAsFiled() {
        FiledYear year = read("reliance-fy26-consolidated.xml", "RELIANCE", 2026, NseFilingListing.Format.INTEGRATED);

        assertThat(year.period().label()).isEqualTo("FY26");
        assertThat(year.period().start()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(crore(year, FiledItem.REVENUE_FROM_OPERATIONS)).isEqualByComparingTo("1075675");
        assertThat(crore(year, FiledItem.PROFIT_FOR_PERIOD)).isEqualByComparingTo("95754");
        assertThat(crore(year, FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS)).isEqualByComparingTo("80775");
        assertThat(crore(year, FiledItem.PROFIT_ATTRIBUTABLE_TO_NON_CONTROLLING_INTERESTS)).isEqualByComparingTo("14979");
        assertThat(crore(year, FiledItem.PROFIT_BEFORE_TAX)).isEqualByComparingTo("123162");
        assertThat(crore(year, FiledItem.FINANCE_COSTS)).isEqualByComparingTo("27061");
        assertThat(crore(year, FiledItem.TOTAL_EXPENSES)).isEqualByComparingTo("981475");
        assertThat(crore(year, FiledItem.EQUITY_ATTRIBUTABLE_TO_OWNERS)).isEqualByComparingTo("904030");
        assertThat(crore(year, FiledItem.TOTAL_ASSETS)).isEqualByComparingTo("2178140");
        assertThat(crore(year, FiledItem.CURRENT_LIABILITIES)).isEqualByComparingTo("541254");
        assertThat(crore(year, FiledItem.BORROWINGS_NON_CURRENT)).isEqualByComparingTo("270751");
        assertThat(crore(year, FiledItem.BORROWINGS_CURRENT)).isEqualByComparingTo("103670");
        assertThat(crore(year, FiledItem.OPERATING_CASH_FLOW)).isEqualByComparingTo("192113");
        assertThat(crore(year, FiledItem.DIVIDENDS_PAID)).isEqualByComparingTo("7879");
        assertThat(crore(year, FiledItem.BASIC_EPS)).isEqualByComparingTo("59.69");
        // a bank's lines are not in a company's statement
        assertThat(year.item(FiledItem.DEPOSITS).status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(year.checks()).allMatch(check -> check.outcome() == FiledYear.Check.Outcome.PASSED)
                .extracting(FiledYear.Check::name).containsExactly(FiledStatementChecks.PROFIT_SPLIT,
                        FiledStatementChecks.BALANCE_SHEET, FiledStatementChecks.EQUITY_SPLIT);
    }

    @Test
    void namesTheFilingAsTheSourceOfEveryFigure() {
        FiledYear year = read("reliance-fy26-consolidated.xml", "RELIANCE", 2026, NseFilingListing.Format.INTEGRATED);

        FinancialDataPoint profit = year.item(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS);
        assertThat(profit.source().source()).isEqualTo("NSE corporate filings (XBRL)");
        assertThat(profit.source().sourceType()).isEqualTo(SourceType.OFFICIAL_FILING);
        assertThat(profit.source().sourceField()).isEqualTo("nse-xbrl.ProfitOrLossAttributableToOwnersOfParent");
        assertThat(profit.source().publishedAt()).isEqualTo(LocalDate.of(2026, 4, 25));
        assertThat(profit.rawValue()).isEqualByComparingTo("807750000000");
        assertThat(profit.display()).isEqualTo("₹80,775.00 crore");
        assertThat(year.documentUrl()).endsWith("RELIANCE_FY2026.xml");
        assertThat(year.scope()).isEqualTo(StatementScope.CONSOLIDATED);
    }

    // The older format gives the full-year column the quarter's dates in xbrli:period.
    @Test
    void readsTheOlderFormatsFullYearColumnNotTheQuarter() {
        FiledYear year = read("reliance-fy24-consolidated.xml", "RELIANCE", 2024, NseFilingListing.Format.RESULTS);

        assertThat(year.period().label()).isEqualTo("FY24");
        // the year, not the quarter's ₹2,40,715 crore
        assertThat(crore(year, FiledItem.REVENUE_FROM_OPERATIONS)).isEqualByComparingTo("914472");
        assertThat(crore(year, FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS)).isEqualByComparingTo("69621");
        assertThat(crore(year, FiledItem.OPERATING_CASH_FLOW)).isEqualByComparingTo("158788");
        assertThat(crore(year, FiledItem.BASIC_EPS)).isEqualByComparingTo("102.90");
    }

    // HCL Technologies' FY24 filing tags profit attributable to owners as 0.
    @Test
    void rejectsAProfitSplitTheFilingContradicts() {
        FiledYear year = read("hcltech-fy24-consolidated.xml", "HCLTECH", 2024, NseFilingListing.Format.RESULTS);

        assertThat(year.item(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS).status()).isEqualTo(DataStatus.INVALID);
        assertThat(year.item(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS).value()).isNull();
        // the filing does not say which line is wrong, so none of them is used
        assertThat(year.item(FiledItem.PROFIT_FOR_PERIOD).status()).isEqualTo(DataStatus.INVALID);
        assertThat(year.item(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS).statusReason())
                .contains("the filing contradicts itself").contains("₹15,710.00 crore").contains("₹0.00 crore");
        assertThat(year.checks()).filteredOn(check -> check.name().equals(FiledStatementChecks.PROFIT_SPLIT))
                .singleElement().extracting(FiledYear.Check::outcome).isEqualTo(FiledYear.Check.Outcome.FAILED);
        // the rest of the filing is unaffected
        assertThat(crore(year, FiledItem.REVENUE_FROM_OPERATIONS)).isEqualByComparingTo("109913");
    }

    // Tech Mahindra's FY25 filing: owners ₹241 crore + minority ₹5 crore beside a profit of ₹4,253 crore.
    @Test
    void rejectsAnotherTaggingErrorTheSameWay() {
        FiledYear year = read("techm-fy25-consolidated.xml", "TECHM", 2025, NseFilingListing.Format.INTEGRATED);

        assertThat(year.item(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS).status()).isEqualTo(DataStatus.INVALID);
        assertThat(year.item(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS).statusReason()).contains("₹241.10 crore")
                .contains("₹4,253.00 crore");
    }

    @Test
    void acceptsAProfitSplitThatAddsUpToWithinTheFilingsRounding() {
        FiledYear year = read("hcltech-fy25-consolidated.xml", "HCLTECH", 2025, NseFilingListing.Format.INTEGRATED);

        // 17,399 = 17,390 + 9
        assertThat(crore(year, FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS)).isEqualByComparingTo("17390");
        assertThat(year.checks()).allMatch(check -> check.outcome() == FiledYear.Check.Outcome.PASSED);
    }

    // An NBFC's statement states interest earned, as a bank's does, but it is an Ind-AS statement.
    @Test
    void readsAnNbfcAsTheIndAsStatementItIsNotAsABank() {
        FiledYear year = read("bajfinance-fy26-consolidated-nbfc.xml", "BAJFINANCE", 2026,
                NseFilingListing.Format.INTEGRATED);

        assertThat(crore(year, FiledItem.REVENUE_FROM_OPERATIONS)).isEqualByComparingTo("81982.38");
        assertThat(crore(year, FiledItem.PROFIT_FOR_PERIOD)).isEqualByComparingTo("19332.36");
        assertThat(crore(year, FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS)).isEqualByComparingTo("19017.39");
        assertThat(crore(year, FiledItem.BASIC_EPS)).isEqualByComparingTo("30.6");
        assertThat(crore(year, FiledItem.EQUITY_ATTRIBUTABLE_TO_OWNERS)).isEqualByComparingTo("113999.02");
        assertThat(crore(year, FiledItem.TOTAL_ASSETS)).isEqualByComparingTo("559952.36");
        // so the profit split is checked, as for any Ind-AS consolidated statement
        assertThat(year.checks()).allMatch(check -> check.outcome() == FiledYear.Check.Outcome.PASSED)
                .extracting(FiledYear.Check::name).containsExactly(FiledStatementChecks.PROFIT_SPLIT,
                        FiledStatementChecks.BALANCE_SHEET, FiledStatementChecks.EQUITY_SPLIT);
    }

    @Test
    void readsABanksStatementInBothFormats() {
        FiledYear fy26 = read("hdfcbank-fy26-consolidated.xml", "HDFCBANK", 2026, NseFilingListing.Format.INTEGRATED);
        FiledYear fy24 = read("hdfcbank-fy24-consolidated.xml", "HDFCBANK", 2024, NseFilingListing.Format.RESULTS);

        assertThat(crore(fy26, FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS)).isEqualByComparingTo("76025.97");
        assertThat(crore(fy26, FiledItem.INTEREST_EARNED)).isEqualByComparingTo("348615.15");
        assertThat(crore(fy26, FiledItem.DEPOSITS)).isEqualByComparingTo("3099638.29");
        assertThat(crore(fy26, FiledItem.BORROWINGS)).isEqualByComparingTo("588484.55");
        assertThat(crore(fy26, FiledItem.SHARE_CAPITAL)).isEqualByComparingTo("1539.34");
        assertThat(crore(fy26, FiledItem.RESERVES_AND_SURPLUS)).isEqualByComparingTo("579975.02");
        assertThat(crore(fy26, FiledItem.BASIC_EPS)).isEqualByComparingTo("49.5");
        assertThat(fy26.item(FiledItem.REVENUE_FROM_OPERATIONS).statusReason())
                .isEqualTo("a bank's statement has no line for revenue from operations");
        assertThat(crore(fy24, FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS)).isEqualByComparingTo("64062.04");
        // an old-format bank filing states some balance-sheet lines without a unit; the document is in INR
        assertThat(crore(fy24, FiledItem.TOTAL_ASSETS)).isEqualByComparingTo("4030194.26");
        assertThat(fy26.checks()).extracting(FiledYear.Check::outcome).containsExactly(
                FiledYear.Check.Outcome.NOT_POSSIBLE, FiledYear.Check.Outcome.PASSED);
    }

    // NSE's 2021-22 documents refer to their columns (OneD, FourD, OneI) without defining them.
    @Test
    void placesAnUndefinedColumnByItsOwnReportingDatesAndNeverGuessesTheBalanceSheetDate() {
        FiledYear year = read("adanient-fy21-consolidated-undefined-contexts.xml", "ADANIENT", 2021,
                NseFilingListing.Format.RESULTS);

        assertThat(year.period().label()).isEqualTo("FY21");
        // the year's column, not the quarter's ₹13,525.07 crore
        assertThat(crore(year, FiledItem.REVENUE_FROM_OPERATIONS)).isEqualByComparingTo("39537.13");
        // the filing tags profit attributable to owners as -₹712.09 crore beside a profit of ₹1,045.76 crore
        assertThat(year.item(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS).status()).isEqualTo(DataStatus.INVALID);
        assertThat(year.item(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS).statusReason()).contains("-₹712.09 crore");
        // the balance-sheet column states no date, so none of its lines is placed
        assertThat(year.item(FiledItem.TOTAL_ASSETS).status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(year.checks()).filteredOn(check -> check.name().equals(FiledStatementChecks.BALANCE_SHEET))
                .singleElement().extracting(FiledYear.Check::outcome).isEqualTo(FiledYear.Check.Outcome.NOT_POSSIBLE);
    }

    // The banking format of FY21-FY23 dates its undefined year-to-date column by its end alone, and states
    // the financial year (1 April 2020 to 31 March 2021) separately.
    @Test
    void readsTheOldBankingFormatsYearToDateColumnAsTheFinancialYearTheDocumentStates() {
        FiledYear year = read("bank-fy21-consolidated-undefined-contexts.xml", "AXISBANK", 2021,
                NseFilingListing.Format.RESULTS);

        assertThat(year.period().label()).isEqualTo("FY21");
        // the year's column, not the quarter's ₹2,941.41 crore and ₹9.60
        assertThat(crore(year, FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS)).isEqualByComparingTo("7195.50");
        assertThat(crore(year, FiledItem.INTEREST_EARNED)).isEqualByComparingTo("64696.42");
        assertThat(crore(year, FiledItem.BASIC_EPS)).isEqualByComparingTo("24.19");
        // the balance-sheet column states no date at all, so it stays unplaced
        assertThat(year.item(FiledItem.DEPOSITS).status()).isEqualTo(DataStatus.UNAVAILABLE);
    }

    @Test
    void refusesADocumentWhoseColumnsStateNoPeriod() {
        // the same filing without its financial year: nothing places the year-to-date column
        byte[] undated = new String(fixture("bank-fy21-consolidated-undefined-contexts.xml"), StandardCharsets.UTF_8)
                .replace("DateOfStartOfFinancialYear", "DateOfSomethingElse").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(XbrlInstance.parse(undated), listing("AXISBANK", 2021,
                StatementScope.CONSOLIDATED, NseFilingListing.Format.RESULTS)))
                .hasMessageContaining("refers to columns it neither defines nor dates").hasMessageContaining("FourD");
    }

    // Zomato became Eternal in 2025; NSE lists its earlier results under ETERNAL, and they say ZOMATO.
    @Test
    void acceptsARenamedCompanysEarlierFilingOnlyWhenItsScripCodeShowsItIsTheSameSecurity() {
        XbrlInstance eternal = XbrlInstance.parse(fixture("eternal-fy25-consolidated.xml"));
        XbrlInstance zomato = XbrlInstance.parse(fixture("zomato-fy24-consolidated-before-rename-to-eternal.xml"));
        NseFilingListing fy25 = listing("ETERNAL", 2025, StatementScope.CONSOLIDATED, NseFilingListing.Format.INTEGRATED);
        NseFilingListing fy24 = listing("ETERNAL", 2024, StatementScope.CONSOLIDATED, NseFilingListing.Format.RESULTS);

        // the filing under the current symbol states the scrip code, 543320
        NseXbrlReader.Security known = reader.learn(NseXbrlReader.Security.UNKNOWN, eternal, fy25);
        assertThat(known.scripCode()).isEqualTo("543320");

        FiledYear year = checks.check(reader.read(zomato, fy24, known));
        assertThat(crore(year, FiledItem.REVENUE_FROM_OPERATIONS)).isEqualByComparingTo("12114");
        assertThat(crore(year, FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS)).isEqualByComparingTo("351");
        // and a filing under ZOMATO stating the same code shows ZOMATO to be Eternal's former symbol
        assertThat(reader.learn(known, zomato, fy24).formerSymbols()).containsExactly("ZOMATO");

        assertThatThrownBy(() -> reader.read(zomato, fy24))
                .hasMessageContaining("the document is for ZOMATO, not ETERNAL")
                .hasMessageContaining("no filing under ETERNAL states a scrip code");
        assertThatThrownBy(() -> reader.read(zomato, fy24, new NseXbrlReader.Security("500325", java.util.Set.of())))
                .hasMessageContaining("its scrip code 543320 is not ETERNAL's 500325");
    }

    // The FY21-FY22 documents state no scrip code, only the symbol they were filed under.
    @Test
    void acceptsAnOlderFilingWithoutAScripCodeOnlyUnderASymbolShownToBeTheCompanys() {
        XbrlInstance zomato = XbrlInstance.parse(fixture("zomato-fy22-consolidated-no-scrip-code.xml"));
        NseFilingListing fy22 = listing("ETERNAL", 2022, StatementScope.CONSOLIDATED, NseFilingListing.Format.RESULTS);

        FiledYear year = checks.check(reader.read(zomato, fy22,
                new NseXbrlReader.Security("543320", java.util.Set.of("ZOMATO"))));
        assertThat(crore(year, FiledItem.REVENUE_FROM_OPERATIONS)).isEqualByComparingTo("4192.4");
        assertThat(crore(year, FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS)).isEqualByComparingTo("-1208.7");

        // the scrip code alone does not vouch for a document that does not state one
        assertThatThrownBy(() -> reader.read(zomato, fy22, new NseXbrlReader.Security("543320", java.util.Set.of())))
                .hasMessageContaining("it states no scrip code, and no filing shows ZOMATO to be a former symbol of ETERNAL");
    }

    @Test
    void readsAnIndianFilingInRupeesEvenWhenYahooReportsTheCompanyInDollars() {
        FiledYear year = read("infy-fy26-consolidated.xml", "INFY", 2026, NseFilingListing.Format.INTEGRATED);

        assertThat(crore(year, FiledItem.REVENUE_FROM_OPERATIONS)).isEqualByComparingTo("178650");
        assertThat(crore(year, FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS)).isEqualByComparingTo("29440");
        assertThat(year.item(FiledItem.REVENUE_FROM_OPERATIONS).currency().originalCurrency()).isEqualTo("INR");
    }

    @Test
    void refusesADocumentThatIsNotWhatTheListingSays() {
        XbrlInstance consolidated = XbrlInstance.parse(fixture("reliance-fy26-consolidated.xml"));

        assertThatThrownBy(() -> reader.read(consolidated, listing("RELIANCE", 2026, StatementScope.STANDALONE,
                NseFilingListing.Format.INTEGRATED))).hasMessageContaining("NSE lists it as STANDALONE");
        assertThatThrownBy(() -> reader.read(consolidated, listing("TCS", 2026, StatementScope.CONSOLIDATED,
                NseFilingListing.Format.INTEGRATED))).hasMessageContaining("the document is for RELIANCE, not TCS");
        // punctuation is not part of a symbol: Bajaj Auto's documents say BAJAJAUTO for NSE's BAJAJ-AUTO
        byte[] bajaj = new String(document(""), StandardCharsets.UTF_8).replace(">X<", ">BAJAJAUTO<")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(reader.read(XbrlInstance.parse(bajaj), listing("BAJAJ-AUTO", 2026, StatementScope.CONSOLIDATED,
                NseFilingListing.Format.INTEGRATED)).period().label()).isEqualTo("FY26");
        // FY25 is not in the FY26 document
        assertThatThrownBy(() -> reader.read(consolidated, listing("RELIANCE", 2025, StatementScope.CONSOLIDATED,
                NseFilingListing.Format.INTEGRATED))).hasMessageContaining("reports no figures for the twelve months");
    }

    private static byte[] document(String facts) {
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance" xmlns:in-capmkt="http://www.sebi.gov.in/xbrl">
                  <xbrli:context id="FourD"><xbrli:entity><xbrli:identifier scheme="x">X</xbrli:identifier></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2025-04-01</xbrli:startDate><xbrli:endDate>2026-03-31</xbrli:endDate></xbrli:period></xbrli:context>
                  <xbrli:context id="FourDAgain"><xbrli:entity><xbrli:identifier scheme="x">X</xbrli:identifier></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2025-04-01</xbrli:startDate><xbrli:endDate>2026-03-31</xbrli:endDate></xbrli:period></xbrli:context>
                  <xbrli:context id="Segment"><xbrli:entity><xbrli:identifier scheme="x">X</xbrli:identifier>
                    <xbrli:segment>retail</xbrli:segment></xbrli:entity>
                    <xbrli:period><xbrli:startDate>2025-04-01</xbrli:startDate><xbrli:endDate>2026-03-31</xbrli:endDate></xbrli:period></xbrli:context>
                  <in-capmkt:Symbol contextRef="FourD">X</in-capmkt:Symbol>
                  <in-capmkt:NatureOfReportStandaloneConsolidated contextRef="FourD">Consolidated</in-capmkt:NatureOfReportStandaloneConsolidated>
                  <in-capmkt:DescriptionOfPresentationCurrency contextRef="FourD">INR</in-capmkt:DescriptionOfPresentationCurrency>
                """ + facts + "\n</xbrli:xbrl>").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void neverChoosesBetweenTwoDifferentValuesForTheSameLine() {
        FiledYear year = reader.read(XbrlInstance.parse(document("""
                <in-capmkt:RevenueFromOperations contextRef="FourD" unitRef="INR" decimals="-7">1000000000000</in-capmkt:RevenueFromOperations>
                <in-capmkt:RevenueFromOperations contextRef="FourDAgain" unitRef="INR" decimals="-7">1200000000000</in-capmkt:RevenueFromOperations>
                <in-capmkt:ProfitLossForPeriod contextRef="FourD" unitRef="INR" decimals="-7">100000000000</in-capmkt:ProfitLossForPeriod>
                <in-capmkt:ProfitLossForPeriod contextRef="FourDAgain" unitRef="INR" decimals="-7">100000000000</in-capmkt:ProfitLossForPeriod>
                <in-capmkt:FinanceCosts contextRef="Segment" unitRef="INR" decimals="-7">5000000000</in-capmkt:FinanceCosts>""")),
                listing("X", 2026, StatementScope.CONSOLIDATED, NseFilingListing.Format.INTEGRATED));

        assertThat(year.item(FiledItem.REVENUE_FROM_OPERATIONS).status()).isEqualTo(DataStatus.INVALID);
        assertThat(year.item(FiledItem.REVENUE_FROM_OPERATIONS).statusReason())
                .contains("more than once with different values").contains("1000000000000").contains("1200000000000");
        // the same value twice is one line
        assertThat(year.item(FiledItem.PROFIT_FOR_PERIOD).value()).isEqualByComparingTo("10000");
        // a segment's figure is never the company's
        assertThat(year.item(FiledItem.FINANCE_COSTS).status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(year.item(FiledItem.PROFIT_BEFORE_TAX).statusReason())
                .isEqualTo("the filing does not report profit before tax (ProfitBeforeTax) for FY26");
    }

    @Test
    void refusesADocumentThatDeclaresADoctype() {
        byte[] hostile = """
                <?xml version="1.0"?>
                <!DOCTYPE x [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <xbrli:xbrl xmlns:xbrli="http://www.xbrl.org/2003/instance">&secret;</xbrli:xbrl>"""
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> XbrlInstance.parse(hostile)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesADocumentInAnotherCurrency() {
        byte[] usd = new String(document(""), StandardCharsets.UTF_8).replace(">INR<", ">USD<")
                .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(XbrlInstance.parse(usd), listing("X", 2026, StatementScope.CONSOLIDATED,
                NseFilingListing.Format.INTEGRATED))).hasMessageContaining("presented in USD, not INR");
    }

    @Test
    void aStandaloneStatementHasNoOwnersSplit() {
        byte[] standalone = new String(document("""
                <in-capmkt:ProfitLossForPeriod contextRef="FourD" unitRef="INR" decimals="-7">100000000000</in-capmkt:ProfitLossForPeriod>"""),
                StandardCharsets.UTF_8).replace(">Consolidated<", ">Standalone<").getBytes(StandardCharsets.UTF_8);

        FiledYear year = checks.check(reader.read(XbrlInstance.parse(standalone),
                listing("X", 2026, StatementScope.STANDALONE, NseFilingListing.Format.INTEGRATED)));

        assertThat(year.item(FiledItem.PROFIT_FOR_PERIOD).value()).isEqualByComparingTo("10000");
        assertThat(year.item(FiledItem.PROFIT_ATTRIBUTABLE_TO_OWNERS).statusReason())
                .startsWith("a standalone statement does not split");
        assertThat(year.checks()).extracting(FiledYear.Check::name).containsExactly(FiledStatementChecks.BALANCE_SHEET);
        assertThat(Unit.INR_CRORE).isEqualTo(FiledItem.PROFIT_FOR_PERIOD.unit());
    }
}
