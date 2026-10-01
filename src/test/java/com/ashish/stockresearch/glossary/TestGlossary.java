package com.ashish.stockresearch.glossary;

import com.ashish.stockresearch.screening.ScreeningPresetsTest;

/** The application's glossary over the configured presets, for tests outside this package. */
public final class TestGlossary {

    private TestGlossary() {
    }

    public static MetricGlossary glossary() {
        return new MetricGlossary(ScreeningPresetsTest.presets());
    }
}
