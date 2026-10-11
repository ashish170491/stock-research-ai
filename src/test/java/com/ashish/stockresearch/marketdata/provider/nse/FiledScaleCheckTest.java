package com.ashish.stockresearch.marketdata.provider.nse;

import com.ashish.stockresearch.research.FiledScaleCheck;
import com.ashish.stockresearch.research.FiledStatementChecks;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.model.FiledItem;
import com.ashish.stockresearch.research.model.FiledYear;
import com.ashish.stockresearch.research.model.FinancialDataPoint;
import com.ashish.stockresearch.research.model.StatementScope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static com.ashish.stockresearch.marketdata.provider.nse.NseXbrlReaderTest.fixture;
import static com.ashish.stockresearch.research.ResearchFixtures.year;
import static org.assertj.core.api.Assertions.assertThat;

/** A filing whose amounts are all on the wrong scale (Marksans Pharma's FY25) is rejected; a normal one is not. */
class FiledScaleCheckTest {

    /** Reliance's real FY24 consolidated filing, which passes its own identities. */
    private static final FiledYear RELIANCE_FY24 = new FiledStatementChecks().check(new NseXbrlReader().read(
            XbrlInstance.parse(fixture("reliance-fy24-consolidated.xml")),
            new NseFilingListing("RELIANCE", LocalDate.of(2024, 3, 31), StatementScope.CONSOLIDATED, true,
                    LocalDateTime.of(2024, 4, 25, 21, 57), "https://nsearchives.nseindia.com/corporate/xbrl/R24.xml",
                    NseFilingListing.Format.RESULTS)));

    private static FinancialDataPoint providerRevenue(BigDecimal factorOfFiled) {
        BigDecimal filed = RELIANCE_FY24.item(FiledItem.REVENUE_FROM_OPERATIONS).value();
        return year(2024, filed.multiply(factorOfFiled).toPlainString(), "69621", "793481", "1755986").revenue();
    }

    @Test
    void aFiledRevenueCloseToTheProvidersPasses() {
        FiledYear checked = FiledScaleCheck.check(RELIANCE_FY24, providerRevenue(new BigDecimal("1.03")));

        assertThat(checked.item(FiledItem.REVENUE_FROM_OPERATIONS).status()).isEqualTo(DataStatus.VALID);
        assertThat(checked.item(FiledItem.TOTAL_ASSETS).status()).isEqualTo(DataStatus.VALID);
        assertThat(checked.checks()).anySatisfy(check -> assertThat(check.name()).isEqualTo(FiledScaleCheck.NAME));
        assertThat(checked.checks()).filteredOn(check -> check.name().equals(FiledScaleCheck.NAME))
                .singleElement().extracting(FiledYear.Check::outcome).isEqualTo(FiledYear.Check.Outcome.PASSED);
    }

    @Test
    void aDifferentDefinitionOfRevenueWithinTheFactorIsNotAnError() {
        // 1.6x apart either way is a definition gap, not a scaling error
        assertThat(FiledScaleCheck.check(RELIANCE_FY24, providerRevenue(new BigDecimal("1.6")))
                .item(FiledItem.REVENUE_FROM_OPERATIONS).status()).isEqualTo(DataStatus.VALID);
        assertThat(FiledScaleCheck.check(RELIANCE_FY24, providerRevenue(new BigDecimal("0.6")))
                .item(FiledItem.REVENUE_FROM_OPERATIONS).status()).isEqualTo(DataStatus.VALID);
    }

    @Test
    void aFilingTenTimesTooSmallHasEveryAmountRejectedAndSaysWhy() {
        // the provider's revenue is ten times the filed one: the filing is on the wrong scale
        FiledYear checked = FiledScaleCheck.check(RELIANCE_FY24, providerRevenue(BigDecimal.TEN));

        for (FiledItem item : FiledItem.values()) {
            FinancialDataPoint original = RELIANCE_FY24.item(item);
            if (item.unit() == com.ashish.stockresearch.research.model.Unit.INR_CRORE && original.available()) {
                assertThat(checked.item(item).status()).as(item.name()).isEqualTo(DataStatus.INVALID);
                assertThat(checked.item(item).statusReason()).contains("scaling error in the filing");
            } else {
                assertThat(checked.item(item)).as(item.name()).isEqualTo(original);
            }
        }
        assertThat(checked.checks()).filteredOn(check -> check.name().equals(FiledScaleCheck.NAME))
                .singleElement().extracting(FiledYear.Check::outcome).isEqualTo(FiledYear.Check.Outcome.FAILED);
    }

    @Test
    void aFilingTenTimesTooLargeIsRejectedToo() {
        assertThat(FiledScaleCheck.check(RELIANCE_FY24, providerRevenue(new BigDecimal("0.1")))
                .item(FiledItem.REVENUE_FROM_OPERATIONS).status()).isEqualTo(DataStatus.INVALID);
    }

    @Test
    void withNoProviderRevenueTheYearIsLeftAsFiled() {
        assertThat(FiledScaleCheck.check(RELIANCE_FY24, null)).isEqualTo(RELIANCE_FY24);
    }
}
