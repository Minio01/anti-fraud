package com.antifraud.mock.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TokenBucketTest {
    @Test
    fun `permissions refill at the requested rate`() {
        val bucket = TokenBucket(initialRps = 10, capacity = 1, startedAtNanos = 0)
        assertEquals(0L, bucket.acquireDelayMillis(0))
        assertEquals(100L, bucket.acquireDelayMillis(0))
        assertEquals(50L, bucket.acquireDelayMillis(50_000_000))
        assertEquals(0L, bucket.acquireDelayMillis(100_000_000))
        assertEquals(100L, bucket.acquireDelayMillis(100_000_000))
    }

    @Test
    fun `idle time cannot accumulate more than the burst capacity`() {
        val bucket = TokenBucket(initialRps = 100, capacity = 2, startedAtNanos = 0)
        val afterIdle = 60_000_000_000L
        assertEquals(0L, bucket.acquireDelayMillis(afterIdle))
        assertEquals(0L, bucket.acquireDelayMillis(afterIdle))
        assertEquals(10L, bucket.acquireDelayMillis(afterIdle))
    }

    @Test
    fun `changing rps preserves accrued tokens and uses the new refill speed`() {
        val bucket = TokenBucket(initialRps = 10, capacity = 1, startedAtNanos = 0)
        assertEquals(0L, bucket.acquireDelayMillis(0))
        bucket.updateRps(newRps = 20, nowNanos = 50_000_000)
        assertEquals(25L, bucket.acquireDelayMillis(50_000_000))
        assertEquals(0L, bucket.acquireDelayMillis(75_000_000))
    }

    @Test
    fun `high rps can issue multiple permissions after a millisecond wait`() {
        val bucket = TokenBucket(initialRps = 20_000, capacity = 20, startedAtNanos = 0)
        repeat(20) { assertEquals(0L, bucket.acquireDelayMillis(0)) }
        assertEquals(1L, bucket.acquireDelayMillis(0))
        repeat(20) { assertEquals(0L, bucket.acquireDelayMillis(1_000_000)) }
        assertEquals(1L, bucket.acquireDelayMillis(1_000_000))
    }

    @Test
    fun `invalid configuration is rejected`() {
        assertFailsWith<IllegalArgumentException> { TokenBucket(0, 1, 0) }
        assertFailsWith<IllegalArgumentException> { TokenBucket(20_001, 1, 0) }
        assertFailsWith<IllegalArgumentException> { TokenBucket(100, 0, 0) }
    }
}
