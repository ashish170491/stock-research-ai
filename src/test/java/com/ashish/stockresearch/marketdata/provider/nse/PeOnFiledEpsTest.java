package com.ashish.stockresearch.marketdata.provider.nse;

import com.ashish.stockresearch.research.FiledStatementChecks;
import com.ashish.stockresearch.research.ValuationService;
import com.ashish.stockresearch.research.calc.CalculationVerifier;
import com.ashish.stockresearch.research.model.CalculationStatus;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FiledItem;
import com.ashish.stockresearch.research.model.FiledPerShare;
import com.ashish.stockresearch.research.model.FiledYear;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.Formula;
import com.ashish.stockresearch.research.model.StatementScope;
import com.ashish.stockresearch.research.model.ValuationSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.ashish.stockresearch.marketdata.provider.nse.NseXbrlReaderTest.fixture;
import static com.ashish.stockresearch.research.ValuationFixtures.valuation;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The share price over the latest filed fiscal year's EPS, on Reliance's real FY26 filing: basic EPS ₹59.69,
 * paid-up equity capital ₹13,532 crore of ₹10 shares, so 13,53,20,00,000 shares. The share price and the
 * provider's share counts are illustrative.
 */
class PeOnFiledEpsTest {

    private final ValuationService service = new ValuationService(BigDecimal.valueOf(5));

    private static FiledYear reliance(String file, int fiscalYear, NseFilingListing.Format format) {
        NseFilingListing listing = new NseFilingListing("RELIANCE", LocalDate.of(fiscalYear, 3, 31),
                StatementScope.CONSOLIDATED, true, LocalDateTime.of(fiscalYear, 4, 25, 21, 57),
                "https://nsearchives.nseindia.com/corporate/xbrl/RELIANCE_FY" + fiscalYear + ".xml", format);
        return new FiledStatementChecks().check(new NseXbrlReader().read(XbrlInstance.parse(fixture(file)), listing));
    }

    private static final FiledPerShare FY26 = FiledPerShare.of(reliance("reliance-fy26-consolidated.xml", 2026,
            NseFilingListing.Format.INTEGRATED));

    /** Price ₹1,400; the provider's own P/E and EPS kept consistent so they pass their own check. */
    private ValuationSnapshot at1400(String providerShares) {
        return service.assess(valuation("1400.00", "59.69", "680.00", "5.50", "23.45", "2.06", "0.0039", providerShares),
                FY26);
    }

    @Test
    void measuresThePriceAgainstTheFiledEpsWhileTheShareCountIsTheSame() {
        ValuationSnapshot v = at1400("13532000000");

        FinancialDataPoint shares = v.filedShareCount();
        assertThat(shares.status()).isEqualTo(DataStatus.VALID);
        assertThat(shares.value()).isEqualByComparingTo("13532000000");
        assertThat(shares.formula()).isEqualTo(Formula.SHARE_COUNT);
        FinancialDataPoint pe = v.peOnFiledEps();
        assertThat(pe.status()).as(pe.statusReason()).isEqualTo(DataStatus.VALID);
        // 1,400 / 59.69
        assertThat(pe.value()).isEqualByComparingTo("23.45");
        assertThat(pe.calculationStatus()).isEqualTo(CalculationStatus.CALCULATED);
        assertThat(pe.calculation()).isEqualTo("share price (As of 2026-09-25) / FY26 basic EPS as filed");
        assertThat(pe.dependsOnFields()).containsExactly("price.regularMarketPrice",
                "nse-xbrl.BasicEarningsLossPerShareFromContinuingAndDiscontinuedOperations");
        assertThat(CalculationVerifier.problem(pe, new CalculationVerifier.SourceValues())).isEmpty();
        assertThat(v.uncheckedMetrics()).doesNotContainKey("peOnFiledEps");
    }

    @Test
    void givesNoPeWhenTheShareCountHasChangedSinceTheYearEnded() {
        // a 1:1 bonus since FY26: twice the shares, so FY26's EPS is per share of half today's count
        ValuationSnapshot v = at1400("27064000000");

        assertThat(v.peOnFiledEps().status()).isEqualTo(DataStatus.UNAVAILABLE);
        assertThat(v.peOnFiledEps().value()).isNull();
        assertThat(v.peOnFiledEps().statusReason()).contains("13,53,20,00,000 shares at the end of FY26")
                .contains("A bonus issue, split or share issue since then");
    }

    @Test
    void showsButNeverTrustsAPeWhoseShareCountCannotBeChecked() {
        ValuationSnapshot v = at1400(null);

        assertThat(v.peOnFiledEps().status()).isEqualTo(DataStatus.VALID);
        assertThat(v.uncheckedMetrics()).containsKey("peOnFiledEps");
        assertThat(v.dataQualityIssues()).filteredOn(i -> i.type() == DataQualityIssue.Type.DATA_QUALITY_WARNING)
                .anySatisfy(i -> assertThat(i.message()).startsWith("The P/E on filed EPS (23.45x) is not checked"));
    }

    @Test
    void givesNoPeOnALossOrWithoutFiledFigures() {
        FiledYear year = reliance("reliance-fy26-consolidated.xml", 2026, NseFilingListing.Format.INTEGRATED);
        FinancialDataPoint eps = year.item(FiledItem.BASIC_EPS);
        FiledPerShare loss = FiledPerShare.of(year.with(FiledItem.BASIC_EPS, FinancialDataPoint.reported(eps.metric(),
                new BigDecimal("-3.10"), eps.providerUnit(), new BigDecimal("-3.10"), eps.unit(), eps.period(),
                eps.source())));

        assertThat(service.assess(valuation("1400.00", "59.69", "680.00", "5.50", "23.45", "2.06", "0.0039",
                "13532000000"), loss).peOnFiledEps().statusReason()).contains("a P/E is not meaningful for a loss");
        assertThat(service.assess(valuation("1400.00", "59.69", "680.00", "5.50", "23.45", "2.06", "0.0039",
                "13532000000"), FiledPerShare.unavailable("FY26: no consolidated filing for FY26 could be read"))
                .peOnFiledEps().statusReason()).isEqualTo("No P/E on filed EPS: FY26: no consolidated filing for FY26 could be read");
    }
}
