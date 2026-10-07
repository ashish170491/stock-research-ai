package com.ashish.stockresearch.research.critic;

import com.ashish.stockresearch.service.OllamaCalls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

/**
 * The evaluator half of the evaluator-optimizer loop: reviews a draft interpretation against its own
 * evidence pack, tool-free - it sees only the evidence and the draft handed to it, nothing live. It never
 * writes or revises the text itself, only judges it (ACCEPT or REVISE) and says why.
 *
 * <p>A model call that fails, or whose answer cannot be parsed as a {@link Critique}, is treated as ACCEPT
 * (logged as a warning): the critic is an added check, not a new way for the report to fail.
 */
@Component
public class ResearchCritic {

    private static final Logger log = LoggerFactory.getLogger(ResearchCritic.class);

    static final String CRITIC_PROMPT = """
            You review a draft INTERPRETATION of a research evidence pack for an Indian listed company,
            checked against that evidence pack only. You do not write or revise the interpretation; you
            report what is wrong with it, if anything.

            Find:
            - unsupportedClaims: sentences in the draft that state something the evidence pack does not
              support.
            - missedDataGaps: data gaps or DATA_CONFLICTs in the evidence pack that bear on what the draft
              discusses, which the draft should have mentioned but did not.
            - missingRisks: risks implied by a data gap, conflict or withheld topic that the draft omits.
            Give verdict REVISE when any of these lists is non-empty, or when the draft draws a conclusion
            the evidence does not support; otherwise ACCEPT.

            Respond with ONLY a JSON object, no other text: {"unsupportedClaims": [...], "missedDataGaps":
            [...], "missingRisks": [...], "verdict": "ACCEPT" or "REVISE"}.
            """;

    private final ChatClient criticClient;
    private final OllamaCalls ollama;

    public ResearchCritic(ChatClient.Builder chatClientBuilder, Advisor conversationTraceAdvisor, OllamaCalls ollama) {
        // No tools: the critic judges only the evidence and draft it is handed, never anything live.
        this.criticClient = chatClientBuilder.clone()
                .defaultSystem(CRITIC_PROMPT)
                .defaultOptions(OllamaChatOptions.builder().disableThinking().temperature(0.0))
                .defaultAdvisors(conversationTraceAdvisor)
                .build();
        this.ollama = ollama;
    }

    public Critique review(String evidence, String draft) {
        String prompt = "EVIDENCE PACK\n" + evidence + "\n\nDRAFT INTERPRETATION\n" + draft;
        try {
            Critique critique = ollama.call(() -> criticClient.prompt().user(prompt).call().entity(Critique.class));
            return critique == null ? Critique.accept() : critique;
        } catch (RuntimeException ex) {
            log.warn("Critic gave no usable answer ({}); treating the draft as accepted", ex.getMessage());
            return Critique.accept();
        }
    }
}
