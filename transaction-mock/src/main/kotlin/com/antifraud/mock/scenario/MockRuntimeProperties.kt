package com.antifraud.mock.scenario

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("mock.runtime")
data class MockRuntimeProperties(
    val userPoolSize: Int = 10_000,
    val meanAmountMinor: Long = 2_500,
    val seed: Long = 42,
    val maxConcurrentScenarios: Int = 16,
    val maxRetainedRuns: Int = 256,
    val maxStreamInFlight: Int = 256,
) {
    init {
        require(userPoolSize > 0 && meanAmountMinor > 0)
        require(maxConcurrentScenarios > 0 && maxRetainedRuns >= maxConcurrentScenarios)
        require(maxStreamInFlight > 0)
    }
}
