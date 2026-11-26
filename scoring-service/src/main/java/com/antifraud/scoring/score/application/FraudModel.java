package com.antifraud.scoring.score.application;

import com.antifraud.features.RequestFeatures;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

public interface FraudModel {
    String version(boolean full);

    CompletionStage<Double> predict(RequestFeatures request, Optional<RedisFeatureSnapshot> history);

    final class InferenceFailure extends RuntimeException {
        public InferenceFailure(Throwable cause) {
            super(cause);
        }
    }
}
