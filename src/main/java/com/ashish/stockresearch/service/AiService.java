package com.ashish.stockresearch.service;

import com.ashish.stockresearch.agent.Intent;
import com.ashish.stockresearch.agent.RequestRouter;
import com.ashish.stockresearch.agent.ResearchSession;
import com.ashish.stockresearch.agent.ResearchSessions;
import com.ashish.stockresearch.agent.RoutedRequest;
import com.ashish.stockresearch.marketdata.StockMarketDataService;
import com.ashish.stockresearch.marketdata.model.StockQuote;
import com.ashish.stockresearch.marketdata.model.StockQuoteResult;
import com.ashish.stockresearch.research.report.NumericClaimVerifier;
import com.ashish.stockresearch.research.report.UnsupportedClaimFilter;
import com.ashish.stockresearch.tool.StockPriceTool;
import com.ashish.stockresearch.tool.StockResearchTools;
import com.ashish.stockresearch.tool.ToolUsage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class AiService {

	/**
	 * Model-agnostic guardrails for the tool loop. The tools carry the data and
	 * its metadata; this states the evidence rules once so a model cannot
	 * paraphrase past them. Which kind of answer a request gets is decided
	 * beforehand by {@link RequestRouter}, not by this prompt.
	 */
	static final String SYSTEM_PROMPT = """
			You are a financial research assistant for Indian equities (NSE/BSE).

			Use only information supplied by tools or explicitly provided context. Do not invent
			financial values, dates, reporting periods, sources, market facts, company
			classifications, or qualitative claims (such as market position, dominance, being the
			largest or leading). Clearly identify unavailable information. Distinguish facts from
			interpretation. Call the tools the question needs; if a tool returns no data, say so.

			Rules for reporting tool data:
			- Quote figures exactly as the tools display them. Never calculate, recalculate, sum,
			  convert or estimate a figure yourself; every figure you write must appear in the tool
			  data. The application removes any statement whose figures do not.
			- A value marked DATA_CONFLICT, INVALID or UNAVAILABLE is not usable. Report its status;
			  draw no observation, trend or conclusion from it.
			- The latest reported quarter is the one the tools name. Never describe a later
			  quarter as reported or current.
			- Financial and historical figures describe past periods; never present them as live.
			- When some data is missing, answer with what you have and state what is missing.

			When writing a research report, use exactly these sections in this order:
			1. FACTS - only values from tool results, each with its period and source.
			2. OBSERVATIONS - only statements derived from the facts or supplied calculations.
			3. RISKS / DATA GAPS - every data gap, data conflict and data-quality warning the tools reported.
			4. INTERPRETATION - what the available evidence suggests, clearly presented as
			   interpretation, not fact.
			5. SOURCES - the sources and dates the tools reported.

			""" + ResearchInstructions.EVIDENCE_RULES;

	/** For questions that are not about stocks: no tools, no research rules. */
	static final String GENERAL_PROMPT = """
			You are a helpful assistant inside an Indian equity research application. Answer the
			question directly and briefly. Do not state any company's financial figures or share
			prices; the research features of this application provide those.
			""";

	static final String NAME_THE_COMPANY = "Which company do you mean? This conversation has not discussed one yet. "
			+ "Name it and ask again, for example \"What is Infosys's debt?\"";

	private static final DateTimeFormatter QUOTE_TIME =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.of("Asia/Kolkata"));

	private final RequestRouter router;
	private final ChatClient toolClient;
	private final ChatClient generalClient;
	private final ResearchReportWriter researchReportWriter;
	private final StockMarketDataService stockMarketDataService;
	private final UnsupportedClaimFilter claimFilter;
	private final NumericClaimVerifier numericVerifier;
	private final OllamaCalls ollama;
	private final ChatMemory chatMemory;
	private final ResearchSessions sessions;

	/**
	 * What a chat turn produced.
	 *
	 * @param companies the companies the answer is about: those routed to, or else those the tools
	 *                  were called for; empty when none
	 */
	public record ChatAnswer(Intent intent, List<String> companies, String text) {
	}

	public AiService(ChatClient.Builder chatClientBuilder,
			RequestRouter router,
			StockPriceTool stockPriceTool,
			StockResearchTools stockResearchTools,
			Advisor conversationTraceAdvisor,
			ResearchReportWriter researchReportWriter,
			StockMarketDataService stockMarketDataService,
			UnsupportedClaimFilter claimFilter,
			NumericClaimVerifier numericVerifier,
			OllamaCalls ollama,
			ChatMemory chatMemory,
			ResearchSessions sessions) {
		// Specific questions: the model picks among the individual research
		// tools and Spring AI runs the tool-calling loop.
		this.toolClient = chatClientBuilder.clone()
				.defaultSystem(SYSTEM_PROMPT)
				.defaultTools(stockPriceTool, stockResearchTools)
				.defaultAdvisors(conversationTraceAdvisor)
				.build();
		this.generalClient = chatClientBuilder.clone()
				.defaultSystem(GENERAL_PROMPT)
				.defaultAdvisors(conversationTraceAdvisor)
				.build();
		this.router = router;
		this.researchReportWriter = researchReportWriter;
		this.stockMarketDataService = stockMarketDataService;
		this.claimFilter = claimFilter;
		this.numericVerifier = numericVerifier;
		this.ollama = ollama;
		this.chatMemory = chatMemory;
		this.sessions = sessions;
	}

	/**
	 * Routes the request, then answers it the way its intent needs:
	 * <ul>
	 *   <li>FULL_RESEARCH - the verified report, built and rendered in Java;</li>
	 *   <li>QUOTE - the quote service's answer, rendered as text with no model involved;</li>
	 *   <li>COMPARE - one verified report per company, one after another (no side-by-side
	 *       analysis yet);</li>
	 *   <li>SPECIFIC_QUESTION - the model with the research tools, its answer screened against
	 *       what the tools returned;</li>
	 *   <li>NOT_STOCK_RELATED - a plain answer without tools.</li>
	 * </ul>
	 *
	 * The conversation's history is passed to the two model paths. What is remembered is what the
	 * user was shown - the screened answer, never the model's draft, so a removed claim cannot come
	 * back from memory. A Java-rendered report is remembered as a one-line note rather than in full:
	 * it would fill the context, and the model should fetch figures, not recall them.
	 */
	public ChatAnswer chat(String conversationId, String message) {
		ResearchSession session = sessions.get(conversationId);
		RoutedRequest request = router.route(message, session);
		List<String> companies = request.companies();
		String answer;
		switch (request.intent()) {
			case FULL_RESEARCH -> {
				answer = researchReportWriter.write(companies.get(0));
				remember(conversationId, message, "[The full research report on " + companies.get(0)
						+ " was shown to the user.]");
			}
			case QUOTE -> {
				answer = companies.stream().map(this::quote).collect(Collectors.joining("\n\n"));
				session.addEvidence(answer);
				remember(conversationId, message, answer);
			}
			case COMPARE -> {
				answer = companies.stream().map(researchReportWriter::write).collect(Collectors.joining("\n\n---\n\n"));
				remember(conversationId, message, "[Full research reports on " + String.join(" and ", companies)
						+ " were shown to the user.]");
			}
			case SPECIFIC_QUESTION -> {
				if (RequestRouter.needsACompany(message, request, session)) {
					// Never let the model pick a company the user did not name.
					answer = NAME_THE_COMPANY;
					remember(conversationId, message, answer);
					break;
				}
				ToolUsage usage = new ToolUsage();
				answer = answerWithTools(conversationId, message, companies, usage, session);
				if (companies.isEmpty()) {
					companies = usage.calls().stream().map(ToolUsage.Call::symbol)
							.filter(symbol -> symbol != null && !symbol.isBlank()).distinct().toList();
				}
			}
			case NOT_STOCK_RELATED -> {
				List<Message> history = chatMemory.get(conversationId);
				answer = ollama.call(() -> ResearchReportWriter.stripThinking(
						generalClient.prompt().messages(history).user(message).call().content()));
				remember(conversationId, message, answer);
			}
			default -> throw new IllegalStateException("Unhandled intent " + request.intent());
		}
		session.rememberCompanies(companies);
		return new ChatAnswer(request.intent(), companies, answer);
	}

	private void remember(String conversationId, String question, String shown) {
		chatMemory.add(conversationId, List.of(new UserMessage(question), new AssistantMessage(shown)));
	}

	/** The same report the chat returns for a research request, for callers who already know the company. */
	public String researchReport(String symbol) {
		return researchReportWriter.write(symbol);
	}

	/**
	 * The tool loop. Every answer is screened - also one given without calling a tool, which
	 * could otherwise repeat a figure from memory unchecked - against what the tools returned in
	 * this turn and in the conversation's earlier turns. A figure found in neither is removed.
	 */
	private String answerWithTools(String conversationId, String message, List<String> companies, ToolUsage usage,
			ResearchSession session) {
		List<Message> history = chatMemory.get(conversationId);
		// A follow-up such as "and its debt?" names no company; say which one the router resolved.
		String prompt = companies.isEmpty() ? message
				: message + "\n\n(This question is about: " + String.join(", ", companies) + ".)";
		String draft = ollama.call(() -> ResearchReportWriter.stripThinking(toolClient.prompt()
				.messages(history)
				.user(prompt)
				.toolContext(usage.asToolContext())
				.call()
				.content()));
		String answer = screened(draft, usage.evidence() + "\n" + session.earlierEvidence());
		session.addEvidence(usage.evidence());
		remember(conversationId, message, answer);
		return answer;
	}

	private String quote(String company) {
		StockQuoteResult result = stockMarketDataService.getQuote(company);
		if (!result.success()) {
			return "No quote is available for %s: %s".formatted(company, result.errorMessage());
		}
		StockQuote q = result.quote();
		StringBuilder text = new StringBuilder("**%s** (%s): %s %s".formatted(q.symbol(), q.exchange(),
				q.price().toPlainString(), q.currency()));
		if (q.timestamp() != null) {
			text.append(" as of ").append(QUOTE_TIME.format(q.timestamp()));
		}
		text.append(". Source: ").append(q.source()).append('.');
		if (q.mock()) {
			text.append(" SAMPLE DATA - not a real quote.");
		}
		return text.toString();
	}

	/** Removes statements the tool data does not support, and says what was removed and why. */
	private String screened(String answer, String evidence) {
		NumericClaimVerifier.Result figures = numericVerifier.verify(answer, evidence);
		UnsupportedClaimFilter.Result claims = claimFilter.filter(figures.text(), evidence);
		StringBuilder text = new StringBuilder(claims.text());
		if (!figures.removedStatements().isEmpty()) {
			text.append("\n\n_Removed %d statement(s) containing figures that do not appear in the data the tools "
					.formatted(figures.removedStatements().size())
					+ "returned (the model mistyped, recalculated, converted or invented them): "
					+ String.join(", ", figures.ungroundedFigures()) + "._");
		}
		if (!claims.removedSentences().isEmpty()) {
			text.append("\n\n_Removed %d statement(s) making claims the data does not support: %s_"
					.formatted(claims.removedSentences().size(), String.join(" | ", claims.removedSentences())));
		}
		return text.toString();
	}
}
