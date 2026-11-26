package com.antifraud.scoring.score.grpc;

import com.antifraud.features.RequestFeatures;
import com.antifraud.scoring.score.application.AmountScoringProperties;
import com.antifraud.scoring.score.application.DecisionProperties;
import com.antifraud.scoring.score.application.FraudModel;
import com.antifraud.scoring.score.application.RiskScorer;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;
import com.antifraud.scoring.score.rules.RuleEngine;
import com.antifraud.scoring.score.rules.RuleProperties;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class TestRiskScorer {
    static RiskScorer create(long limit) {
        var properties = new RuleProperties(new RuleProperties.Velocity(8, 0.5),
                new RuleProperties.AccountTakeover(3, 0.6), new RuleProperties.CardTesting(5, 3, 0.5),
                new RuleProperties.AmountAnomaly(10, 0.4), new RuleProperties.ImpossibleTravel(900, 0.7));
        var model = new FraudModel() {
            @Override
            public String version(boolean full) {
                return full ? "test-full" : "test-light";
            }

            @Override
            public CompletionStage<Double> predict(RequestFeatures request, Optional<RedisFeatureSnapshot> history) {
                return CompletableFuture.completedFuture(0.0);
            }
        };
        return new RiskScorer(new RuleEngine(properties, new AmountScoringProperties(limit)),
                model, new DecisionProperties(0.90, 0.50, 0.85));
    }

    private TestRiskScorer() {}
}
