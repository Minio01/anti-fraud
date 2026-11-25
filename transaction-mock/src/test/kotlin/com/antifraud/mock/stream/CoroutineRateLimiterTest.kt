package com.antifraud.mock.stream

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineRateLimiterTest {
    @Test
    fun `waiting call returns after receiving one permission`() = runTest {
        val limiter = CoroutineRateLimiter(10, 1) { testScheduler.currentTime * 1_000_000 }
        limiter.awaitPermit()
        val waiting = launch { limiter.awaitPermit() }
        runCurrent()
        assertFalse(waiting.isCompleted)

        advanceTimeBy(100)
        runCurrent()

        assertTrue(waiting.isCompleted, "После пополнения одного разрешения вызов должен завершиться")
    }

    @Test
    fun `waiting coroutine can be cancelled without advancing time`() = runTest {
        val limiter = CoroutineRateLimiter(1, 1) { testScheduler.currentTime * 1_000_000 }
        limiter.awaitPermit()
        val waiting = launch { limiter.awaitPermit() }
        runCurrent()

        waiting.cancel()
        runCurrent()

        assertTrue(waiting.isCancelled)
        assertTrue(waiting.isCompleted)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `parallel callers each consume one permission without exceeding the rate`() = runTest {
        val limiter = CoroutineRateLimiter(10, 1) { testScheduler.currentTime * 1_000_000 }
        var completed = 0
        val callers = List(4) { launch { limiter.awaitPermit(); completed++ } }
        runCurrent()
        assertEquals(1, completed)

        advanceTimeBy(300)
        runCurrent()

        assertEquals(4, completed)
        assertTrue(callers.all { it.isCompleted })
    }

    @Test
    fun `waiting does not hold the mutex and prevent rps updates`() = runTest {
        val limiter = CoroutineRateLimiter(1, 1) { testScheduler.currentTime * 1_000_000 }
        limiter.awaitPermit()
        val waiting = launch { limiter.awaitPermit() }
        runCurrent()

        val update = launch { limiter.updateRps(10) }
        runCurrent()

        assertTrue(update.isCompleted)
        waiting.cancel()
        runCurrent()
    }
}
