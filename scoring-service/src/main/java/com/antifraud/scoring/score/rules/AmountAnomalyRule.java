package com.antifraud.scoring.score.rules;

import com.antifraud.proto.v1.Reason;
import com.antifraud.scoring.score.domain.Transaction;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;

import java.util.Optional;

record AmountAnomalyRule(RuleProperties.AmountAnomaly config) implements RiskRule {
    @Override
    public Reason reason() {
        return Reason.REASON_R4_AMOUNT_ANOMALY;
    }

    @Override
    public double weight() {
        return config.weight();
    }

    @Override
    public boolean matches(Transaction transaction, Optional<RedisFeatureSnapshot> history) {
        return history.filter(f -> f.amountToAvgRatio() >= config.minRatio()).isPresent();
    }
}
