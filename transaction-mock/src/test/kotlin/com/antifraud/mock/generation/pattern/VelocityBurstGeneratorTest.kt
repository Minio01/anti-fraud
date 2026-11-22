package com.antifraud.mock.generation.pattern

import com.antifraud.mock.generation.HomeLocation
import com.antifraud.mock.generation.UserProfile
import com.antifraud.mock.generation.UserSeeds
import com.antifraud.proto.v1.Channel
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VelocityBurstGeneratorTest {
    private val user = UserProfile(
        "user-1", HomeLocation("US", 40.7128, -74.0060),
        listOf("device-1", "device-2"), listOf("192.0.2.1", "192.0.2.2"), 2_500L,
    )
    private val startedAt = Instant.parse("2026-01-01T00:00:00.123456789Z")
    private val generator = VelocityBurstGenerator()

    @Test
    fun `burst has ten to twenty transactions within thirty to sixty seconds`() {
        repeat(100) { seed ->
            val events = generator.generate(user, UserSeeds.random(seed.toLong(), user.userId), startedAt)

            assertTrue(events.size in 10..20)
            assertEquals(Duration.ZERO, events.first().offset)
            assertTrue(events.last().offset in Duration.ofSeconds(30)..Duration.ofSeconds(60))
            assertTrue(events.zipWithNext().all { (first, second) -> first.offset < second.offset })
            assertEquals(events.size, events.map { it.transaction.transactionId }.toSet().size)
            events.forEach { event ->
                val tx = event.transaction
                assertEquals(user.userId, tx.userId)
                assertEquals(user.averageAmountMinor, tx.amountMinor)
                assertEquals("USD", tx.currency)
                assertEquals(user.home.country, tx.country)
                assertEquals(user.home.latitude, tx.latitude)
                assertEquals(user.home.longitude, tx.longitude)
                assertTrue(tx.hasLatitude() && tx.hasLongitude())
                assertTrue(tx.deviceId in user.deviceIds)
                assertTrue(tx.ip in user.ips)
                assertEquals(Channel.CHANNEL_ONLINE, tx.channel)
                assertTrue(tx.merchantId.isNotBlank())
                assertTrue(tx.merchantCategory.matches(Regex("[0-9]{4}")))
                UUID.fromString(tx.transactionId)
                assertEquals(startedAt.plus(event.offset), Instant.ofEpochSecond(tx.timestamp.seconds, tx.timestamp.nanos.toLong()))
                assertTrue(event.expectedFraud)
                assertEquals(FraudPattern.VELOCITY_BURST, event.pattern)
            }
        }
    }

    @Test
    fun `same seed user and start reproduce all transaction fields`() {
        assertEquals(
            generator.generate(user, UserSeeds.random(42, user.userId), startedAt),
            generator.generate(user, UserSeeds.random(42, user.userId), startedAt),
        )
    }

    @Test
    fun `other users do not consume this users random sequence`() {
        val actual = UserSeeds.random(42, user.userId)
        val expected = UserSeeds.random(42, user.userId)
        assertEquals(expected.nextLong(), actual.nextLong())

        val other = UserSeeds.random(42, "other-user")
        repeat(1_000) { other.nextLong() }

        assertEquals(expected.nextLong(), actual.nextLong())
        assertNotEquals(UserSeeds.derive(42, user.userId), UserSeeds.derive(42, "other-user"))
        assertNotEquals(UserSeeds.derive(42, user.userId), UserSeeds.derive(43, user.userId))
    }
}
