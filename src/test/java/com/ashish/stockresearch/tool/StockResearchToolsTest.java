package com.ashish.stockresearch.tool;

import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.model.CompanyProfileResult;
import com.ashish.stockresearch.research.model.FinancialSummaryResult;
import com.ashish.stockresearch.research.model.HistoricalPerformanceResult;
import com.ashish.stockresearch.research.model.ResearchStatus;
import com.ashish.stockresearch.research.model.ShareholdingResult;
import com.ashish.stockresearch.research.report.ResearchReportRenderer;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
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

    private StockResearchTools tools;
    private ToolUsage usage;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        tools = new StockResearchTools(stockResearchService, new ResearchReportRenderer(), new SectorClassifier());
        usage = new ToolUsage();
        context = new ToolContext(usage.asToolContext());
    }

    private static List<Method> toolMethods() {
        return Arrays.stream(StockResearchTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .toList();
    }

    private static String description(String tool) {
        return toolMethods().stream().filter(m -> m.getName().equals(tool)).findFirst().orElseThrow()
                .getAnnotation(Tool.class).description();
    }

    @Test
    void exposesEveryResearchCapabilityAsADistinctTool() {
        assertThat(toolMethods().stream().map(Method::getName).sorted().toList()).containsExactly(
                "getCompanyProfile", "getFinancialSummary", "getHistoricalPerformance", "getShareholding");
    }

    @Test
    void describesEveryToolAndEveryModelFacingParameterSoTheModelCanRouteCorrectly() {
        toolMethods().forEach(method -> {
            assertThat(method.getAnnotation(Tool.class).description())
                    .as("description of %s", method.getName())
                    .contains("symbol")
                    .hasSizeGreaterThan(200);
            assertThat(Arrays.stream(method.getParameters()).filter(p -> p.getType() != ToolContext.class))
                    .allSatisfy(parameter -> assertThat(parameter.getAnnotations())
                            .as("parameter %s of %s", parameter.getName(), method.getName())
                            .isNotEmpty());
        });
    }

    @Test
    void tellsTheModelAProfileAloneIsNotAFundamentalOverview() {
        assertThat(description("getCompanyProfile"))
                .contains("NOT a fundamental overview")
                .contains("getFinancialSummary", "getHistoricalPerformance", "getShareholding");
        assertThat(description("getFinancialSummary")).contains("Normally needed for any fundamental overview");
        assertThat(description("getHistoricalPerformance")).contains("Normally needed when analysing a stock");
        assertThat(description("getShareholding")).contains("Consider it for any fundamental overview")
                .contains("capability gap");
    }

    @Test
    void tellsTheModelOnlyValidValuesAreUsableAndNothingIsRecalculated() {
        assertThat(description("getFinancialSummary"))
                .contains("DATA_CONFLICT, INVALID and UNAVAILABLE values are not usable")
                .contains("Never recalculate")
                .contains("never convert units or currency");
    }

    @Test
    void rendersTheCompanyProfileAsCompactTextAndRecordsTheCall() {
        when(stockResearchService.getCompanyProfile("TCS"))
                .thenReturn(CompanyProfileResult.failure(ResearchStatus.SYMBOL_NOT_FOUND, "nope"));

        String text = tools.getCompanyProfile("TCS", context);

        assertThat(text).contains("TOOL RESULT: getCompanyProfile - status SYMBOL_NOT_FOUND").contains("nope");
        verify(stockResearchService).getCompanyProfile("TCS");
        assertThat(usage.calls()).singleElement().satisfies(call -> {
            assertThat(call.tool()).isEqualTo("getCompanyProfile");
            assertThat(call.status()).isEqualTo("SYMBOL_NOT_FOUND");
            assertThat(call.evidence()).isEqualTo(text);
        });
    }

    @Test
    void rendersFinancialSummaryFailuresWithTheirStatus() {
        when(stockResearchService.getFinancialSummary("TCS"))
                .thenReturn(FinancialSummaryResult.failure(ResearchStatus.PROVIDER_ERROR, "nope"));

        assertThat(tools.getFinancialSummary("TCS", context))
                .contains("getFinancialSummary - status PROVIDER_ERROR").contains("nope");
        assertThat(usage.called("getFinancialSummary")).isTrue();
    }

    @Test
    void reportsTheShareholdingCapabilityGapExplicitly() {
        when(stockResearchService.getShareholding("TCS"))
                .thenReturn(ShareholdingResult.failure(ResearchStatus.CAPABILITY_NOT_SUPPORTED, "no source"));

        assertThat(tools.getShareholding("TCS", context)).contains("CAPABILITY GAP").contains("UNKNOWN");
        assertThat(usage.calls().get(0).status()).isEqualTo("CAPABILITY_NOT_SUPPORTED");
    }

    @Test
    void passesTheRequestedWindowThroughIncludingWhenTheModelOmitsIt() {
        when(stockResearchService.getHistoricalPerformance("TCS", null))
                .thenReturn(HistoricalPerformanceResult.failure(ResearchStatus.DATA_NOT_AVAILABLE, "nope"));

        assertThat(tools.getHistoricalPerformance("TCS", null, context)).contains("DATA_NOT_AVAILABLE");
        verify(stockResearchService).getHistoricalPerformance("TCS", null);
    }

    @Test
    void worksWithoutAToolContextForDirectCallers() {
        when(stockResearchService.getShareholding("TCS"))
                .thenReturn(ShareholdingResult.failure(ResearchStatus.CAPABILITY_NOT_SUPPORTED, "no source"));

        assertThat(tools.getShareholding("TCS", null)).contains("CAPABILITY GAP");
    }

    @Test
    void neverTellsTheModelToConvertUnitsItself() {
        toolMethods().forEach(method -> assertThat(method.getAnnotation(Tool.class).description())
                .as("description of %s", method.getName())
                .doesNotContain("divide by")
                .doesNotContain("10,000,000"));
    }

    @Test
    void passesStringsThroughUnchanged() {
        assertThat(new PlainTextResultConverter().convert("# a\nb", String.class)).isEqualTo("# a\nb");
    }
}
