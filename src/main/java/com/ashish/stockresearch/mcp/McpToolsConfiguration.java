package com.ashish.stockresearch.mcp;

import com.ashish.stockresearch.tool.DocumentSearchTools;
import com.ashish.stockresearch.tool.ScreeningTools;
import com.ashish.stockresearch.tool.StockPriceTool;
import com.ashish.stockresearch.tool.StockResearchTools;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Exposes the same verified, rendered tools the chat agent uses (roadmap Step 9) to any MCP client -
 * Claude Desktop, Claude Code, MCP Inspector. Nothing about a tool's behaviour or rendering changes for
 * MCP; it still records on {@code ToolUsage} when a caller supplies one, a no-op over MCP since there is
 * no chat-time {@code ToolContext} to supply it on.
 *
 * This returns the already-converted {@code SyncToolSpecification} list, not a {@code ToolCallbackProvider}
 * or {@code ToolCallback} bean: Spring AI's own {@code ToolCallingAutoConfiguration} collects every such
 * bean in the context into the resolver the chat agent's own default tool-calling manager uses, which
 * would pull {@link StockPriceTool}'s full dependency chain (down to {@code LlmSymbolResolver}, which
 * needs a {@code ChatClient.Builder}) into the eager construction of {@code ollamaChatModel} - a bean
 * {@code ChatClient.Builder} itself depends on, and a genuine circular-dependency startup failure found
 * while wiring this in.
 */
@Configuration
public class McpToolsConfiguration {

    @Bean
    public List<McpServerFeatures.SyncToolSpecification> researchMcpTools(StockPriceTool stockPriceTool,
                                                                            StockResearchTools stockResearchTools,
                                                                            ScreeningTools screeningTools,
                                                                            DocumentSearchTools documentSearchTools) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(stockPriceTool, stockResearchTools, screeningTools, documentSearchTools)
                .build()
                .getToolCallbacks();
        return McpToolUtils.toSyncToolSpecification(List.of(callbacks));
    }
}
