package com.ashish.stockresearch.orchestrator;

import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.service.OllamaCalls;
import com.ashish.stockresearch.service.ResearchInstructions;
import com.ashish.stockresearch.service.ResearchReportWriter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

/**
 * Writes the orchestrator's final memo (S8-5, WRITE_MEMO) from the evidence every earlier plan step
 * gathered - tool-free, like {@link com.ashish.stockresearch.research.comparison.ComparisonService}'s
 * narrative: one model call over text already assembled in Java, then the same verifier chain every
 * other model-written answer in this application goes through.
 */
@Component
public class MemoWriter {

    static final String MEMO_PROMPT = """
            You write the final memo for a multi-step stock research plan, from the evidence already
            gathered below. You do not screen, fetch data, compare companies, critique a draft or search
            documents yourself - every figure and finding you write must already appear in the evidence.

            Write exactly these sections, in this order, with these headings:
            1. CRITERIA RESULTS - which companies were screened and against what criteria, and which met
               every criterion, from the evidence's SCREEN step.
            2. COMPARISON - what the evidence shows across the candidates, if more than one were examined;
               say so plainly if only one was.
            3. RISKS / DATA GAPS - every data gap, data conflict, data-quality issue and critique finding
               the evidence mentions.
            4. OPEN QUESTIONS - what a reader should check next, given what the evidence does not establish.
            5. SOURCES - where each piece of evidence in this memo came from (screen, report, comparison,
               document search), as the evidence itself names them.

            """ + ResearchInstructions.EVIDENCE_RULES + """

            Give no buy, sell or hold recommendation, rating, score, price target or valuation opinion -
            this application never gives one, in this memo or anywhere else. Quote figures exactly as they
            appear in the evidence below; calculate nothing yourself.
            """;

    private final ChatClient memoClient;
    private final OllamaCalls ollama;
    private final NumericClaimVerifier numericVerifier;
    private final UnsupportedClaimFilter claimFilter;
    private final MetricGlossary glossary;

    public MemoWriter(ChatClient.Builder chatClientBuilder, Advisor conversationTraceAdvisor, OllamaCalls ollama,
            NumericClaimVerifier numericVerifier, UnsupportedClaimFilter claimFilter, MetricGlossary glossary) {
        // No tools: every fact the memo may state is already in the evidence text it is handed.
        this.memoClient = chatClientBuilder.clone()
                .defaultSystem(MEMO_PROMPT)
                .defaultOptions(OllamaChatOptions.builder().disableThinking())
                .defaultAdvisors(conversationTraceAdvisor)
                .build();
        this.ollama = ollama;
        this.numericVerifier = numericVerifier;
        this.claimFilter = claimFilter;
        this.glossary = glossary;
    }

    public String write(String goal, String evidence) {
        String prompt = "GOAL\n" + goal + "\n\nEVIDENCE GATHERED\n" + evidence;
        String draft = ResearchReportWriter.stripThinking(
                ollama.call(() -> memoClient.prompt().user(prompt).call().content()));
        NumericClaimVerifier.Result figures = numericVerifier.verify(draft, evidence);
        UnsupportedClaimFilter.Result screened = claimFilter.filter(figures.text(), evidence);
        StringBuilder text = new StringBuilder(screened.text());
        if (!figures.removedStatements().isEmpty()) {
            text.append("\n\n_%d sentence(s) were removed from this memo because their figures do not appear in "
                    .formatted(figures.removedStatements().size())
                    + "the gathered evidence (" + String.join(", ", figures.ungroundedFigures()) + ")._");
        }
        if (!screened.removedSentences().isEmpty()) {
            text.append("\n\n_%d sentence(s) were removed from this memo because they made claims the evidence "
                    .formatted(screened.removedSentences().size())
                    + "does not support: " + String.join(" | ", screened.removedSentences()) + "_");
        }
        String rendered = text.toString();
        return rendered + "\n\n" + glossary.section(glossary.termsIn(rendered));
    }
}
