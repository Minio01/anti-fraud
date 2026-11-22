package com.antifraud.mock.generation.pattern

import com.antifraud.features.GeoDistance
import com.antifraud.mock.generation.HomeLocation
import com.antifraud.mock.generation.LogNormalAmountSampler
import com.antifraud.mock.generation.UserProfile
import com.antifraud.mock.generation.UserSeeds
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemainingPatternsTest {
    private val user = UserProfile(
        "user-1", HomeLocation("US", 40.7128, -74.0060),
        listOf("device-1"), listOf("203.0.113.1"), 2_500,
    )
    private val startedAt = Instant.parse("2026-01-01T00:00:00.123456789Z")
    private val amounts = LogNormalAmountSampler(sigma = 0.75)

    @Test
    fun `card testing sends five to fifteen small payments at distinct merchants then a large one`() {
        repeat(100) { seed ->
            val events = CardTestingGenerator().generate(user, UserSeeds.random(seed.toLong(), user.userId), startedAt)
            val small = events.dropLast(1)
            assertTrue(small.size in 5..15)
            assertTrue(small.all { it.transaction.amountMinor in 1L..199L })
            assertEquals(small.size, small.map { it.transaction.merchantId }.toSet().size)
            assertTrue(events.last().transaction.amountMinor >= user.averageAmountMinor * 3)
            assertTrue(events.last().offset in Duration.ofMinutes(2)..Duration.ofMinutes(5))
            assertSchedule(events, FraudPattern.CARD_TESTING)
            assertTrue(events.all { it.expectedFraud })
        }
    }

    @Test
    fun `takeover can include a large foreign payment with an unknown device and ip`() {
        repeat(100) { seed ->
            val events = AccountTakeoverGenerator(largePaymentProbability = 1.0, amounts)
                .generate(user, UserSeeds.random(seed.toLong(), user.userId), startedAt)
            assertEquals(2, events.size)
            assertTrue(events.last().offset in Duration.ofMinutes(1)..Duration.ofMinutes(3))
            val payment = events.last().transaction
            assertTrue(payment.amountMinor >= user.averageAmountMinor * 3)
            assertTrue(payment.deviceId !in user.deviceIds)
            assertTrue(payment.ip !in user.ips)
            assertTrue(payment.country != user.home.country)
            assertTrue(events.last().expectedFraud)
            assertSchedule(events, FraudPattern.ACCOUNT_TAKEOVER)
        }
    }

    @Test
    fun `takeover without a large followup is still labelled as fraud`() {
        val generator = AccountTakeoverGenerator(largePaymentProbability = 0.0, amounts)
        val sampledAmounts = mutableSetOf<Long>()
        repeat(100) { seed ->
            val events = generator.generate(user, UserSeeds.random(seed.toLong(), user.userId), startedAt)
            assertEquals(1, events.size)
            val event = events.single()
            assertTrue(event.expectedFraud)
            assertTrue(event.transaction.deviceId !in user.deviceIds)
            assertTrue(event.transaction.ip !in user.ips)
            assertTrue(event.transaction.country != user.home.country)
            sampledAmounts += event.transaction.amountMinor
            assertSchedule(events, FraudPattern.ACCOUNT_TAKEOVER)
        }
        assertTrue(sampledAmounts.size > 1)
    }

    @Test
    fun `takeover probability produces both branches reproducibly`() {
        // 0,5 — тестовый параметр, а не оценка реальной частоты атак.
        val generator = AccountTakeoverGenerator(largePaymentProbability = 0.5, amounts)
        val sizes = mutableSetOf<Int>()
        repeat(100) { seed ->
            val first = generator.generate(user, UserSeeds.random(seed.toLong(), user.userId), startedAt)
            val second = generator.generate(user, UserSeeds.random(seed.toLong(), user.userId), startedAt)
            assertEquals(first, second)
            sizes += first.size
        }
        assertEquals(setOf(1, 2), sizes)
    }

    @Test
    fun `takeover rejects invalid probabilities`() {
        listOf(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY).forEach { probability ->
            assertFailsWith<IllegalArgumentException> { AccountTakeoverGenerator(probability, amounts) }
        }
    }

    @Test
    fun `impossible travel starts at home and exceeds the travel speed threshold`() {
        val homes = listOf(user.home, HomeLocation("JP", 35.6762, 139.6503), HomeLocation("AU", -33.8688, 151.2093))
        homes.forEach { home ->
            val traveler = user.copy(home = home)
            val events = ImpossibleTravelGenerator().generate(traveler, UserSeeds.random(42, user.userId), startedAt)
            assertEquals(2, events.size)
            assertTrue(!events.first().expectedFraud)
            assertTrue(events.last().expectedFraud)
            assertEquals(home.country, events.first().transaction.country)
            assertTrue(events.last().transaction.country != home.country)
            val destination = events.last().transaction
            val distance = GeoDistance.kilometers(home.latitude, home.longitude, destination.latitude, destination.longitude)
            val speed = distance / (events.last().offset.toMillis() / 3_600_000.0)
            assertTrue(distance > 1_000)
            assertTrue(speed > 900)
            assertSchedule(events, FraudPattern.IMPOSSIBLE_TRAVEL)
        }
    }

    @Test
    fun `remaining patterns reproduce all fields for the same seed and start`() {
        listOf(CardTestingGenerator(), AccountTakeoverGenerator(0.5, amounts), ImpossibleTravelGenerator()).forEach { generator ->
            assertEquals(
                generator.generate(user, UserSeeds.random(42, user.userId), startedAt),
                generator.generate(user, UserSeeds.random(42, user.userId), startedAt),
            )
        }
    }

    private fun assertSchedule(events: List<ScheduledTransaction>, pattern: FraudPattern) {
        assertEquals(Duration.ZERO, events.first().offset)
        assertEquals(events.size, events.map { it.transaction.transactionId }.toSet().size)
        assertTrue(events.zipWithNext().all { (first, second) -> first.offset < second.offset })
        events.forEach { event ->
            assertEquals(pattern, event.pattern)
            assertEquals(user.userId, event.transaction.userId)
            assertEquals("USD", event.transaction.currency)
            assertTrue(event.transaction.amountMinor > 0)
            assertEquals(startedAt.plus(event.offset), Instant.ofEpochSecond(
                event.transaction.timestamp.seconds, event.transaction.timestamp.nanos.toLong(),
            ))
        }
    }
}
