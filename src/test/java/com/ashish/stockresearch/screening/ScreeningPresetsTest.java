package com.ashish.stockresearch.screening;

import com.ashish.stockresearch.research.sector.IndustryGroup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The presets exactly as application.yml defines them. */
public class ScreeningPresetsTest {

    static ScreeningProperties properties() {
        try {
            StandardEnvironment environment = new StandardEnvironment();
            for (PropertySource<?> source : new YamlPropertySourceLoader().load("application.yml",
                    new ClassPathResource("application.yml"))) {
                environment.getPropertySources().addLast(source);
            }
            return Binder.get(environment).bind("app.screening", ScreeningProperties.class).get();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static ScreeningPresets presets() {
        return new ScreeningPresets(properties());
    }

    @Test
    void definesTheThreePresets() {
        assertThat(presets().names()).containsExactly("quality-compounder", "reasonable-value", "dividend");
        assertThat(presets().find("Quality Compounder")).isPresent();
        assertThat(presets().find("nonsense")).isEmpty();
    }

    // S3-7: industry-group aware, and no bank or NBFC rule evaluates debt-to-equity or operating margin.
    @Test
    void bankAndNbfcPresetsNeverEvaluateDebtToEquityOrOperatingMargin() {
        for (String name : presets().names()) {
            ScreeningCriteria criteria = presets().find(name).orElseThrow();
            for (IndustryGroup group : List.of(IndustryGroup.BANK, IndustryGroup.NBFC)) {
                List<Rule> rules = criteria.rulesFor(group).orElseThrow();
                assertThat(rules).as("%s / %s", name, group).extracting(Rule::metric)
                        .doesNotContain(ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE, ScreeningMetric.OPERATING_MARGIN_PERCENT,
                                ScreeningMetric.ROCE_PERCENT, ScreeningMetric.OCF_TO_PAT_3Y_MULTIPLE,
                                ScreeningMetric.DIVIDENDS_TO_OCF_PERCENT);
                assertThat(rules).isNotEqualTo(criteria.nonFinancialRules());
            }
            assertThat(criteria.rulesFor(IndustryGroup.UNKNOWN)).as(name).isEmpty();
            assertThat(criteria.rulesFor(IndustryGroup.INSURANCE)).as(name).isEmpty();
        }
    }

    @Test
    void qualityCompounderHasTheRoadmapRulesOverThreeYearSpans() {
        ScreeningCriteria quality = presets().find("quality-compounder").orElseThrow();

        assertThat(quality.rulesFor(IndustryGroup.IT_SERVICES).orElseThrow()).extracting(Rule::label).containsExactly(
                "ROE 3y avg ≥ 15.00%", "ROCE ≥ 15.00%", "Revenue CAGR 3y ≥ 10.00%", "Profit CAGR 3y ≥ 10.00%",
                "OCF/PAT 3y ≥ 0.80x", "D/E ≤ 0.50x");
        assertThat(quality.rulesFor(IndustryGroup.BANK).orElseThrow()).extracting(Rule::label)
                .containsExactly("ROA ≥ 1.00%", "ROE ≥ 14.00%");
        assertThat(quality.rulesFor(IndustryGroup.NBFC).orElseThrow()).extracting(Rule::label)
                .containsExactly("ROA ≥ 2.00%", "ROE ≥ 15.00%");
    }

    @Test
    void refusesAPresetThatTestsANonPrimaryMetricForItsGroup() {
        ScreeningProperties.Preset bad = new ScreeningProperties.Preset("bad", ScreeningMetric.ROE_PERCENT, false,
                Map.of("BANK", List.of(new Rule(ScreeningMetric.DEBT_TO_EQUITY_MULTIPLE, Operator.AT_MOST,
                        java.math.BigDecimal.ONE))));

        assertThatThrownBy(() -> new ScreeningPresets(new ScreeningProperties(null, Map.of("bad", bad))))
                .hasMessageContaining("not a primary metric for BANK");
    }

    @Test
    void refusesAnUnknownIndustryGroupKey() {
        ScreeningProperties.Preset bad = new ScreeningProperties.Preset("bad", ScreeningMetric.ROE_PERCENT, false,
                Map.of("BANKS", List.of(new Rule(ScreeningMetric.ROE_PERCENT, Operator.AT_LEAST, java.math.BigDecimal.TEN))));

        assertThatThrownBy(() -> new ScreeningPresets(new ScreeningProperties(null, Map.of("bad", bad))))
                .hasMessageContaining("'BANKS' is neither 'default' nor an industry group");
    }

    @Test
    void schedulesTheSnapshotAfterMarketClose() {
        assertThat(properties().snapshot().cron()).isEqualTo("0 30 18 * * MON-FRI");
        assertThat(properties().snapshot().pauseBetweenSymbols()).isPositive();
    }
}
