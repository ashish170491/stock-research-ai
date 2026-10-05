package com.ashish.stockresearch.research.comparison;

import com.ashish.stockresearch.research.report.ResearchReportService;
import com.ashish.stockresearch.research.report.StockResearchReport;
import com.ashish.stockresearch.service.ResearchReportWriter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A full, verified report for each of the screener's top N results. Every company's data is fetched at once,
 * each on its own virtual thread; the model's interpretation is then written one company after another, since
 * a single local Ollama instance runs one generation at a time unless {@code OLLAMA_NUM_PARALLEL} is raised.
 */
@Component
public class ShortlistDeepDive {

    private final ResearchReportService researchReportService;
    private final ResearchReportWriter researchReportWriter;

    public ShortlistDeepDive(ResearchReportService researchReportService, ResearchReportWriter researchReportWriter) {
        this.researchReportService = researchReportService;
        this.researchReportWriter = researchReportWriter;
    }

    public List<String> run(List<String> symbols) {
        return fetchAll(symbols).stream().map(researchReportWriter::write).toList();
    }

    private List<StockResearchReport> fetchAll(List<String> symbols) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<StockResearchReport>> futures = symbols.stream()
                    .map(symbol -> CompletableFuture.supplyAsync(() -> researchReportService.build(symbol), executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        }
    }
}
