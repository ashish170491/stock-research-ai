package com.ashish.stockresearch.tool;

import com.ashish.stockresearch.screening.ScreeningOutcome;
import com.ashish.stockresearch.screening.ScreeningRenderer;
import com.ashish.stockresearch.screening.ScreeningService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// S3-8, INV-8, INV-9
class ScreeningToolsTest {

    private static String description() {
        Method method = Arrays.stream(ScreeningTools.class.getMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class)).findFirst().orElseThrow();
        return method.getAnnotation(Tool.class).description();
    }

    @Test
    void describesWhatItReturnsWhatItDoesNotAndThatItsFiguresAreQuotedAsGiven() {
        assertThat(description())
                .contains("nightly fundamentals snapshot")
                .contains("PASS").contains("FAIL").contains("INSUFFICIENT_DATA")
                .contains("It does NOT return live prices or live data")
                .contains("a recommendation, rating, score, fair value or price target")
                .contains("quality-compounder").doesNotContain("buy")
                .contains("never recalculate them, re-rank the stocks, or apply thresholds of your own");
    }

    @Test
    void recordsTheCallWithNoCompanyAndReturnsTheRenderedTable() {
        ScreeningService service = mock(ScreeningService.class);
        ScreeningOutcome none = new ScreeningOutcome(false, ScreeningOutcome.Status.NO_SNAPSHOT, "No snapshot yet",
                null, null, null, java.util.List.of(), 0);
        when(service.screen("quality-compounder", "NIFTY50", null)).thenReturn(none);
        ToolUsage usage = new ToolUsage();

        String text = new ScreeningTools(service, new ScreeningRenderer())
                .screenStocks("quality-compounder", "NIFTY50", null, new ToolContext(usage.asToolContext()));

        assertThat(text).isEqualTo("TOOL RESULT: screenStocks - status NO_SNAPSHOT\nNo screen: No snapshot yet\n");
        assertThat(usage.calls()).singleElement().satisfies(call -> {
            assertThat(call.tool()).isEqualTo("screenStocks");
            // a screen names no company, so none is remembered for follow-up questions
            assertThat(call.symbol()).isNull();
            assertThat(call.status()).isEqualTo("NO_SNAPSHOT");
            assertThat(call.evidence()).isEqualTo(text);
        });
    }

    // A Nifty 500 table would fill the model's context: the tool says where that screen is answered instead.
    @Test
    void leavesTheNifty500ToAScreenAskedForInWords() {
        ScreeningService service = mock(ScreeningService.class);
        ToolUsage usage = new ToolUsage();

        String text = new ScreeningTools(service, new ScreeningRenderer())
                .screenStocks("dividend", "Nifty 500", null, new ToolContext(usage.asToolContext()));

        assertThat(text).startsWith("TOOL RESULT: screenStocks - status UNKNOWN_UNIVERSE\nNo screen: this tool screens "
                + "the Nifty 50 only. The Nifty 500 is screened when the user asks for the screen in words");
        org.mockito.Mockito.verifyNoInteractions(service);
        assertThat(usage.calls()).singleElement().satisfies(call -> assertThat(call.status()).isEqualTo("UNKNOWN_UNIVERSE"));
        assertThat(description()).contains("does NOT screen the Nifty 500");
    }
}
