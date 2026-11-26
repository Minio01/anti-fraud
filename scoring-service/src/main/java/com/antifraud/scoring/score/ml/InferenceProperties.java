package com.antifraud.scoring.score.ml;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("scoring.ml")
public record InferenceProperties(
        @DefaultValue("2") int threads,
        @DefaultValue("64") int queueCapacity
) {
    public InferenceProperties {
        if (threads <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("Пул инференса и его очередь должны иметь положительный размер");
        }
    }
}
