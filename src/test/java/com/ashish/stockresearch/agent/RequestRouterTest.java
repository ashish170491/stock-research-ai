package com.ashish.stockresearch.agent;

import com.ashish.stockresearch.marketdata.NseSymbolDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestRouterTest {

    private static final NseSymbolDirectory NSE = new NseSymbolDirectory();

    /** A model that always gives the same reply and records what it was asked. */
    private static final class StubModel implements ChatModel {

        private final String reply;
        private final RuntimeException failure;
        private final List<Prompt> prompts = new ArrayList<>();

        StubModel(String reply) {
            this(reply, null);
        }

        StubModel(String reply, RuntimeException failure) {
            this.reply = reply;
            this.failure = failure;
        }

        /** Ollama's own options, as the real model has, so per-client options are merged as they are there. */
        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().model("qwen3:8b").temperature(0.3).numCtx(16384).build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            if (failure != null) {
                throw failure;
            }
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(reply).build())));
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

    private static RequestRouter router(StubModel model) {
        return new RequestRouter(ChatClient.builder(model), NSE, new PassThrough());
    }

    // --- The model's classification, one test per intent -------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "FULL_RESEARCH     | Tell me everything about Bharti Airtel as an investment story | {\"intent\":\"FULL_RESEARCH\",\"companies\":[\"Bharti Airtel\"]}",
            "QUOTE             | Where is Infosys trading right now?                            | {\"intent\":\"QUOTE\",\"companies\":[\"Infosys\"]}",
            "COMPARE           | How do HDFC Bank and ICICI Bank stack up?                     | {\"intent\":\"COMPARE\",\"companies\":[\"HDFC Bank\",\"ICICI Bank\"]}",
            "SPECIFIC_QUESTION | What was Tech Mahindra's operating margin in FY26?            | {\"intent\":\"SPECIFIC_QUESTION\",\"companies\":[\"Tech Mahindra\"]}",
            "NOT_STOCK_RELATED | Explain Spring AI in two sentences                             | {\"intent\":\"NOT_STOCK_RELATED\",\"companies\":[]}",
    })
    void usesTheModelsClassificationWhenNoKeywordRuleApplies(Intent intent, String message, String json) {
        StubModel model = new StubModel(json);

        RoutedRequest routed = router(model).route(message);

        assertThat(routed.intent()).isEqualTo(intent);
        assertThat(model.prompts).hasSize(1);
        // The model is asked for JSON matching RoutedRequest, with no tools to call.
        assertThat(model.prompts.get(0).getContents()).contains("FULL_RESEARCH").contains("companies");
    }

    @Test
    void asksTheModelWithoutThinkingAtZeroTemperatureAndASmallContext() {
        StubModel model = new StubModel("{\"intent\":\"NOT_STOCK_RELATED\",\"companies\":[]}");

        router(model).route("Explain Spring AI in two sentences");

        assertThat(model.prompts.get(0).getOptions()).isInstanceOfSatisfying(OllamaChatOptions.class, options -> {
            assertThat(options.getThinkOption()).isEqualTo(ThinkOption.ThinkBoolean.DISABLED);
            assertThat(options.getTemperature()).isEqualTo(0.0);
            assertThat(options.getNumCtx()).isEqualTo(4096);
        });
    }

    @Test
    void keepsTheCompaniesTheModelNamedInOrder() {
        RoutedRequest routed = router(new StubModel(
                "{\"intent\":\"COMPARE\",\"companies\":[\"HDFC Bank\",\" ICICI Bank \",\"\"]}"))
                .route("How do HDFC Bank and ICICI Bank stack up?");

        assertThat(routed.companies()).containsExactly("HDFC Bank", "ICICI Bank");
    }

    @Test
    void readsJsonAfterTheModelsInlineReasoning() {
        RoutedRequest routed = router(new StubModel(
                "<think>The user wants the live price.</think>\n{\"intent\":\"QUOTE\",\"companies\":[\"Infosys\"]}"))
                .route("Where is Infosys trading right now?");

        assertThat(routed).isEqualTo(new RoutedRequest(Intent.QUOTE, List.of("Infosys")));
    }

    // --- Fallbacks ---------------------------------------------------------------------------

    @Test
    void fallsBackToASpecificQuestionOnMalformedJson() {
        RoutedRequest routed = router(new StubModel("{\"intent\": \"QUOTE\", \"companies\": [\"Infosys\""))
                .route("Where is Infosys trading right now?");

        assertThat(routed).isEqualTo(RoutedRequest.specificQuestion());
    }

    @Test
    void fallsBackToASpecificQuestionOnProse() {
        assertThat(router(new StubModel("I think this is a quote request.")).route("Where is Infosys trading?"))
                .isEqualTo(RoutedRequest.specificQuestion());
    }

    @Test
    void fallsBackToASpecificQuestionOnAnUnknownIntent() {
        assertThat(router(new StubModel("{\"intent\":\"BUY_ADVICE\",\"companies\":[\"Infosys\"]}"))
                .route("Should I buy Infosys?")).isEqualTo(RoutedRequest.specificQuestion());
    }

    @Test
    void fallsBackToASpecificQuestionWhenTheModelIsUnreachable() {
        assertThat(router(new StubModel(null, new IllegalStateException("connection refused")))
                .route("Where is Infosys trading?")).isEqualTo(RoutedRequest.specificQuestion());
    }

    @Test
    void neverDispatchesACompanyIntentWithoutACompany() {
        assertThat(router(new StubModel("{\"intent\":\"FULL_RESEARCH\",\"companies\":[]}")).route("Analyse it"))
                .isEqualTo(RoutedRequest.specificQuestion());
        assertThat(router(new StubModel("{\"intent\":\"COMPARE\",\"companies\":[\"Infosys\"]}"))
                .route("How does Infosys stack up?"))
                .isEqualTo(new RoutedRequest(Intent.FULL_RESEARCH, List.of("Infosys")));
    }

    // --- Keyword rules: no model call at all ---------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "Fundamental Overview of ICICI Bank          | FULL_RESEARCH | ICICIBANK",
            "Analyse Infosys                             | FULL_RESEARCH | INFY",
            "Give me a fundamental analysis of TECHM     | FULL_RESEARCH | TECHM",
            "Research HDFC Bank                          | FULL_RESEARCH | HDFCBANK",
            "What is the share price of Bharti Airtel?   | QUOTE         | BHARTIARTL",
            "Infosys stock price                         | QUOTE         | INFY",
    })
    void routesUnambiguousRequestsByKeywordWithoutAskingTheModel(String message, Intent intent, String symbol) {
        StubModel model = new StubModel("{\"intent\":\"NOT_STOCK_RELATED\",\"companies\":[]}");

        RoutedRequest routed = router(model).route(message);

        assertThat(routed).isEqualTo(new RoutedRequest(intent, List.of(symbol)));
        assertThat(model.prompts).isEmpty();
    }

    @Test
    void routesComparisonsByKeyword() {
        StubModel model = new StubModel("not used");

        assertThat(router(model).route("Compare HDFC Bank and ICICI Bank"))
                .isEqualTo(new RoutedRequest(Intent.COMPARE, List.of("HDFCBANK", "ICICIBANK")));
        assertThat(router(model).route("Infosys vs Tech Mahindra?"))
                .isEqualTo(new RoutedRequest(Intent.COMPARE, List.of("INFY", "TECHM")));
        assertThat(model.prompts).isEmpty();
    }

    @Test
    void leavesKeywordMatchesWithoutAListedCompanyToTheModel() {
        StubModel model = new StubModel("{\"intent\":\"NOT_STOCK_RELATED\",\"companies\":[]}");

        RoutedRequest routed = router(model).route("Research the history of Spring AI");

        assertThat(routed.intent()).isEqualTo(Intent.NOT_STOCK_RELATED);
        assertThat(model.prompts).hasSize(1);
    }

    // --- Follow-ups within a conversation --------------------------------------------------------

    private static ResearchSession about(String... companies) {
        ResearchSession session = new ResearchSession();
        session.rememberCompanies(List.of(companies));
        return session;
    }

    @Test
    void aFollowUpThatNamesNoCompanyIsAboutTheOneDiscussedEarlier() {
        StubModel model = new StubModel("{\"intent\":\"SPECIFIC_QUESTION\",\"companies\":[]}");

        RoutedRequest routed = router(model).route("what about its debt?", about("TCS"));

        assertThat(routed).isEqualTo(new RoutedRequest(Intent.SPECIFIC_QUESTION, List.of("TCS")));
        assertThat(model.prompts.get(0).getContents()).contains("Company discussed earlier: TCS")
                .contains("Message: what about its debt?");
    }

    @Test
    void researchItIsLeftToTheModelAndResolvedToTheEarlierCompany() {
        StubModel model = new StubModel("{\"intent\":\"FULL_RESEARCH\",\"companies\":[]}");

        RoutedRequest routed = router(model).route("Research it", about("INFY"));

        assertThat(routed).isEqualTo(new RoutedRequest(Intent.FULL_RESEARCH, List.of("INFY")));
    }

    @Test
    void compareItWithAnotherCompanyComparesTheEarlierOneWithIt() {
        StubModel model = new StubModel("{\"intent\":\"COMPARE\",\"companies\":[\"TCS\"]}");

        RoutedRequest routed = router(model).route("compare it with TCS", about("INFY"));

        assertThat(routed).isEqualTo(new RoutedRequest(Intent.COMPARE, List.of("INFY", "TCS")));
    }

    @Test
    void aNewCompanyNamedInTheMessageWins() {
        StubModel model = new StubModel("{\"intent\":\"SPECIFIC_QUESTION\",\"companies\":[\"Wipro\"]}");

        RoutedRequest routed = router(model).route("and what about Wipro's margins?", about("INFY"));

        assertThat(routed.companies()).containsExactly("Wipro");
    }

    @Test
    void aMessageThatDoesNotReferBackGetsNoEarlierCompany() {
        StubModel model = new StubModel("{\"intent\":\"SPECIFIC_QUESTION\",\"companies\":[]}");

        RoutedRequest routed = router(model).route("What is a P/E ratio?", about("INFY"));

        assertThat(routed.companies()).isEmpty();
        assertThat(model.prompts.get(0).getContents()).doesNotContain("Company discussed earlier");
    }

    @Test
    void aQuestionNotAboutStocksStaysThatWay() {
        StubModel model = new StubModel("{\"intent\":\"NOT_STOCK_RELATED\",\"companies\":[]}");

        RoutedRequest routed = router(model).route("Explain it in simpler words", about("INFY"));

        assertThat(routed).isEqualTo(new RoutedRequest(Intent.NOT_STOCK_RELATED, List.of()));
    }

    @Test
    void anUnreachableModelStillRoutesAFollowUpToTheEarlierCompany() {
        StubModel model = new StubModel(null, new RuntimeException("connection refused"));

        RoutedRequest routed = router(model).route("how has it performed?", about("INFY"));

        assertThat(routed).isEqualTo(new RoutedRequest(Intent.SPECIFIC_QUESTION, List.of("INFY")));
    }

    @Test
    void dropsACompanyTheModelAddedThatTheMessageDoesNotName() {
        // Seen live: asked with nothing to refer to, the model answered with a company of its own.
        StubModel model = new StubModel("{\"intent\":\"SPECIFIC_QUESTION\",\"companies\":[\"Reliance Industries Ltd\"]}");

        RoutedRequest routed = router(model).route("And its debt?", new ResearchSession());

        assertThat(routed).isEqualTo(new RoutedRequest(Intent.SPECIFIC_QUESTION, List.of()));
    }

    @Test
    void keepsACompanyNamedByADistinctiveWordOfItsName() {
        StubModel model = new StubModel("{\"intent\":\"SPECIFIC_QUESTION\",\"companies\":[\"Reliance Industries Ltd\"]}");

        RoutedRequest routed = router(model).route("What is Reliance's debt?", new ResearchSession());

        assertThat(routed.companies()).containsExactly("Reliance Industries Ltd");
    }

    @Test
    void aGenericWordAloneDoesNotCountAsNamingACompany() {
        StubModel model = new StubModel("{\"intent\":\"SPECIFIC_QUESTION\",\"companies\":[\"HDFC Bank\"]}");

        RoutedRequest routed = router(model).route("What about the bank's deposits?", new ResearchSession());

        assertThat(routed.companies()).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "What is the price to book of TCS?", "What is the P/E of Infosys?", "Price-to-earnings of TCS",
            "What is the dividend yield on the TCS share price?"})
    void leavesValuationQuestionsThatSayPriceToTheModelRatherThanQuoting(String message) {
        StubModel model = new StubModel("{\"intent\":\"SPECIFIC_QUESTION\",\"companies\":[\"TCS\"]}");

        RoutedRequest routed = router(model).route(message);

        assertThat(routed.intent()).isEqualTo(Intent.SPECIFIC_QUESTION);
        assertThat(model.prompts).hasSize(1);
    }

    @Test
    void stillQuotesAPlainPriceQuestionWithoutTheModel() {
        StubModel model = new StubModel(null, new IllegalStateException("the model must not be called"));

        assertThat(router(model).route("price of TCS").intent()).isEqualTo(Intent.QUOTE);
        assertThat(model.prompts).isEmpty();
    }

    // --- Screening: many stocks, no single company ------------------------------------------

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Which Nifty 50 stocks meet the quality-compounder criteria?",
            "Screen the Nifty 50 for dividend stocks",
            "Find stocks with reasonable value",
            "Which banks' stocks pass the quality compounder screen?",
            "List companies that meet the dividend preset"})
    void sendsAScreeningRequestToTheToolLoopWithoutAskingTheModel(String message) {
        StubModel model = new StubModel("{\"intent\":\"FULL_RESEARCH\",\"companies\":[\"RELIANCE\"]}");

        RoutedRequest routed = router(model).route(message);

        assertThat(routed).isEqualTo(RoutedRequest.specificQuestion());
        assertThat(model.prompts).isEmpty();
    }

    @Test
    void neverAsksWhichCompanyAScreenMeans() {
        String message = "Which stocks have their ROE above the quality-compounder threshold?";

        assertThat(RequestRouter.needsACompany(message, RoutedRequest.specificQuestion(), null)).isFalse();
        // a follow-up naming no company is still asked about
        assertThat(RequestRouter.needsACompany("and its debt?", RoutedRequest.specificQuestion(), null)).isTrue();
    }

    @Test
    void stillRoutesAQuestionAboutOneCompanysSharesAsBefore() {
        StubModel model = new StubModel("not used");

        assertThat(router(model).route("Infosys stock price")).isEqualTo(new RoutedRequest(Intent.QUOTE, List.of("INFY")));
    }
}
