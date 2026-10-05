package com.ashish.stockresearch.research.comparison;

import com.ashish.stockresearch.context.RatioContextService;
import com.ashish.stockresearch.context.ReportContext;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.ResearchReportRenderer;
import com.ashish.stockresearch.research.report.ResearchReportService;
import com.ashish.stockresearch.research.report.StockResearchReport;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.service.OllamaCalls;
import com.ashish.stockresearch.service.ResearchInstructions;
import com.ashish.stockresearch.service.ResearchReportWriter;
import com.ashish.stockresearch.trace.Progress;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Builds and renders a comparison of 2 to 5 companies: their reports are fetched at once, each on its own
 * virtual thread, aligned on the same metrics and periods by {@link ComparisonBuilder}, and a single
 * tool-free model call writes the narrative from that aligned table only - never from the companies'
 * separate reports, and never a second LLM call per company as the previous COMPARE behaviour made.
 */
@Service
public class ComparisonService {

    static final String COMPARISON_PROMPT = """
            You are a financial research assistant. You are given a comparison table of two to five Indian
            listed companies, aligned on the same metrics and the same fiscal periods, with each company's
            figure beside its own industry group's median where the table gives one.

            Write only a short narrative, 2-4 short paragraphs: what the table shows about how these companies
            differ on the figures given, what a NOT_COMPARABLE or UNAVAILABLE cell means for the comparison, and
            what a reader should check next. Use only the table below. Output plain paragraphs, with no
            headings. Quote figures exactly as given and calculate nothing.

            """ + ResearchInstructions.EVIDENCE_RULES;

    private final ResearchReportService researchReportService;
    private final RatioContextService contextService;
    private final ComparisonBuilder builder;
    private final MetricGlossary glossary;
    private final NumericClaimVerifier numericVerifier;
    private final UnsupportedClaimFilter claimFilter;
    private final OllamaCalls ollama;
    private final ChatClient narrativeClient;

    public ComparisonService(ChatClient.Builder chatClientBuilder,
            Advisor conversationTraceAdvisor,
            ResearchReportService researchReportService,
            RatioContextService contextService,
            ComparisonBuilder builder,
            MetricGlossary glossary,
            NumericClaimVerifier numericVerifier,
            UnsupportedClaimFilter claimFilter,
            OllamaCalls ollama) {
        this.researchReportService = researchReportService;
        this.contextService = contextService;
        this.builder = builder;
        this.glossary = glossary;
        this.numericVerifier = numericVerifier;
        this.claimFilter = claimFilter;
        this.ollama = ollama;
        this.narrativeClient = chatClientBuilder.clone()
                .defaultSystem(COMPARISON_PROMPT)
                .defaultOptions(OllamaChatOptions.builder().disableThinking())
                .defaultAdvisors(conversationTraceAdvisor)
                .build();
    }

    public String compare(List<String> symbols) {
        List<ReportContext> contexts = new ArrayList<>();
        List<ComparisonTable.Gap> gaps = new ArrayList<>();
        for (FetchResult result : fetchAll(symbols)) {
            if (result.report() != null) {
                contexts.add(contextService.build(result.report(), ResearchReportRenderer.disputed(result.report())));
            } else {
                gaps.add(new ComparisonTable.Gap(result.symbol(), result.failure()));
            }
        }
        if (contexts.size() < ComparisonBuilder.MIN_COMPANIES) {
            return "A comparison needs at least %d companies with data; only %d could be built (%s)."
                    .formatted(ComparisonBuilder.MIN_COMPANIES, contexts.size(), gaps.stream()
                            .map(gap -> gap.symbol() + ": " + gap.reason()).collect(Collectors.joining("; ")));
        }
        ComparisonTable table = builder.build(contexts, gaps);
        String tableText = ComparisonRenderer.render(table);
        String narrative = Progress.step("Writing the comparison (local model)", () -> ResearchReportWriter.stripThinking(
                ollama.call(() -> narrativeClient.prompt().user(tableText).call().content())));
        Progress.Step checking = Progress.start("Checking every figure and claim in the comparison against the table");
        NumericClaimVerifier.Result figures = numericVerifier.verify(narrative, tableText);
        UnsupportedClaimFilter.Result screened = claimFilter.filter(figures.text(), tableText);
        int removed = figures.removedStatements().size() + screened.removedSentences().size();
        checking.done(removed == 0 ? "Checked the comparison: every figure and claim is supported by the table"
                : "Checked the comparison: %d sentence(s) removed as unsupported".formatted(removed));
        String text = screened.text();
        if (!figures.removedStatements().isEmpty()) {
            text += "\n\n_%d sentence(s) were removed from this narrative because their figures do not appear in the table (%s)._"
                    .formatted(figures.removedStatements().size(), String.join(", ", figures.ungroundedFigures()));
        }
        if (!screened.removedSentences().isEmpty()) {
            text += "\n\n_%d sentence(s) were removed from this narrative because they made claims not supported by the table: %s_"
                    .formatted(screened.removedSentences().size(), String.join(" | ", screened.removedSentences()));
        }
        String rendered = tableText + "\n" + text + "\n";
        return rendered + "\n" + glossary.section(glossary.termsIn(rendered));
    }

    private record FetchResult(String symbol, StockResearchReport report, String failure) {
    }

    /** Every company's report is fetched at once, each on its own virtual thread - the companies are
     * independent, so the comparison waits for the slowest rather than for their sum. */
    private List<FetchResult> fetchAll(List<String> symbols) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<FetchResult>> futures = symbols.stream()
                    .map(symbol -> CompletableFuture.supplyAsync(() -> fetch(symbol), executor)).toList();
            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    private FetchResult fetch(String symbol) {
        try {
            return new FetchResult(symbol, Progress.step("Building the report on " + symbol,
                    () -> researchReportService.build(symbol)), null);
        } catch (RuntimeException e) {
            return new FetchResult(symbol, null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
