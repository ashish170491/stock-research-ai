package com.ashish.stockresearch.service;

import com.ashish.stockresearch.agent.RequestRouter;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;
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

	private static final DateTimeFormatter QUOTE_TIME =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(ZoneId.of("Asia/Kolkata"));

	private final RequestRouter router;
	private final ChatClient toolClient;
	private final ChatClient generalClient;
	private final ResearchReportWriter researchReportWriter;
	private final StockMarketDataService stockMarketDataService;
	private final UnsupportedClaimFilter claimFilter;
	private final NumericClaimVerifier numericVerifier;

	public AiService(ChatClient.Builder chatClientBuilder,
			RequestRouter router,
			StockPriceTool stockPriceTool,
			StockResearchTools stockResearchTools,
			Advisor conversationTraceAdvisor,
			ResearchReportWriter researchReportWriter,
			StockMarketDataService stockMarketDataService,
			UnsupportedClaimFilter claimFilter,
			NumericClaimVerifier numericVerifier) {
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
	 */
	public String chat(String message) {
		RoutedRequest request = router.route(message);
		return switch (request.intent()) {
			case FULL_RESEARCH -> researchReportWriter.write(request.companies().get(0));
			case QUOTE -> request.companies().stream().map(this::quote).collect(Collectors.joining("\n\n"));
			case COMPARE -> request.companies().stream().map(researchReportWriter::write)
					.collect(Collectors.joining("\n\n---\n\n"));
			case SPECIFIC_QUESTION -> answerWithTools(message);
			case NOT_STOCK_RELATED -> callOllama(() -> ResearchReportWriter.stripThinking(
					generalClient.prompt().user(message).call().content()));
		};
	}

	/** The same report the chat returns for a research request, for callers who already know the company. */
	public String researchReport(String symbol) {
		return researchReportWriter.write(symbol);
	}

	private String answerWithTools(String message) {
		ToolUsage usage = new ToolUsage();
		String answer = callOllama(() -> ResearchReportWriter.stripThinking(toolClient.prompt()
				.user(message)
				.toolContext(usage.asToolContext())
				.call()
				.content()));
		return usage.calls().isEmpty() ? answer : screened(answer, usage.evidence());
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
