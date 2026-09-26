package com.ashish.stockresearch.service;

import com.ashish.stockresearch.tool.StockPriceTool;
import com.ashish.stockresearch.tool.StockResearchTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class AiService {

	/**
	 * Model-agnostic guardrails. These restate, in one place, the data-quality
	 * rules the tool results already carry in their metadata - the tools stay
	 * the source of truth, this just stops a model from paraphrasing past them.
	 */
	private static final String SYSTEM_PROMPT = """
			You are a stock research assistant for Indian equities (NSE/BSE).

			Use the available tools to answer questions about listed companies. Decide for
			yourself which tools a question needs - one, several, or none. For questions
			unrelated to stocks, just answer directly without calling any tool.

			Rules for reporting tool data:
			- Never state a financial figure, price or ownership percentage that did not come
			  from a tool result in this conversation. If a tool could not retrieve something,
			  say so plainly and name what is missing.
			- A null field or a listed unavailable metric means UNKNOWN. It never means zero.
			- Respect each result's provenance: report filing and historical figures with the
			  period they cover, and never present them as current or live.
			- When a result reports a currency, use it. Do not assume rupees.
			- When some tools succeed and others fail, answer with what you have and state
			  clearly which information could not be retrieved.
			""";

	private final ChatClient chatClient;

	public AiService(ChatClient.Builder chatClientBuilder,
			StockPriceTool stockPriceTool,
			StockResearchTools stockResearchTools) {
		// Every research tool is registered up front and the model picks
		// which ones a question needs. There is no routing or orchestration
		// here on purpose: Spring AI runs the tool-calling loop, so a
		// question needing four tools and one needing none both work
		// without any code path of their own.
		this.chatClient = chatClientBuilder
				.defaultSystem(SYSTEM_PROMPT)
				.defaultTools(stockPriceTool, stockResearchTools)
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
