package com.antifraud.mock.generation

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("mock.generation")
data class GenerationProperties(
    val sigma: Double,
    val accountTakeoverLargePaymentProbability: Double,
) {
    init {
        require(sigma.isFinite() && sigma > 0) { "mock.generation.sigma должна быть конечной и положительной" }
        require(accountTakeoverLargePaymentProbability.isFinite() && accountTakeoverLargePaymentProbability in 0.0..1.0) {
            "mock.generation.account-takeover-large-payment-probability должна быть от 0 до 1"
        }
    }
}
