package com.ashish.stockresearch.documents;

import com.ashish.stockresearch.documents.DocumentIngestionService.IngestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ingesting the fixture annual report (S7-2) and the vector store surviving a restart (S7-3), with
 * a deterministic embedding model so no Ollama is needed (S7-7).
 */
class DocumentIngestionServiceTest {

    private static final Resource FIXTURE = new ClassPathResource("fixtures/documents/hdfcbank-annual-report-fy2026.pdf");

    @TempDir
    Path tempDir;

    private DocumentsProperties properties() {
        return new DocumentsProperties(tempDir.resolve("vector-store.json").toString());
    }

    // S7-2
    @Test
    void ingestsEveryPageAsChunksTaggedWithTheDocumentsMetadata() {
        SimpleVectorStore store = SimpleVectorStore.builder(new FixedEmbeddingModel()).build();
        DocumentIngestionService service = new DocumentIngestionService(store, properties());

        IngestResult result = service.ingest(FIXTURE, "hdfcbank", DocType.ANNUAL_REPORT, 2026,
                "HDFC Bank Annual Report FY2026.pdf");

        assertThat(result.symbol()).isEqualTo("HDFCBANK");
        assertThat(result.pages()).isEqualTo(3);
        assertThat(result.chunks()).isGreaterThanOrEqualTo(3);

        List<Document> hits = store.similaritySearch(SearchRequest.builder()
                .query("Gross NPA ratio provisioning").topK(10)
                .filterExpression("symbol == 'HDFCBANK'").build());
        assertThat(hits).isNotEmpty();
        Document npaChunk = hits.stream().filter(d -> d.getText().contains("Gross")).findFirst()
                .orElseThrow(() -> new AssertionError("no chunk mentions the Gross NPA ratio: " + hits));
        assertThat(npaChunk.getMetadata())
                .containsEntry("symbol", "HDFCBANK")
                .containsEntry("docType", "ANNUAL_REPORT")
                .containsEntry("fiscalYear", 2026)
                .containsEntry("document", "HDFC Bank Annual Report FY2026.pdf")
                .containsEntry("page", 2);
    }

    // S7-3
    @Test
    void theVectorStoreFileSurvivesAFreshStoreLoadingIt() {
        File file = new File(properties().vectorStorePath());
        SimpleVectorStore first = SimpleVectorStore.builder(new FixedEmbeddingModel()).build();
        new DocumentIngestionService(first, properties())
                .ingest(FIXTURE, "HDFCBANK", DocType.ANNUAL_REPORT, 2026, "HDFC Bank Annual Report FY2026.pdf");
        assertThat(file).exists();

        // A fresh store, as a restarted application would build, loading what was saved before.
        SimpleVectorStore restarted = SimpleVectorStore.builder(new FixedEmbeddingModel()).build();
        restarted.load(file);

        List<Document> hits = restarted.similaritySearch(SearchRequest.builder()
                .query("Capital Adequacy Ratio").topK(10)
                .filterExpression("symbol == 'HDFCBANK'").build());
        assertThat(hits).isNotEmpty();
    }
}
