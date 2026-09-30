package com.ashish.stockresearch;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.FileAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the whole application context, so a wiring mistake - a bean Spring cannot construct, a
 * missing property, a screening preset that breaks a sector rule - fails the build rather than the first
 * start. Nothing here calls Ollama or Yahoo: beans are created, not used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // the snapshot database in memory, so the build never writes ./data
        properties = "spring.datasource.url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1")
class StockResearchApplicationTests {

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
