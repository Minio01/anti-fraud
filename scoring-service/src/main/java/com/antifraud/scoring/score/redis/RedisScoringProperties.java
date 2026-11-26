package com.antifraud.scoring.score.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("scoring.redis")
public record RedisScoringProperties(
        @DefaultValue("5ms") Duration deadline,
        @DefaultValue("1s") Duration openDuration
) {
    public RedisScoringProperties {
        if (deadline.isZero() || deadline.isNegative() || openDuration.isZero() || openDuration.isNegative()) {
            throw new IllegalArgumentException("Таймаут Redis и время открытого circuit breaker должны быть положительными");
        }
    }
}
