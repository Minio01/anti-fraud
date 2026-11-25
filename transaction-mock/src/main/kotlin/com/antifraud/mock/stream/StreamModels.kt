package com.antifraud.mock.stream

import com.antifraud.mock.generation.pattern.FraudPattern
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant

data class StreamRequest(
    val rps: Int?,
    val fraudRate: Double? = 0.015,
    val patterns: List<FraudPattern?> = FraudPattern.entries,
)

data class StreamConfig(val rps: Int, val fraudRate: Double, val patterns: List<FraudPattern>)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class StreamStatus(
    val running: Boolean,
    val config: StreamConfig? = null,
    val startedAt: Instant? = null,
    val sent: Long = 0,
    val errors: Long = 0,
    val dropped: Long = 0,
    val actualRps: Double = 0.0,
)
