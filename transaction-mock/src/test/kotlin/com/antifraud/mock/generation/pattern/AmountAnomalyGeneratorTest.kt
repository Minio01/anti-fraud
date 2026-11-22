package com.antifraud.mock.generation.pattern

import com.antifraud.mock.generation.HomeLocation
import com.antifraud.mock.generation.UserProfile
import com.antifraud.mock.generation.UserSeeds
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AmountAnomalyGeneratorTest {
    private val user = UserProfile(
        "user-1", HomeLocation("US", 40.7128, -74.0060),
        listOf("device-1", "device-2"), listOf("192.0.2.1", "192.0.2.2"), 2_500L,
    )
    private val startedAt = Instant.parse("2026-01-01T00:00:00.123456789Z")
    private val generator = AmountAnomalyGenerator()

    @Test
    fun `one immediate fraudulent payment has ten to fifty times the average amount`() {
        repeat(100) { seed ->
            val events = generator.generate(user, UserSeeds.random(seed.toLong(), user.userId), startedAt)

            assertEquals(1, events.size, "AMOUNT_ANOMALY должен создавать ровно один платёж")
            val event = events.single()
            val tx = event.transaction
            assertEquals(Duration.ZERO, event.offset, "Платёж должен отправляться без задержки")

            val minimumAmount = user.averageAmountMinor * 10
            val maximumAmount = user.averageAmountMinor * 50

            assertTrue(tx.amountMinor in minimumAmount..maximumAmount)
            assertTrue(tx.deviceId in user.deviceIds)
            assertTrue(tx.ip in user.ips)
            assertEquals(user.home.country, tx.country)
            assertTrue(tx.hasLatitude() && tx.hasLongitude())
            assertEquals(user.home.latitude, tx.latitude)
            assertEquals(user.home.longitude, tx.longitude)
            assertEquals(user.userId, tx.userId)
            assertEquals("USD", tx.currency)
            assertEquals(startedAt, Instant.ofEpochSecond(tx.timestamp.seconds, tx.timestamp.nanos.toLong()))
            assertTrue(event.expectedFraud)
            assertEquals(FraudPattern.AMOUNT_ANOMALY, event.pattern)
        }
    }

    @Test
    fun `same seed user and start reproduce the transaction`() {
        val first = generator.generate(user, UserSeeds.random(42, user.userId), startedAt)
        val second = generator.generate(user, UserSeeds.random(42, user.userId), startedAt)

        assertEquals(first, second)
    }

    @Test
    fun `amount overflow is rejected instead of producing a wrapped amount`() {
        val oversizedProfile = user.copy(averageAmountMinor = Long.MAX_VALUE / 10 + 1)

        assertFailsWith<ArithmeticException> {
            generator.generate(oversizedProfile, UserSeeds.random(42, user.userId), startedAt)
        }
    }
}
