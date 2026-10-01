package com.ashish.stockresearch.trace;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallProgressTest {

    private static AssistantMessage.ToolCall call(String name, String arguments) {
        return new AssistantMessage.ToolCall("1", "function", name, arguments);
    }

    @Test
    void saysInWordsWhatTheModelAskedToFetch() {
        assertThat(ToolCallTracingManager.fetching(List.of(call("getFinancialSummary", "{\"symbol\":\"TCS\"}"),
                call("getValuation", "{\"symbol\": \"TCS\"}"))))
                .isEqualTo("Fetching the financial results of TCS (NSE filings, cross-checked with Yahoo Finance) and "
                        + "the valuation of TCS (Yahoo Finance, checked against filed EPS)");
        assertThat(ToolCallTracingManager.fetching(List.of(call("getStockQuote", "{\"symbol\":\"INFY\"}"))))
                .isEqualTo("Fetching the latest price of INFY (Yahoo Finance)");
        assertThat(ToolCallTracingManager.fetching(List.of())).isEqualTo("Fetching the data the local model asked for");
    }
}
