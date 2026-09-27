package com.ashish.stockresearch.service;

import com.ashish.stockresearch.tool.StockPriceTool;
import com.ashish.stockresearch.tool.StockResearchTools;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.function.Supplier;

@Service
public class AiService {

	/**
	 * Model-agnostic guardrails. The tools carry the data and its metadata;
	 * this states the evidence rules once so a model cannot paraphrase past
	 * them.
	 */
	static final String SYSTEM_PROMPT = """
			You are a financial research assistant for Indian equities (NSE/BSE).

			Use only information supplied by tools or explicitly provided context. Do not invent
			financial values, dates, reporting periods, sources, market facts, company
			classifications, or qualitative claims (such as market position, dominance, being the
			largest or leading). Clearly identify unavailable information. Distinguish facts from
			interpretation.

			Decide for yourself which tools a question needs - one, several, or none. For a broad
			request to analyse or research a company, call getStockResearchReport; its report goes to
			the user directly. For questions unrelated to stocks, answer directly without calling
			any tool.

			Rules for reporting tool data:
			- The latest reported quarter is the one the tools name. Never describe a later
			  quarter as reported or current.
			- Financial and historical figures describe past periods; never present them as live.
			- When some data is missing, answer with what you have and state what is missing.

			When writing a research report, use exactly these sections in this order:
			1. FACTS - only values from tool results, each with its period and source.
			2. OBSERVATIONS - only statements derived from the facts or supplied calculations.
			3. RISKS / DATA GAPS - every data gap and data-quality warning the tools reported.
			4. INTERPRETATION - what the available evidence suggests, clearly presented as
			   interpretation, not fact.
			5. SOURCES - the sources and dates the tools reported.

			""" + ResearchInstructions.EVIDENCE_RULES;

	private final ChatClient chatClient;
	private final ResearchReportWriter researchReportWriter;

	public AiService(ChatClient.Builder chatClientBuilder,
			StockPriceTool stockPriceTool,
			StockResearchTools stockResearchTools,
			Advisor conversationTraceAdvisor,
			ResearchReportWriter researchReportWriter) {
		// Every research tool is registered up front and the model picks
		// which ones a question needs. There is no routing or orchestration
		// here on purpose: Spring AI runs the tool-calling loop, so a
		// question needing four tools and one needing none both work
		// without any code path of their own.
		this.chatClient = chatClientBuilder
				.defaultSystem(SYSTEM_PROMPT)
				.defaultTools(stockPriceTool, stockResearchTools)
				.defaultAdvisors(conversationTraceAdvisor)
				.build();
		this.researchReportWriter = researchReportWriter;
	}

	public String chat(String message) {
		return callOllama(() -> chatClient.prompt()
				.user(message)
				.call()
				.content());
	}

	/** The same report the chat tool returns, for callers who already know the company. */
	public String researchReport(String symbol) {
		return researchReportWriter.write(symbol);
	}

	private String callOllama(Supplier<String> call) {
		try {
			return call.get();
		} catch (ResourceAccessException ex) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Could not reach Ollama. Make sure it is running (ollama run qwen3:8b) at http://localhost:11434",
					ex);
		}
	}

}
