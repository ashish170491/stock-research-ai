package com.ashish.stockresearch.marketdata.provider.yahoo;

import com.ashish.stockresearch.context.RatioContextService;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.glossary.TestGlossary;
import com.ashish.stockresearch.research.calc.FinancialDataValidator;
import com.ashish.stockresearch.research.critic.CriticTestSupport;
import com.ashish.stockresearch.research.critic.ResearchCritic;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.ResearchReportRenderer;
import com.ashish.stockresearch.research.report.ResearchReportService;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.research.sector.SectorClassifier;
import com.ashish.stockresearch.screening.FundamentalsSnapshotRepository;
import com.ashish.stockresearch.screening.SnapshotDatabase;
import com.ashish.stockresearch.service.OllamaCalls;
import com.ashish.stockresearch.service.ResearchReportWriter;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The critic loop (roadmap Step 6) over a real report pipeline: HDFC Bank's recorded Yahoo data, a scripted
 * interpretation model, and a scripted critic. */
// S6-2, S6-3
class ResearchReportWriterCriticLoopTest {

    /** Replies in order; the last reply repeats for any further call. */
    private static final class ScriptedModel implements ChatModel {

        private final List<String> replies;
        final List<Prompt> prompts = new ArrayList<>();

        ScriptedModel(List<String> replies) {
            this.replies = replies;
        }

        @Override
        public ChatOptions getOptions() {
            return OllamaChatOptions.builder().model("qwen3:8b").build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            String reply = replies.get(Math.min(prompts.size() - 1, replies.size() - 1));
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

    private final MetricGlossary glossary = TestGlossary.glossary();

    private ResearchReportWriter writer(ScriptedModel interpretationModel, ResearchCritic critic) {
        ResearchReportService service = new ResearchReportService(ResearchPipelineRegressionTest.research("HDFCBANK"),
                new FinancialDataValidator(ResearchPipelineRegressionTest.SEPT_2026), new SectorClassifier());
        RatioContextService noSnapshot = new RatioContextService(
                new FundamentalsSnapshotRepository(SnapshotDatabase.create()), 5);
        return new ResearchReportWriter(ChatClient.builder(interpretationModel), new PassThrough(), service,
                new ResearchReportRenderer(), new UnsupportedClaimFilter(), new NumericClaimVerifier(),
                new OllamaCalls("qwen3:8b", "http://localhost:11434"), glossary, noSnapshot, critic,
                CriticTestSupport.noBearCase(), false);
    }

    // S6-2
    @Test
    void writerRunsAtMostTwiceWhenTheCriticAlwaysAsksForARevision() {
        ScriptedModel model = new ScriptedModel(List.of(
                "A plain first draft with no issues of its own.",
                "A plain revised draft with no issues of its own."));

        String report = writer(model, CriticTestSupport.alwaysRevise()).write("HDFCBANK");

        assertThat(model.prompts).hasSize(2);
        assertThat(report).contains("revision limit").contains("already reached");
    }

    @Test
    void writerRunsOnceWhenTheCriticAccepts() {
        ScriptedModel model = new ScriptedModel(List.of("A single accepted draft, with no issues."));

        String report = writer(model, CriticTestSupport.alwaysAccept()).write("HDFCBANK");

        assertThat(model.prompts).hasSize(1);
        assertThat(report).contains("A single accepted draft").doesNotContain("revision limit");
    }

    // S6-3
    @Test
    void verifiersCheckTheFinalRevisionNotTheFirstDraft() {
        ScriptedModel model = new ScriptedModel(List.of(
                "Revenue fell 999.99% last year, a figure this evidence does not give.",
                "A clean revised interpretation with every figure grounded in the evidence."));

        String report = writer(model, CriticTestSupport.alwaysRevise()).write("HDFCBANK");

        assertThat(report).contains("A clean revised interpretation")
                .doesNotContain("999.99%")
                .doesNotContain("sentence(s) were removed from this interpretation because their figures");
    }
}
