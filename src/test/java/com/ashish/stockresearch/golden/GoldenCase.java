package com.ashish.stockresearch.golden;

import java.util.List;

/**
 * One golden-set case (roadmap Step 10, S10-1): a question with the intent it must route to, the tools
 * (if any) the model must call to answer it, and the facts its answer must contain.
 */
public record GoldenCase(String id, String question, String expectedIntent, List<String> expectedTools,
                          List<String> requiredFacts) {
}
