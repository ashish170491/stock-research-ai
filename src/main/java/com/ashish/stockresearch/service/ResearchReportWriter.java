package com.ashish.stockresearch.service;

import com.ashish.stockresearch.context.ContextRenderer;
import com.ashish.stockresearch.context.PositionClaimFilter;
import com.ashish.stockresearch.context.RatioContextService;
import com.ashish.stockresearch.context.ReportContext;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.research.critic.BearCaseWriter;
import com.ashish.stockresearch.research.critic.Critique;
import com.ashish.stockresearch.research.critic.ResearchCritic;
import com.ashish.stockresearch.trace.Progress;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.ResearchReportRenderer;
import com.ashish.stockresearch.research.report.ResearchReportService;
import com.ashish.stockresearch.research.report.StockResearchReport;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Writes a complete research report: FACTS, OBSERVATIONS, DATA GAPS and
 * SOURCES are built and rendered in Java; the model contributes only the
 * INTERPRETATION, from a tool-free client that sees nothing but the
 * evidence, and that text is screened before it is included: unsupported
 * claims are removed, and so is any conclusion about a topic whose values
 * were withheld (DATA_CONFLICT, INVALID or not relevant to the sector).
 * The report ends with the glossary's entries for the terms it uses, rendered
 * by Java from {@link MetricGlossary} after the checks, never by the model.
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
			CONTEXT places each ratio beside the company's own fiscal years. It does NOT give you any
			peer, industry or sector figure - those are shown to the reader separately, not to you - so
			never state, estimate or imply how the company compares with other companies, a peer group,
			an industry or a sector, and never give a rank or place among years or companies (highest,
			lowest, Nth, top, bottom).

			""" + ResearchInstructions.EVIDENCE_RULES;

	/** At most one revision round: the writer runs at most twice for one report (draft, then one revision). */
	static final int MAX_REVISIONS = 1;

	private final ChatClient interpretationClient;
	private final ResearchReportService researchReportService;
	private final ResearchReportRenderer renderer;
	private final UnsupportedClaimFilter claimFilter;
	private final NumericClaimVerifier numericVerifier;
	private final OllamaCalls ollama;
	private final MetricGlossary glossary;
	private final RatioContextService contextService;
	private final ResearchCritic critic;
	private final BearCaseWriter bearCaseWriter;

	public ResearchReportWriter(ChatClient.Builder chatClientBuilder,
			Advisor conversationTraceAdvisor,
			ResearchReportService researchReportService,
			ResearchReportRenderer renderer,
			UnsupportedClaimFilter claimFilter,
			NumericClaimVerifier numericVerifier,
			OllamaCalls ollama,
			MetricGlossary glossary,
			RatioContextService contextService,
			ResearchCritic critic,
			BearCaseWriter bearCaseWriter,
			@Value("${app.research.interpretation.thinking:false}") boolean thinking) {
		// No tools: interpretation must see only the evidence it is handed,
		// with no way to fetch or invent more. Whether the model reasons before
		// writing is a setting: it costs time, and may or may not improve the text.
		OllamaChatOptions.Builder options = OllamaChatOptions.builder();
		this.interpretationClient = chatClientBuilder.clone()
				.defaultSystem(INTERPRETATION_PROMPT)
				.defaultOptions(thinking ? options.enableThinking() : options.disableThinking())
				.defaultAdvisors(conversationTraceAdvisor)
				.build();
		this.researchReportService = researchReportService;
		this.renderer = renderer;
		this.claimFilter = claimFilter;
		this.numericVerifier = numericVerifier;
		this.ollama = ollama;
		this.glossary = glossary;
		this.contextService = contextService;
		this.critic = critic;
		this.bearCaseWriter = bearCaseWriter;
	}

	public String write(String symbol) {
		return write(researchReportService.build(symbol));
	}

	/** The same report, for a caller that already built it (e.g. a comparison fetching several at once). */
	public String write(StockResearchReport report) {
		ReportContext context = Progress.step("Placing each ratio beside the company's own years and its peers "
				+ "(stored snapshot)", () -> contextService.build(report, ResearchReportRenderer.disputed(report)));
		// The reader's context carries every peer figure, verified, with its snapshot date and peer count. The
		// model is given a version with no peer figure at all - a number it never has, it cannot restate, round
		// to a coarser precision, or misquote - so peer figures reach the reader only through this class's own
		// rendering, never through the model's words.
		String contextText = ContextRenderer.render(context, glossary::label);
		String modelContext = ContextRenderer.renderForModel(context, glossary::label);
		String facts = renderer.renderEvidence(report);
		String evidence = facts + "\nCONTEXT\n" + modelContext;

		String draft = Progress.step("Writing the interpretation (local model)",
				() -> writeInterpretation(evidence));
		String firstDraft = draft;
		Critique critique = Progress.step("Reviewing the draft interpretation (local model)",
				() -> critic.review(evidence, firstDraft));
		int revisions = 0;
		while (critique.needsRevision() && revisions < MAX_REVISIONS) {
			String toRevise = draft;
			Critique found = critique;
			draft = Progress.step("Revising the interpretation (local model)",
					() -> writeInterpretation(revisionPrompt(evidence, toRevise, found)));
			revisions++;
			String revised = draft;
			critique = Progress.step("Reviewing the revised interpretation (local model)",
					() -> critic.review(evidence, revised));
		}
		String interpretation = draft;

		Progress.Step checking = Progress.start("Checking every figure and claim in the interpretation against the data");
		NumericClaimVerifier.Result figures = numericVerifier.verify(interpretation, evidence);
		UnsupportedClaimFilter.Result screened = claimFilter.filter(figures.text(), evidence, report.withheldTopics());
		PositionClaimFilter.Result positions = PositionClaimFilter.filter(screened.text());
		int removed = figures.removedStatements().size() + screened.removedSentences().size()
				+ positions.removedStatements().size();
		checking.done(removed == 0 ? "Checked the interpretation: every figure and claim is supported by the data"
				: "Checked the interpretation: %d sentence(s) removed as unsupported".formatted(removed));
		String text = positions.text();
		if (!figures.removedStatements().isEmpty()) {
			text += "\n\n_%d sentence(s) were removed from this interpretation because their figures do not appear in the evidence (%s)._"
					.formatted(figures.removedStatements().size(), String.join(", ", figures.ungroundedFigures()));
		}
		if (!screened.removedSentences().isEmpty()) {
			text += "\n\n_%d sentence(s) were removed from this interpretation because they made claims not supported by the data: %s_"
					.formatted(screened.removedSentences().size(), String.join(" | ", screened.removedSentences()));
		}
		if (!positions.removedStatements().isEmpty()) {
			text += "\n\n_%d sentence(s) were removed from this interpretation because they compare the company with other companies or place a value among years or companies, which only the Context section gives, as calculated and verified by the application: %s_"
					.formatted(positions.removedStatements().size(), String.join(" | ", positions.removedStatements()));
		}
		if (critique.needsRevision()) {
			text += "\n\n_The critic still found issues with this interpretation, but the revision limit (%d) was already reached; this is the final draft after %d revision(s)._"
					.formatted(MAX_REVISIONS, revisions);
		}
		String rendered = renderer.render(report, text, glossary, contextText);
		String bearCase = Progress.step("Writing the bear case (local model)", () -> bearCaseWriter.write(report));
		String withBearCase = rendered + "\n## Bear case\n\n_What could go wrong, drawn only from the data gaps, "
				+ "data-quality issues and failed screening rules above - never from the facts or observations._\n\n"
				+ bearCase + "\n";
		return withBearCase + "\n" + glossary.section(glossary.termsIn(withBearCase));
	}

	private String writeInterpretation(String prompt) {
		return ollama.call(() -> stripThinking(interpretationClient.prompt().user(prompt).call().content()));
	}

	/** The evidence, the previous draft and exactly what the critic found, so the revision fixes those issues
	 * rather than rewriting from scratch. */
	private static String revisionPrompt(String evidence, String draft, Critique critique) {
		StringBuilder prompt = new StringBuilder("A reviewer found problems with your draft INTERPRETATION below. ")
				.append("Revise it using only the evidence pack, fixing these issues. Output only the revised ")
				.append("INTERPRETATION text, following the same rules as before (2-4 short paragraphs, no headings).\n\n");
		appendIssues(prompt, "Unsupported claims", critique.unsupportedClaims());
		appendIssues(prompt, "Data gaps not mentioned", critique.missedDataGaps());
		appendIssues(prompt, "Risks not mentioned", critique.missingRisks());
		return prompt.append("\nEVIDENCE PACK\n").append(evidence).append("\n\nPREVIOUS DRAFT\n").append(draft).toString();
	}

	private static void appendIssues(StringBuilder prompt, String label, List<String> issues) {
		if (!issues.isEmpty()) {
			prompt.append(label).append(":\n");
			issues.forEach(issue -> prompt.append("- ").append(issue).append('\n'));
		}
	}

	/** Qwen3 can emit its reasoning inline; it is not part of the report. */
	public static String stripThinking(String text) {
		return text == null ? null : text.replaceAll("(?s)<think>.*?</think>", "").strip();
	}
}
