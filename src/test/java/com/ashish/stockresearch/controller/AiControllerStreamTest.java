package com.ashish.stockresearch.controller;

import com.ashish.stockresearch.agent.Intent;
import com.ashish.stockresearch.service.AiService;
import com.ashish.stockresearch.trace.Progress;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The chat answer streamed as server-sent events: the steps as they happen, then the answer. */
class AiControllerStreamTest {

    private final AiService aiService = mock(AiService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AiController(aiService)).build();

    private String stream(String message) throws Exception {
        MvcResult started = mvc.perform(post("/api/ai/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"conversationId\":\"c1\",\"message\":\"" + message + "\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        started.getAsyncResult(10_000);
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
    }

    @Test
    void streamsEachStepAsItHappensThenTheAnswer() throws Exception {
        when(aiService.chat(eq("c1"), anyString())).thenAnswer(call -> {
            Progress.Step understanding = Progress.start("Understanding your question");
            understanding.done("Understood: a research report on TCS");
            String text = Progress.step("Writing the interpretation (local model)", () -> "The report.");
            return new AiService.ChatAnswer(Intent.FULL_RESEARCH, List.of("TCS"), text);
        });

        String events = stream("Research TCS");

        assertThat(events).contains("event:step\ndata:{\"id\":1,\"status\":\"STARTED\",\"label\":\"Understanding your question\"")
                .contains("{\"id\":1,\"status\":\"DONE\",\"label\":\"Understood: a research report on TCS\"")
                .contains("{\"id\":2,\"status\":\"STARTED\",\"label\":\"Writing the interpretation (local model)\"");
        assertThat(events.indexOf("event:answer")).isGreaterThan(events.lastIndexOf("event:step"));
        assertThat(events).contains("\"intent\":\"FULL_RESEARCH\"").contains("\"answer\":\"The report.\"");
    }

    @Test
    void streamsAFailureAsAnErrorEventWithItsStatus() throws Exception {
        when(aiService.chat(eq("c1"), anyString())).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Could not reach Ollama. Make sure it is running"));

        String events = stream("Research TCS");

        assertThat(events).contains("event:error").contains("\"status\":503")
                .contains("\"message\":\"Could not reach Ollama. Make sure it is running\"")
                .doesNotContain("event:answer");
    }
}
