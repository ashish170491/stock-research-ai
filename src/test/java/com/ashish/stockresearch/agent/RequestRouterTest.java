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
import org.springframework.ai.chat.prompt.Prompt;

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
}
