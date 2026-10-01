package com.ashish.stockresearch.research;

import java.math.BigDecimal;
import com.ashish.stockresearch.research.model.CompanyProfile;
import com.ashish.stockresearch.research.model.CompanyProfileResult;
import com.ashish.stockresearch.research.model.FinancialSummaryResult;
import com.ashish.stockresearch.research.model.HistoricalPerformanceResult;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.ShareholdingResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockResearchServiceTest {

    @Mock
    private CompanyProfileProvider companyProfileProvider;
    @Mock
    private FinancialDataProvider financialDataProvider;
    @Mock
    private ShareholdingProvider shareholdingProvider;
    @Mock
    private HistoricalMarketDataProvider historicalMarketDataProvider;
    @Mock
    private ValuationDataProvider valuationDataProvider;

    private StockResearchService service() {
        return new StockResearchService(companyProfileProvider, financialDataProvider,
                shareholdingProvider, historicalMarketDataProvider,
                new FinancialMetricsService(), new HistoricalPerformanceService(), valuationDataProvider,
                new ValuationService(BigDecimal.valueOf(5)), TestFilings.none());
    }

    @Test
    void returnsProfileTheProviderResolves() {
        CompanyProfile profile = mock(CompanyProfile.class);
        when(companyProfileProvider.getCompanyProfile("TCS")).thenReturn(profile);

        CompanyProfileResult result = service().getCompanyProfile("TCS");

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo(ResearchStatus.OK);
        assertThat(result.companyProfile()).isSameAs(profile);
    }

    @Test
    void trimsSurroundingWhitespaceBeforeCallingTheProvider() {
        when(companyProfileProvider.getCompanyProfile("TCS")).thenReturn(mock(CompanyProfile.class));

        service().getCompanyProfile("  TCS  ");

        verify(companyProfileProvider).getCompanyProfile("TCS");
    }

    @Test
    void rejectsBlankSymbolWithoutTouchingTheProvider() {
        FinancialSummaryResult result = service().getFinancialSummary("   ");

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(ResearchStatus.INVALID_SYMBOL);
        assertThat(result.financialSummary()).isNull();
    }

    @Test
    void preservesTheProviderStatusSoTheModelCanTellFailureModesApart() {
        when(financialDataProvider.getReportedFinancials("NOTLISTED")).thenThrow(
                new ResearchDataUnavailableException(ResearchStatus.SYMBOL_NOT_FOUND, "No such listing"));

        FinancialSummaryResult result = service().getFinancialSummary("NOTLISTED");

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(ResearchStatus.SYMBOL_NOT_FOUND);
        assertThat(result.message()).isEqualTo("No such listing");
    }

    @Test
    void reportsAnUnsupportedCapabilityAsSuchRatherThanAsAnError() {
        when(shareholdingProvider.getShareholding("TCS")).thenThrow(
                new ResearchDataUnavailableException(ResearchStatus.CAPABILITY_NOT_SUPPORTED, "No source wired up"));

        ShareholdingResult result = service().getShareholding("TCS");

        assertThat(result.status()).isEqualTo(ResearchStatus.CAPABILITY_NOT_SUPPORTED);
        assertThat(result.shareholding()).isNull();
    }

    @Test
    void containsAnUnexpectedProviderExceptionInsteadOfLettingItReachTheToolLayer() {
        when(historicalMarketDataProvider.getPriceHistory(any(), anyInt()))
                .thenThrow(new IllegalStateException("boom"));

        HistoricalPerformanceResult result = service().getHistoricalPerformance("TCS", 5);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo(ResearchStatus.PROVIDER_ERROR);
        assertThat(result.message()).contains("do not assume zero");
    }

    @Test
    void defaultsToFiveYearsWhenTheModelOmitsTheWindow() {
        service().getHistoricalPerformance("TCS", null);

        verify(historicalMarketDataProvider).getPriceHistory("TCS", 5);
    }

    @Test
    void clampsAnAbsurdWindowInsteadOfFailingTheCall() {
        service().getHistoricalPerformance("TCS", 900);

        verify(historicalMarketDataProvider).getPriceHistory(eq("TCS"), eq(25));
    }

    @Test
    void treatsANonPositiveWindowAsTheDefault() {
        service().getHistoricalPerformance("TCS", -3);

        verify(historicalMarketDataProvider).getPriceHistory("TCS", 5);
    }

    @Test
    void runsTheJavaCalculationsOverTheProvidersFactsBeforeReturningThem() {
        when(financialDataProvider.getReportedFinancials("HDFCBANK")).thenReturn(ResearchFixtures.consistentReported());

        FinancialSummaryResult result = service().getFinancialSummary("HDFCBANK");

        assertThat(result.success()).isTrue();
        assertThat(result.financialSummary().calculated().revenueCagrPercent().value()).isEqualByComparingTo("16.90");
    }

    @Test
    void neverReturnsAMetricCalculatedFromConflictingProviderFigures() {
        when(financialDataProvider.getReportedFinancials("HDFCBANK")).thenReturn(ResearchFixtures.hdfcReported());

        FinancialSummaryResult result = service().getFinancialSummary("HDFCBANK");

        assertThat(result.financialSummary().calculated().revenueCagrPercent().value()).isNull();
        assertThat(result.financialSummary().calculated().revenueCagrPercent().status())
                .isEqualTo(com.ashish.stockresearch.research.model.DataStatus.DATA_CONFLICT);
    }
}
