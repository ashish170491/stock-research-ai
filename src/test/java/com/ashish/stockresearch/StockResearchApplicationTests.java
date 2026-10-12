package com.ashish.stockresearch;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.FileAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the whole application context, so a wiring mistake - a bean Spring cannot construct, a
 * missing property, a screening preset that breaks a sector rule - fails the build rather than the first
 * start. Nothing here calls Ollama or Yahoo: beans are created, not used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // the test container's PostgreSQL, never compose.yaml's, and nothing under ./data
        properties = {
                "spring.docker.compose.enabled=false",
                "app.documents.vector-store-path=target/test-data/vector-store.json"
        })
class StockResearchApplicationTests {

    /** A database of its own in the tests' PostgreSQL container; the application's Flyway migrates it on start. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = TestPostgres.newDatabaseUrl();
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> TestPostgres.container().getUsername());
        registry.add("spring.datasource.password", () -> TestPostgres.container().getPassword());
    }

    @Test
    void contextLoads() {
    }

    // logback-test.xml: a test run never writes (or rolls over) the running application's log file.
    @Test
    void theTestsLogToTheConsoleOnlyNeverToTheApplicationsLogFile() {
        Logger root = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(Logger.ROOT_LOGGER_NAME);
        List<Object> appenders = new ArrayList<>();
        root.iteratorForAppenders().forEachRemaining(appenders::add);

        assertThat(appenders).isNotEmpty().noneMatch(appender -> appender instanceof FileAppender<?>);
    }
}
