package com.ashish.stockresearch.service;

import com.ashish.stockresearch.research.ValuationService;
import com.ashish.stockresearch.agent.Intent;
import com.ashish.stockresearch.agent.RequestRouter;
import com.ashish.stockresearch.agent.ResearchSessions;
import com.ashish.stockresearch.agent.RoutedRequest;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.marketdata.StockMarketDataService;
import com.ashish.stockresearch.marketdata.model.StockQuote;
import com.ashish.stockresearch.marketdata.model.StockQuoteResult;
import com.ashish.stockresearch.research.FinancialMetricsService;
import com.ashish.stockresearch.research.HistoricalPerformanceService;
import com.ashish.stockresearch.research.StockResearchService;
import com.ashish.stockresearch.research.provider.UnsupportedShareholdingProvider;
import com.ashish.stockresearch.research.provider.mock.MockStockResearchProvider;
import com.ashish.stockresearch.agent.ScreenerAgent;
import com.ashish.stockresearch.research.report.AnswerChecks;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.ScreeningCountVerifier;
import com.ashish.stockresearch.research.report.ResearchReportRenderer;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import com.ashish.stockresearch.screening.ScreeningRenderer;
import com.ashish.stockresearch.screening.ScreeningService;
import com.ashish.stockresearch.tool.DocumentSearchTools;
import com.ashish.stockresearch.tool.ScreeningTools;
import com.ashish.stockresearch.tool.StockPriceTool;
import com.ashish.stockresearch.tool.StockResearchTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
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
import org.springframework.ai.vectorstore.VectorStore;

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
import static org.mockito.ArgumentMatchers.eq;
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
    private final ScreenerAgent screenerAgent = mock(ScreenerAgent.class);
    private final com.ashish.stockresearch.research.comparison.ComparisonService comparisonService =
            mock(com.ashish.stockresearch.research.comparison.ComparisonService.class);
    private final com.ashish.stockresearch.focus.FocusService focusService =
            mock(com.ashish.stockresearch.focus.FocusService.class);
    private final com.ashish.stockresearch.verdict.VerdictService verdictService =
            mock(com.ashish.stockresearch.verdict.VerdictService.class);
    private final com.ashish.stockresearch.orchestrator.ResearchOrchestrator orchestrator =
            mock(com.ashish.stockresearch.orchestrator.ResearchOrchestrator.class);
    private final StockResearchService research = new StockResearchService(
            new MockStockResearchProvider(), new MockStockResearchProvider(), new UnsupportedShareholdingProvider(),
            new MockStockResearchProvider(), new FinancialMetricsService(), new HistoricalPerformanceService(),
            new MockStockResearchProvider(), new ValuationService(BigDecimal.valueOf(5)),
            com.ashish.stockresearch.research.TestFilings.none());

    private static final String CONVERSATION = "c1";
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
    private final ResearchSessions sessions = new ResearchSessions(com.ashish.stockresearch.agent.SessionStore.NONE);

    private AiService service(ChatModel model) {
        return service(model, mock(VectorStore.class));
    }

    private AiService service(ChatModel model, VectorStore documentVectorStore) {
        StockResearchTools tools = new StockResearchTools(research, new ResearchReportRenderer(), new SectorClassifier());
        return new AiService(ChatClient.builder(model), router, new StockPriceTool(quotes), tools,
                new ScreeningTools(mock(ScreeningService.class), new ScreeningRenderer()),
                new DocumentSearchTools(documentVectorStore), new PassThrough(),
                reportWriter, comparisonService, quotes, screenerAgent, new AnswerChecks(new NumericClaimVerifier(),
                        new ScreeningCountVerifier(), new UnsupportedClaimFilter()),
                new OllamaCalls("qwen3:8b", "http://localhost:11434"), chatMemory, sessions,
                com.ashish.stockresearch.glossary.TestGlossary.glossary(), orchestrator, verdictService, focusService);
    }

    private void routes(String message, Intent intent, String... companies) {
        when(router.route(eq(message), any())).thenReturn(new RoutedRequest(intent, List.of(companies)));
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

        assertThat(service(UNUSED).chat(CONVERSATION, "Fundamental Overview of ICICI Bank").text())
                .isEqualTo("# Research report: ICICI Bank Limited (ICICIBANK)");
    }

    @Test
    void quoteIsRenderedFromTheQuoteServiceWithoutTheModel() {
        routes("Price of Infosys and TCS", Intent.QUOTE, "INFY", "TCS");
        when(quotes.getQuote("INFY")).thenReturn(StockQuoteResult.success(new StockQuote("INFY", "NSE",
                new BigDecimal("1520.40"), "INR", Instant.parse("2026-09-25T10:00:00Z"), false, "Yahoo Finance")));
        when(quotes.getQuote("TCS")).thenReturn(StockQuoteResult.error("Market data is currently unavailable"));

        String answer = service(UNUSED).chat(CONVERSATION, "Price of Infosys and TCS").text();

        assertThat(answer).isEqualTo("""
                **INFY** (NSE): 1520.40 INR as of 2026-09-25 15:30 IST. Source: Yahoo Finance.

                No quote is available for TCS: Market data is currently unavailable""");
    }

    @Test
    void compareUsesTheComparisonServiceForAnAlignedTableNotSeparateReports() {
        routes("Compare HDFC Bank and ICICI Bank", Intent.COMPARE, "HDFCBANK", "ICICIBANK");
        when(comparisonService.compare(List.of("HDFCBANK", "ICICIBANK"))).thenReturn("## Comparison\n...");

        assertThat(service(UNUSED).chat(CONVERSATION, "Compare HDFC Bank and ICICI Bank").text())
                .isEqualTo("## Comparison\n...");
        verify(reportWriter, never()).write(anyString());
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

        String answer = service(model).chat(CONVERSATION, question).text();

        assertThat(model.toolsOffered()).contains("getFinancialSummary", "getCompanyProfile", "getStockQuote")
                .doesNotContain("getStockResearchReport");
        assertThat(answer).startsWith("Revenue was ₹").doesNotContain("Net profit was ₹12,345.67 crore")
                .contains("Removed 1 statement(s) containing figures that do not appear");
        verify(reportWriter, never()).write(anyString());
    }

    @Test
    void screenRequestsGoToTheScreenerAgentAndItsTableIsKeptAsEvidenceNotMemory() {
        String question = "Find profitable IT companies with low debt";
        routes(question, Intent.SCREEN);
        when(screenerAgent.answer(question)).thenReturn(new ScreenerAgent.ScreenAnswer("**How I read your request**",
                "TOOL RESULT: screenStocks - status OK", "[A screen was shown to the user]"));

        AiService.ChatAnswer answer = service(UNUSED).chat(CONVERSATION, question);

        assertThat(answer.intent()).isEqualTo(Intent.SCREEN);
        assertThat(answer.text()).isEqualTo("**How I read your request**");
        assertThat(chatMemory.get(CONVERSATION).get(1).getText()).isEqualTo("[A screen was shown to the user]");
    }

    // A broad question gets a question back, with choices, before anything is fetched or a model is called.
    @Test
    void aBroadQuestionAboutACompanyIsAnsweredWithChoicesFirst() {
        String question = "Should I buy Marksans Pharma?";
        routes(question, Intent.FULL_RESEARCH, "MARKSANS");

        AiService.ChatAnswer answer = service(UNUSED).chat(CONVERSATION, question);

        assertThat(answer.clarification()).isNotNull();
        assertThat(answer.clarification().company()).isEqualTo("MARKSANS");
        assertThat(answer.clarification().multiSelect()).isTrue();
        // a question about whether to invest starts with the rating ticked
        assertThat(answer.clarification().options()).filteredOn(com.ashish.stockresearch.focus.Clarification.Option::selected)
                .extracting(com.ashish.stockresearch.focus.Clarification.Option::id).containsExactly("rating");
        assertThat(answer.text()).contains("What would you like to know about MARKSANS?").contains("- Is it growing?");
        verify(reportWriter, never()).write(anyString());
        verify(verdictService, never()).answer(anyString());
        verify(focusService, never()).answer(anyString(), any());
    }

    // The choice is answered by FocusService alone, remembered, and ticked first the next time.
    @Test
    void aChosenFocusIsAnsweredRememberedAndTickedNextTime() {
        when(focusService.answer("MARKSANS", List.of(com.ashish.stockresearch.focus.Topic.GROWING,
                com.ashish.stockresearch.focus.Topic.STRETCHED))).thenReturn("# Marksans\n\n### Is it growing?\n");

        AiService.ChatAnswer answer = service(UNUSED).focus(CONVERSATION, "MARKSANS: Is it growing?; debt",
                "MARKSANS", List.of("stretched", "growing", "nonsense"));

        assertThat(answer.text()).startsWith("# Marksans");
        assertThat(answer.focus()).containsExactly("Is it growing?", "Does it carry a lot of debt?");
        assertThat(answer.suggestions()).extracting(com.ashish.stockresearch.focus.Suggestion::text)
                .startsWith("Is MARKSANS a good company to invest in?", "Is MARKSANS profitable?")
                .contains("Show me the full report on MARKSANS");
        assertThat(chatMemory.get(CONVERSATION).get(1).getText()).isEqualTo(
                "[An answer on MARKSANS covering Is it growing?; Does it carry a lot of debt? was shown to the user.]");

        routes("Tell me about Infosys", Intent.FULL_RESEARCH, "INFY");
        AiService.ChatAnswer next = service(UNUSED).chat(CONVERSATION, "Tell me about Infosys");
        assertThat(next.clarification().options()).filteredOn(com.ashish.stockresearch.focus.Clarification.Option::selected)
                .extracting(com.ashish.stockresearch.focus.Clarification.Option::id).containsExactly("growing", "stretched");
    }

    // The choices typed back (an old page, or a client that shows them as text) are read as the choice, in code.
    @Test
    void aTypedReplyToTheChoicesIsAnsweredAsThatChoiceWithoutRouting() {
        routes("Is Lupin a good company to invest in?", Intent.FULL_RESEARCH, "LUPIN");
        AiService service = service(UNUSED);
        service.chat(CONVERSATION, "Is Lupin a good company to invest in?");
        when(focusService.answer("LUPIN", List.of(com.ashish.stockresearch.focus.Topic.RATING))).thenReturn("THE RATING");

        AiService.ChatAnswer answer = service.chat(CONVERSATION, "Overall rating: Buy, Hold or Sell");

        assertThat(answer.text()).isEqualTo("THE RATING");
        verify(router, never()).route(eq("Overall rating: Buy, Hold or Sell"), any());
    }

    // "Why a Hold?" is answered by the rating itself, never by a model that has not been given it.
    @Test
    void aQuestionAboutTheRatingOfTheCompanyDiscussedIsAnsweredByTheRating() {
        routes("Research Lupin", Intent.FULL_RESEARCH, "LUPIN");
        when(reportWriter.write("LUPIN")).thenReturn("# Research report: Lupin");
        AiService service = service(UNUSED);
        service.chat(CONVERSATION, "Research Lupin");
        when(focusService.answer("LUPIN", List.of(com.ashish.stockresearch.focus.Topic.RATING))).thenReturn("THE RATING");

        assertThat(service.chat(CONVERSATION, "What is the reason of HOLD rating?").text()).isEqualTo("THE RATING");
        verify(router, never()).route(eq("What is the reason of HOLD rating?"), any());
    }

    // A model that invents a rating has those sentences removed, and is told where the real one is.
    @Test
    void aModelAnswerNeverCarriesARating() {
        String cleaned = AiService.withoutRatingClaims("Lupin makes generic medicines. The overall rating for Lupin is "
                + "**Hold**. The recommendation is based on market conditions.\nIt is based in Mumbai.", List.of("LUPIN"));

        assertThat(cleaned).startsWith("Lupin makes generic medicines.\nIt is based in Mumbai.")
                .doesNotContain("Hold**").doesNotContain("recommendation is")
                .contains("_2 sentence(s) were removed because only the application's rule-based rating may say buy, "
                        + "hold or sell. To see it, ask \"Is LUPIN a good company to invest in?\"_");
        assertThat(AiService.withoutRatingClaims("Lupin makes medicines.", List.of("LUPIN"))).isEqualTo("Lupin makes medicines.");
    }

    // "Are valuations expensive?" about the one company being discussed: the checked figures, in code, no model.
    @Test
    void aGeneralTopicQuestionIsAnsweredFromTheFiguresOfTheCompanyBeingDiscussed() {
        routes("Research Lupin", Intent.FULL_RESEARCH, "LUPIN");
        when(reportWriter.write("LUPIN")).thenReturn("# Research report: Lupin");
        AiService service = service(UNUSED);
        service.chat(CONVERSATION, "Research Lupin");
        when(focusService.answer("LUPIN", List.of(com.ashish.stockresearch.focus.Topic.PRICE))).thenReturn("THE PRICE");

        assertThat(service.chat(CONVERSATION, "Are valuations expensive?").text()).isEqualTo("THE PRICE");
        verify(router, never()).route(eq("Are valuations expensive?"), any());
    }

    // With no company named and none discussed, the application asks which one, then answers the question asked.
    @Test
    void asksWhichCompanyWhenItIsNotClearThenAnswersTheOriginalQuestion() {
        AiService service = service(UNUSED);

        AiService.ChatAnswer asked = service.chat(CONVERSATION, "Are valuations expensive?");

        assertThat(asked.clarification().kind()).isEqualTo(com.ashish.stockresearch.focus.Clarification.COMPANY);
        assertThat(asked.clarification().options()).isEmpty();
        assertThat(asked.text()).startsWith("Which company is this about? Type its name");
        verify(focusService, never()).answer(anyString(), any());

        when(router.resolveCompany("Sky Gold")).thenReturn(java.util.Optional.of("SKYGOLD"));
        when(router.companyNamed("Are valuations expensive? (SKYGOLD)")).thenReturn(java.util.Optional.of("SKYGOLD"));
        when(focusService.answer("SKYGOLD", List.of(com.ashish.stockresearch.focus.Topic.PRICE))).thenReturn("THE PRICE");

        assertThat(service.chat(CONVERSATION, "Sky Gold").text()).isEqualTo("THE PRICE");
    }

    // After a comparison, "how much debt?" could be about either company: ask, offering both.
    @Test
    void offersTheCompaniesDiscussedWhenAQuestionCouldBeAboutAnyOfThem() {
        routes("Compare TCS and Infosys", Intent.COMPARE, "TCS", "INFY");
        when(comparisonService.compare(List.of("TCS", "INFY"))).thenReturn("## Comparison");
        AiService service = service(UNUSED);
        service.chat(CONVERSATION, "Compare TCS and Infosys");

        AiService.ChatAnswer asked = service.chat(CONVERSATION, "Is the debt high?");

        assertThat(asked.clarification().multiSelect()).isFalse();
        assertThat(asked.clarification().options()).extracting(com.ashish.stockresearch.focus.Clarification.Option::id)
                .containsExactly("TCS", "INFY");
        verify(focusService, never()).answer(anyString(), any());
    }

    // "Does Asian Paints have a moat?": the signs in its figures, its price and its own words, rendered in code.
    @Test
    void aMoatQuestionAboutANamedCompanyIsAnsweredInCode() {
        when(router.companyNamed("Does Asian Paints have a moat?")).thenReturn(java.util.Optional.of("ASIANPAINT"));
        when(focusService.answer("ASIANPAINT", List.of(com.ashish.stockresearch.focus.Topic.MOAT)))
                .thenReturn("THE SIGNS");
        AiService service = service(UNUSED);

        assertThat(service.chat(CONVERSATION, "Does Asian Paints have a moat?").text()).isEqualTo("THE SIGNS");
        verify(router, never()).route(eq("Does Asian Paints have a moat?"), any());
    }

    // "What is a moat?" asks what the term means: the glossary answers it, about no company.
    @Test
    void whatAMoatIsStaysWithTheGlossary() {
        routes("What is a moat?", Intent.EXPLAIN_TERM);
        AiService service = service(UNUSED);

        assertThat(service.chat(CONVERSATION, "What is a moat?").intent()).isEqualTo(Intent.EXPLAIN_TERM);
        verify(focusService, never()).answer(anyString(), any());
    }

    // "What is ROE?" asks what a term means, even while a company is being discussed.
    @Test
    void aTermQuestionStaysWithTheGlossaryWhileACompanyIsDiscussed() {
        routes("Research Lupin", Intent.FULL_RESEARCH, "LUPIN");
        when(reportWriter.write("LUPIN")).thenReturn("# Research report: Lupin");
        routes("What is ROE?", Intent.EXPLAIN_TERM);
        AiService service = service(UNUSED);
        service.chat(CONVERSATION, "Research Lupin");

        assertThat(service.chat(CONVERSATION, "What is ROE?").intent()).isEqualTo(Intent.EXPLAIN_TERM);
        verify(focusService, never()).answer(anyString(), any());
    }

    @Test
    void anAnswerWithBlanksWhereFiguresShouldBeOrToolNamesIsNotShownAsIs() {
        assertThat(AiService.PLACEHOLDER.matcher("Trailing P/E: [X] (as of [date])").find()).isTrue();
        assertThat(AiService.PLACEHOLDER.matcher("Trailing P/E: 45.20x (as of 2026-10-09)").find()).isFalse();
        assertThat(AiService.withoutToolNames("P/E is 45.20x. Use the getValuation tool for the exact figures.\n"
                + "_Removed 1 statement(s) containing figures that do not appear in the data the tools returned._"))
                .isEqualTo("P/E is 45.20x.\n_Removed 1 statement(s) containing figures that do not appear in the data "
                        + "the tools returned._");
    }

    @Test
    void aFocusWithNoKnownTopicIsRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(UNUSED).focus(CONVERSATION, "x", "TCS",
                List.of("nonsense"))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    // Asking for the analysis in words skips the question; asking whether to buy it as well gets the rating card.
    @Test
    void aQuestionAboutWhetherToInvestThatAsksForTheAnalysisIsAnsweredByTheRating() {
        String question = "Should I buy Marksans Pharma? Give me the full analysis";
        routes(question, Intent.FULL_RESEARCH, "MARKSANS");
        when(verdictService.answer("MARKSANS")).thenReturn("THE RATING CARD");

        AiService.ChatAnswer answer = service(UNUSED).chat(CONVERSATION, question);

        assertThat(answer.text()).isEqualTo("THE RATING CARD");
        verify(reportWriter, never()).write(anyString());
        assertThat(chatMemory.get(CONVERSATION).get(1).getText()).isEqualTo(
                "[A rating on MARKSANS, with its figures, was shown to the user.]");
    }

    // Step 8: an orchestrated goal is handed straight to the orchestrator, which lays out and runs its own plan.
    @Test
    void orchestrateRequestsGoToTheOrchestrator() {
        String goal = "Find 3 long-term candidates in pharma and explain the trade-offs";
        routes(goal, Intent.ORCHESTRATE);
        when(orchestrator.start(eq(goal), any())).thenReturn("THE RESEARCH PLAN'S ANSWER");

        AiService.ChatAnswer answer = service(UNUSED).chat(CONVERSATION, goal);

        assertThat(answer.intent()).isEqualTo(Intent.ORCHESTRATE);
        assertThat(answer.text()).isEqualTo("THE RESEARCH PLAN'S ANSWER");
    }

    // S8-4: a reply of "continue" to a paused plan resumes it - it never goes through the router at all.
    @Test
    void continueResumesAPausedOrchestratorPlanWithoutRouting() {
        com.ashish.stockresearch.orchestrator.ResearchPlan plan = new com.ashish.stockresearch.orchestrator.ResearchPlan(
                "find candidates", List.of(new com.ashish.stockresearch.orchestrator.PlanStep(
                        com.ashish.stockresearch.orchestrator.StepType.WRITE_MEMO, null, List.of())));
        com.ashish.stockresearch.orchestrator.OrchestratorCheckpoint checkpoint =
                new com.ashish.stockresearch.orchestrator.OrchestratorCheckpoint(plan, 0, List.of(), List.of(), 0, 0,
                        java.time.Duration.ZERO);
        com.ashish.stockresearch.agent.ResearchSession session = sessions.get(CONVERSATION);
        session.pause(checkpoint);
        when(orchestrator.resume(session)).thenReturn("THE REST OF THE PLAN");

        AiService.ChatAnswer answer = service(UNUSED).chat(CONVERSATION, "continue");

        assertThat(answer.text()).isEqualTo("THE REST OF THE PLAN");
        verify(router, never()).route(org.mockito.ArgumentMatchers.anyString(), any());
    }

    // Term explanations live on the Terms tab, so a screen's answer carries none.
    @Test
    void aScreenAnswerCarriesNoTermExplanations() {
        String question = "Find profitable companies";
        routes(question, Intent.SCREEN);
        when(screenerAgent.answer(question)).thenReturn(new ScreenerAgent.ScreenAnswer("TCS meets ROE 3y avg ≥ 15.00% "
                + "and D/E ≤ 0.50x.", "TOOL RESULT: screenStocks - status OK", "[A screen was shown to the user]"));

        String text = service(UNUSED).chat(CONVERSATION, question).text();

        assertThat(text).isEqualTo("TCS meets ROE 3y avg ≥ 15.00% and D/E ≤ 0.50x.");
        // the memory keeps the note, not the glossary
        assertThat(chatMemory.get(CONVERSATION).get(1).getText()).isEqualTo("[A screen was shown to the user]");
    }

    @Test
    void aScreenAnswerShowsItsCriteriaByQuestion() {
        String question = "Find quality compounders";
        routes(question, Intent.SCREEN);
        com.ashish.stockresearch.screening.ScreeningCriteria quality = com.ashish.stockresearch.screening
                .ScreeningPresetsTest.presets().find("quality-compounder").orElseThrow();
        when(screenerAgent.answer(question)).thenReturn(new ScreenerAgent.ScreenAnswer("5 stocks meet every criterion.",
                "TOOL RESULT: screenStocks - status OK", "[A screen was shown to the user]", quality, List.of()));
        MetricGlossary glossary = com.ashish.stockresearch.glossary.TestGlossary.glossary();

        String text = service(UNUSED).chat(CONVERSATION, question).text();

        assertThat(text).isEqualTo("5 stocks meet every criterion.\n\n" + glossary.criteriaByQuestion(quality))
                .doesNotContain("## Explain the terms");
    }

    // S11-7: "what is ROCE?" is the glossary's entry, with no model call at all.
    @Test
    void aQuestionAboutATermIsAnsweredFromTheGlossaryWithoutTheModel() {
        routes("What is ROCE?", Intent.EXPLAIN_TERM);

        AiService.ChatAnswer answer = service(UNUSED).chat(CONVERSATION, "What is ROCE?");

        assertThat(answer.intent()).isEqualTo(Intent.EXPLAIN_TERM);
        assertThat(answer.text()).contains("**Return on capital employed (ROCE)**")
                .contains(com.ashish.stockresearch.research.sector.IndustryGroup.BANK.note())
                .contains("not generated by the language model");
        routes("What is beta?", Intent.EXPLAIN_TERM);
        assertThat(service(UNUSED).chat(CONVERSATION, "What is beta?").text())
                .startsWith("“beta” is not in the application's glossary");
    }

    @Test
    void aScreensTableIsKeptAsEvidenceForFollowUps() {
        String question = "Find profitable IT companies with low debt";
        routes(question, Intent.SCREEN);
        when(screenerAgent.answer(question)).thenReturn(new ScreenerAgent.ScreenAnswer("**How I read your request**",
                "TOOL RESULT: screenStocks - status OK", "[A screen was shown to the user]"));

        service(UNUSED).chat(CONVERSATION, question);
        assertThat(sessions.get(CONVERSATION).earlierEvidence()).contains("TOOL RESULT: screenStocks");
        assertThat(chatMemory.get(CONVERSATION).get(1).getText()).isEqualTo("[A screen was shown to the user]");
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

        String answer = service(model).chat(CONVERSATION, "Explain Spring AI in two sentences").text();

        assertThat(answer).isEqualTo("Spring AI is a framework.");
        assertThat(model.toolsOffered()).isEmpty();
        verify(quotes, never()).getQuote(any());
    }

    // --- Conversations --------------------------------------------------------------------------

    /** Answers the first question with a figure from getFinancialSummary, and records it. */
    private static AssistantMessage revenueFromTools(List<Message> messages, StringBuilder revenue) {
        Message last = messages.get(messages.size() - 1);
        if (!(last instanceof ToolResponseMessage response)) {
            return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                    "1", "function", "getFinancialSummary", "{\"symbol\":\"ICICIBANK\"}"))).build();
        }
        Matcher figure = CRORE_FIGURE.matcher(response.getResponses().get(0).responseData());
        revenue.append(figure.find() ? figure.group() : "none");
        return text("Revenue was " + revenue + ". Net profit was ₹12,345.67 crore.");
    }

    @Test
    void aFollowUpSeesTheConversationAndCanRepeatAnEarlierFigureButNotInventOne() {
        String first = "What was ICICI Bank's revenue?";
        String followUp = "And how does that compare with a year earlier?";
        routes(first, Intent.SPECIFIC_QUESTION);
        routes(followUp, Intent.SPECIFIC_QUESTION, "ICICIBANK");
        StringBuilder revenue = new StringBuilder();
        ScriptedModel model = new ScriptedModel(messages -> {
            boolean isFollowUp = messages.stream().anyMatch(m -> m.getText() != null && m.getText().startsWith(followUp));
            // The follow-up is answered from memory, without calling a tool.
            return isFollowUp ? text("Revenue was " + revenue + ". A year earlier it was ₹99,999.99 crore.")
                    : revenueFromTools(messages, revenue);
        });
        AiService service = service(model);

        AiService.ChatAnswer answer1 = service.chat(CONVERSATION, first);
        AiService.ChatAnswer answer2 = service.chat(CONVERSATION, followUp);

        assertThat(answer1.companies()).containsExactly("ICICIBANK");
        // The earlier figure is still checkable against the earlier turn's tool output; the invented one is not.
        assertThat(answer2.text()).startsWith("Revenue was " + revenue + ".").doesNotContain("₹99,999.99 crore")
                .contains("Removed 1 statement(s)");
        String followUpPrompt = model.prompts.get(model.prompts.size() - 1).getContents();
        assertThat(followUpPrompt).contains(first).contains("Revenue was " + revenue)
                .contains("(This question is about: ICICIBANK.)");
    }

    @Test
    void remembersTheScreenedAnswerNeverTheModelsDraft() {
        String question = "What was ICICI Bank's revenue?";
        routes(question, Intent.SPECIFIC_QUESTION);
        StringBuilder revenue = new StringBuilder();

        service(new ScriptedModel(messages -> revenueFromTools(messages, revenue))).chat(CONVERSATION, question);

        List<Message> remembered = chatMemory.get(CONVERSATION);
        assertThat(remembered).hasSize(2);
        assertThat(remembered.get(0).getText()).isEqualTo(question);
        assertThat(remembered.get(1).getText()).contains("Revenue was " + revenue)
                .doesNotContain("Net profit was ₹12,345.67 crore");
    }

    @Test
    void remembersAReportAsANoteAndCarriesItsCompanyToTheNextTurn() {
        routes("Research ICICI Bank", Intent.FULL_RESEARCH, "ICICIBANK");
        when(reportWriter.write("ICICIBANK")).thenReturn("# Research report: ICICI Bank Limited (ICICIBANK)");

        AiService.ChatAnswer answer = service(UNUSED).chat(CONVERSATION, "Research ICICI Bank");

        assertThat(answer.intent()).isEqualTo(Intent.FULL_RESEARCH);
        assertThat(sessions.get(CONVERSATION).companies()).containsExactly("ICICIBANK");
        assertThat(chatMemory.get(CONVERSATION).get(1).getText())
                .isEqualTo("[The full research report on ICICIBANK was shown to the user.]");
    }

    @Test
    void screensAnAnswerGivenWithoutAnyToolOrEarlierEvidence() {
        routes("What did ICICI Bank earn?", Intent.SPECIFIC_QUESTION);
        ScriptedModel model = new ScriptedModel(messages -> text("Net profit was ₹12,345.67 crore. I need more data."));

        String answer = service(model).chat(CONVERSATION, "What did ICICI Bank earn?").text();

        assertThat(answer).startsWith("I need more data.").doesNotContain("₹12,345.67");
    }

    @Test
    void keepsConversationsApart() {
        routes("Explain Spring AI in two sentences", Intent.NOT_STOCK_RELATED);
        ScriptedModel model = new ScriptedModel(messages -> text("Spring AI is a framework."));
        AiService service = service(model);

        service.chat("first", "Explain Spring AI in two sentences");
        service.chat("second", "Explain Spring AI in two sentences");

        assertThat(model.prompts.get(1).getInstructions())
                .noneMatch(m -> "Spring AI is a framework.".equals(m.getText()));
        assertThat(chatMemory.get("first")).hasSize(2);
        assertThat(chatMemory.get("second")).hasSize(2);
    }

    @Test
    void asksWhichCompanyInsteadOfGuessingWhenAFollowUpHasNothingToReferTo() {
        routes("And its debt?", Intent.SPECIFIC_QUESTION);

        AiService.ChatAnswer answer = service(UNUSED).chat("fresh", "And its debt?");

        assertThat(answer.text()).startsWith("Which company is this about?");
        assertThat(answer.clarification().kind()).isEqualTo(com.ashish.stockresearch.focus.Clarification.COMPANY);
        assertThat(answer.companies()).isEmpty();
        // Remembered, so the reply "Infosys" is understood as the answer to the question.
        assertThat(chatMemory.get("fresh")).hasSize(2);
    }

    @Test
    void answersAGeneralQuestionThatSaysItWithoutAskingForACompany() {
        routes("How is it calculated?", Intent.SPECIFIC_QUESTION);
        ScriptedModel model = new ScriptedModel(messages -> text("It is price divided by earnings per share."));

        String answer = service(model).chat("fresh", "How is it calculated?").text();

        assertThat(answer).startsWith("It is price divided by earnings per share.");
    }

    // A tool answer carries no term list: the terms are on the Terms tab.
    @Test
    void aToolAnswerCarriesNoTermList() {
        routes("How is it calculated?", Intent.SPECIFIC_QUESTION);
        ScriptedModel model = new ScriptedModel(messages -> text("It is price divided by earnings per share."));

        String answer = service(model).chat("fresh", "How is it calculated?").text();

        assertThat(answer).isEqualTo("It is price divided by earnings per share.");
    }

    // Progress: each step of a tool-loop answer is reported as it starts and ends.
    @Test
    void reportsTheStepsOfAToolLoopAnswerAsTheyHappen() {
        String question = "What was ICICI Bank's revenue?";
        routes(question, Intent.SPECIFIC_QUESTION, "ICICIBANK");
        ScriptedModel model = new ScriptedModel(messages -> text("Revenue is shown in the filings."));
        List<com.ashish.stockresearch.trace.Progress.Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (com.ashish.stockresearch.trace.Progress.Scope scope = com.ashish.stockresearch.trace.Progress.open(events::add)) {
            service(model).chat(CONVERSATION, question);
        }

        assertThat(events).filteredOn(e -> e.status() == com.ashish.stockresearch.trace.Progress.Status.DONE)
                .extracting(com.ashish.stockresearch.trace.Progress.Event::label).containsExactly(
                        "Understood: a question about ICICIBANK",
                        "Answering with the research tools (local model)",
                        "Checking every figure in the answer against the data the tools returned");
        // no step carries a figure
        assertThat(events).extracting(com.ashish.stockresearch.trace.Progress.Event::label)
                .noneMatch(label -> label.contains("₹"));
    }

    @Test
    void reportsTheStepsOfAQuoteAndOfAGlossaryAnswer() {
        routes("Price of TCS", Intent.QUOTE, "TCS");
        when(quotes.getQuote("TCS")).thenReturn(StockQuoteResult.error("Market data is currently unavailable"));
        routes("What is ROCE?", Intent.EXPLAIN_TERM);
        List<com.ashish.stockresearch.trace.Progress.Event> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        try (com.ashish.stockresearch.trace.Progress.Scope scope = com.ashish.stockresearch.trace.Progress.open(events::add)) {
            service(UNUSED).chat(CONVERSATION, "Price of TCS");
            service(UNUSED).chat(CONVERSATION, "What is ROCE?");
        }

        assertThat(events).filteredOn(e -> e.status() == com.ashish.stockresearch.trace.Progress.Status.DONE)
                .extracting(com.ashish.stockresearch.trace.Progress.Event::label).containsExactly(
                        "Understood: the latest price of TCS",
                        "Fetching the latest price of TCS (Yahoo Finance)",
                        "Understood: what a term means",
                        "Looking the term up in the application's glossary");
    }

    // Step 7: a tool-loop answer that used searchCompanyDocuments shows the citation regardless of
    // whether the model's own words repeat it (S7-5), and the document's figure grounds the answer
    // (S7-6) without the application ever saying it came from Yahoo.
    @Test
    void aDocumentSearchAnswerShowsItsCitationAndIsNotAttributedToYahoo() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("doc-vector-store");
        org.springframework.ai.vectorstore.SimpleVectorStore store = org.springframework.ai.vectorstore.SimpleVectorStore
                .builder(new com.ashish.stockresearch.documents.FixedEmbeddingModel()).build();
        new com.ashish.stockresearch.documents.DocumentIngestionService(store)
                .ingest(new org.springframework.core.io.ClassPathResource("fixtures/documents/hdfcbank-annual-report-fy2026.pdf"),
                        "HDFCBANK", com.ashish.stockresearch.documents.DocType.ANNUAL_REPORT, 2026,
                        "HDFC Bank Annual Report FY2026.pdf");

        String question = "What was HDFC Bank's Gross NPA ratio?";
        routes(question, Intent.SPECIFIC_QUESTION, "HDFCBANK");
        ScriptedModel model = new ScriptedModel(messages -> {
            Message last = messages.get(messages.size() - 1);
            if (!(last instanceof ToolResponseMessage)) {
                return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                        "1", "function", "searchCompanyDocuments",
                        "{\"symbol\":\"HDFCBANK\",\"question\":\"Gross NPA ratio\"}"))).build();
            }
            return text("The Gross NPA ratio was 1.24%.");
        });

        String answer = service(model, store).chat(CONVERSATION, question).text();

        assertThat(answer).contains("The Gross NPA ratio was 1.24%.").doesNotContain("Yahoo")
                .contains("**Document sources**").contains("HDFCBANK").contains("ANNUAL_REPORT")
                .contains("FY2026").contains("p. 2");
    }

    // A screen's first step names no index: the screen itself says which one the request's words chose.
    @Test
    void theUnderstoodStepOfAScreenNamesNoIndex() {
        assertThat(AiService.understood(new com.ashish.stockresearch.agent.RoutedRequest(
                com.ashish.stockresearch.agent.Intent.SCREEN, List.of())))
                .isEqualTo("a screen of stocks against criteria");
    }
}
