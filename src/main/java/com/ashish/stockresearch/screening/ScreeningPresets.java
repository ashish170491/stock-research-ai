package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The configured presets as {@link ScreeningCriteria}. Built when the application starts, so a preset
 * that names an unknown industry group, or tests a metric that is not primary for a group (a bank
 * rule on debt-to-equity), stops the application instead of producing a misleading screen.
 */
@Component
public class ScreeningPresets {

    private static final String NON_FINANCIAL = "default";

    private final Map<String, ScreeningCriteria> presets = new LinkedHashMap<>();

    public ScreeningPresets(ScreeningProperties properties) {
        properties.presets().forEach((name, preset) -> presets.put(key(name), criteria(key(name), preset)));
    }

    /** "quality-compounder", "Quality Compounder" and "quality_compounder" name the same preset. */
    public Optional<ScreeningCriteria> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(presets.get(key(name)));
    }

    public Set<String> names() {
        return presets.keySet();
    }

    private static ScreeningCriteria criteria(String name, ScreeningProperties.Preset preset) {
        List<Rule> nonFinancial = List.of();
        Map<IndustryGroup, List<Rule>> byGroup = new EnumMap<>(IndustryGroup.class);
        Map<String, List<Rule>> rules = preset.rules() == null ? Map.of() : preset.rules();
        for (Map.Entry<String, List<Rule>> entry : rules.entrySet()) {
            List<Rule> list = entry.getValue() == null ? List.of() : entry.getValue();
            if (entry.getKey().equalsIgnoreCase(NON_FINANCIAL)) {
                nonFinancial = list;
                continue;
            }
            IndustryGroup group;
            try {
                group = IndustryGroup.valueOf(entry.getKey().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Screening preset %s: '%s' is neither 'default' nor an industry group"
                        .formatted(name, entry.getKey()), ex);
            }
            byGroup.put(group, list);
        }
        if (preset.tieBreak() == null) {
            throw new IllegalStateException("Screening preset %s has no tie-break metric".formatted(name));
        }
        return new ScreeningCriteria(name, preset.description(), nonFinancial, byGroup,
                new ScreeningCriteria.TieBreak(preset.tieBreak(), !preset.tieBreakLowerFirst()), Set.of());
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
    }
}
