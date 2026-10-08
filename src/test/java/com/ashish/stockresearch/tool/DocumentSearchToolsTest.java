package com.ashish.stockresearch.tool;

import com.ashish.stockresearch.documents.DocType;
import com.ashish.stockresearch.documents.DocumentIngestionService;
import com.ashish.stockresearch.documents.DocumentsProperties;
import com.ashish.stockresearch.documents.FixedEmbeddingModel;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code searchCompanyDocuments}: filters by symbol (S7-4), carries citations the model can show
 * (S7-5), and its figures are grounded as evidence and attributed to the document, not Yahoo
 * (S7-6). A deterministic embedding model stands in for Ollama (S7-7).
 */
class DocumentSearchToolsTest {

    private static final Resource FIXTURE = new ClassPathResource("fixtures/documents/hdfcbank-annual-report-fy2026.pdf");

    @TempDir
    Path tempDir;

    private DocumentSearchTools tools;
    private ToolUsage usage;
    private ToolContext context;

    @BeforeEach
    void ingestForTwoCompanies() {
        SimpleVectorStore store = SimpleVectorStore.builder(new FixedEmbeddingModel()).build();
        DocumentIngestionService ingestion = new DocumentIngestionService(store,
                new DocumentsProperties(tempDir.resolve("vector-store.json").toString()));
        ingestion.ingest(FIXTURE, "HDFCBANK", DocType.ANNUAL_REPORT, 2026, "HDFC Bank Annual Report FY2026.pdf");
        ingestion.ingest(FIXTURE, "ICICIBANK", DocType.ANNUAL_REPORT, 2026, "ICICI Bank Annual Report FY2026.pdf");

        tools = new DocumentSearchTools(store);
        usage = new ToolUsage();
        context = new ToolContext(usage.asToolContext());
    }

    // S7-4
    @Test
    void neverReturnsAChunkIngestedForAnotherSymbol() {
        String text = tools.searchCompanyDocuments("HDFCBANK", "Gross NPA ratio provisioning", context);

        assertThat(text).doesNotContain("ICICIBANK");
        assertThat(usage.calls()).hasSize(1);
        assertThat(usage.calls().get(0).symbol()).isEqualTo("HDFCBANK");
    }

    // S7-5
    @Test
    void resultsCarryCitationsRecordedForTheAnswerToShow() {
        String text = tools.searchCompanyDocuments("HDFCBANK", "Gross NPA ratio", context);

        assertThat(text).contains("HDFCBANK").contains("ANNUAL_REPORT").contains("FY2026").contains("p. 2");
        assertThat(usage.citations()).isNotEmpty();
        assertThat(usage.citations().get(0).label()).contains("HDFCBANK").contains("FY2026").contains("p. 2");
    }

    // S7-6
    @Test
    void figuresAreAttributedToTheDocumentNotYahooAndGroundTheAnswer() {
        String text = tools.searchCompanyDocuments("HDFCBANK", "Gross NPA ratio", context);

        assertThat(text).contains("1.24%").doesNotContain("Yahoo");
        NumericClaimVerifier.Result verified = new NumericClaimVerifier()
                .verify("The Gross NPA ratio was 1.24%.", usage.evidence());
        assertThat(verified.text()).contains("1.24%");
    }

    @Test
    void saysSoWhenNothingWasIngestedForTheSymbol() {
        String text = tools.searchCompanyDocuments("TCS", "Gross NPA ratio", context);

        assertThat(text).contains("status NO_MATCH").contains("No ingested documents matched");
        assertThat(usage.citations()).isEmpty();
    }
}
