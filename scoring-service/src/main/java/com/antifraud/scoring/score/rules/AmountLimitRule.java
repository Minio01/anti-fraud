package com.antifraud.scoring.score.rules;

import com.antifraud.proto.v1.Reason;
import com.antifraud.scoring.score.application.AmountScoringProperties;
import com.antifraud.scoring.score.domain.Transaction;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;

import java.util.Currency;
import java.util.Optional;

record AmountLimitRule(AmountScoringProperties config) implements RiskRule {
    private static final Currency USD = Currency.getInstance("USD");

    @Override
    public Reason reason() {
        return Reason.REASON_R6_AMOUNT_LIMIT;
    }

    @Override
    public double weight() {
        return 0;
    }

    @Override
    public boolean hardBlock() {
        return true;
    }

    @Override
    public boolean matches(Transaction transaction, Optional<RedisFeatureSnapshot> history) {
        var money = transaction.money();
        return USD.equals(money.currency()) && money.amountMinor() >= config.thresholdMinor();
    }
}
