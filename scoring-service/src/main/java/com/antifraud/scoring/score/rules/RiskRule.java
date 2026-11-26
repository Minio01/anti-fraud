package com.antifraud.scoring.score.rules;

import com.antifraud.proto.v1.Reason;
import com.antifraud.scoring.score.domain.Transaction;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;

import java.util.Optional;

interface RiskRule {
    Reason reason();

    double weight();

    boolean matches(Transaction transaction, Optional<RedisFeatureSnapshot> history);

    default boolean hardBlock() {
        return false;
    }
}
