package com.antifraud.mock.scenario

import com.antifraud.mock.generation.LogNormalAmountSampler
import com.antifraud.mock.generation.pattern.AccountTakeoverGenerator
import com.antifraud.mock.generation.pattern.AmountAnomalyGenerator
import com.antifraud.mock.generation.pattern.CardTestingGenerator
import com.antifraud.mock.generation.pattern.FraudPattern
import com.antifraud.mock.generation.pattern.ImpossibleTravelGenerator
import com.antifraud.mock.generation.pattern.VelocityBurstGenerator
import com.antifraud.mock.scoring.ScoringGateway
import com.antifraud.proto.v1.Decision
import com.antifraud.proto.v1.ScoreResponse
import io.grpc.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioServiceTest {
    private val amounts = LogNormalAmountSampler(0.75)
    private val properties = MockRuntimeProperties(userPoolSize = 10, maxConcurrentScenarios = 1, maxRetainedRuns = 1)
    private val catalog = ScenarioCatalog(listOf(
        VelocityBurstGenerator(), AccountTakeoverGenerator(0.2, amounts), CardTestingGenerator(),
        AmountAnomalyGenerator(), ImpossibleTravelGenerator(),
    ))
    private val success = ScoringGateway { transaction ->
        ScoreResponse.newBuilder().setTransactionId(transaction.transactionId).setDecision(Decision.DECISION_ALLOW).build()
    }

    @Test
    fun `scenario returns immediately and sends transactions according to offsets`() = runTest {
        val sendTimes = mutableListOf<Long>()
        val gateway = ScoringGateway { transaction ->
            sendTimes += testScheduler.currentTime
            success.score(transaction)
        }
        service(gateway).use { service ->
            val run = service.start(FraudPattern.IMPOSSIBLE_TRAVEL, ScenarioRunRequest("user-1"))
            assertEquals(RunStatus.RUNNING, run.status)
            assertTrue(sendTimes.isEmpty())
            runCurrent()
            assertEquals(listOf(0L), sendTimes)
            assertEquals(1, service.get(run.pattern, run.runId).transactions.size)
            advanceTimeBy(300_000)
            runCurrent()
            val completed = service.get(run.pattern, run.runId)
            assertEquals(RunStatus.COMPLETED, completed.status)
            assertEquals(2, completed.transactions.size)
            assertTrue(sendTimes.last() in 120_000L..300_000L)
            assertTrue(completed.transactions.all { it.decision == "ALLOW" })
        }
    }

    @Test
    fun `grpc failure marks the run failed and preserves the attempted transaction`() = runTest {
        service(ScoringGateway { throw Status.UNAVAILABLE.asException() }).use { service ->
            val run = service.start(FraudPattern.AMOUNT_ANOMALY)
            runCurrent()
            val failed = service.get(run.pattern, run.runId)
            assertEquals(RunStatus.FAILED, failed.status)
            assertEquals(1, failed.transactions.size)
            assertNull(failed.transactions.single().decision)
        }
    }

    @Test
    fun `concurrent runs are limited and old completed runs are evicted`() = runTest {
        service(success).use { service ->
            val first = service.start(FraudPattern.VELOCITY_BURST)
            assertEquals(429, assertFailsWith<ResponseStatusException> {
                service.start(FraudPattern.AMOUNT_ANOMALY)
            }.statusCode.value())
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(RunStatus.COMPLETED, service.get(first.pattern, first.runId).status)
            service.start(FraudPattern.AMOUNT_ANOMALY)
            assertEquals(404, assertFailsWith<ResponseStatusException> { service.get(first.pattern, first.runId) }.statusCode.value())
        }
    }

    @Test
    fun `unknown user and wrong pattern are rejected`() = runTest {
        service(success).use { service ->
            assertEquals(404, assertFailsWith<ResponseStatusException> {
                service.start(FraudPattern.AMOUNT_ANOMALY, ScenarioRunRequest("unknown"))
            }.statusCode.value())
            assertEquals(400, assertFailsWith<ResponseStatusException> {
                service.start(FraudPattern.AMOUNT_ANOMALY, ScenarioRunRequest(""))
            }.statusCode.value())
            val run = service.start(FraudPattern.AMOUNT_ANOMALY)
            assertEquals(404, assertFailsWith<ResponseStatusException> {
                service.get(FraudPattern.CARD_TESTING, run.runId)
            }.statusCode.value())
            assertEquals(404, assertFailsWith<ResponseStatusException> {
                service.get(run.pattern, UUID.randomUUID())
            }.statusCode.value())
        }
    }

    @Test
    fun `closing before execution cancels the run and rejects new starts`() = runTest {
        val service = service(success)
        val run = service.start(FraudPattern.AMOUNT_ANOMALY)
        service.close()
        runCurrent()
        assertEquals(RunStatus.FAILED, service.get(run.pattern, run.runId).status)
        assertEquals(503, assertFailsWith<ResponseStatusException> {
            service.start(FraudPattern.AMOUNT_ANOMALY)
        }.statusCode.value())
    }

    private fun TestScope.service(gateway: ScoringGateway): ScenarioService = ScenarioService(
        catalog, UserDirectory(properties, amounts), gateway, properties,
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
        { testScheduler.currentTime * 1_000_000 },
    )
}
