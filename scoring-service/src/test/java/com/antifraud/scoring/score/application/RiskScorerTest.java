package com.antifraud.scoring.score.application;

import com.antifraud.proto.v1.Decision;
import com.antifraud.proto.v1.Reason;
import com.antifraud.scoring.score.domain.Money;
import com.antifraud.scoring.score.domain.Transaction;
import com.antifraud.scoring.score.domain.UserId;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;
import com.antifraud.scoring.score.rules.RuleEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskScorerTest {
    private final RuleEngine rules = mock(RuleEngine.class);
    private final FraudModel model = mock(FraudModel.class);
    private final Transaction tx = new Transaction(UUID.randomUUID(), new UserId("user-1"),
            new Money(1000, Currency.getInstance("USD")), Instant.EPOCH, "merchant", "5411", "device",
            "192.0.2.1", "US", OptionalDouble.empty(), OptionalDouble.empty(), Transaction.Channel.ONLINE);

    @Test
    void combinesBothScoresAndProvidesBothReasons() {
        var history = Optional.of(mock(RedisFeatureSnapshot.class));
        when(rules.evaluate(tx, history)).thenReturn(new RuleEngine.Assessment(0.5,
                List.of(Reason.REASON_R1_VELOCITY_1M), false));
        when(model.version(true)).thenReturn("test-full");
        when(model.predict(any(), eq(history))).thenReturn(CompletableFuture.completedFuture(0.6));
        var scorer = new RiskScorer(rules, model, new DecisionProperties(0.90, 0.50, 0.85));

        var result = scorer.score(tx, history).toCompletableFuture().join();

        assertThat(result.riskScore()).isCloseTo(0.59, within(1e-12));
        assertThat(result.decision()).isEqualTo(Decision.DECISION_FLAG);
        assertThat(result.modelVersion()).isEqualTo("test-full");
        assertThat(result.reasons()).containsExactly(Reason.REASON_R1_VELOCITY_1M, Reason.REASON_ML_HIGH_RISK);
    }

    @ParameterizedTest
    @CsvSource({"0.4999, DECISION_ALLOW", "0.5, DECISION_FLAG", "0.8499, DECISION_FLAG", "0.85, DECISION_BLOCK"})
    void usesInclusiveThresholdsAndTheLightModelWithoutHistory(double probability, Decision decision) {
        when(rules.evaluate(tx, Optional.empty())).thenReturn(new RuleEngine.Assessment(0, List.of(), false));
        when(model.version(false)).thenReturn("test-light");
        when(model.predict(any(), eq(Optional.empty()))).thenReturn(CompletableFuture.completedFuture(probability));
        var scorer = new RiskScorer(rules, model, new DecisionProperties(1, 0.50, 0.85));

        var result = scorer.score(tx, Optional.empty()).toCompletableFuture().join();

        assertThat(result.decision()).isEqualTo(decision);
        assertThat(result.modelVersion()).isEqualTo("test-light");
        assertThat(result.riskScore()).isEqualTo(probability);
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void fallsBackToTheRulesScoreWhenNativeInferenceFails() {
        var history = Optional.of(mock(RedisFeatureSnapshot.class));
        when(rules.evaluate(tx, history)).thenReturn(new RuleEngine.Assessment(0.7,
                List.of(Reason.REASON_R5_IMPOSSIBLE_TRAVEL), false));
        when(model.predict(any(), eq(history))).thenReturn(CompletableFuture.failedFuture(
                new FraudModel.InferenceFailure(new IllegalStateException("test"))));
        var scorer = new RiskScorer(rules, model, new DecisionProperties(0.90, 0.50, 0.85));

        var result = scorer.score(tx, history).toCompletableFuture().join();

        assertThat(result.decision()).isEqualTo(Decision.DECISION_FLAG);
        assertThat(result.riskScore()).isEqualTo(0.7);
        assertThat(result.reasons()).containsExactly(Reason.REASON_R5_IMPOSSIBLE_TRAVEL);
        assertThat(result.modelVersion()).isEqualTo("rules-only");
        assertThat(result.degraded()).isTrue();
    }

    @Test
    void hardBlockSkipsInferenceEvenWithTheMaximumMlWeight() {
        when(rules.evaluate(tx, Optional.empty())).thenReturn(new RuleEngine.Assessment(0,
                List.of(Reason.REASON_R6_AMOUNT_LIMIT), true));
        when(model.version(false)).thenReturn("test-light");
        var scorer = new RiskScorer(rules, model, new DecisionProperties(1, 0.50, 0.85));

        var result = scorer.score(tx, Optional.empty()).toCompletableFuture().join();

        assertThat(result.decision()).isEqualTo(Decision.DECISION_BLOCK);
        assertThat(result.riskScore()).isEqualTo(1);
        assertThat(result.reasons()).containsExactly(Reason.REASON_R6_AMOUNT_LIMIT);
        verify(model, never()).predict(any(), any());
    }
}
