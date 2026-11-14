package com.antifraud.scoring.score.application;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AmountScoringPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsConfiguredThreshold() {
        contextRunner.withPropertyValues("scoring.amount-rule.threshold-minor=1200")
                .run(context -> assertThat(context.getBean(AmountScoringProperties.class).thresholdMinor())
                        .isEqualTo(1200));
    }

    @Test
    void usesDefaultThresholdWhenPropertyIsAbsent() {
        contextRunner.run(context -> assertThat(context.getBean(AmountScoringProperties.class).thresholdMinor())
                .isEqualTo(1_000_000));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AmountScoringProperties.class)
    static class TestConfiguration {
    }
}
