package com.antifraud.scoring;

import com.antifraud.scoring.score.application.AmountScoringProperties;
import com.antifraud.scoring.score.application.DecisionProperties;
import com.antifraud.scoring.score.ml.InferenceProperties;
import com.antifraud.scoring.score.redis.RedisScoringProperties;
import com.antifraud.scoring.score.rules.RuleProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AmountScoringProperties.class, DecisionProperties.class, InferenceProperties.class,
        RedisScoringProperties.class, RuleProperties.class})
public class ScoringApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScoringApplication.class, args);
    }
}
