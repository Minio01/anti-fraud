package com.antifraud.scoring.score.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("scoring.decision")
public record DecisionProperties(
        @DefaultValue("0.90") double mlWeight,
        @DefaultValue("0.50") double flagThreshold,
        @DefaultValue("0.85") double blockThreshold
) {
    public DecisionProperties {
        if (!(mlWeight >= 0 && mlWeight <= 1)) {
            throw new IllegalArgumentException("Вес ML должен лежать от 0 до 1");
        }
        if (!(flagThreshold > 0 && flagThreshold < blockThreshold && blockThreshold <= 1)) {
            throw new IllegalArgumentException("Требуется 0 < порог FLAG < порог BLOCK <= 1");
        }
    }
}
