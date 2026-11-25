package com.antifraud.mock.stream

import kotlin.math.ceil
import kotlin.math.min

internal class TokenBucket(
    initialRps: Int,
    private val capacity: Int,
    startedAtNanos: Long,
) {
    private var rps = initialRps
    private var tokens = capacity.toDouble()
    private var updatedAtNanos = startedAtNanos

    init {
        require(initialRps in 1..20_000)
        require(capacity > 0)
    }

    fun acquireDelayMillis(nowNanos: Long): Long {
        refill(nowNanos)
        if (tokens >= 1.0) {
            tokens -= 1.0
            return 0
        }
        return ceil((1.0 - tokens) * 1_000 / rps).toLong().coerceAtLeast(1)
    }

    fun updateRps(newRps: Int, nowNanos: Long) {
        require(newRps in 1..20_000)
        // До смены скорости прошедшее время учитывается по прежнему RPS.
        refill(nowNanos)
        rps = newRps
    }

    private fun refill(nowNanos: Long) {
        val elapsed = (nowNanos - updatedAtNanos).coerceAtLeast(0)
        tokens = min(capacity.toDouble(), tokens + elapsed / 1_000_000_000.0 * rps)
        updatedAtNanos = nowNanos
    }
}
