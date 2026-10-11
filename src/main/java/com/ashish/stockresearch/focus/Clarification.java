package com.ashish.stockresearch.focus;

import java.util.List;

/**
 * A question back to the user before a broad request is answered: which parts of a company they want to hear
 * about. The page shows {@code options} as checkboxes ({@code multiSelect}) or radio buttons.
 */
public record Clarification(String question, String company, boolean multiSelect, List<Option> options) {

    /** @param selected whether the option starts ticked */
    public record Option(String id, String label, String hint, boolean selected) {
    }

    public Clarification {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
