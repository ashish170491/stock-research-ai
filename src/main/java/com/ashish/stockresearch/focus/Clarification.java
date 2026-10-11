package com.ashish.stockresearch.focus;

import java.util.List;

/**
 * A question back to the user instead of a guess. {@link #TOPICS}: which parts of a company they want to hear
 * about, as checkboxes. {@link #COMPANY}: which company a question is about, as radio buttons of the companies
 * discussed so far (none when none has been), or a name typed in reply. The page shows {@code options} as
 * checkboxes ({@code multiSelect}) or radio buttons.
 */
public record Clarification(String question, String company, boolean multiSelect, List<Option> options, String kind) {

    public static final String TOPICS = "topics";
    public static final String COMPANY = "company";

    public Clarification(String question, String company, boolean multiSelect, List<Option> options) {
        this(question, company, multiSelect, options, TOPICS);
    }

    /** @param selected whether the option starts ticked */
    public record Option(String id, String label, String hint, boolean selected) {
    }

    public Clarification {
        options = options == null ? List.of() : List.copyOf(options);
        kind = kind == null ? TOPICS : kind;
    }
}
