package com.ashish.stockresearch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Starts the whole application context, so a wiring mistake - a bean Spring cannot construct, a
 * missing property - fails the build rather than the first start. Nothing here calls Ollama or
 * Yahoo: beans are created, not used.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class StockResearchApplicationTests {

    @Test
    void contextLoads() {
    }
}
