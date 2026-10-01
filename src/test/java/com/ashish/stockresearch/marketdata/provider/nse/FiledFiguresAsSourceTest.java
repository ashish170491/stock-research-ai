package com.ashish.stockresearch.marketdata.provider.nse;

import com.ashish.stockresearch.research.FinancialMetricsService;
import com.ashish.stockresearch.research.FiledStatementChecks;
import com.ashish.stockresearch.research.ResearchDataUnavailableException;
import com.ashish.stockresearch.research.TestFilings;
import com.ashish.stockresearch.research.model.AnnualFinancials;
import com.ashish.stockresearch.research.model.AnnualRatios;
import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FiledFinancials;
import com.ashish.stockresearch.research.model.FiledYear;
import com.ashish.stockresearch.research.model.FinancialSummary;
import com.ashish.stockresearch.research.model.ReportedFinancials;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.SourceType;
import com.ashish.stockresearch.research.model.StatementScope;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.ashish.stockresearch.marketdata.provider.nse.NseXbrlReaderTest.fixture;
import static com.ashish.stockresearch.research.ResearchFixtures.CALENDAR;
import static com.ashish.stockresearch.research.ResearchFixtures.LATEST_QUARTER_END;
import static com.ashish.stockresearch.research.ResearchFixtures.consistentReported;
import static com.ashish.stockresearch.research.ResearchFixtures.hdfcQuarters;
import static com.ashish.stockresearch.research.ResearchFixtures.reported;
import static com.ashish.stockresearch.research.ResearchFixtures.year;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reliance's real FY24 and FY26 filings as the source of its fiscal years, with Yahoo's figures as the
 * cross-check. Yahoo's FY24 net profit is its real one (₹69,621 crore, as filed); its FY26 figure here is
 * made up, ₹70,000 crore against the ₹80,775 crore filed, to show a disagreement confined to one year.
 */
class FiledFiguresAsSourceTest {

    private final FinancialMetricsService metrics = new FinancialMetricsService();

    private static FiledYear filed(String file, int fiscalYear, NseFilingListing.Format format) {
        NseFilingListing listing = new NseFilingListing("RELIANCE", LocalDate.of(fiscalYear, 3, 31),
                StatementScope.CONSOLIDATED, true, LocalDateTime.of(fiscalYear, 4, 25, 21, 57),
                "https://nsearchives.nseindia.com/corporate/xbrl/RELIANCE_FY" + fiscalYear + ".xml", format);
        return new FiledStatementChecks().check(new NseXbrlReader().read(XbrlInstance.parse(fixture(file)), listing));
    }

    private static final FiledFinancials RELIANCE = new FiledFinancials("RELIANCE", NseXbrlReader.SOURCE,
            StatementScope.CONSOLIDATED, List.of(filed("reliance-fy24-consolidated.xml", 2024, NseFilingListing.Format.RESULTS),
            filed("reliance-fy26-consolidated.xml", 2026, NseFilingListing.Format.INTEGRATED)),
            List.of(new DataGap("Filed results", "FY25", "no consolidated filing for FY25 could be read; see the log for why")));

    /** Yahoo's fiscal years: revenue defined its own way, net profit ₹69,621 crore for FY24 and ₹70,000 for FY26. */
    private static ReportedFinancials yahoo(String exchange) {
        ReportedFinancials base = reported(consistentReported().headline(), hdfcQuarters(), List.of(
                        year(2024, "901064", "69621", "793481", "1755986"),
                        year(2025, "964693", "69648", "843041", "1950121"),
                        year(2026, "1057219", "70000", "904030", "2178140")),
                CALENDAR.quarter(LATEST_QUARTER_END));
        return new ReportedFinancials("RELIANCE", exchange, "Reliance Industries Limited", "INR",
                base.latestReportedQuarter(), base.latestFiscalYear(), base.headline(), base.recentQuarters(),
                base.annualHistory(), List.of(), List.of(new DataGap("Financials", "annualHistory", "a Yahoo gap")),
                base.provenance());
    }

    private static AnnualRatios ratios(FinancialSummary summary, String label) {
        return summary.calculated().annual().stream().filter(y -> y.period().label().equals(label)).findFirst()
                .orElseThrow();
    }

    @Test
    void takesTheFiscalYearsFromTheFilingsAndDisputesOnlyTheYearTheCrossCheckContradicts() {
        ReportedFinancials merged = TestFilings.of((symbol, years) -> RELIANCE).withFiledAnnualHistory(yahoo("NSE"));
        FinancialSummary summary = metrics.summarize(merged);

        // the filings' years, never Yahoo's FY25 in the gap between them
        List<AnnualFinancials> annual = summary.reported().annualHistory();
        assertThat(annual).extracting(y -> y.period().label()).containsExactly("FY24", "FY26");
        assertThat(annual).allSatisfy(y -> assertThat(y.points())
                .allMatch(p -> p.source() == null || p.source().sourceType() == SourceType.OFFICIAL_FILING));
        assertThat(summary.dataGaps()).extracting(DataGap::item).contains("FY25").doesNotContain("annualHistory");

        // FY26 disagrees: one conflict, for FY26 alone, naming both sources
        assertThat(summary.dataQualityIssues()).filteredOn(i -> i.type() == DataQualityIssue.Type.DATA_CONFLICT)
                .singleElement().satisfies(issue -> {
                    assertThat(issue.affectedPeriods()).containsExactly("FY26");
                    assertThat(issue.affectedFields()).containsExactly("nse-xbrl.ProfitOrLossAttributableToOwnersOfParent",
                            "fundamentals-timeseries.annualNetIncome");
                    assertThat(issue.message()).contains("FY26 net profit").contains("₹80,775").contains("₹70,000")
                            .contains("NSE corporate filings (XBRL)").contains("Yahoo Finance");
                });
        AnnualFinancials fy24 = annual.get(0);
        AnnualFinancials fy26 = annual.get(1);
        assertThat(fy24.netProfit().status()).isEqualTo(DataStatus.VALID);
        assertThat(fy26.netProfit().status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(fy26.netProfit().value()).isEqualByComparingTo("80775");
        // what FY24 alone measures stands; what FY26's profit enters does not
        assertThat(ratios(summary, "FY24").netProfitMarginPercent().status()).isEqualTo(DataStatus.VALID);
        assertThat(ratios(summary, "FY24").returnOnEquityPercent().status()).isEqualTo(DataStatus.VALID);
        assertThat(ratios(summary, "FY26").returnOnEquityPercent().status()).isEqualTo(DataStatus.DATA_CONFLICT);
        assertThat(summary.calculated().netProfitCagrPercent().status()).isEqualTo(DataStatus.DATA_CONFLICT);
        // FY26's figures that do not involve its profit stand too
        assertThat(ratios(summary, "FY26").operatingMarginPercent().status()).isEqualTo(DataStatus.VALID);
        assertThat(ratios(summary, "FY26").returnOnCapitalEmployedPercent().status()).isEqualTo(DataStatus.VALID);
        // revenue is not compared: Yahoo's ₹10,57,219 crore is defined differently from the filed ₹10,75,675
        assertThat(summary.calculated().revenueCagrPercent().status()).isEqualTo(DataStatus.VALID);
    }

    @Test
    void theReportTagsOnlyTheDisputedYearAndNamesTheFilingsAsTheSource() {
        FinancialSummary summary = metrics.summarize(
                TestFilings.of((symbol, years) -> RELIANCE).withFiledAnnualHistory(yahoo("NSE")));

        String tool = new com.ashish.stockresearch.research.report.ResearchReportRenderer()
                .renderFinancialSummaryTool(com.ashish.stockresearch.research.model.FinancialSummaryResult.success(summary));

        assertThat(tool).contains("### Fiscal years (as the company filed them with NSE; source fields: nse-xbrl)");
        String fy24 = tool.lines().filter(line -> line.startsWith("| FY24 | 2024-03-31 |")).findFirst().orElseThrow();
        String fy26 = tool.lines().filter(line -> line.startsWith("| FY26 | 2026-03-31 |")).findFirst().orElseThrow();
        // the model is shown FY24's profit; FY26's, in dispute, is withheld from it
        assertThat(fy24).contains("₹69,621").doesNotContain("WITHHELD");
        assertThat(fy26).contains("WITHHELD");
    }

    @Test
    void keepsTheProvidersFiscalYearsAndSaysWhyWhenThereAreNoFilingsToUse() {
        ReportedFinancials outage = TestFilings.of((symbol, years) -> {
            throw new ResearchDataUnavailableException(ResearchStatus.PROVIDER_UNAVAILABLE, "NSE timed out");
        }).withFiledAnnualHistory(yahoo("NSE"));
        ReportedFinancials bseOnly = TestFilings.of((symbol, years) -> RELIANCE).withFiledAnnualHistory(yahoo("BSE"));

        for (ReportedFinancials fallback : List.of(outage, bseOnly)) {
            assertThat(fallback.annualHistory()).extracting(y -> y.period().label())
                    .containsExactly("FY24", "FY25", "FY26");
            assertThat(fallback.annualHistory().get(0).netProfit().source().sourceType())
                    .isEqualTo(SourceType.MARKET_DATA_PROVIDER);
        }
        assertThat(outage.dataGaps()).anySatisfy(gap -> assertThat(gap.reason())
                .isEqualTo("The fiscal-year figures are Yahoo Finance's, not the company's filings: the NSE filings "
                        + "could not be used (NSE timed out)"));
        assertThat(bseOnly.dataGaps()).anySatisfy(gap -> assertThat(gap.reason()).contains("is not listed on NSE (BSE)"));
        // no filings source at all (the mock profile): nothing changes
        ReportedFinancials provider = yahoo("NSE");
        assertThat(TestFilings.none().withFiledAnnualHistory(provider)).isSameAs(provider);
    }

    @Test
    void saysWhichFiledYearsCouldNotBeCrossChecked() {
        ReportedFinancials merged = TestFilings.of((symbol, years) -> RELIANCE).withFiledAnnualHistory(
                reported(consistentReported().headline(), hdfcQuarters(), List.of(year(2026, "1057219", "80775",
                        "904030", "2178140")), CALENDAR.quarter(LATEST_QUARTER_END)));

        assertThat(merged.dataGaps()).filteredOn(gap -> gap.item().endsWith("net profit cross-check"))
                .extracting(DataGap::reason)
                .containsExactly("The filed net profit for FY24 is not cross-checked: Yahoo Finance has no figure for FY24");
    }
}
