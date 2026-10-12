package com.ashish.stockresearch.documents;

import com.ashish.stockresearch.TestPostgres;
import com.ashish.stockresearch.tool.DocumentSearchTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ingested documents in PostgreSQL (pgvector), in the table Flyway creates - as the application stores them. */
class PgVectorDocumentsTest {

    private static final Resource FIXTURE = new ClassPathResource("fixtures/documents/hdfcbank-annual-report-fy2026.pdf");

    private final EmbeddedDatabase database = TestPostgres.newDatabase();

    @AfterEach
    void dropDatabase() {
        database.shutdown();
    }

    /** A store as the application's auto-configuration builds it: Flyway's table, checked, never created. */
    private PgVectorStore store(int dimensions) {
        PgVectorStore store = PgVectorStore.builder(new JdbcTemplate(database), new FixedEmbeddingModel(dimensions))
                .dimensions(dimensions).distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW).initializeSchema(false).vectorTableValidationsEnabled(true)
                .build();
        store.afterPropertiesSet();
        return store;
    }

    // S7-5: a search is filtered to one company, and every passage carries its citation.
    @Test
    void aSearchReturnsOnlyThatCompanysPassagesWithTheirCitation() {
        PgVectorStore store = store(768);
        DocumentIngestionService ingestion = new DocumentIngestionService(store);
        ingestion.ingest(FIXTURE, "HDFCBANK", DocType.ANNUAL_REPORT, 2026, "HDFC Bank Annual Report FY2026.pdf");
        ingestion.ingest(FIXTURE, "ICICIBANK", DocType.ANNUAL_REPORT, 2026, "Another bank's report.pdf");

        List<DocumentSearchTools.Passage> passages = new DocumentSearchTools(store)
                .passages("hdfcbank", "Gross NPA ratio", 10);

        assertThat(passages).isNotEmpty().allSatisfy(passage ->
                assertThat(passage.citation()).startsWith("HDFCBANK — ANNUAL_REPORT FY2026, p. "));
    }

    // S7-3: what was ingested is a row, so a store built after a restart finds it.
    @Test
    void documentsSurviveARestart() {
        new DocumentIngestionService(store(768))
                .ingest(FIXTURE, "HDFCBANK", DocType.ANNUAL_REPORT, 2026, "HDFC Bank Annual Report FY2026.pdf");

        assertThat(new DocumentSearchTools(store(768)).passages("HDFCBANK", "Capital Adequacy Ratio", 5)).isNotEmpty();
    }

    @Test
    void ingestingTheSameDocumentAgainReplacesItsRows() {
        DocumentIngestionService ingestion = new DocumentIngestionService(store(768));
        int chunks = ingestion.ingest(FIXTURE, "HDFCBANK", DocType.ANNUAL_REPORT, 2026,
                "HDFC Bank Annual Report FY2026.pdf").chunks();

        ingestion.ingest(FIXTURE, "HDFCBANK", DocType.ANNUAL_REPORT, 2026, "HDFC Bank Annual Report FY2026.pdf");

        assertThat(new JdbcTemplate(database).queryForObject("SELECT count(*) FROM vector_store", Integer.class))
                .isEqualTo(chunks);
    }

    // A different embedding size stops the application at startup instead of corrupting the search.
    @Test
    void anEmbeddingModelOfAnotherSizeIsRefusedAtStartup() {
        assertThatThrownBy(() -> store(1024)).hasMessageContaining("dimension");
    }
}
