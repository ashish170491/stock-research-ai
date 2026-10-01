package com.ashish.stockresearch.controller;

import com.ashish.stockresearch.glossary.GlossaryEntry;
import com.ashish.stockresearch.glossary.MetricGlossary;
import com.ashish.stockresearch.screening.ScreeningMetric;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The application's glossary, for the web page: the same fixed, reviewed text the chat answers carry. */
@RestController
public class GlossaryController {

    private final MetricGlossary glossary;

    public GlossaryController(MetricGlossary glossary) {
        this.glossary = glossary;
    }

    public record Entry(String key, String name, String question, String meaning, String calculation,
                        String howToRead, List<String> readWith, List<String> industryNotes,
                        List<String> screenSettings, List<ScreeningMetric> screeningMetrics) {
    }

    public record Glossary(String note, String settingsNote, List<Entry> entries) {
    }

    @GetMapping("/api/glossary")
    public Glossary glossary() {
        return new Glossary(MetricGlossary.SOURCE_NOTE.replace("_", ""), MetricGlossary.SETTINGS_NOTE,
                glossary.entries().stream().map(this::entry).toList());
    }

    private Entry entry(GlossaryEntry entry) {
        return new Entry(entry.key(), entry.name(), entry.question().heading(), entry.meaning(), entry.calculation(),
                entry.howToRead(), entry.readWith().stream().map(key -> glossary.byKey(key).orElseThrow().name()).toList(),
                glossary.industryNotes(entry), glossary.screenSettings(entry),
                entry.screeningMetrics().stream().sorted().toList());
    }
}
