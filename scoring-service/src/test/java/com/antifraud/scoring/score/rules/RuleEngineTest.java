package com.antifraud.scoring.score.rules;

import com.antifraud.proto.v1.Reason;
import com.antifraud.scoring.score.application.AmountScoringProperties;
import com.antifraud.scoring.score.domain.Money;
import com.antifraud.scoring.score.domain.Transaction;
import com.antifraud.scoring.score.domain.UserId;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {
    private final RuleEngine rules = new RuleEngine(
            new RuleProperties(new RuleProperties.Velocity(8, 0.5), new RuleProperties.AccountTakeover(3, 0.6),
                    new RuleProperties.CardTesting(5, 3, 0.5), new RuleProperties.AmountAnomaly(10, 0.4),
                    new RuleProperties.ImpossibleTravel(900, 0.7)),
            new AmountScoringProperties(1_000_000));

    @ParameterizedTest
    @CsvSource({
            "7, 4, 3, 1, false, false, 899, , 0",
            "8, 0, 0, 1, false, false, 0, REASON_R1_VELOCITY_1M, 0.5",
            "0, 0, 0, 3, true, true, 0, REASON_R2_ACCOUNT_TAKEOVER, 0.6",
            "0, 0, 0, 3, true, false, 0, , 0",
            "0, 0, 0, 2.99, true, true, 0, , 0",
            "0, 5, 2, 1, false, false, 0, , 0",
            "0, 5, 3, 1, false, false, 0, REASON_R3_CARD_TESTING, 0.5",
            "0, 0, 0, 9.99, false, false, 0, , 0",
            "0, 0, 0, 10, false, false, 0, REASON_R4_AMOUNT_ANOMALY, 0.4",
            "0, 0, 0, 1, false, false, 900, REASON_R5_IMPOSSIBLE_TRAVEL, 0.7"
    })
    void testsEachHistoryRuleAtItsBoundary(
            long tx, long small, long merchants, double ratio, boolean device, boolean country,
            double speed, Reason reason, double score
    ) {
        var history = new RedisFeatureSnapshot(tx, 0, 0, BigInteger.ZERO, small, merchants,
                speed, ratio, 1, device, false, country);
        var result = rules.evaluate(transaction(1000, "USD"), Optional.of(history));
        assertThat(result.score()).isEqualTo(score);
        assertThat(result.hardBlock()).isFalse();
        if (reason == null) {
            assertThat(result.reasons()).isEmpty();
        } else {
            assertThat(result.reasons()).containsExactly(reason);
        }
    }

    @Test
    void capsTheSumAndKeepsAllReasonsInRuleOrder() {
        var history = new RedisFeatureSnapshot(8, 0, 0, BigInteger.ZERO, 5, 3, 900, 10, 1, true, false, true);
        var result = rules.evaluate(transaction(1_000_000, "USD"), Optional.of(history));
        assertThat(result.score()).isEqualTo(1);
        assertThat(result.hardBlock()).isTrue();
        assertThat(result.reasons()).containsExactly(Reason.REASON_R1_VELOCITY_1M, Reason.REASON_R2_ACCOUNT_TAKEOVER,
                Reason.REASON_R3_CARD_TESTING, Reason.REASON_R4_AMOUNT_ANOMALY, Reason.REASON_R5_IMPOSSIBLE_TRAVEL,
                Reason.REASON_R6_AMOUNT_LIMIT);
    }

    @Test
    void runsTheUsdHardLimitWithoutHistoryAndUsesMinorUnits() {
        assertThat(rules.evaluate(transaction(999_999, "USD"), Optional.empty()).reasons()).isEmpty();
        assertThat(rules.evaluate(transaction(1_000_000, "EUR"), Optional.empty()).reasons()).isEmpty();
        var blocked = rules.evaluate(transaction(1_000_000, "USD"), Optional.empty());
        assertThat(blocked.hardBlock()).isTrue();
        assertThat(blocked.reasons()).containsExactly(Reason.REASON_R6_AMOUNT_LIMIT);
    }

    private static Transaction transaction(long cents, String currency) {
        return new Transaction(UUID.randomUUID(), new UserId("user-1"), new Money(cents, Currency.getInstance(currency)),
                Instant.EPOCH, "merchant", "5411", "device", "192.0.2.1", "US",
                OptionalDouble.empty(), OptionalDouble.empty(), Transaction.Channel.ONLINE);
    }
}
