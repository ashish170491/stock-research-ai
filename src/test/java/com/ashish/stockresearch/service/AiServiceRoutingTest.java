package com.ashish.stockresearch.service;

import com.ashish.stockresearch.agent.Intent;
import com.ashish.stockresearch.agent.RequestRouter;
import com.ashish.stockresearch.agent.RoutedRequest;
import com.ashish.stockresearch.marketdata.StockMarketDataService;
import com.ashish.stockresearch.marketdata.model.StockQuote;
import com.ashish.stockresearch.marketdata.model.StockQuoteResult;
import com.ashish.stockresearch.research.FinancialMetricsService;
import com.ashish.stockresearch.research.HistoricalPerformanceService;
import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.provider.UnsupportedShareholdingProvider;
import com.ashish.stockresearch.research.provider.mock.MockStockResearchProvider;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.ResearchReportRenderer;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import com.ashish.stockresearch.tool.StockPriceTool;
import com.ashish.stockresearch.tool.StockResearchTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Each routed intent reaches the right answer path. The router is stubbed (it has its own tests);
 * the tool loop runs for real through Spring AI with a scripted model and the real research tools.
 */
class AiServiceRoutingTest {

    private static final Pattern CRORE_FIGURE = Pattern.compile("₹[\\d,]+\\.\\d{2} crore");

    /** A model whose replies are decided by a script over the conversation; records every prompt. */
    private static final class ScriptedModel implements ChatModel {

        private final Function<List<Message>, AssistantMessage> script;
        private final List<Prompt> prompts = new ArrayList<>();

        ScriptedModel(Function<List<Message>, AssistantMessage> script) {
            this.script = script;
        }

        /** Tool-calling options, so the chat client runs Spring AI's tool loop as it does for Ollama. */
        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(script.apply(prompt.getInstructions()))));
        }

        List<String> toolsOffered() {
            return prompts.stream().map(Prompt::getOptions)
                    .filter(o -> o instanceof ToolCallingChatOptions)
                    .map(o -> ((ToolCallingChatOptions) o).getToolCallbacks())
                    .filter(java.util.Objects::nonNull)
                    .flatMap(List::stream)
                    .map(ToolCallback::getToolDefinition).map(d -> d.name()).distinct().toList();
        }
    }

    private static final class PassThrough implements CallAdvisor {
        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            return chain.nextCall(request);
        }

        @Override
        public String getName() {
            return "pass-through";
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }

    private final RequestRouter router = mock(RequestRouter.class);
    private final ResearchReportWriter reportWriter = mock(ResearchReportWriter.class);
    private final StockMarketDataService quotes = mock(StockMarketDataService.class);
    private final StockResearchService research = new StockResearchService(
            new MockStockResearchProvider(), new MockStockResearchProvider(), new UnsupportedShareholdingProvider(),
            new MockStockResearchProvider(), new FinancialMetricsService(), new HistoricalPerformanceService());

    private AiService service(ChatModel model) {
        StockResearchTools tools = new StockResearchTools(research, new ResearchReportRenderer(), new SectorClassifier());
        return new AiService(ChatClient.builder(model), router, new StockPriceTool(quotes), tools, new PassThrough(),
                reportWriter, quotes, new UnsupportedClaimFilter(), new NumericClaimVerifier());
    }

    private void routes(String message, Intent intent, String... companies) {
        when(router.route(message)).thenReturn(new RoutedRequest(intent, List.of(companies)));
    }

    private static AssistantMessage text(String content) {
        return AssistantMessage.builder().content(content).build();
    }

    private static final ScriptedModel UNUSED = new ScriptedModel(messages -> {
        throw new AssertionError("the model must not be called for this intent");
    });

    @Test
    void fullResearchReturnsTheJavaBuiltReportWithoutTheModel() {
        routes("Fundamental Overview of ICICI Bank", Intent.FULL_RESEARCH, "ICICIBANK");
        when(reportWriter.write("ICICIBANK")).thenReturn("# Research report: ICICI Bank Limited (ICICIBANK)");

        assertThat(service(UNUSED).chat("Fundamental Overview of ICICI Bank"))
                .isEqualTo("# Research report: ICICI Bank Limited (ICICIBANK)");
    }

    @Test
    void quoteIsRenderedFromTheQuoteServiceWithoutTheModel() {
        routes("Price of Infosys and TCS", Intent.QUOTE, "INFY", "TCS");
        when(quotes.getQuote("INFY")).thenReturn(StockQuoteResult.success(new StockQuote("INFY", "NSE",
                new BigDecimal("1520.40"), "INR", Instant.parse("2026-09-25T10:00:00Z"), false, "Yahoo Finance")));
        when(quotes.getQuote("TCS")).thenReturn(StockQuoteResult.error("Market data is currently unavailable"));

        String answer = service(UNUSED).chat("Price of Infosys and TCS");

        assertThat(answer).isEqualTo("""
                **INFY** (NSE): 1520.40 INR as of 2026-09-25 15:30 IST. Source: Yahoo Finance.

                No quote is available for TCS: Market data is currently unavailable""");
    }

    @Test
    void compareReturnsOneReportPerCompany() {
        routes("Compare HDFC Bank and ICICI Bank", Intent.COMPARE, "HDFCBANK", "ICICIBANK");
        when(reportWriter.write("HDFCBANK")).thenReturn("# HDFC report");
        when(reportWriter.write("ICICIBANK")).thenReturn("# ICICI report");

        assertThat(service(UNUSED).chat("Compare HDFC Bank and ICICI Bank"))
                .isEqualTo("# HDFC report\n\n---\n\n# ICICI report");
    }

    @Test
    void specificQuestionsUseTheToolLoopWithoutTheReportTool() {
        String question = "What was ICICI Bank's revenue?";
        routes(question, Intent.SPECIFIC_QUESTION);
        ScriptedModel model = new ScriptedModel(messages -> {
            Message last = messages.get(messages.size() - 1);
            if (!(last instanceof ToolResponseMessage response)) {
                return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                        "1", "function", "getFinancialSummary", "{\"symbol\":\"ICICIBANK\"}"))).build();
            }
            Matcher figure = CRORE_FIGURE.matcher(response.getResponses().get(0).responseData());
            return text("Revenue was " + (figure.find() ? figure.group() : "none") + ". Net profit was ₹12,345.67 crore.");
        });

        String answer = service(model).chat(question);

        assertThat(model.toolsOffered()).contains("getFinancialSummary", "getCompanyProfile", "getStockQuote")
                .doesNotContain("getStockResearchReport");
        assertThat(answer).startsWith("Revenue was ₹").doesNotContain("Net profit was ₹12,345.67 crore")
                .contains("Removed 1 statement(s) containing figures that do not appear");
        verify(reportWriter, never()).write(anyString());
    }

    @Test
    void theToolLoopPromptNoLongerCoercesToolChoice() {
        assertThat(AiService.SYSTEM_PROMPT).doesNotContain("CHOOSING TOOLS").doesNotContain("getStockResearchReport")
                .contains(ResearchInstructions.EVIDENCE_RULES);
    }

    @Test
    void generalQuestionsAreAnsweredWithoutTools() {
        routes("Explain Spring AI in two sentences", Intent.NOT_STOCK_RELATED);
        ScriptedModel model = new ScriptedModel(messages -> text("<think>easy</think>Spring AI is a framework."));

        String answer = service(model).chat("Explain Spring AI in two sentences");

        assertThat(answer).isEqualTo("Spring AI is a framework.");
        assertThat(model.toolsOffered()).isEmpty();
        verify(quotes, never()).getQuote(any());
    }
}
