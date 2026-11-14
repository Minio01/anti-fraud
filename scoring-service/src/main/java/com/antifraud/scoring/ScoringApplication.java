package com.antifraud.scoring;

import com.antifraud.scoring.score.application.AmountScoringProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AmountScoringProperties.class)
public class ScoringApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScoringApplication.class, args);
    }
}
