package com.ashish.stockresearch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

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
}
