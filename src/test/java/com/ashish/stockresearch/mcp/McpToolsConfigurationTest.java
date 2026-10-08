package com.ashish.stockresearch.mcp;

import com.ashish.stockresearch.marketdata.StockMarketDataService;
import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.report.ResearchReportRenderer;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import com.ashish.stockresearch.screening.ScreeningRenderer;
import com.ashish.stockresearch.screening.ScreeningService;
import com.ashish.stockresearch.tool.DocumentSearchTools;
import com.ashish.stockresearch.tool.ScreeningTools;
import com.ashish.stockresearch.tool.StockPriceTool;
import com.ashish.stockresearch.tool.StockResearchTools;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.ParameterizedTypeReference;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * S9-4: the MCP server exposes at least the tools S9-2 names, through the same {@code @Tool} beans the
 * chat agent uses - nothing about a tool's behaviour or rendering changes for MCP.
 */
class McpToolsConfigurationTest {

    @Test
    void exposesEveryRequiredToolToMcpClients() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(McpToolsConfiguration.class);
        context.registerBean(StockPriceTool.class,
                () -> new StockPriceTool(mock(StockMarketDataService.class)));
        context.registerBean(StockResearchTools.class,
                () -> new StockResearchTools(mock(StockResearchService.class), new ResearchReportRenderer(),
                        new SectorClassifier()));
        context.registerBean(ScreeningTools.class,
                () -> new ScreeningTools(mock(ScreeningService.class), new ScreeningRenderer()));
        context.registerBean(DocumentSearchTools.class,
                () -> new DocumentSearchTools(mock(VectorStore.class)));
        context.refresh();

        List<McpServerFeatures.SyncToolSpecification> specs = context.getBeanProvider(
                        new ParameterizedTypeReference<List<McpServerFeatures.SyncToolSpecification>>() { })
                .getObject();
        Set<String> toolNames = specs.stream()
                .map(spec -> spec.tool().name())
                .collect(Collectors.toSet());

        assertThat(toolNames).contains(
                "getStockQuote",
                "getCompanyProfile",
                "getFinancialSummary",
                "getHistoricalPerformance",
                "getValuation",
                "screenStocks");
        context.close();
    }
}
