package com.ashish.stockresearch.research.critic;

import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.service.OllamaCalls;
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

/**
 * A harmless {@link ResearchCritic} (always ACCEPT) and {@link BearCaseWriter} (a fixed, neutral bear case) for
 * tests of the report pipeline that are not themselves about the critic loop or the bear case - so those tests
 * keep asserting only what they already did, on an unrelated, separately-scripted interpretation model.
 */
public final class CriticTestSupport {

    private CriticTestSupport() {
    }

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

    public static ResearchCritic alwaysAccept() {
        ChatModel model = new FixedModel(
                "{\"unsupportedClaims\":[],\"missedDataGaps\":[],\"missingRisks\":[],\"verdict\":\"ACCEPT\"}");
        return new ResearchCritic(ChatClient.builder(model), new PassThrough(), OLLAMA);
    }

    public static ResearchCritic alwaysRevise() {
        ChatModel model = new FixedModel("{\"unsupportedClaims\":[\"a claim with no support\"],"
                + "\"missedDataGaps\":[],\"missingRisks\":[],\"verdict\":\"REVISE\"}");
        return new ResearchCritic(ChatClient.builder(model), new PassThrough(), OLLAMA);
    }

    public static BearCaseWriter noBearCase() {
        ChatModel model = new FixedModel("Nothing beyond what the data gaps above already show.");
        return new BearCaseWriter(ChatClient.builder(model), new PassThrough(), new NumericClaimVerifier(),
                new UnsupportedClaimFilter(), OLLAMA);
    }
}
