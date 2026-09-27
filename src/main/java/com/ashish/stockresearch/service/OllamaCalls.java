package com.ashish.stockresearch.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.util.function.Supplier;

/**
 * Runs a model call and turns "Ollama is not reachable" into a 503 that says
 * how to fix it, naming the model and host this application is configured for.
 */
@Component
public class OllamaCalls {

	private final String model;
	private final String baseUrl;

	public OllamaCalls(@Value("${spring.ai.ollama.chat.options.model:unknown}") String model,
			@Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
		this.model = model;
		this.baseUrl = baseUrl;
	}

	public <T> T call(Supplier<T> call) {
		try {
			return call.get();
		} catch (ResourceAccessException ex) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Could not reach Ollama. Make sure it is running (ollama run %s) at %s".formatted(model, baseUrl),
					ex);
		}
	}
}
