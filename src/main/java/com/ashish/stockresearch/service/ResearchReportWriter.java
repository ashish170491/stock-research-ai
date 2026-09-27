package com.ashish.stockresearch.service;

import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.ResearchReportRenderer;
import com.ashish.stockresearch.research.report.ResearchReportService;
import com.ashish.stockresearch.research.report.StockResearchReport;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Writes a complete research report: FACTS, OBSERVATIONS, DATA GAPS and
 * SOURCES are built and rendered in Java; the model contributes only the
 * INTERPRETATION, from a tool-free client that sees nothing but the
 * evidence, and that text is screened before it is included: unsupported
 * claims are removed, and so is any conclusion about a topic whose values
 * were withheld (DATA_CONFLICT, INVALID or not relevant to the sector).
 */
@Service
public class ResearchReportWriter {

	static final String INTERPRETATION_PROMPT = """
			You are a financial research assistant. You are given a research evidence pack for an
			Indian listed company: facts, observations calculated by software, and data gaps.

			Write only the INTERPRETATION section: 2-4 short paragraphs on what the available
			evidence suggests, what the data gaps mean for confidence, and what a reader should
			check next. Use only the evidence pack. Output plain paragraphs, with no headings.
			Write no conclusion about any topic listed as NOT GENERATED; you may say that conclusions
			on it were withheld and why. Quote figures exactly as given and calculate nothing.

			""" + ResearchInstructions.EVIDENCE_RULES;

	private final ChatClient interpretationClient;
	private final ResearchReportService researchReportService;
	private final ResearchReportRenderer renderer;
	private final UnsupportedClaimFilter claimFilter;
	private final NumericClaimVerifier numericVerifier;

	public ResearchReportWriter(ChatClient.Builder chatClientBuilder,
			Advisor conversationTraceAdvisor,
			ResearchReportService researchReportService,
			ResearchReportRenderer renderer,
			UnsupportedClaimFilter claimFilter,
			NumericClaimVerifier numericVerifier) {
		// No tools: interpretation must see only the evidence it is handed,
		// with no way to fetch or invent more.
		this.interpretationClient = chatClientBuilder.clone()
				.defaultSystem(INTERPRETATION_PROMPT)
				.defaultAdvisors(conversationTraceAdvisor)
				.build();
		this.researchReportService = researchReportService;
		this.renderer = renderer;
		this.claimFilter = claimFilter;
		this.numericVerifier = numericVerifier;
	}

	public String write(String symbol) {
		StockResearchReport report = researchReportService.build(symbol);
		String evidence = renderer.renderEvidence(report);
		String interpretation;
		try {
			interpretation = stripThinking(interpretationClient.prompt().user(evidence).call().content());
		} catch (ResourceAccessException ex) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Could not reach Ollama. Make sure it is running (ollama run qwen3:8b) at http://localhost:11434",
					ex);
		}
		NumericClaimVerifier.Result figures = numericVerifier.verify(interpretation, evidence);
		UnsupportedClaimFilter.Result screened = claimFilter.filter(figures.text(), evidence, report.withheldTopics());
		String text = screened.text();
		if (!figures.removedStatements().isEmpty()) {
			text += "\n\n_%d sentence(s) were removed from this interpretation because their figures do not appear in the evidence (%s)._"
					.formatted(figures.removedStatements().size(), String.join(", ", figures.ungroundedFigures()));
		}
		if (!screened.removedSentences().isEmpty()) {
			text += "\n\n_%d sentence(s) were removed from this interpretation because they made claims not supported by the data: %s_"
					.formatted(screened.removedSentences().size(), String.join(" | ", screened.removedSentences()));
		}
		return renderer.render(report, text);
	}

	/** Qwen3 can emit its reasoning inline; it is not part of the report. */
	public static String stripThinking(String text) {
		return text == null ? null : text.replaceAll("(?s)<think>.*?</think>", "").strip();
	}
}
