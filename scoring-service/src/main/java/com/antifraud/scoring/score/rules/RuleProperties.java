package com.antifraud.scoring.score.rules;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("scoring.rules")
public record RuleProperties(
        @DefaultValue Velocity velocity,
        @DefaultValue AccountTakeover accountTakeover,
        @DefaultValue CardTesting cardTesting,
        @DefaultValue AmountAnomaly amountAnomaly,
        @DefaultValue ImpossibleTravel impossibleTravel
) {
    public record Velocity(@DefaultValue("8") long minCount, @DefaultValue("0.5") double weight) {
        public Velocity {
            requirePositive(minCount);
            requireWeight(weight);
        }
    }

    public record AccountTakeover(@DefaultValue("3") double minRatio, @DefaultValue("0.6") double weight) {
        public AccountTakeover {
            requirePositive(minRatio);
            requireWeight(weight);
        }
    }

    public record CardTesting(
            @DefaultValue("5") long minCount, @DefaultValue("3") long minMerchants, @DefaultValue("0.5") double weight
    ) {
        public CardTesting {
            requirePositive(minCount);
            requirePositive(minMerchants);
            requireWeight(weight);
        }
    }

    public record AmountAnomaly(@DefaultValue("10") double minRatio, @DefaultValue("0.4") double weight) {
        public AmountAnomaly {
            requirePositive(minRatio);
            requireWeight(weight);
        }
    }

    public record ImpossibleTravel(@DefaultValue("900") double minSpeedKmh, @DefaultValue("0.7") double weight) {
        public ImpossibleTravel {
            requirePositive(minSpeedKmh);
            requireWeight(weight);
        }
    }

    private static void requirePositive(double threshold) {
        if (!Double.isFinite(threshold) || threshold <= 0) {
            throw new IllegalArgumentException("Порог правила должен быть положительным и конечным");
        }
    }

    private static void requireWeight(double weight) {
        if (!(weight >= 0 && weight <= 1)) {
            throw new IllegalArgumentException("Вес правила должен лежать от 0 до 1");
        }
    }
}
