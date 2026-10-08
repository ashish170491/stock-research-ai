package com.ashish.stockresearch.orchestrator;

import com.ashish.stockresearch.agent.ResearchSession;
import com.ashish.stockresearch.agent.ScreenerAgent;
import com.ashish.stockresearch.research.comparison.ComparisonService;
import com.ashish.stockresearch.research.comparison.ShortlistDeepDive;
import com.ashish.stockresearch.research.critic.Critique;
import com.ashish.stockresearch.research.critic.ResearchCritic;
import com.ashish.stockresearch.service.OllamaCalls;
import com.ashish.stockresearch.tool.DocumentSearchTools;
import com.ashish.stockresearch.trace.ChainTracer;
import com.ashish.stockresearch.trace.Progress;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The autonomous research orchestrator (roadmap Step 8): an LLM produces a {@link ResearchPlan} of
 * steps from the fixed {@link StepType} set, and Java - never the model - runs each one in order
 * (S8-1, S8-2). Three budgets stop a plan that runs too long (S8-3); a SCREEN step always pauses for a
 * human checkpoint before the rest of the plan runs (S8-4); the last step is always a verified memo
 * with no recommendation (S8-5); every step is logged to the trace with its duration and model-call
 * count (S8-7).
 */
@Service
public class ResearchOrchestrator {

    static final String PLANNER_PROMPT = """
            You turn a stock-research goal into an ordered research plan for an Indian-equities research
            application. You never screen stocks, fetch data, compare companies, critique a draft, search
            documents or write the memo yourself - you only choose and order steps for the application to
            run, and fill in their fields.

            Each step has exactly one of these types:
            - SCREEN: screen stocks against criteria described in words. "description" is the screen
              request, in words, exactly as a user would phrase it (e.g. "pharma companies with ROE above
              15% and low debt"). Leave "symbols" empty.
            - DEEP_DIVE: a full verified research report on specific companies. "symbols" names them;
              leave "symbols" empty to mean "the shortlist from the SCREEN step before this one" - only
              valid when a SCREEN step precedes it.
            - COMPARE: one aligned comparison of 2 to 5 companies. "symbols" names them (2 to 5); leave
              "symbols" empty to mean the shortlist from a preceding SCREEN step.
            - SEARCH_DOCS: search one company's ingested annual reports or transcripts. "symbols" names
              exactly one company; "description" is the question to search for.
            - CRITIQUE: review the most recent DEEP_DIVE, COMPARE or SEARCH_DOCS step's output against the
              evidence gathered so far. Needs no "symbols"; "description" may be empty. Only valid after
              one of those step types.
            - WRITE_MEMO: write the final memo from everything gathered. Always the plan's last step,
              used exactly once. Needs no "symbols" or "description".

            A plan normally starts with SCREEN when the goal asks to find, shortlist or identify
            candidates, and always ends with exactly one WRITE_MEMO. Use only as many steps as the goal
            needs - do not pad the plan with steps it did not ask for. Never use a step type other than
            these six, and never invent one.

            Respond with ONLY a JSON object, no other text: {"goal": "...", "steps": [{"type": "...",
            "description": "...", "symbols": [...]}, ...]}.
            """;

    private final ChatClient plannerClient;
    private final OllamaCalls ollama;
    private final ChainTracer tracer;
    private final ScreenerAgent screenerAgent;
    private final ShortlistDeepDive deepDive;
    private final ComparisonService comparisonService;
    private final DocumentSearchTools documentSearch;
    private final ResearchCritic critic;
    private final MemoWriter memoWriter;
    private final OrchestratorProperties properties;

    public ResearchOrchestrator(PlannerChatClientBuilder plannerChatClientBuilder,
            Advisor conversationTraceAdvisor,
            OllamaCalls ollama,
            ChainTracer tracer,
            ScreenerAgent screenerAgent,
            ShortlistDeepDive deepDive,
            ComparisonService comparisonService,
            DocumentSearchTools documentSearch,
            ResearchCritic critic,
            MemoWriter memoWriter,
            OrchestratorProperties properties) {
        // No tools: the planner only lays out steps; Java resolves every one of them to real data.
        this.plannerClient = plannerChatClientBuilder.builder().clone()
                .defaultSystem(PLANNER_PROMPT)
                .defaultOptions(OllamaChatOptions.builder().disableThinking().temperature(0.2))
                .defaultAdvisors(conversationTraceAdvisor)
                .build();
        this.ollama = ollama;
        this.tracer = tracer;
        this.screenerAgent = screenerAgent;
        this.deepDive = deepDive;
        this.comparisonService = comparisonService;
        this.documentSearch = documentSearch;
        this.critic = critic;
        this.memoWriter = memoWriter;
        this.properties = properties;
    }

    /** Plans the goal and runs it until a checkpoint, a budget limit, or the memo. */
    public String start(String goal, ResearchSession session) {
        try (ChainTracer.Chain chain = tracer.start("orchestrator", goal)) {
            ResearchPlan plan;
            try {
                plan = ollama.call(() -> plannerClient.prompt().user(goal).call().entity(ResearchPlan.class));
                if (plan == null) {
                    throw new InvalidPlanException("The planning model gave no usable plan.");
                }
                PlanValidator.validate(plan);
            } catch (RuntimeException ex) {
                chain.stage(1, "plan", "rejected: " + ex.getMessage());
                return "Could not build a usable research plan for this goal: " + ex.getMessage();
            }
            chain.stage(1, "plan", plan.steps().size() + " step(s): "
                    + plan.steps().stream().map(step -> step.type().name()).collect(Collectors.joining(", ")));
            OrchestratorBudget budget = new OrchestratorBudget(properties, chain, 0, 0, java.time.Duration.ZERO);
            return execute(plan, 0, new ArrayList<>(), List.of(), budget, chain, session);
        }
    }

    /** Resumes a plan paused at a checkpoint; "there is nothing to continue" if none is saved. */
    public String resume(ResearchSession session) {
        Optional<OrchestratorCheckpoint> saved = session.checkpoint();
        if (saved.isEmpty()) {
            return "There is no paused research plan in this conversation to continue.";
        }
        OrchestratorCheckpoint checkpoint = saved.get();
        session.clearCheckpoint();
        try (ChainTracer.Chain chain = tracer.start("orchestrator", "(continuing) " + checkpoint.plan().goal())) {
            OrchestratorBudget budget = new OrchestratorBudget(properties, chain, checkpoint.stepsConsumed(),
                    checkpoint.llmCallsConsumed(), checkpoint.elapsedBeforePause());
            return execute(checkpoint.plan(), checkpoint.nextStepIndex(), new ArrayList<>(checkpoint.completedResults()),
                    checkpoint.shortlist(), budget, chain, session);
        }
    }

    private String execute(ResearchPlan plan, int fromIndex, List<StepResult> results, List<String> shortlistAtStart,
            OrchestratorBudget budget, ChainTracer.Chain chain, ResearchSession session) {
        List<PlanStep> steps = plan.steps();
        List<String> shortlist = shortlistAtStart;
        for (int i = fromIndex; i < steps.size(); i++) {
            Optional<String> exceeded = budget.exceeded();
            if (exceeded.isPresent()) {
                return partialMemo(plan, results, exceeded.get());
            }
            PlanStep step = steps.get(i);
            List<String> shortlistSoFar = shortlist;
            long started = System.nanoTime();
            int callsBefore = budget.llmCalls();
            StepResult result = Progress.step(describeProgress(step),
                    () -> executeStep(plan.goal(), step, results, shortlistSoFar));
            results.add(result);
            if (step.type() == StepType.SCREEN) {
                shortlist = result.shortlist();
            }
            budget.stepCompleted();
            // Stage 1 is the plan itself (logged in start()), so a plan step's own stage starts at 2.
            chain.stage(i + 2, step.type().name(), "%d ms, %d model call(s): %s".formatted(
                    (System.nanoTime() - started) / 1_000_000, budget.llmCalls() - callsBefore, summarize(result)));
            if (step.type() == StepType.SCREEN) {
                session.pause(new OrchestratorCheckpoint(plan, i + 1, shortlist, List.copyOf(results),
                        budget.stepsConsumed(), budget.llmCalls(), budget.elapsed()));
                return pausedAnswer(plan, i, result, shortlist);
            }
        }
        return results.get(results.size() - 1).output();
    }

    private StepResult executeStep(String goal, PlanStep step, List<StepResult> priorResults, List<String> shortlist) {
        return switch (step.type()) {
            case SCREEN -> {
                ScreenerAgent.ScreenAnswer answer = screenerAgent.answer(step.description());
                // answer.evidence() carries the "TOOL RESULT: screenStocks" marker UnsupportedClaimFilter's
                // SUPPLIED_CRITERIA exemption looks for; without it, a later step's legitimate sentence about
                // which criteria stocks met can be over-removed as an unsupported judgement.
                String output = answer.evidence().isBlank() ? answer.text()
                        : answer.text() + "\n\n" + answer.evidence();
                yield new StepResult(StepType.SCREEN, step.description(), output, answer.shortlist());
            }
            case DEEP_DIVE -> {
                List<String> symbols = resolve(step.symbols(), shortlist);
                List<String> reports = deepDive.run(symbols);
                yield new StepResult(StepType.DEEP_DIVE, step.description(), String.join("\n\n---\n\n", reports),
                        List.of());
            }
            case COMPARE -> {
                List<String> symbols = resolve(step.symbols(), shortlist);
                List<String> capped = symbols.size() > 5 ? symbols.subList(0, 5) : symbols;
                yield new StepResult(StepType.COMPARE, step.description(), comparisonService.compare(capped), List.of());
            }
            case SEARCH_DOCS -> {
                String symbol = step.symbols().get(0);
                yield new StepResult(StepType.SEARCH_DOCS, step.description(),
                        documentSearch.search(symbol, step.description()), List.of());
            }
            case CRITIQUE -> {
                StepResult toCheck = lastCritiquable(priorResults);
                Critique critique = critic.review(evidenceText(priorResults), toCheck.output());
                yield new StepResult(StepType.CRITIQUE, step.description(), renderCritique(critique), List.of());
            }
            case WRITE_MEMO -> new StepResult(StepType.WRITE_MEMO, step.description(),
                    memoWriter.write(goal, evidenceText(priorResults)), List.of());
        };
    }

    /** Explicit symbols if the step named any; otherwise the shortlist a preceding SCREEN step produced. */
    private static List<String> resolve(List<String> explicit, List<String> shortlist) {
        return explicit.isEmpty() ? shortlist : explicit;
    }

    private static StepResult lastCritiquable(List<StepResult> results) {
        for (int i = results.size() - 1; i >= 0; i--) {
            StepType type = results.get(i).type();
            if (type == StepType.DEEP_DIVE || type == StepType.COMPARE || type == StepType.SEARCH_DOCS) {
                return results.get(i);
            }
        }
        throw new IllegalStateException("no DEEP_DIVE, COMPARE or SEARCH_DOCS result to critique"
                + " (PlanValidator should have rejected this plan before it ran)");
    }

    private static String evidenceText(List<StepResult> results) {
        return results.stream().map(result -> "=== " + result.type() + (result.description().isBlank() ? ""
                : ": " + result.description()) + " ===\n" + result.output()).collect(Collectors.joining("\n\n"));
    }

    private static String renderCritique(Critique critique) {
        if (!critique.needsRevision()) {
            return "Critique: ACCEPT - no unsupported claims, missed data gaps or missing risks found.";
        }
        StringBuilder text = new StringBuilder("Critique: REVISE\n");
        appendIssues(text, "Unsupported claims", critique.unsupportedClaims());
        appendIssues(text, "Missed data gaps", critique.missedDataGaps());
        appendIssues(text, "Missing risks", critique.missingRisks());
        return text.toString().stripTrailing();
    }

    private static void appendIssues(StringBuilder text, String label, List<String> issues) {
        if (issues.isEmpty()) {
            return;
        }
        text.append(label).append(":\n");
        issues.forEach(issue -> text.append("- ").append(issue).append('\n'));
    }

    private static String summarize(StepResult result) {
        String output = result.output() == null ? "" : result.output().replace('\n', ' ');
        return output.length() > 160 ? output.substring(0, 160) + "..." : output;
    }

    private static String describeProgress(PlanStep step) {
        return switch (step.type()) {
            case SCREEN -> "Screening: " + step.description();
            case DEEP_DIVE -> "Deep-diving: " + String.join(", ", step.symbols());
            case COMPARE -> "Comparing: " + String.join(", ", step.symbols());
            case SEARCH_DOCS -> "Searching documents: " + step.symbols().get(0);
            case CRITIQUE -> "Critiquing the latest findings";
            case WRITE_MEMO -> "Writing the memo";
        };
    }

    private static String pausedAnswer(ResearchPlan plan, int stepIndex, StepResult screenResult, List<String> shortlist) {
        int remaining = plan.steps().size() - stepIndex - 1;
        return screenResult.output() + "\n\n_" + shortlist.size() + " stock(s) meet every criterion"
                + (shortlist.isEmpty() ? "" : ": " + String.join(", ", shortlist))
                + ". Say \"continue\" to run the remaining " + remaining + " step(s) of this research plan._";
    }

    private String partialMemo(ResearchPlan plan, List<StepResult> results, String limitReason) {
        String memo = memoWriter.write(plan.goal(), evidenceText(results));
        return "_This research plan stopped early because it reached " + limitReason + ", after completing "
                + results.size() + " of " + plan.steps().size() + " step(s). The memo below reflects only what was "
                + "gathered up to that point._\n\n" + memo;
    }
}
