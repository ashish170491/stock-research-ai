package com.ashish.stockresearch.moat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * {@code app.moat}: how the signs of a lasting edge are looked for.
 *
 * @param preset          the screening preset whose rules are the signs, per industry group (default "moat-signs")
 * @param shorterSpanYears when a sign's multi-year figure is UNAVAILABLE (the company has not filed that many years
 *                        yet), the same rule is checked over this shorter span, and the answer says so; 0 never
 *                        falls back. Default 3.
 * @param maxStatements   how many passages from the company's own documents are quoted at most. Default 3.
 */
@ConfigurationProperties("app.moat")
public record MoatProperties(String preset, Integer shorterSpanYears, Integer maxStatements) {

    @ConstructorBinding
    public MoatProperties {
        preset = preset == null || preset.isBlank() ? "moat-signs" : preset;
        shorterSpanYears = shorterSpanYears == null ? 3 : shorterSpanYears;
        maxStatements = maxStatements == null || maxStatements < 1 ? 3 : maxStatements;
    }

    public static MoatProperties defaults() {
        return new MoatProperties(null, null, null);
    }
}
