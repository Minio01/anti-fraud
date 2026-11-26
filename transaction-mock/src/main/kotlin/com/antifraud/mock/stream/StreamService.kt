package com.antifraud.mock.stream

import com.antifraud.mock.generation.NormalTransactionGenerator
import com.antifraud.mock.scenario.ScenarioCatalog
import com.antifraud.mock.scenario.UserDirectory
import com.antifraud.mock.labels.TransactionLabels
import com.antifraud.mock.scoring.ScoringGateway
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import kotlin.random.Random

class StreamService(
    private val users: UserDirectory,
    private val normal: NormalTransactionGenerator,
    private val catalog: ScenarioCatalog,
    private val scoring: ScoringGateway,
    meters: MeterRegistry,
    private val maxInFlight: Int,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: Clock = Clock.systemUTC(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val labels: TransactionLabels = TransactionLabels {},
) : AutoCloseable {
    private val lifecycle = Mutex()
    private var run: Run? = null
    private var lastStatus = StreamStatus(running = false)
    private val sentMetric = meters.counter("mock.stream.sent")
    private val errorMetric = meters.counter("mock.stream.errors")
    private val droppedMetric = meters.counter("mock.stream.dropped")

    init {
        require(maxInFlight > 0)
    }

    suspend fun configure(config: StreamConfig): StreamStatus = lifecycle.withLock {
        val active = run
        if (active != null && active.job.isActive) {
            if (active.config != config) {
                active.limiter.updateRps(config.rps)
                active.config = config
            }
            active.snapshot()
        } else {
            val started = Run(config, clock.instant(), nanoTime())
            started.job = scope.launch { produce(started) }.also { job ->
                job.invokeOnCompletion { started.finishedAtNanos = nanoTime() }
            }
            run = started
            started.snapshot()
        }
    }

    suspend fun status(): StreamStatus = lifecycle.withLock { run?.snapshot() ?: lastStatus }

    suspend fun stop() = lifecycle.withLock {
        val active = run ?: return@withLock
        // Завершаем остановку даже при отмене HTTP-запроса, прежде чем разрешить новый запуск.
        withContext(NonCancellable) { active.job.cancelAndJoin() }
        if (active.finishedAtNanos == null) active.finishedAtNanos = nanoTime()
        lastStatus = active.snapshot()
        run = null
    }

    private suspend fun produce(active: Run) = coroutineScope {
        val slots = Semaphore(maxInFlight)
        val transactions = StreamTransactions(users.users, normal, catalog, Random.nextLong())
        while (true) {
            active.limiter.awaitPermit()
            val event = transactions.next(active.config, clock.instant())
            if (!slots.tryAcquire()) {
                active.dropped()
                continue
            }
            launch {
                labels.publish(event)
                active.sent()
                try {
                    scoring.score(event.transaction)
                } catch (_: StatusException) {
                    active.error()
                } catch (_: StatusRuntimeException) {
                    active.error()
                }
            }.invokeOnCompletion { slots.release() }
        }
    }

    override fun close() = scope.cancel()

    private inner class Run(initial: StreamConfig, val startedAt: Instant, val startedAtNanos: Long) {
        @Volatile var config = initial
        @Volatile var finishedAtNanos: Long? = null
        val limiter = CoroutineRateLimiter(initial.rps, burstSize = 40, initialTokens = 1, nanoTime = nanoTime)
        lateinit var job: Job
        private var sent = 0L
        private var errors = 0L
        private var dropped = 0L

        @Synchronized
        fun sent() {
            sent++
            sentMetric.increment()
        }

        @Synchronized
        fun error() {
            errors++
            errorMetric.increment()
        }

        @Synchronized
        fun dropped() {
            dropped++
            droppedMetric.increment()
        }

        @Synchronized fun snapshot(): StreamStatus {
            val elapsed = ((finishedAtNanos ?: nanoTime()) - startedAtNanos).coerceAtLeast(0)
            return StreamStatus(job.isActive, config, startedAt, sent, errors, dropped,
                if (elapsed == 0L) 0.0 else sent * 1_000_000_000.0 / elapsed)
        }
    }
}
