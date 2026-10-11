package com.ashish.stockresearch.focus;

import java.util.List;

/**
 * A follow-up the user can tap. {@code text} is what is shown and sent; when {@code topics} is not empty it is
 * answered as a focused request on {@code company} rather than routed as free text.
 */
public record Suggestion(String text, String company, List<String> topics) {

    public Suggestion {
        topics = topics == null ? List.of() : List.copyOf(topics);
    }

    public static Suggestion ask(String text) {
        return new Suggestion(text, null, List.of());
    }

    public static Suggestion focus(String company, Topic topic) {
        return new Suggestion(topic.prompt(company), company, List.of(topic.id()));
    }
}
