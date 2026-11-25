package com.antifraud.mock.generation

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("mock.generation")
data class GenerationProperties(
    val sigma: Double,
    val accountTakeoverLargePaymentProbability: Double,
    val tripDuration: Duration = Duration.ofMinutes(30),
) {
    init {
        require(sigma.isFinite() && sigma > 0) { "mock.generation.sigma должна быть конечной и положительной" }
        require(tripDuration > Duration.ZERO) { "mock.generation.trip-duration должна быть положительной" }
        require(accountTakeoverLargePaymentProbability.isFinite() && accountTakeoverLargePaymentProbability in 0.0..1.0) {
            "mock.generation.account-takeover-large-payment-probability должна быть от 0 до 1"
        }
    }
}
