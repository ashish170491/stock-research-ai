package com.ashish.stockresearch.service;

import com.ashish.stockresearch.agent.Intent;
import com.ashish.stockresearch.agent.RequestRouter;
import com.ashish.stockresearch.agent.ResearchSession;
import com.ashish.stockresearch.agent.ResearchSessions;
import com.ashish.stockresearch.agent.RoutedRequest;
import com.ashish.stockresearch.agent.ScreenerAgent;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.marketdata.StockMarketDataService;
import com.ashish.stockresearch.marketdata.model.StockQuote;
import com.ashish.stockresearch.marketdata.model.StockQuoteResult;
import com.ashish.stockresearch.orchestrator.ResearchOrchestrator;
import com.ashish.stockresearch.research.comparison.ComparisonService;
import com.ashish.stockresearch.research.report.AnswerChecks;
import com.ashish.stockresearch.tool.DocumentSearchTools;
import com.ashish.stockresearch.tool.ScreeningTools;
import com.ashish.stockresearch.tool.StockPriceTool;
import com.ashish.stockresearch.tool.StockResearchTools;
import com.ashish.stockresearch.tool.ToolUsage;
import com.ashish.stockresearch.trace.Progress;
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

	/** A reply to a paused research plan's checkpoint (roadmap Step 8): resumes it instead of routing. */
	private static final java.util.regex.Pattern CONTINUE =
			java.util.regex.Pattern.compile("^\\s*continue\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

	private final RequestRouter router;
	private final ChatClient toolClient;
	private final ChatClient generalClient;
	private final ResearchReportWriter researchReportWriter;
	private final ComparisonService comparisonService;
	private final StockMarketDataService stockMarketDataService;
	private final ScreenerAgent screenerAgent;
	private final AnswerChecks checks;
	private final OllamaCalls ollama;
	private final ChatMemory chatMemory;
	private final ResearchSessions sessions;
	private final MetricGlossary glossary;
	private final ResearchOrchestrator orchestrator;

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
			ScreeningTools screeningTools,
			DocumentSearchTools documentSearchTools,
			Advisor conversationTraceAdvisor,
			ResearchReportWriter researchReportWriter,
			ComparisonService comparisonService,
			StockMarketDataService stockMarketDataService,
			ScreenerAgent screenerAgent,
			AnswerChecks checks,
			OllamaCalls ollama,
			ChatMemory chatMemory,
			ResearchSessions sessions,
			MetricGlossary glossary,
			ResearchOrchestrator orchestrator) {
		// Specific questions: the model picks among the individual research
		// tools and Spring AI runs the tool-calling loop.
		this.toolClient = chatClientBuilder.clone()
				.defaultSystem(SYSTEM_PROMPT)
				.defaultTools(stockPriceTool, stockResearchTools, screeningTools, documentSearchTools)
				.defaultAdvisors(conversationTraceAdvisor)
				.build();
		this.generalClient = chatClientBuilder.clone()
				.defaultSystem(GENERAL_PROMPT)
				.defaultAdvisors(conversationTraceAdvisor)
				.build();
		this.router = router;
		this.researchReportWriter = researchReportWriter;
		this.comparisonService = comparisonService;
		this.stockMarketDataService = stockMarketDataService;
		this.screenerAgent = screenerAgent;
		this.checks = checks;
		this.ollama = ollama;
		this.chatMemory = chatMemory;
		this.sessions = sessions;
		this.glossary = glossary;
		this.orchestrator = orchestrator;
	}

	/**
	 * Routes the request, then answers it the way its intent needs:
	 * <ul>
	 *   <li>FULL_RESEARCH - the verified report, built and rendered in Java;</li>
	 *   <li>QUOTE - the quote service's answer, rendered as text with no model involved;</li>
	 *   <li>COMPARE - {@link ComparisonService}'s aligned table of all the companies at once, with one
	 *       verified narrative over that table, not a separate report per company;</li>
	 *   <li>SCREEN - {@link ScreenerAgent}'s chain: criteria read from the request and validated in
	 *       Java, the screen, and a checked summary;</li>
	 *   <li>SPECIFIC_QUESTION - the model with the research tools, its answer screened against
	 *       what the tools returned;</li>
	 *   <li>EXPLAIN_TERM - the glossary's entry, rendered in Java with no model call;</li>
	 *   <li>NOT_STOCK_RELATED - a plain answer without tools.</li>
	 * </ul>
	 *
	 * Screen and tool answers end with the glossary's explanation of the terms they use, added by Java after
	 * the answer checks; a report carries its own ({@link ResearchReportWriter}).
	 *
	 * The conversation's history is passed to the two model paths. What is remembered is what the
	 * user was shown - the screened answer, never the model's draft, so a removed claim cannot come
	 * back from memory. A Java-rendered report is remembered as a one-line note rather than in full:
	 * it would fill the context, and the model should fetch figures, not recall them.
	 */
	public ChatAnswer chat(String conversationId, String message) {
		ResearchSession session = sessions.get(conversationId);
		if (session.checkpoint().isPresent() && CONTINUE.matcher(message).find()) {
			String answer = orchestrator.resume(session);
			remember(conversationId, message, answer);
			return new ChatAnswer(Intent.ORCHESTRATE, session.companies(), answer);
		}
		RoutedRequest request;
		try (Progress.Step understanding = Progress.start("Understanding your question")) {
			request = router.route(message, session);
			understanding.done("Understood: " + understood(request));
		}
		List<String> companies = request.companies();
		String answer;
		switch (request.intent()) {
			case FULL_RESEARCH -> {
				answer = researchReportWriter.write(companies.get(0));
				remember(conversationId, message, "[The full research report on " + companies.get(0)
						+ " was shown to the user.]");
			}
			case QUOTE -> {
				answer = companies.stream().map(company -> Progress.step(
						"Fetching the latest price of %s (Yahoo Finance)".formatted(company), () -> quote(company)))
						.collect(Collectors.joining("\n\n"));
				session.addEvidence(answer);
				remember(conversationId, message, answer);
			}
			case COMPARE -> {
				answer = comparisonService.compare(companies);
				remember(conversationId, message, "[A comparison of " + String.join(" and ", companies)
						+ " was shown to the user.]");
			}
			case SCREEN -> {
				ScreenerAgent.ScreenAnswer screen = screenerAgent.answer(message);
				answer = screen.criteria() == null ? screen.text()
						: screen.text() + "\n\n" + glossary.criteriaByQuestion(screen.criteria());
				answer = withTermsSection(answer);
				// The table itself stays out of the memory, but follow-up answers may quote it.
				session.addEvidence(screen.evidence());
				remember(conversationId, message, screen.memoryNote());
			}
			case ORCHESTRATE -> {
				answer = orchestrator.start(message, session);
				remember(conversationId, message, "[A multi-step research plan for \"" + message
						+ "\" was shown to the user.]");
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
				String terms = glossary.termsUsed(glossary.termsIn(answer));
				if (!terms.isBlank()) {
					answer = answer + "\n\n" + terms;
				}
				answer = answer + documentSourcesSection(usage);
				if (companies.isEmpty()) {
					companies = usage.calls().stream().map(ToolUsage.Call::symbol)
							.filter(symbol -> symbol != null && !symbol.isBlank()).distinct().toList();
				}
			}
			case EXPLAIN_TERM -> {
				answer = Progress.step("Looking the term up in the application's glossary", () -> glossary.answer(message));
				remember(conversationId, message, "[The application's glossary entry was shown to the user.]");
			}
			case NOT_STOCK_RELATED -> {
				List<Message> history = chatMemory.get(conversationId);
				answer = Progress.step("Answering (local model)", () -> ollama.call(() -> ResearchReportWriter.stripThinking(
						generalClient.prompt().messages(history).user(message).call().content())));
				remember(conversationId, message, answer);
			}
			default -> throw new IllegalStateException("Unhandled intent " + request.intent());
		}
		session.rememberCompanies(companies);
		return new ChatAnswer(request.intent(), companies, answer);
	}

	/** The answer, followed by the glossary's entries for the terms it uses, if any. */
	private String withTermsSection(String answer) {
		String section = glossary.section(glossary.termsIn(answer));
		return section.isBlank() ? answer : answer + "\n\n" + section;
	}

	/**
	 * Every document citation {@code searchCompanyDocuments} returned this turn, Java-rendered so it is
	 * shown whether or not the model's own words repeat it (S7-5).
	 */
	private static String documentSourcesSection(ToolUsage usage) {
		List<ToolUsage.Citation> citations = usage.citations();
		if (citations.isEmpty()) {
			return "";
		}
		String lines = citations.stream().map(citation -> "- " + citation.label())
				.collect(Collectors.joining("\n"));
		return "\n\n**Document sources**\n" + lines;
	}

	/** What the request was understood as, for the progress shown while it is answered. */
	static String understood(RoutedRequest request) {
		List<String> companies = request.companies();
		String named = String.join(" and ", companies);
		return switch (request.intent()) {
			case FULL_RESEARCH -> "a research report on " + named;
			case QUOTE -> "the latest price of " + named;
			case COMPARE -> "a comparison of " + named;
			case SCREEN -> "a screen of stocks against criteria";
			case ORCHESTRATE -> "a multi-step research goal";
			case SPECIFIC_QUESTION -> companies.isEmpty() ? "a question for the research tools" : "a question about " + named;
			case EXPLAIN_TERM -> "what a term means";
			case NOT_STOCK_RELATED -> "a general question, not about stocks";
		};
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
		String draft = Progress.step("Answering with the research tools (local model)",
				() -> ollama.call(() -> ResearchReportWriter.stripThinking(toolClient.prompt()
						.messages(history)
						.user(prompt)
						.toolContext(usage.asToolContext())
						.call()
						.content())));
		String answer = Progress.step("Checking every figure in the answer against the data the tools returned",
				() -> screened(draft, usage.evidence() + "\n" + session.earlierEvidence()));
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

	/**
	 * Removes statements the tool data does not support, and says what was removed and why:
	 * {@link AnswerChecks} runs {@link com.ashish.stockresearch.research.report.NumericClaimVerifier},
	 * {@link com.ashish.stockresearch.research.report.ScreeningCountVerifier} and
	 * {@link com.ashish.stockresearch.research.report.UnsupportedClaimFilter}.
	 */
	private String screened(String answer, String evidence) {
		return checks.check(answer, evidence).text();
	}
}
