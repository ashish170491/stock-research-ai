package com.ashish.stockresearch.focus;

import com.ashish.stockresearch.agent.RequestRouter;
import com.ashish.stockresearch.agent.RoutedRequest;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decides, in code, when a request is too broad to answer well and the user should first be asked what they want
 * to know: a company's name on its own, "tell me about X", "is X a good investment?", "how is X doing?". A request
 * that asks for the report in words ("research X", "full analysis of X") or a precise question ("what is TCS's
 * debt?") is answered straight away. No model decides this.
 */
public final class Clarifier {

    /** Words that ask for the whole report, so there is nothing to ask. */
    private static final Pattern ASKS_FOR_THE_REPORT = Pattern.compile(
            "\\b(?:report|full|fundamentals?|overview|analy[sz]e|analysis|deep[- ]dive|research|everything|detailed"
                    + "|in detail)\\b", Pattern.CASE_INSENSITIVE);
    /** A question about one company that names no particular figure. */
    private static final Pattern VAGUE = Pattern.compile(
            "\\bhow(?:'s|\\s+is|\\s+are)\\s+.+\\s+doing\\b|\\b(?:thoughts|views?)\\s+on\\b|\\bopinion\\s+(?:on|of|about)\\b"
                    + "|\\btell\\s+me\\s+(?:about|more)\\b|\\bwhat\\s+do\\s+you\\s+think\\b",
            Pattern.CASE_INSENSITIVE);

    private Clarifier() {
    }

    public static boolean needsFocus(String message, RoutedRequest routed) {
        if (message == null || routed == null || routed.companies().size() != 1) {
            return false;
        }
        return switch (routed.intent()) {
            case FULL_RESEARCH -> !ASKS_FOR_THE_REPORT.matcher(message).find();
            case SPECIFIC_QUESTION -> VAGUE.matcher(message).find() || RequestRouter.asksForAJudgement(message);
            default -> false;
        };
    }

    /**
     * The question to ask, when one is needed. Ticked to start: what this conversation chose last time, else the
     * rating for a question about whether to invest, else profit and growth.
     */
    public static Optional<Clarification> clarification(String message, RoutedRequest routed, List<Topic> remembered) {
        if (!needsFocus(message, routed)) {
            return Optional.empty();
        }
        String company = routed.companies().get(0);
        List<Topic> ticked = remembered != null && !remembered.isEmpty() ? remembered
                : RequestRouter.asksForAJudgement(message) ? List.of(Topic.RATING)
                : List.of(Topic.PROFITABLE, Topic.GROWING);
        List<Clarification.Option> options = Arrays.stream(Topic.values())
                .map(topic -> new Clarification.Option(topic.id(), topic.label(), topic.hint(), ticked.contains(topic)))
                .toList();
        return Optional.of(new Clarification("What would you like to know about %s?".formatted(company), company,
                true, options));
    }

    /** "Which company is this about?", offering the companies discussed so far; the user may also type a name. */
    public static Clarification whichCompany(List<String> discussed) {
        List<Clarification.Option> options = discussed == null ? List.of() : discussed.stream()
                .map(company -> new Clarification.Option(company, company, "discussed earlier", false)).toList();
        return new Clarification("Which company is this about?", null, false, options, Clarification.COMPANY);
    }

    /** The question as plain text, for a client that does not draw the choices. */
    public static String asText(Clarification clarification) {
        if (Clarification.COMPANY.equals(clarification.kind())) {
            StringBuilder md = new StringBuilder(clarification.question());
            if (clarification.options().isEmpty()) {
                return md.append(" Type its name, for example \"Infosys\" or \"TCS\".").toString();
            }
            md.append(" Pick one, or type another company's name:\n\n");
            clarification.options().forEach(option -> md.append("- ").append(option.label()).append("\n"));
            return md.toString().stripTrailing();
        }
        StringBuilder md = new StringBuilder(clarification.question()).append(" Pick one or more:\n\n");
        clarification.options().forEach(option -> md.append("- ").append(option.label()).append("\n"));
        md.append("\nOr type your own question.");
        return md.toString();
    }
}
