package com.ashish.stockresearch.orchestrator;

import com.ashish.stockresearch.glossary.TestGlossary;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.service.OllamaCalls;
import org.junit.jupiter.api.Test;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The orchestrator's final memo (S8-5): verified like every other model-written answer, and never a
 * buy/sell/hold recommendation - even one the model writes anyway is stripped. */
class MemoWriterTest {

    private static final class FixedModel implements ChatModel {

        private final String content;

        FixedModel(String content) {
            this.content = content;
        }

        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().model("qwen3:8b").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(content).build())));
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

    private static final OllamaCalls OLLAMA = new OllamaCalls("qwen3:8b", "http://localhost:11434");

    private static MemoWriter writer(ChatModel model) {
        return new MemoWriter(ChatClient.builder(model), new PassThrough(), OLLAMA, new NumericClaimVerifier(),
                new UnsupportedClaimFilter(), TestGlossary.glossary());
    }

    // S8-5
    @Test
    void stripsAFigureNotInTheEvidence() {
        String evidence = "=== SCREEN: pharma with ROE above 15% ===\nTOOL RESULT: screenStocks - status OK\n"
                + "SUNPHARMA meets ROE ≥ 15.00%, with 18.20% for FY26.";
        FixedModel model = new FixedModel("CRITERIA RESULTS\nSUNPHARMA meets the ROE criterion at 18.20%. "
                + "Its revenue grew 999.99% last year, a figure this evidence does not give.");

        String memo = writer(model).write("find pharma candidates", evidence);

        assertThat(memo).contains("18.20%").doesNotContain("Its revenue grew 999.99%")
                .contains("sentence(s) were removed from this memo because their figures");
    }

    // S8-5: no buy/sell/hold recommendation ever, even stripping one the model writes anyway.
    @Test
    void stripsARecommendationTheModelWritesAnyway() {
        String evidence = "=== COMPARE ===\nSUNPHARMA trades at a P/E of 20.00x.";
        FixedModel model = new FixedModel("COMPARISON\nSUNPHARMA trades at a P/E of 20.00x. "
                + "This makes it a good buy at current levels.");

        String memo = writer(model).write("compare pharma candidates", evidence);

        // The recommendation sentence is gone from the memo's own body - the removal footnote
        // quotes it back only for transparency about what was stripped and why, like every other
        // verified answer in this application.
        String body = memo.substring(0, memo.indexOf("\n\n_"));
        assertThat(body).contains("20.00x").doesNotContain("a good buy");
        assertThat(memo).contains("sentence(s) were removed from this memo because they made claims");
    }

    @Test
    void keepsAWellGroundedMemoUnchanged() {
        String evidence = "=== SCREEN ===\nTOOL RESULT: screenStocks - status OK\n"
                + "2 of 5 screened stocks meet every criterion: SUNPHARMA, CIPLA.";
        FixedModel model = new FixedModel("CRITERIA RESULTS\n2 of 5 screened stocks meet every criterion: "
                + "SUNPHARMA, CIPLA.");

        String memo = writer(model).write("find pharma candidates", evidence);

        assertThat(memo).contains("SUNPHARMA, CIPLA").doesNotContain("were removed from this memo");
    }
}
