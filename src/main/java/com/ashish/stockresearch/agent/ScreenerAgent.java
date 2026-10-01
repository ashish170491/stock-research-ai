package com.ashish.stockresearch.agent;

import com.ashish.stockresearch.research.report.AnswerChecks;
import com.ashish.stockresearch.research.sector.IndustryGroup;
import com.ashish.stockresearch.screening.CriteriaValidator;
import com.ashish.stockresearch.screening.ExtractedCriteria;
import com.ashish.stockresearch.screening.Rule;
import com.ashish.stockresearch.screening.ScreenInterpretation;
import com.ashish.stockresearch.screening.ScreeningCriteria;
import com.ashish.stockresearch.screening.ScreeningMetric;
import com.ashish.stockresearch.screening.ScreeningOutcome;
import com.ashish.stockresearch.screening.ScreeningPresets;
import com.ashish.stockresearch.screening.ScreeningRenderer;
import com.ashish.stockresearch.screening.ScreeningResult;
import com.ashish.stockresearch.screening.ScreeningService;
import com.ashish.stockresearch.service.OllamaCalls;
import com.ashish.stockresearch.service.ResearchInstructions;
import com.ashish.stockresearch.service.ResearchReportWriter;
import com.ashish.stockresearch.trace.ChainTracer;
import com.ashish.stockresearch.trace.Progress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Answers a screening request in words - "find profitable IT companies with low debt and ROE above 18%" -
 * as a chain (roadmap Step 4), each stage doing only what it is suited to:
 *
 * <ol>
 *   <li><b>Extract</b> (model, no tools, thinking off, temperature 0): the request's criteria as
 *       {@link ExtractedCriteria}, the screening criteria as the model read them.</li>
 *   <li><b>Validate</b> (Java, {@link CriteriaValidator}): whitelisted metrics, sane bounds, thresholds the user
 *       actually wrote, vague words read as preset thresholds - producing {@link ScreeningCriteria} and a
 *       record of every assumption.</li>
 *   <li><b>Screen</b> (Java, {@code StockScreener} through {@link ScreeningService}): the pass/fail decisions,
 *       from the snapshot.</li>
 *   <li><b>Summarise</b> (model, no tools, thinking off, the shared evidence rules): a few sentences from the
 *       result only, checked by {@link AnswerChecks} against the full result - figures by
 *       {@link com.ashish.stockresearch.research.report.NumericClaimVerifier}, the screen's counts by
 *       {@link com.ashish.stockresearch.research.report.ScreeningCountVerifier}, and claims by
 *       {@link com.ashish.stockresearch.research.report.UnsupportedClaimFilter}.</li>
 * </ol>
 *
 * The answer shows the user how their request was read and what was not applied, then the summary, then the
 * application's own table. If extraction fails, stage 2 works from the request's words alone and refuses
 * rather than guess at criteria it cannot read; if the summary cannot be written, the table is shown without
 * one. Nothing unscreened is ever shown, and no stage throws to the user.
 */
@Component
public class ScreenerAgent {

    private static final Logger log = LoggerFactory.getLogger(ScreenerAgent.class);

    static final String EXTRACTION_PROMPT = """
            You read a request to screen Indian stocks and write down its criteria as JSON. You do not screen,
            judge or recommend anything.

            - preset: a preset the request names (%s), otherwise null.
            - industryGroups: the kinds of company the request limits itself to, from: %s. Empty if it names none.
            - criteria: every condition the request states WITH A NUMBER. For each one:
              - phrase: the request's own words for the condition, copied exactly.
              - metric: one of the metric names listed below.
              - operator: AT_LEAST (at least, minimum, or more), AT_MOST (at most, up to, or less),
                ABOVE (above, over, more than), BELOW (below, under, less than).
              - threshold: the number exactly as the request writes it, without %% or x. Never convert it
                or change its unit.
            - vagueTerms: qualities the request asks for WITHOUT a number, copied exactly, such as "low debt"
              or "profitable". Never give them a number or a metric.

            Metrics:
            %s
            Plain "ROE" is ROE_PERCENT; "average ROE" is ROE_3Y_AVG_PERCENT. Revenue or sales growth is
            REVENUE_CAGR_5Y_PERCENT; profit or earnings growth is NET_PROFIT_CAGR_5Y_PERCENT. A request that
            names a span ("3-year growth") uses the metric over that span. "P/E" is TRAILING_PE_MULTIPLE and
            "P/B" is PRICE_TO_BOOK_MULTIPLE.
            A condition with a number on something not in this list still goes in criteria, with metric set
            to the request's own name for it.
            """;

    static final String SUMMARY_PROMPT = """
            You write a short summary of a stock screen. You are given the application's result: the criteria,
            a summary the application counted, and which stocks were not screened.

            Write 2 to 4 plain sentences, with no heading, list or table:
            - how many stocks meet every criterion, copying the count from the Summary exactly, and which ones,
              by symbol;
            - how many have at least one criterion that could not be assessed (INSUFFICIENT_DATA), copying that
              count exactly;
            - if stocks were not screened, how many, copying the count, and the reason the result gives.
            Copy counts, symbols and figures exactly as given. Never count, calculate, compare or rank anything
            yourself. INSUFFICIENT_DATA means a criterion could not be assessed: it is neither met nor failed.
            Do not explain why a stock meets or fails a criterion, describe any company or its business, or say
            what the result means for investing. Meeting the criteria is not a judgement of any stock: never
            call a stock good, strong, quality, cheap or an investment, and give no recommendation.

            """ + ResearchInstructions.EVIDENCE_RULES + """

            Remember: write only the 2 to 4 sentences described at the top - the counts, symbols and reasons,
            copied exactly from the result. The result is the screen's: say which criteria stocks meet, never what
            that means for them.
            """;

    static final String NO_SUMMARY = "_The summary could not be written (the model did not answer). The table "
            + "below is the application's own result._";

    private final ChatClient extractionClient;
    private final ChatClient summaryClient;
    private final CriteriaValidator validator;
    private final ScreeningService screeningService;
    private final ScreeningRenderer renderer;
    private final AnswerChecks checks;
    private final OllamaCalls ollama;
    private final ChainTracer tracer;

    /**
     * @param text       the answer to show
     * @param evidence   what the answer rests on (the rendered screen), for checking later answers that quote it;
     *                   empty when no screen ran
     * @param memoryNote a one-line record of the turn for the conversation's memory
     */
    /** @param criteria the criteria the screen ran with; null when no screen was run */
    public record ScreenAnswer(String text, String evidence, String memoryNote, ScreeningCriteria criteria) {

        public ScreenAnswer(String text, String evidence, String memoryNote) {
            this(text, evidence, memoryNote, null);
        }
    }

    public ScreenerAgent(ChatClient.Builder chatClientBuilder, Advisor conversationTraceAdvisor,
                         ScreeningPresets presets, CriteriaValidator validator, ScreeningService screeningService,
                         ScreeningRenderer renderer, AnswerChecks checks, OllamaCalls ollama, ChainTracer tracer) {
        // No tools on either client: stage 1 only reads the request and stage 4 only the result, so neither
        // can fetch or invent more. No thinking: neither needs a reasoning trace, and it is most of the latency.
        this.extractionClient = chatClientBuilder.clone()
                .defaultSystem(extractionPrompt(presets))
                .defaultOptions(OllamaChatOptions.builder().disableThinking().temperature(0.0).numCtx(4096))
                .defaultAdvisors(conversationTraceAdvisor)
                .build();
        this.summaryClient = chatClientBuilder.clone()
                .defaultSystem(SUMMARY_PROMPT)
                .defaultOptions(OllamaChatOptions.builder().disableThinking().temperature(0.0).numCtx(8192))
                .defaultAdvisors(conversationTraceAdvisor)
                .build();
        this.validator = validator;
        this.screeningService = screeningService;
        this.renderer = renderer;
        this.checks = checks;
        this.ollama = ollama;
        this.tracer = tracer;
    }

    static String extractionPrompt(ScreeningPresets presets) {
        String metrics = Arrays.stream(ScreeningMetric.values())
                .map(metric -> "- %s: %s, %s (%s)".formatted(metric.name(), metric.shortLabel(), metric.description(),
                        metric.unit()))
                .collect(Collectors.joining("\n"));
        String groups = Arrays.stream(IndustryGroup.values()).filter(group -> group != IndustryGroup.UNKNOWN)
                .map(IndustryGroup::name).collect(Collectors.joining(", "));
        return EXTRACTION_PROMPT.formatted(String.join(", ", presets.names()), groups, metrics);
    }

    public ScreenAnswer answer(String message) {
        try (ChainTracer.Chain chain = tracer.start("screener", message)) {
            // Stage 1: the model reads the request.
            ExtractedCriteria extracted;
            boolean extractionFailed = false;
            Progress.Step reading = Progress.start("Reading the criteria in your request (local model)");
            try {
                extracted = ollama.call(() -> extractionClient.prompt().user(message).call()
                        .entity(ExtractedCriteria.class));
            } catch (RuntimeException ex) {
                // unreachable model (OllamaCalls' 503), malformed JSON or prose: stage 2 reads the words alone
                log.warn("Criteria extraction gave no usable answer for '{}': {}", message, ex.toString());
                extracted = null;
            }
            if (extracted == null) {
                extractionFailed = true;
                extracted = ExtractedCriteria.none();
            }
            reading.done(extractionFailed ? "The local model gave no usable reading, so only your own words are read"
                    : "Read the criteria in your request");
            chain.stage(1, "extract criteria", extractionFailed
                    ? "no usable answer from the model; reading the request's own words only" : extracted.toString());

            // Stage 2: Java decides what can be screened, and records every assumption.
            Progress.Step checking = Progress.start("Checking every criterion against your own words");
            ScreenInterpretation interpretation = validator.interpret(message, extracted, extractionFailed);
            checking.done(interpretation.criteria().isPresent() ? "Checked the criteria against your words"
                    : "Checked the criteria: no screen can be run (the answer says why)");
            chain.stage(2, "validate", interpretation.criteria().map(ScreenerAgent::rules)
                    .orElse("no screen: " + interpretation.refusal())
                    + " | readings: " + interpretation.readings() + " | not applied: " + interpretation.notApplied());
            if (interpretation.criteria().isEmpty()) {
                return new ScreenAnswer(compose(interpretation, null, interpretation.refusal()), "",
                        "[No screen was run: " + interpretation.refusal() + "]");
            }

            // Stage 3: StockScreener makes every pass/fail decision.
            Progress.Step screening = Progress.start("Screening the stored Nifty 50 snapshot");
            ScreeningOutcome outcome = screeningService.screen(interpretation.criteria().get(), null);
            if (outcome.success()) {
                screening.done("Screened %d stocks from the snapshot of %s".formatted(outcome.result().ranked().size(),
                        outcome.run().snapshotDate()));
            } else {
                screening.failed(outcome.message());
            }
            String result = renderer.render(outcome);
            chain.stage(3, "screen", outcome.success() ? "%s: %d screened, %d meet every criterion, in %d ms"
                    .formatted(outcome.status(), outcome.result().ranked().size(), meetingAll(outcome).size(),
                            outcome.elapsedMillis()) : outcome.status() + ": " + outcome.message());
            if (!outcome.success()) {
                return new ScreenAnswer(compose(interpretation, null, outcome.message()), "",
                        "[No screen was run: " + outcome.message() + "]");
            }

            // Stage 4: the model summarises the result it is shown, and nothing else; the checks screen it.
            String summary;
            Progress.Step summarising = Progress.start("Writing the summary (local model), then checking its counts");
            try {
                String draft = ollama.call(() -> ResearchReportWriter.stripThinking(
                        summaryClient.prompt().user(renderer.renderSummary(outcome)).call().content()));
                AnswerChecks.Checked checked = checks.check(draft, result);
                summary = checked.text();
                chain.stage(4, "summarise", checked.removed() + " statement(s) removed by the checks");
                summarising.done(checked.removed() == 0 ? "Wrote the summary; every count checked"
                        : "Wrote the summary; %d statement(s) removed by the checks".formatted(checked.removed()));
            } catch (RuntimeException ex) {
                log.warn("Screen summary could not be written: {}", ex.toString());
                summary = NO_SUMMARY;
                chain.stage(4, "summarise", "no summary: " + ex.getMessage());
                summarising.failed("the local model did not answer");
            }
            return new ScreenAnswer(compose(interpretation, summary, ScreeningRenderer.forReaders(result)), result,
                    memoryNote(interpretation.criteria().get(), outcome), interpretation.criteria().get());
        }
    }

    /** How the request was read, what was not applied, then the summary and the result. */
    static String compose(ScreenInterpretation interpretation, String summary, String body) {
        StringBuilder text = new StringBuilder();
        if (!interpretation.readings().isEmpty()) {
            text.append("**How I read your request**\n");
            interpretation.readings().forEach(reading -> text.append("- ").append(reading).append('\n'));
            text.append('\n');
        }
        if (!interpretation.notApplied().isEmpty()) {
            text.append("**Not applied**\n");
            interpretation.notApplied().forEach(item -> text.append("- ").append(item).append('\n'));
            text.append('\n');
        }
        if (summary != null && !summary.isBlank()) {
            text.append(summary.strip()).append("\n\n");
        }
        return text.append(body == null ? "" : body.strip()).toString().strip();
    }

    private static String rules(ScreeningCriteria criteria) {
        Set<String> labels = new LinkedHashSet<>();
        criteria.nonFinancialRules().forEach(rule -> labels.add(rule.label()));
        criteria.groupRules().forEach((group, rules) -> labels.add(group + ": "
                + rules.stream().map(Rule::label).collect(Collectors.joining(", "))));
        return criteria.name() + " [" + String.join("; ", labels) + "]"
                + (criteria.industryGroups().isEmpty() ? "" : " groups " + criteria.industryGroups());
    }

    private static List<String> meetingAll(ScreeningOutcome outcome) {
        List<String> symbols = new ArrayList<>();
        for (ScreeningResult.StockScreening stock : outcome.result().ranked()) {
            if (stock.meetsAll()) {
                symbols.add(stock.symbol());
            }
        }
        return symbols;
    }

    /** What the turn showed, counted here, for the conversation memory: a screen table would fill the context. */
    private static String memoryNote(ScreeningCriteria criteria, ScreeningOutcome outcome) {
        List<String> meeting = meetingAll(outcome);
        return "[A screen was shown to the user: %s, on the %s snapshot of %s. %d of the %d screened stocks meet every "
                .formatted(rules(criteria), outcome.universe().displayName(), outcome.run().snapshotDate(),
                        meeting.size(), outcome.result().ranked().size())
                + "criterion" + (meeting.isEmpty() ? "" : ": " + String.join(", ", meeting)) + ".]";
    }
}
