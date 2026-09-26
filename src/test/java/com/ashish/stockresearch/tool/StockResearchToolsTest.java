package com.ashish.stockresearch.tool;

import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.model.CompanyProfileResult;
import com.ashish.stockresearch.research.model.FinancialSummaryResult;
import com.ashish.stockresearch.research.model.HistoricalPerformanceResult;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.ShareholdingResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockResearchToolsTest {

    @Mock
    private StockResearchService stockResearchService;

    @InjectMocks
    private StockResearchTools tools;

    @Test
    void exposesEveryResearchCapabilityAsADistinctTool() {
        List<String> toolNames = Arrays.stream(StockResearchTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .map(Method::getName)
                .sorted()
                .toList();

        assertThat(toolNames).containsExactly(
                "getCompanyProfile", "getFinancialSummary", "getHistoricalPerformance", "getShareholding");
    }

    @Test
    void describesEveryToolAndEveryParameterSoTheModelCanRouteCorrectly() {
        Arrays.stream(StockResearchTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .forEach(method -> {
                    assertThat(method.getAnnotation(Tool.class).description())
                            .as("description of %s", method.getName())
                            .contains("symbol")
                            .hasSizeGreaterThan(200);
                    assertThat(method.getParameters())
                            .allSatisfy(parameter -> assertThat(parameter.getAnnotations())
                                    .as("parameter %s of %s", parameter.getName(), method.getName())
                                    .isNotEmpty());
                });
    }

    @Test
    void delegatesCompanyProfileToTheService() {
        CompanyProfileResult expected = CompanyProfileResult.failure(ResearchStatus.SYMBOL_NOT_FOUND, "nope");
        when(stockResearchService.getCompanyProfile("TCS")).thenReturn(expected);

        assertThat(tools.getCompanyProfile("TCS")).isSameAs(expected);
        verify(stockResearchService).getCompanyProfile("TCS");
    }

    @Test
    void delegatesFinancialSummaryToTheService() {
        FinancialSummaryResult expected = FinancialSummaryResult.failure(ResearchStatus.PROVIDER_ERROR, "nope");
        when(stockResearchService.getFinancialSummary("TCS")).thenReturn(expected);

        assertThat(tools.getFinancialSummary("TCS")).isSameAs(expected);
    }

    @Test
    void delegatesShareholdingToTheServiceEvenThoughTheCapabilityIsUnavailable() {
        ShareholdingResult expected =
                ShareholdingResult.failure(ResearchStatus.CAPABILITY_NOT_SUPPORTED, "no source");
        when(stockResearchService.getShareholding("TCS")).thenReturn(expected);

        assertThat(tools.getShareholding("TCS")).isSameAs(expected);
    }

    @Test
    void passesTheRequestedWindowThroughIncludingWhenTheModelOmitsIt() {
        HistoricalPerformanceResult expected =
                HistoricalPerformanceResult.failure(ResearchStatus.DATA_NOT_AVAILABLE, "nope");
        when(stockResearchService.getHistoricalPerformance("TCS", null)).thenReturn(expected);

        assertThat(tools.getHistoricalPerformance("TCS", null)).isSameAs(expected);
        verify(stockResearchService).getHistoricalPerformance("TCS", null);
    }

    @Test
    void containsNoBusinessLogicOfItsOwn() {
        when(stockResearchService.getHistoricalPerformance("TCS", 10))
                .thenReturn(HistoricalPerformanceResult.failure(ResearchStatus.PROVIDER_UNAVAILABLE, "down"));

        assertThat(tools.getHistoricalPerformance("TCS", 10).success()).isFalse();
    }
}
