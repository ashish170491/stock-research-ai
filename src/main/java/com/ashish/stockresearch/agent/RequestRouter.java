package com.ashish.stockresearch.agent;

import com.ashish.stockresearch.marketdata.NseSymbolDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides what a chat request is asking for before any tool runs.
 *
 * <ol>
 *   <li><b>Keyword rules</b> for the unambiguous shapes - "compare X and Y", "fundamental
 *       overview of X", "price of X". A rule fires only when every company it extracts is found
 *       in the NSE equity list, so "research the history of Spring AI" is not mistaken for a
 *       company report.</li>
 *   <li>Otherwise a <b>tool-free model call</b> classifies the request into a
 *       {@link RoutedRequest}.</li>
 * </ol>
 *
 * A follow-up such as "what about its debt?" names no company. When the conversation has discussed
 * one ({@link ResearchSession#companies()}), the routing model is told which, and a message that
 * refers back to it ("it", "its", "the company", "and ...") gets it even if the model leaves it out.
 * Only the company is passed on, never the chat history, so routing stays one short call.
 *
 * Any failure - an unreachable model, malformed JSON, a missing intent, a company-specific intent
 * with no company - falls back to {@link Intent#SPECIFIC_QUESTION}, whose tool loop can handle
 * any question. Routing never fails a request.
 */
@Component
public class RequestRouter {

    private static final Logger log = LoggerFactory.getLogger(RequestRouter.class);

    static final String ROUTING_PROMPT = """
            You route questions for an Indian equity research assistant. Classify the user's message
            into exactly one intent and list the companies it names, as written, in order:
            - FULL_RESEARCH: a broad overview, analysis, research or report on ONE company.
            - QUOTE: the current, latest or live share price of one or more companies.
            - COMPARE: two or more companies compared side by side.
            - SPECIFIC_QUESTION: any narrower question about a company or stock (a metric, a period,
              ownership, performance over time, what the company does).
            - NOT_STOCK_RELATED: anything not about companies, stocks or markets.
            Name only companies in the message; never add one. Use an empty list when none is named.
            The one exception: when the message refers to a company only as "it", "its", "they" or
            similar, and a company discussed earlier is given, list that company.
            """;

    private static final Pattern COMPARE = Pattern.compile(
            "^\\s*(?:please\\s+)?compare\\s+(.+?)\\s+(?:and|with|to|vs\\.?|versus)\\s+(.+?)[\\s?.!]*$"
                    + "|^\\s*(.+?)\\s+(?:vs\\.?|versus)\\s+(.+?)[\\s?.!]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OVERVIEW = Pattern.compile(
            "\\b(?:fundamentals?|analy[sz]e|analysis|research|overview|deep[- ]dive|report)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTE = Pattern.compile(
            "\\b(?:price|quote|trading at)\\b", Pattern.CASE_INSENSITIVE);
    /** Words around a company name in these requests, removed to leave the name itself. */
    private static final Set<String> FILLER = Set.of(
            "give", "me", "a", "an", "the", "of", "on", "for", "please", "can", "you", "provide", "do", "show",
            "what", "whats", "what's", "is", "s", "tell", "about", "full", "detailed", "complete", "company",
            "stock", "stocks", "share", "shares", "today", "now", "current", "currently", "latest", "live",
            "price", "quote", "trading", "at", "fundamental", "fundamentals", "overview", "analysis", "analyse",
            "analyze", "research", "deep", "dive", "report",
            // pronouns: "research it" names no company, so it is left to the model and the conversation
            "it", "its", "it's", "they", "their", "them", "this", "that", "these", "those", "same", "and", "how");
    /** A message that points back at a company named earlier in the conversation. */
    private static final Pattern REFERS_BACK = Pattern.compile(
            "\\b(?:it|its|it's|itself|they|their|them|this|that|these|those|same|the (?:company|stock|shares?|"
                    + "bank|business|firm|group))\\b|^\\s*(?:and|what about|how about)\\b",
            Pattern.CASE_INSENSITIVE);

    private final ChatClient routingClient;
    private final NseSymbolDirectory directory;

    public RequestRouter(ChatClient.Builder chatClientBuilder, NseSymbolDirectory directory,
                         Advisor conversationTraceAdvisor) {
        // No tools: routing only classifies, it never fetches. No thinking either: a classification
        // needs no reasoning trace, and generating one is most of the latency of a routed request.
        // The prompt is short, so a small context keeps the KV cache small.
        this.routingClient = chatClientBuilder.clone()
                .defaultSystem(ROUTING_PROMPT)
                .defaultOptions(OllamaChatOptions.builder().disableThinking().temperature(0.0).numCtx(4096))
                .defaultAdvisors(conversationTraceAdvisor)
                .build();
        this.directory = directory;
    }

    public RoutedRequest route(String message) {
        return route(message, null);
    }

    /** @param session the conversation so far, or null for a request with no conversation */
    public RoutedRequest route(String message, ResearchSession session) {
        List<String> earlier = session == null ? List.of() : session.companies();
        RoutedRequest routed = byKeywords(message).orElseGet(() -> byModel(message, earlier));
        log.info("Routed '{}' -> {} {}{}", message, routed.intent(), routed.companies(),
                earlier.isEmpty() ? "" : " (earlier: " + earlier + ")");
        return routed;
    }

    /** The keyword rules; empty when none applies or a company cannot be found in the NSE list. */
    Optional<RoutedRequest> byKeywords(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }
        Matcher compare = COMPARE.matcher(message);
        if (compare.matches()) {
            String first = compare.group(1) != null ? compare.group(1) : compare.group(3);
            String second = compare.group(2) != null ? compare.group(2) : compare.group(4);
            Optional<String> a = listed(first);
            Optional<String> b = listed(second);
            return a.isPresent() && b.isPresent()
                    ? Optional.of(new RoutedRequest(Intent.COMPARE, List.of(a.get(), b.get())))
                    : Optional.empty();
        }
        Intent intent = OVERVIEW.matcher(message).find() ? Intent.FULL_RESEARCH
                : QUOTE.matcher(message).find() ? Intent.QUOTE : null;
        if (intent == null) {
            return Optional.empty();
        }
        return listed(message).map(symbol -> new RoutedRequest(intent, List.of(symbol)));
    }

    /** The NSE symbol of the company a phrase names, once the request's filler words are removed. */
    private Optional<String> listed(String phrase) {
        String name = String.join(" ", Arrays.stream(phrase.split("[^\\p{Alnum}&.'-]+"))
                .filter(word -> !word.isBlank() && !FILLER.contains(word.toLowerCase(Locale.ROOT)))
                .toList());
        return name.isBlank() ? Optional.empty()
                : directory.resolve(name).map(NseSymbolDirectory.Listing::symbol);
    }

    RoutedRequest byModel(String message) {
        return byModel(message, List.of());
    }

    RoutedRequest byModel(String message, List<String> earlier) {
        boolean refersBack = !earlier.isEmpty() && message != null && REFERS_BACK.matcher(message).find();
        String prompt = refersBack
                ? "Company discussed earlier: " + String.join(", ", earlier) + "\n\nMessage: " + message
                : message;
        RoutedRequest routed;
        try {
            routed = routingClient.prompt().user(prompt).call().entity(RoutedRequest.class);
        } catch (RuntimeException ex) {
            log.warn("Routing model gave no usable answer for '{}' ({}); treating it as a specific question",
                    message, ex.getMessage());
            routed = RoutedRequest.specificQuestion();
        }
        return validated(refersBack ? withEarlierCompanies(routed, earlier) : routed);
    }

    /**
     * Fills in the company a follow-up refers back to, when the model left it out: "and its debt?"
     * about the last company, "compare it with TCS" as the last company and TCS.
     */
    static RoutedRequest withEarlierCompanies(RoutedRequest routed, List<String> earlier) {
        if (routed == null || routed.intent() == null) {
            return new RoutedRequest(Intent.SPECIFIC_QUESTION, earlier);
        }
        List<String> named = routed.companies();
        return switch (routed.intent()) {
            case FULL_RESEARCH, QUOTE, SPECIFIC_QUESTION ->
                    named.isEmpty() ? new RoutedRequest(routed.intent(), earlier) : routed;
            case COMPARE -> {
                if (named.size() >= 2) {
                    yield routed;
                }
                List<String> companies = new java.util.ArrayList<>(earlier.stream()
                        .filter(company -> !named.contains(company)).toList());
                companies.addAll(named);
                yield new RoutedRequest(Intent.COMPARE, companies);
            }
            case NOT_STOCK_RELATED -> routed;
        };
    }

    /** A company-specific intent needs its companies; anything unusable becomes a specific question. */
    static RoutedRequest validated(RoutedRequest routed) {
        if (routed == null || routed.intent() == null) {
            return RoutedRequest.specificQuestion();
        }
        return switch (routed.intent()) {
            case FULL_RESEARCH, QUOTE -> routed.companies().isEmpty() ? RoutedRequest.specificQuestion() : routed;
            case COMPARE -> switch (routed.companies().size()) {
                case 0 -> RoutedRequest.specificQuestion();
                case 1 -> new RoutedRequest(Intent.FULL_RESEARCH, routed.companies());
                default -> routed;
            };
            case SPECIFIC_QUESTION, NOT_STOCK_RELATED -> routed;
        };
    }
}
