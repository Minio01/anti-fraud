package com.antifraud.mock.stream

import com.antifraud.mock.generation.LogNormalAmountSampler
import com.antifraud.mock.generation.NormalTransactionGenerator
import com.antifraud.mock.generation.pattern.AccountTakeoverGenerator
import com.antifraud.mock.generation.pattern.AmountAnomalyGenerator
import com.antifraud.mock.generation.pattern.CardTestingGenerator
import com.antifraud.mock.generation.pattern.FraudPattern
import com.antifraud.mock.generation.pattern.ImpossibleTravelGenerator
import com.antifraud.mock.generation.pattern.VelocityBurstGenerator
import com.antifraud.mock.scenario.MockRuntimeProperties
import com.antifraud.mock.scenario.ScenarioCatalog
import com.antifraud.mock.scenario.UserDirectory
import com.antifraud.mock.scoring.ScoringGateway
import com.antifraud.proto.v1.ScoreResponse
import io.grpc.Status
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StreamServiceTest {
    private val amounts = LogNormalAmountSampler(0.75)
    private val normal = NormalTransactionGenerator(amounts, deviationRate = 0.0)
    private val users = UserDirectory(MockRuntimeProperties(userPoolSize = 10), amounts)
    private val catalog = ScenarioCatalog(listOf(
        VelocityBurstGenerator(), AccountTakeoverGenerator(0.2, amounts), CardTestingGenerator(),
        AmountAnomalyGenerator(), ImpossibleTravelGenerator(),
    ))
    private val startedAt = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `slow scoring drops attempts without stopping the producer and stop cancels in flight calls`() = runTest {
        var cancelled = 0
        val gateway = ScoringGateway {
            try {
                awaitCancellation()
            } finally {
                cancelled++
            }
        }
        SimpleMeterRegistry().let { meters ->
            StreamService(users, normal, catalog, gateway, meters, 1,
                CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                Clock.fixed(startedAt, ZoneOffset.UTC), { testScheduler.currentTime * 1_000_000 },
            ).use { stream ->
                val config = StreamConfig(100, 0.0, emptyList())
                assertFalse(stream.status().running)
                assertTrue(stream.configure(config).running)
                runCurrent()
                advanceTimeBy(40)
                runCurrent()
                val beforeUpdate = stream.configure(config)
                assertEquals(1L, beforeUpdate.sent)
                assertEquals(4L, beforeUpdate.dropped)
                assertEquals(0L, beforeUpdate.errors)

                assertEquals(startedAt, stream.configure(config.copy(rps = 200)).startedAt)
                advanceTimeBy(40)
                runCurrent()
                assertEquals(12L, stream.status().dropped)
                assertEquals(12.0, meters.get("mock.stream.dropped").counter().count())

                stream.stop()
                val stopped = stream.status()
                assertFalse(stopped.running)
                assertEquals(1, cancelled)
                advanceTimeBy(100)
                stream.stop()
                assertEquals(stopped, stream.status())
                assertEquals(0L, stream.configure(config).dropped)
            }
        }
    }

    @Test
    fun `grpc errors count as sent and release the slot for the next request`() = runTest {
        var calls = 0
        val gateway = ScoringGateway {
            calls++
            if (calls == 1) throw Status.UNAVAILABLE.asException()
            ScoreResponse.getDefaultInstance()
        }
        SimpleMeterRegistry().let { meters ->
            StreamService(users, normal, catalog, gateway, meters, 1,
                CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
                Clock.fixed(startedAt, ZoneOffset.UTC), { testScheduler.currentTime * 1_000_000 },
            ).use { stream ->
                stream.configure(StreamConfig(100, 0.0, emptyList()))
                advanceTimeBy(21)
                assertEquals(3L, stream.status().sent)
                assertEquals(1L, stream.status().errors)
                assertEquals(0L, stream.status().dropped)
            }
        }
    }

    @Test
    fun `ordinary payments select users in proportion to their daily activity`() {
        val quiet = users.users[0].copy(paymentsPerDay = 1)
        val active = users.users[1].copy(paymentsPerDay = 20)
        val transactions = StreamTransactions(listOf(quiet, active), normal, catalog, seed = 42)
        val config = StreamConfig(100, 0.0, emptyList())
        var quietPayments = 0
        repeat(21_000) { index ->
            val event = transactions.next(config, startedAt.plusMillis(index * 10L))
            if (event.transaction.userId == quiet.userId) quietPayments++
        }
        assertTrue(quietPayments in 900..1_100, "Ожидалось около 1000 платежей, получено $quietPayments")
    }

    @Test
    fun `mix counts fraud per transaction and preserves the spacing of a pattern`() {
        val transactions = StreamTransactions(users.users, normal, catalog, seed = 42)
        val config = StreamConfig(10, 0.2, listOf(FraudPattern.CARD_TESTING))
        val events = List(100_000) { index ->
            transactions.next(config, startedAt.plusMillis(index * 100L))
        }
        val fraudRate = events.count { it.expectedFraud }.toDouble() / events.size
        assertTrue(fraudRate in 0.19..0.21, "Фактическая доля фрода: $fraudRate")
        assertTrue(events.all { it.pattern == null || it.pattern == FraudPattern.CARD_TESTING })
        assertTrue(events.any { it.expectedFraud && it.offset.toMinutes() >= 2 })
        assertFalse(transactions.next(config.copy(fraudRate = 0.0), startedAt.plusSeconds(11_000)).expectedFraud)
    }
}
