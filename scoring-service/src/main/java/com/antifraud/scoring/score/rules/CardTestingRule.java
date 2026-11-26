package com.antifraud.scoring.score.rules;

import com.antifraud.proto.v1.Reason;
import com.antifraud.scoring.score.domain.Transaction;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;

import java.util.Optional;

record CardTestingRule(RuleProperties.CardTesting config) implements RiskRule {
    @Override
    public Reason reason() {
        return Reason.REASON_R3_CARD_TESTING;
    }

    @Override
    public double weight() {
        return config.weight();
    }

    @Override
    public boolean matches(Transaction transaction, Optional<RedisFeatureSnapshot> history) {
        return history.filter(f -> f.smallTxCount10m() >= config.minCount()
                && f.smallTxDistinctMerchants10m() >= config.minMerchants()).isPresent();
    }
}
