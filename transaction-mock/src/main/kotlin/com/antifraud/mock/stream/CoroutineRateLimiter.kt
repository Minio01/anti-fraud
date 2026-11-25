package com.antifraud.mock.stream

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CoroutineRateLimiter(
    initialRps: Int,
    burstSize: Int,
    initialTokens: Int = burstSize,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val mutex = Mutex()
    private val bucket = TokenBucket(initialRps, burstSize, nanoTime(), initialTokens)

    suspend fun awaitPermit() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val waitingTime = mutex.withLock {
                bucket.acquireDelayMillis(nanoTime())
            }

            if (waitingTime != 0L) {
                delay(waitingTime)
            } else {
                return
            }
        }
    }

    suspend fun updateRps(newRps: Int) {
        mutex.withLock { bucket.updateRps(newRps, nanoTime()) }
    }
}
