package com.antifraud.scoring.score.rules;

import com.antifraud.proto.v1.Reason;
import com.antifraud.scoring.score.application.AmountScoringProperties;
import com.antifraud.scoring.score.domain.Transaction;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public final class RuleEngine {
    private final List<RiskRule> rules;

    public RuleEngine(RuleProperties config, AmountScoringProperties limit) {
        rules = List.of(new VelocityRule(config.velocity()), new AccountTakeoverRule(config.accountTakeover()),
                new CardTestingRule(config.cardTesting()), new AmountAnomalyRule(config.amountAnomaly()),
                new ImpossibleTravelRule(config.impossibleTravel()), new AmountLimitRule(limit));
    }

    public Assessment evaluate(Transaction transaction, Optional<RedisFeatureSnapshot> history) {
        double score = 0;
        boolean hardBlock = false;
        var reasons = new ArrayList<Reason>();
        for (var rule : rules) {
            if (rule.matches(transaction, history)) {
                score += rule.weight();
                hardBlock |= rule.hardBlock();
                reasons.add(rule.reason());
            }
        }
        return new Assessment(Math.min(score, 1), List.copyOf(reasons), hardBlock);
    }

    public record Assessment(double score, List<Reason> reasons, boolean hardBlock) {}
}
