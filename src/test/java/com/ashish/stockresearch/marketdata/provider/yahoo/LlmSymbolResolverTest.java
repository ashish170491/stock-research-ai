package com.ashish.stockresearch.marketdata.provider.yahoo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmSymbolResolverTest {

    private ChatClient.Builder chatClientBuilder;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec callResponseSpec;

    @BeforeEach
    void setUp() {
        chatClientBuilder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    @Test
    void returnsCandidateTickerFromCleanResponse() {
        when(callResponseSpec.content()).thenReturn("BHARTIARTL");

        Optional<String> result = new LlmSymbolResolver(chatClientBuilder).resolveTickerGuess("Bharti Airtel");

        assertThat(result).contains("BHARTIARTL");
    }

    @Test
    void normalizesWhitespaceAndCase() {
        when(callResponseSpec.content()).thenReturn("  bhartiartl  \n");

        Optional<String> result = new LlmSymbolResolver(chatClientBuilder).resolveTickerGuess("Bharti Airtel");

        assertThat(result).contains("BHARTIARTL");
    }

    @Test
    void returnsEmptyWhenModelRespondsUnknown() {
        when(callResponseSpec.content()).thenReturn("UNKNOWN");

        Optional<String> result = new LlmSymbolResolver(chatClientBuilder).resolveTickerGuess("Some Obscure Company");

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenResponseIsNotAPlausibleTicker() {
        when(callResponseSpec.content()).thenReturn("I'm not sure, but it might be XYZ or ABC.");

        Optional<String> result = new LlmSymbolResolver(chatClientBuilder).resolveTickerGuess("Some Company");

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenResponseIsBlank() {
        when(callResponseSpec.content()).thenReturn("   ");

        Optional<String> result = new LlmSymbolResolver(chatClientBuilder).resolveTickerGuess("Some Company");

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenChatClientThrows() {
        when(chatClient.prompt(anyString())).thenThrow(new RuntimeException("Ollama unreachable"));

        Optional<String> result = new LlmSymbolResolver(chatClientBuilder).resolveTickerGuess("Some Company");

        assertThat(result).isEmpty();
    }

    @Test
    void ignoresTheModelsInlineReasoning() {
        when(callResponseSpec.content()).thenReturn("<think>Bharti Airtel trades as BHARTIARTL on NSE.</think>\nBHARTIARTL");

        Optional<String> result = new LlmSymbolResolver(chatClientBuilder).resolveTickerGuess("Bharti Airtel");

        assertThat(result).contains("BHARTIARTL");
    }
}
