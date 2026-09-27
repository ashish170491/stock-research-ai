package com.ashish.stockresearch.research;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ResearchConfiguration {

    /**
     * "Today" for period validation, on the Indian exchange calendar. A bean
     * rather than {@code LocalDate.now()} so tests can pin the date and
     * check future-quarter handling deterministically.
     */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Kolkata"));
    }
}
