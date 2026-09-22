package com.ashish.stockresearch.service;

import com.ashish.stockresearch.tool.StockPriceTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class AiService {

	private final ChatClient chatClient;

	public AiService(ChatClient.Builder chatClientBuilder, StockPriceTool stockPriceTool) {
		this.chatClient = chatClientBuilder
				.defaultTools(stockPriceTool)
				.build();
	}

	public String chat(String message) {
		try {
			return chatClient.prompt()
					.user(message)
					.call()
					.content();
		} catch (ResourceAccessException ex) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Could not reach Ollama. Make sure it is running (ollama run qwen3:8b) at http://localhost:11434",
					ex);
		}
	}

}
