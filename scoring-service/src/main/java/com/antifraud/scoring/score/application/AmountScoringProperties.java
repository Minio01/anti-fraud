package com.antifraud.scoring.score.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("scoring.amount-rule")
public record AmountScoringProperties(@DefaultValue("1000000") long thresholdMinor) {
    public AmountScoringProperties {
        if (thresholdMinor <= 0) {
            throw new IllegalArgumentException("Порог суммы должен быть больше нуля");
        }
    }
}
