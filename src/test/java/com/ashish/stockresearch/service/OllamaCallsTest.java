package com.ashish.stockresearch.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaCallsTest {

    private final OllamaCalls ollama = new OllamaCalls("llama3.1:8b", "http://gpu-box:11434");

    @Test
    void returnsTheCallsResult() {
        assertThat(ollama.call(() -> "answer")).isEqualTo("answer");
    }

    @Test
    void reportsAnUnreachableOllamaAs503NamingTheConfiguredModelAndHost() {
        assertThatThrownBy(() -> ollama.call(() -> {
            throw new ResourceAccessException("Connection refused");
        }))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getReason()).contains("ollama run llama3.1:8b").contains("http://gpu-box:11434");
                })
                .hasCauseInstanceOf(ResourceAccessException.class);
    }

    @Test
    void letsOtherFailuresThrough() {
        assertThatThrownBy(() -> ollama.call(() -> {
            throw new IllegalStateException("bad json");
        })).isInstanceOf(IllegalStateException.class);
    }
}
