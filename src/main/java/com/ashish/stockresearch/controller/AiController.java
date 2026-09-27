package com.ashish.stockresearch.controller;

import com.ashish.stockresearch.agent.Intent;
import com.ashish.stockresearch.service.AiService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
public class AiController {

	private static final Pattern CONVERSATION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	private final AiService aiService;

	public AiController(AiService aiService) {
		this.aiService = aiService;
	}

	/** @param conversationId continues that conversation; omit it to start a new one */
	public record ChatRequest(String conversationId, String message) {
	}

	/**
	 * @param intent        how the message was routed
	 * @param companies     the companies the answer is about
	 * @param elapsedMillis how long the answer took, end to end
	 */
	public record ChatReply(String conversationId, Intent intent, List<String> companies, String answer,
			long elapsedMillis) {
	}

	@PostMapping("/api/ai/chat")
	public ChatReply chat(@RequestBody ChatRequest request) {
		if (request == null || request.message() == null || request.message().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
		}
		String conversationId = request.conversationId() == null || request.conversationId().isBlank()
				? UUID.randomUUID().toString()
				: request.conversationId();
		if (!CONVERSATION_ID.matcher(conversationId).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"conversationId must be 1-64 letters, digits, '-' or '_'");
		}
		long start = System.nanoTime();
		AiService.ChatAnswer answer = aiService.chat(conversationId, request.message().strip());
		return new ChatReply(conversationId, answer.intent(), answer.companies(), answer.text(),
				(System.nanoTime() - start) / 1_000_000);
	}
}
