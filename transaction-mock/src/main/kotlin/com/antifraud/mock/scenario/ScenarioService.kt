package com.antifraud.mock.scenario

import com.antifraud.mock.generation.UserSeeds
import com.antifraud.mock.generation.pattern.FraudPattern
import com.antifraud.mock.generation.pattern.ScheduledTransaction
import com.antifraud.mock.labels.TransactionLabels
import com.antifraud.mock.scoring.ScoringGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.util.UUID

class ScenarioService(
    private val catalog: ScenarioCatalog,
    private val users: UserDirectory,
    private val scoring: ScoringGateway,
    private val properties: MockRuntimeProperties,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: Clock = Clock.systemUTC(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val labels: TransactionLabels = TransactionLabels {},
) : AutoCloseable {
    private val slots = Semaphore(properties.maxConcurrentScenarios)
    private val lock = Any()
    private val runs = linkedMapOf<UUID, ScenarioRun>()
    private var closed = false
    //private val patterns = FraudPattern

    //fun listOfScenario() {}

    fun start(pattern: FraudPattern, request: ScenarioRunRequest = ScenarioRunRequest()): ScenarioRun {
        if (request.userId != null && (request.userId.isBlank() || request.userId.length > 64)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "userId должен содержать от 1 до 64 символов")
        }
        val runId = UUID.randomUUID()
        val user = users.findOrChoose(request.userId, UserSeeds.random(properties.seed, runId.toString()))
        val events = catalog.generator(pattern).generate(
            user, UserSeeds.random(properties.seed xor runId.mostSignificantBits, user.userId), clock.instant(),
        )
        if (!slots.tryAcquire()) throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Слишком много активных сценариев")
        val run = ScenarioRun(runId, pattern, user.userId, RunStatus.RUNNING, emptyList())
        try {
            synchronized(lock) {
                if (closed) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Сервис останавливается")
                if (runs.size >= properties.maxRetainedRuns) {
                    val completed = runs.values.firstOrNull { it.status != RunStatus.RUNNING }
                        ?: throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "История запусков заполнена")
                    runs.remove(completed.runId)
                }
                runs[runId] = run
            }
        } catch (exception: Exception) {
            slots.release()
            throw exception
        }
        scope.launch { execute(runId, events) }.invokeOnCompletion { failure ->
            if (failure != null) synchronized(lock) {
                runs[runId]?.let { run ->
                    if (run.status == RunStatus.RUNNING) runs[runId] = run.copy(status = RunStatus.FAILED)
                }
            }
            slots.release()
        }
        return run
    }

    fun get(pattern: FraudPattern, runId: UUID): ScenarioRun = synchronized(lock) {
        runs[runId]?.takeIf { it.pattern == pattern }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Запуск сценария не найден")
    }

    private suspend fun execute(runId: UUID, events: List<ScheduledTransaction>) {
        val startedAtNanos = nanoTime()
        var pending: ScenarioTransactionResult? = null
        try {
            for (event in events) {
                val remaining = event.offset.toNanos() - (nanoTime() - startedAtNanos)
                if (remaining > 0) delay((remaining + 999_999) / 1_000_000)
                pending = ScenarioTransactionResult(event.transaction.transactionId, event.expectedFraud)
                labels.publish(event)
                val response = scoring.score(event.transaction)
                append(runId, pending.copy(
                    decision = response.decision.name.removePrefix("DECISION_"),
                    riskScore = response.riskScore,
                    reasons = response.reasonsList.map { it.name },
                ))
                pending = null
            }
            setStatus(runId, RunStatus.COMPLETED)
        } catch (exception: CancellationException) {
            setStatus(runId, RunStatus.FAILED)
            throw exception
        } catch (exception: Exception) {
            pending?.let { append(runId, it) }
            setStatus(runId, RunStatus.FAILED)
        }
    }

    private fun append(runId: UUID, result: ScenarioTransactionResult) = synchronized(lock) {
        val run = runs.getValue(runId)
        runs[runId] = run.copy(transactions = run.transactions + result)
    }

    private fun setStatus(runId: UUID, status: RunStatus) = synchronized(lock) {
        val run = runs.getValue(runId)
        runs[runId] = run.copy(status = status)
    }

    override fun close() {
        synchronized(lock) { closed = true }
        scope.cancel()
    }
}
