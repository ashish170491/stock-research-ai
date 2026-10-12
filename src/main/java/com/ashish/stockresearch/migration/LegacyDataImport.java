package com.ashish.stockresearch.migration;

import com.ashish.stockresearch.documents.DocumentsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copies, once, what earlier versions kept outside PostgreSQL: the screening snapshots and saved screens of the H2
 * file ({@code app.migrate.h2-file}, e.g. {@code ./data/stock-research}), and the ingested documents of the JSON
 * vector store ({@code app.documents.vector-store-path}). Run it by starting the application with that property:
 * <pre>./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.migrate.h2-file=./data/stock-research</pre>
 * Rows keep their ids, in one transaction: either everything is copied and every table's count matches the
 * source's, or nothing is. It never runs into a database that already holds snapshots, screens or documents, so
 * leaving the property set is harmless. The H2 and JSON files are only read, never changed.
 */
@Component
@ConditionalOnProperty("app.migrate.h2-file")
public class LegacyDataImport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyDataImport.class);

    /** The tables in foreign-key order, each with the columns both versions have. */
    static final Map<String, List<String>> TABLES = new LinkedHashMap<>();
    /** The tables whose ids are identity values, so their sequence must continue after the copied rows. */
    private static final List<String> IDENTITY = List.of("snapshot_run", "fundamentals_snapshot", "saved_screen",
            "snapshot_failure");
    private static final int BATCH = 1_000;

    static {
        TABLES.put("snapshot_run", List.of("id", "universe", "snapshot_date", "started_at", "finished_at", "status",
                "symbols_requested", "symbols_stored", "symbols_failed"));
        TABLES.put("fundamentals_snapshot", List.of("id", "run_id", "symbol", "company_name", "industry_group",
                "classification_basis", "snapshot_date", "captured_at", "filed_fiscal_years"));
        TABLES.put("snapshot_metric", List.of("snapshot_id", "metric", "metric_value", "metric_unit", "display",
                "data_status", "status_reason", "period_label", "source", "calculation", "unchecked_reason"));
        // the H2 table had no id: PostgreSQL gives each copied failure a new one
        TABLES.put("snapshot_failure", List.of("run_id", "symbol", "reason"));
        TABLES.put("saved_screen", List.of("id", "name", "universe", "preset", "industry_group", "created_at"));
    }

    private final DataSource target;
    private final VectorStore vectorStore;
    private final JsonMapper json;
    private final String h2File;
    private final String vectorStorePath;

    public LegacyDataImport(DataSource target, VectorStore vectorStore, JsonMapper json, DocumentsProperties documents,
                            @Value("${app.migrate.h2-file}") String h2File) {
        this.target = target;
        this.vectorStore = vectorStore;
        this.json = json;
        this.h2File = h2File;
        this.vectorStorePath = documents.vectorStorePath();
    }

    /** What one import copied, table by table, and how many document chunks. */
    public record Report(Map<String, Integer> rows, int documents, boolean skipped, String reason) {
    }

    @Override
    public void run(ApplicationArguments args) {
        Report report = importAll();
        if (report.skipped()) {
            log.warn("Legacy data import skipped: {}", report.reason());
            return;
        }
        log.info("Legacy data import done: {} and {} document chunk(s). The files in data/ were left unchanged and "
                + "can be deleted.", report.rows(), report.documents());
    }

    public Report importAll() {
        JdbcTemplate to = new JdbcTemplate(target);
        for (String table : List.of("snapshot_run", "saved_screen", "vector_store")) {
            Integer rows = to.queryForObject("SELECT count(*) FROM " + table, Integer.class);
            if (rows != null && rows > 0) {
                return new Report(Map.of(), 0, true, ("the database already holds %d row(s) in %s, so the data was "
                        + "imported before or the application has been in use; nothing was copied").formatted(rows, table));
            }
        }
        File source = new File(h2File + ".mv.db");
        if (!source.isFile()) {
            return new Report(Map.of(), 0, true, "no H2 file at " + source.getAbsolutePath());
        }
        DataSource from = new DriverManagerDataSource("jdbc:h2:file:" + new File(h2File).getAbsolutePath()
                + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE", "sa", "");
        List<Document> documents = legacyDocuments();
        return new TransactionTemplate(new DataSourceTransactionManager(target)).execute(status -> {
            Map<String, Integer> copied = new LinkedHashMap<>();
            TABLES.forEach((table, columns) -> copied.put(table, copy(new JdbcTemplate(from), to, table, columns)));
            for (String table : IDENTITY) {
                to.execute(("SELECT setval(pg_get_serial_sequence('%1$s', 'id'), "
                        + "COALESCE((SELECT max(id) FROM %1$s), 0) + 1, false)").formatted(table));
            }
            if (!documents.isEmpty()) {
                // embedded again with the configured model, in the same transaction: all or nothing
                vectorStore.add(documents);
            }
            return new Report(copied, documents.size(), false, null);
        });
    }

    /** Copies one table in batches and checks that the target holds exactly as many rows as the source. */
    private static int copy(JdbcTemplate from, JdbcTemplate to, String table, List<String> columns) {
        String list = String.join(", ", columns);
        String insert = "INSERT INTO %s (%s) VALUES (%s)".formatted(table, list,
                String.join(", ", columns.stream().map(column -> "?").toList()));
        List<Object[]> batch = new ArrayList<>(BATCH);
        int[] total = {0};
        from.query("SELECT %s FROM %s ORDER BY %s".formatted(list, table, columns.get(0)), rs -> {
            Object[] row = new Object[columns.size()];
            for (int i = 0; i < row.length; i++) {
                row[i] = rs.getObject(i + 1);
            }
            batch.add(row);
            if (batch.size() == BATCH) {
                to.batchUpdate(insert, batch);
                total[0] += batch.size();
                batch.clear();
            }
        });
        if (!batch.isEmpty()) {
            to.batchUpdate(insert, batch);
            total[0] += batch.size();
        }
        Integer source = from.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        Integer copied = to.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        if (source == null || !source.equals(copied) || copied != total[0]) {
            throw new IllegalStateException("%s: %s row(s) in the H2 file but %s copied; nothing was imported"
                    .formatted(table, source, copied));
        }
        return copied;
    }

    /** The chunks of the JSON vector store earlier versions wrote: {id: {id, text, metadata, embedding}}. */
    private List<Document> legacyDocuments() {
        Path path = Path.of(vectorStorePath);
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        JsonNode stored = json.readTree(path.toFile());
        List<Document> documents = new ArrayList<>();
        for (JsonNode chunk : stored.values()) {
            Map<String, Object> metadata = json.convertValue(chunk.get("metadata"),
                    json.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            documents.add(Document.builder().id(chunk.get("id").asString()).text(chunk.get("text").asString())
                    .metadata(metadata).build());
        }
        return documents;
    }
}
