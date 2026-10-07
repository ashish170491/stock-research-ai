package com.ashish.stockresearch.research.critic;

import com.ashish.stockresearch.research.model.DataGap;
import com.ashish.stockresearch.research.model.DataQualityIssue;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.StockResearchReport;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.service.OllamaCalls;
import com.ashish.stockresearch.service.ResearchInstructions;
import com.ashish.stockresearch.service.ResearchReportWriter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

/**
 * What could go wrong with a company, drawn only from its data gaps, data-quality issues and (where
 * given) failed screening rules - never from the facts or observations, so the bear case cannot invent a
 * risk the rest of the report does not already show. Tool-free, and checked by the same deterministic
 * verifiers a report's interpretation is.
 */
@Component
public class BearCaseWriter {

    static final String NOTHING_TO_DRAW_FROM = "No data gaps, data-quality issues or failed screening rules were "
            + "recorded for this report, so the data supports no bear case.";

    static final String BEAR_CASE_PROMPT = """
            You write only a bear case for an Indian listed company: 2-4 short bullet points on what could
            go wrong, using only the data gaps, data-quality issues and failed screening rules given below -
            nothing else. Do not introduce a risk this evidence does not name, and do not speculate about a
            cause the evidence does not state. Quote figures exactly as given and calculate nothing. Output
            only the bullet points, no heading.

            """ + ResearchInstructions.EVIDENCE_RULES;

    private final ChatClient bearCaseClient;
    private final NumericClaimVerifier numericVerifier;
    private final UnsupportedClaimFilter claimFilter;
    private final OllamaCalls ollama;

    public BearCaseWriter(ChatClient.Builder chatClientBuilder, Advisor conversationTraceAdvisor,
            NumericClaimVerifier numericVerifier, UnsupportedClaimFilter claimFilter, OllamaCalls ollama) {
        // No tools: the bear case is drawn only from the restricted evidence it is handed.
        this.bearCaseClient = chatClientBuilder.clone()
                .defaultSystem(BEAR_CASE_PROMPT)
                .defaultOptions(OllamaChatOptions.builder().disableThinking())
                .defaultAdvisors(conversationTraceAdvisor)
                .build();
        this.numericVerifier = numericVerifier;
        this.claimFilter = claimFilter;
        this.ollama = ollama;
    }

    public String write(StockResearchReport report) {
        String evidence = evidence(report);
        if (evidence.isBlank()) {
            return NOTHING_TO_DRAW_FROM;
        }
        String draft = ollama.call(() -> ResearchReportWriter.stripThinking(
                bearCaseClient.prompt().user(evidence).call().content()));
        NumericClaimVerifier.Result figures = numericVerifier.verify(draft, evidence);
        UnsupportedClaimFilter.Result screened = claimFilter.filter(figures.text(), evidence);
        String text = screened.text();
        if (!figures.removedStatements().isEmpty()) {
            text += "\n\n_%d statement(s) were removed from the bear case because their figures do not appear in "
                    + "this evidence (%s)._".formatted(figures.removedStatements().size(),
                            String.join(", ", figures.ungroundedFigures()));
        }
        if (!screened.removedSentences().isEmpty()) {
            text += "\n\n_%d statement(s) were removed from the bear case because they made claims this evidence "
                    + "does not support: %s_".formatted(screened.removedSentences().size(),
                            String.join(" | ", screened.removedSentences()));
        }
        return text.isBlank() ? NOTHING_TO_DRAW_FROM : text;
    }

    /** Only data gaps and data-quality issues; never a fact, observation or peer figure. */
    static String evidence(StockResearchReport report) {
        StringBuilder md = new StringBuilder();
        for (DataQualityIssue issue : report.dataQualityIssues()) {
            md.append("- ").append(issue.type()).append(": ").append(issue.message()).append('\n');
        }
        for (DataGap gap : report.dataGaps()) {
            md.append("- ").append(gap.area()).append(" / ").append(gap.item()).append(": ").append(gap.reason())
                    .append('\n');
        }
        return md.toString();
    }
}
