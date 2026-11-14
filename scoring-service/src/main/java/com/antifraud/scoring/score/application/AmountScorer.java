package com.antifraud.scoring.score.application;

import com.antifraud.scoring.score.domain.Transaction;
import org.springframework.stereotype.Service;

import java.util.Currency;

@Service
public final class AmountScorer {
    private static final Currency USD = Currency.getInstance("USD");

    private final long thresholdMinor;

    public AmountScorer(AmountScoringProperties properties) {
        this.thresholdMinor = properties.thresholdMinor();
    }

    public Result score(Transaction transaction) {
        var money = transaction.money();
        if (USD.equals(money.currency()) && money.amountMinor() >= thresholdMinor) {
            return Result.AMOUNT_LIMIT_EXCEEDED;
        }
        return Result.ALLOWED;
    }

    public enum Result {
        ALLOWED(0.0), AMOUNT_LIMIT_EXCEEDED(1.0);

        private final double riskScore;

        Result(double riskScore) {
            this.riskScore = riskScore;
        }

        public double riskScore() {
            return riskScore;
        }
    }
}
