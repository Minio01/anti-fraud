package com.antifraud.mock.generation.pattern

import java.time.Duration

data class PatternMetadata(
    val description: String,
    val maxTransactions: Int,
    val maxDuration: Duration,
) {
    init {
        require(description.isNotBlank())
        require(maxTransactions > 0)
        require(!maxDuration.isNegative)
    }
}
