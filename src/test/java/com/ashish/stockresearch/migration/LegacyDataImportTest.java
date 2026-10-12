package com.ashish.stockresearch.migration;

import com.ashish.stockresearch.TestPostgres;
import com.ashish.stockresearch.documents.DocType;
import com.ashish.stockresearch.documents.DocumentIngestionService;
import com.ashish.stockresearch.documents.DocumentsProperties;
import com.ashish.stockresearch.documents.FixedEmbeddingModel;
import com.ashish.stockresearch.research.model.DataStatus;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.FundamentalsSnapshotRepository;
import com.ashish.stockresearch.screening.ScreeningMetric;
import com.ashish.stockresearch.screening.SnapshotRun;
import com.ashish.stockresearch.screening.Universe;
import com.ashish.stockresearch.watchlist.SavedScreenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;

import static com.ashish.stockresearch.screening.ScreeningFixtures.stock;
import static com.ashish.stockresearch.screening.ScreeningFixtures.withStatus;
import static org.assertj.core.api.Assertions.assertThat;

/** The one-off copy of an earlier version's H2 file and JSON document store into PostgreSQL. */
class LegacyDataImportTest {

    private static final Instant NOW = Instant.parse("2026-10-09T13:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 9);

    @TempDir
    Path dir;

    private final EmbeddedDatabase target = TestPostgres.newDatabase();

    @AfterEach
    void dropDatabase() {
        target.shutdown();
    }

    /** An H2 file as earlier versions wrote it: their own schema script, filled by the same repositories. */
    private String legacyH2File() throws Exception {
        String file = dir.resolve("stock-research").toString();
        DataSource h2 = new DriverManagerDataSource("jdbc:h2:file:" + file, "sa", "");
        try (Connection connection = h2.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("legacy/h2-screening-schema.sql"));
        }
        FundamentalsSnapshotRepository snapshots = new FundamentalsSnapshotRepository(h2);
        for (int day = 0; day < 2; day++) {
            SnapshotRun run = snapshots.startRun(Universe.NIFTY50, DATE.plusDays(day), NOW.plusSeconds(day * 86_400L), 3);
            snapshots.save(run.id(), stock("TCS", IndustryGroup.IT_SERVICES).filedFiscalYears(6)
                    .with(ScreeningMetric.ROE_PERCENT, "45.12").with(ScreeningMetric.TRAILING_PE_MULTIPLE, "24.5")
                    .with(withStatus(ScreeningMetric.NET_PROFIT_CAGR_5Y_PERCENT, DataStatus.DATA_CONFLICT, null)).build());
            snapshots.save(run.id(), stock("HDFCBANK", IndustryGroup.BANK).with(ScreeningMetric.ROE_PERCENT, "14.03")
                    .build());
            snapshots.recordFailure(run.id(), "XYZ", "the profile resolved to XYZ.BO, not XYZ's own NSE listing");
            snapshots.finishRun(run.id(), SnapshotRun.Status.COMPLETED, 2, 1, NOW.plusSeconds(day * 86_400L + 200));
        }
        new SavedScreenRepository(h2).save("IT quality", Universe.NIFTY50, "quality-compounder", "IT_SERVICES");
        new JdbcTemplate(h2).execute("SHUTDOWN");
        return file;
    }

    /** The JSON file SimpleVectorStore wrote in earlier versions. */
    private String legacyDocuments() {
        SimpleVectorStore store = SimpleVectorStore.builder(new FixedEmbeddingModel()).build();
        new DocumentIngestionService(store).ingest(new ClassPathResource(
                "fixtures/documents/hdfcbank-annual-report-fy2026.pdf"), "HDFCBANK", DocType.ANNUAL_REPORT, 2026,
                "HDFC Bank Annual Report FY2026.pdf");
        String file = dir.resolve("vector-store.json").toString();
        store.save(new java.io.File(file));
        return file;
    }

    private LegacyDataImport importer(String h2File, String documents) {
        PgVectorStore vectors = PgVectorStore.builder(new JdbcTemplate(target), new FixedEmbeddingModel(768))
                .dimensions(768).initializeSchema(false).build();
        return new LegacyDataImport(target, vectors, JsonMapper.builder().build(), new DocumentsProperties(documents),
                h2File);
    }

    @Test
    void copiesEveryRowWithItsIdsAndTheSnapshotsReadBackTheSame() throws Exception {
        String h2File = legacyH2File();
        FundamentalsSnapshotRepository before = new FundamentalsSnapshotRepository(
                new DriverManagerDataSource("jdbc:h2:file:" + h2File + ";ACCESS_MODE_DATA=r", "sa", ""));
        long latest = before.latestCompletedRun(Universe.NIFTY50).orElseThrow().id();

        LegacyDataImport.Report report = importer(h2File, legacyDocuments()).importAll();

        assertThat(report.skipped()).isFalse();
        assertThat(report.rows()).containsEntry("snapshot_run", 2).containsEntry("fundamentals_snapshot", 4)
                .containsEntry("snapshot_metric", 8).containsEntry("snapshot_failure", 2).containsEntry("saved_screen", 1);
        assertThat(report.documents()).isPositive();
        FundamentalsSnapshotRepository after = new FundamentalsSnapshotRepository(target);
        assertThat(after.latestCompletedRun(Universe.NIFTY50)).isEqualTo(before.latestCompletedRun(Universe.NIFTY50));
        assertThat(after.previousCompletedRun(Universe.NIFTY50)).isEqualTo(before.previousCompletedRun(Universe.NIFTY50));
        assertThat(after.snapshots(latest)).isEqualTo(before.snapshots(latest));
        assertThat(after.failures(latest)).isEqualTo(before.failures(latest));
        assertThat(new SavedScreenRepository(target).findByName("IT quality")).isPresent();
        assertThat(new JdbcTemplate(target).queryForObject("SELECT count(*) FROM vector_store", Integer.class))
                .isEqualTo(report.documents());
    }

    // The identity sequences continue after the copied rows, so the next nightly run does not collide with them.
    @Test
    void newRowsAfterTheImportTakeTheNextIds() throws Exception {
        importer(legacyH2File(), dir.resolve("none.json").toString()).importAll();

        SnapshotRun next = new FundamentalsSnapshotRepository(target).startRun(Universe.NIFTY50, DATE.plusDays(3),
                NOW.plusSeconds(3 * 86_400L), 50);

        assertThat(next.id()).isEqualTo(3);
    }

    @Test
    void neverImportsTwiceOrIntoADatabaseInUse() throws Exception {
        String h2File = legacyH2File();
        importer(h2File, dir.resolve("none.json").toString()).importAll();

        LegacyDataImport.Report again = importer(h2File, dir.resolve("none.json").toString()).importAll();

        assertThat(again.skipped()).isTrue();
        assertThat(again.reason()).contains("already holds").contains("snapshot_run");
        assertThat(new JdbcTemplate(target).queryForObject("SELECT count(*) FROM snapshot_run", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void aMissingH2FileIsReportedNotCreated() {
        String missing = dir.resolve("nothing-here").toString();

        LegacyDataImport.Report report = importer(missing, dir.resolve("none.json").toString()).importAll();

        assertThat(report.skipped()).isTrue();
        assertThat(report.reason()).startsWith("no H2 file at");
        assertThat(dir.resolve("nothing-here.mv.db")).doesNotExist();
    }
}
