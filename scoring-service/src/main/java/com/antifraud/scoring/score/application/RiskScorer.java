package com.antifraud.scoring.score.application;

import com.antifraud.features.RequestFeatures;
import com.antifraud.proto.v1.Decision;
import com.antifraud.proto.v1.Reason;
import com.antifraud.scoring.score.domain.Transaction;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;
import com.antifraud.scoring.score.rules.RuleEngine;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

@Service
public final class RiskScorer {
    private final RuleEngine rules;
    private final FraudModel model;
    private final DecisionProperties config;

    public RiskScorer(RuleEngine rules, FraudModel model, DecisionProperties config) {
        this.rules = rules;
        this.model = model;
        this.config = config;
    }

    public CompletionStage<Result> score(Transaction transaction, Optional<RedisFeatureSnapshot> history) {
        var assessment = rules.evaluate(transaction, history);
        var version = model.version(history.isPresent());
        if (assessment.hardBlock()) {
            return CompletableFuture.completedFuture(
                    new Result(Decision.DECISION_BLOCK, 1, assessment.reasons(), version, history.isEmpty()));
        }
        var request = RequestFeatures.from(transaction.money().amountMinor(), transaction.money().currency().getCurrencyCode(),
                transaction.timestamp(), transaction.merchantCategory(), transaction.channel().name(), transaction.country());
        return model.predict(request, history).handle((probability, error) -> {
            if (error == null) {
                return decide(assessment, probability, version, history.isEmpty());
            }
            var cause = error instanceof CompletionException ? error.getCause() : error;
            if (cause instanceof FraudModel.InferenceFailure) {
                return new Result(verdict(assessment.score()), assessment.score(), assessment.reasons(), "rules-only", true);
            }
            throw new CompletionException(cause);
        });
    }

    private Result decide(RuleEngine.Assessment assessment, double probability, String version, boolean degraded) {
        var risk = config.mlWeight() * probability + (1 - config.mlWeight()) * assessment.score();
        var decision = verdict(risk);
        var reasons = assessment.reasons();
        if (decision != Decision.DECISION_ALLOW && config.mlWeight() > 0 && probability >= config.flagThreshold()) {
            var withMl = new ArrayList<>(reasons);
            withMl.add(Reason.REASON_ML_HIGH_RISK);
            reasons = List.copyOf(withMl);
        }
        return new Result(decision, risk, reasons, version, degraded);
    }

    private Decision verdict(double risk) {
        if (risk >= config.blockThreshold()) {
            return Decision.DECISION_BLOCK;
        }
        return risk >= config.flagThreshold() ? Decision.DECISION_FLAG : Decision.DECISION_ALLOW;
    }

    public record Result(Decision decision, double riskScore, List<Reason> reasons, String modelVersion, boolean degraded) {}
}
