package com.ashish.stockresearch.screening;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Screening presets, and the scheduler that runs the nightly snapshot. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ScreeningProperties.class)
class ScreeningConfiguration {
}
