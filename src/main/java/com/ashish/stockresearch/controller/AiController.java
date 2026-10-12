package com.ashish.stockresearch.controller;

import com.ashish.stockresearch.agent.Intent;
import com.ashish.stockresearch.focus.Clarification;
import com.ashish.stockresearch.focus.Suggestion;
import com.ashish.stockresearch.service.AiService;
import com.ashish.stockresearch.trace.Progress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
public class AiController {

	private static final Logger log = LoggerFactory.getLogger(AiController.class);
	private static final Pattern CONVERSATION_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
	/** Longer than any answer takes, so the stream outlives a slow model rather than cutting it off. */
	static final Duration STREAM_TIMEOUT = Duration.ofMinutes(20);

	private final AiService aiService;

	public AiController(AiService aiService) {
		this.aiService = aiService;
	}

	/**
	 * @param conversationId continues that conversation; omit it to start a new one
	 * @param focus          the user's choice from a clarification or a suggestion: answer only these topics about
	 *                       this company; {@code message} is how the choice reads in the conversation
	 */
	public record ChatRequest(String conversationId, String message, Focus focus) {

		public ChatRequest(String conversationId, String message) {
			this(conversationId, message, null);
		}
	}

	public record Focus(String company, List<String> topics) {
	}

	/**
	 * @param intent        how the message was routed
	 * @param companies     the companies the answer is about
	 * @param elapsedMillis how long the answer took, end to end
	 * @param clarification a question back to the user with choices, instead of an answer; null otherwise
	 * @param suggestions   follow-ups the user can tap
	 * @param focus         the topics a focused answer covers; empty otherwise
	 */
	public record ChatReply(String conversationId, Intent intent, List<String> companies, String answer,
			long elapsedMillis, Clarification clarification, List<Suggestion> suggestions, List<String> focus) {

		static ChatReply of(String conversationId, AiService.ChatAnswer answer, long startNanos) {
			return new ChatReply(conversationId, answer.intent(), answer.companies(), answer.text(),
					(System.nanoTime() - startNanos) / 1_000_000, answer.clarification(), answer.suggestions(),
					answer.focus());
		}
	}

	@PostMapping("/api/ai/chat")
	public ChatReply chat(@RequestBody ChatRequest request) {
		String conversationId = conversationId(request);
		long start = System.nanoTime();
		return ChatReply.of(conversationId, answer(conversationId, request), start);
	}

	private AiService.ChatAnswer answer(String conversationId, ChatRequest request) {
		String message = request.message().strip();
		Focus focus = request.focus();
		return focus == null ? aiService.chat(conversationId, message)
				: aiService.focus(conversationId, message, focus.company(), focus.topics());
	}

	/** A failed answer on the stream: the HTTP status it would have had, and why. */
	public record StreamError(int status, String message) {
	}

	/**
	 * The same answer as {@link #chat}, streamed as server-sent events: a {@code step} event as each step of the
	 * work starts and ends ({@link Progress.Event}), then one {@code answer} event ({@link ChatReply}), or one
	 * {@code error} event ({@link StreamError}). Steps name what is being done, never a figure; the figures are
	 * only in the checked answer.
	 */
	@PostMapping(value = "/api/ai/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter chatStream(@RequestBody ChatRequest request) {
		String conversationId = conversationId(request);
		String message = request.message().strip();
		SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
		Thread.ofVirtual().name("chat-stream-", 0).start(() -> {
			Object lock = new Object();
			Progress.Sink sink = event -> send(emitter, lock, "step", event);
			long start = System.nanoTime();
			try (Progress.Scope scope = Progress.open(sink)) {
				send(emitter, lock, "answer", ChatReply.of(conversationId, answer(conversationId, request), start));
			} catch (UncheckedIOException ex) {
				log.info("The reader left before the answer to '{}' was sent; it was still remembered", message);
			} catch (ResponseStatusException ex) {
				sendQuietly(emitter, lock, new StreamError(ex.getStatusCode().value(), ex.getReason()));
			} catch (RuntimeException ex) {
				log.error("Streamed chat request failed", ex);
				sendQuietly(emitter, lock, new StreamError(500, "The answer could not be produced: " + ex.getMessage()));
			} finally {
				try {
					emitter.complete();
				} catch (RuntimeException ignored) {
					// already closed by the reader
				}
			}
		});
		return emitter;
	}

	private static void send(SseEmitter emitter, Object lock, String name, Object data) {
		synchronized (lock) {
			try {
				emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
			} catch (IOException ex) {
				// the reader has gone; the request still finishes and is remembered
				throw new UncheckedIOException(ex);
			}
		}
	}

	private static void sendQuietly(SseEmitter emitter, Object lock, StreamError error) {
		try {
			send(emitter, lock, "error", error);
		} catch (UncheckedIOException ignored) {
			// nobody is listening
		}
	}

	/** Deletes a conversation's history and session - "New conversation" in the page, or a user's own request. */
	@DeleteMapping("/api/ai/conversations/{conversationId}")
	public ResponseEntity<Void> forget(@PathVariable String conversationId) {
		if (!CONVERSATION_ID.matcher(conversationId).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"conversationId must be 1-64 letters, digits, '-' or '_'");
		}
		aiService.forget(conversationId);
		return ResponseEntity.noContent().build();
	}

	private static String conversationId(ChatRequest request) {
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
		return conversationId;
	}
}
