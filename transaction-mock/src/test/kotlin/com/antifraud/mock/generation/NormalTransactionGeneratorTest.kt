package com.antifraud.mock.generation

import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NormalTransactionGeneratorTest {
    private val user = UserProfile(
        "user-1", HomeLocation("US", 40.7128, -74.0060),
        listOf("device-1"), listOf("192.0.2.1"), 2_500,
    )
    private val at = Instant.parse("2026-01-01T00:00:00.123456789Z")
    private val amounts = LogNormalAmountSampler(sigma = 0.75)

    @Test
    fun `normal payments use familiar attributes and reproduce all fields`() {
        val generator = NormalTransactionGenerator(amounts, deviationRate = 0.0)
        val first = generator.newSession()
        val second = generator.newSession()
        val firstRandom = UserSeeds.random(42, user.userId)
        val secondRandom = UserSeeds.random(42, user.userId)
        repeat(100) {
            val event = first.generate(user, firstRandom, at, 0.0)
            assertEquals(event, second.generate(user, secondRandom, at, 0.0))
            assertEquals(user.userId, event.transaction.userId)
            assertEquals("USD", event.transaction.currency)
            assertEquals(user.home.country, event.transaction.country)
            assertEquals(user.home.latitude, event.transaction.latitude)
            assertEquals(user.home.longitude, event.transaction.longitude)
            assertTrue(event.transaction.deviceId in user.deviceIds)
            assertTrue(event.transaction.ip in user.ips)
            assertTrue(event.transaction.amountMinor > 0)
            assertEquals(Duration.ZERO, event.offset)
            assertFalse(event.expectedFraud)
            assertNull(event.pattern)
            assertEquals(at, Instant.ofEpochSecond(event.transaction.timestamp.seconds, event.transaction.timestamp.nanos.toLong()))
        }
    }

    @Test
    fun `default legitimate deviations occur about half a percent and preserve the profile`() {
        val session = NormalTransactionGenerator(amounts).newSession()
        val random = UserSeeds.random(42, user.userId)
        val size = 20_000
        var newDevices = 0
        var trips = 0
        repeat(size) { index ->
            val event = session.generate(user, random, at.plusSeconds(index * 1_801L), 0.0)
            val tx = event.transaction
            if (tx.deviceId !in user.deviceIds) newDevices++
            if (tx.country != user.home.country) trips++
            assertTrue(tx.ip in user.ips)
            assertFalse(event.expectedFraud)
            assertNull(event.pattern)
        }
        assertTrue(newDevices > 0 && trips > 0)
        assertTrue((newDevices + trips).toDouble() / size in 0.003..0.007)
        assertEquals(listOf("device-1"), user.deviceIds)
        assertEquals("US", user.home.country)
    }

    @Test
    fun `trip keeps its destination until expiry and is isolated per user and session`() {
        val duration = Duration.ofMinutes(30)
        val generator = NormalTransactionGenerator(amounts, deviationRate = 1.0, tripDuration = duration)
        val session = generator.newSession()
        val random = object : Random() {
            private val delegate = Random(42)
            override fun nextDouble() = 0.75
            override fun nextBits(bitCount: Int) = delegate.nextBits(bitCount)
        }
        val first = session.generate(user, random, at, 0.0)
        assertTrue(first.transaction.country != user.home.country)

        listOf(at.plusSeconds(60), at.plus(duration).minusNanos(1)).forEach { timestamp ->
            val next = session.generate(user, random, timestamp, 1.0)
            assertEquals(first.transaction.country, next.transaction.country)
            assertEquals(first.transaction.latitude, next.transaction.latitude)
            assertEquals(first.transaction.longitude, next.transaction.longitude)
            assertFalse(next.expectedFraud)
            assertNull(next.pattern)
        }

        val other = user.copy(userId = "user-2")
        assertEquals(user.home.country, session.generate(other, random, at.plusSeconds(120), 1.0).transaction.country)
        assertEquals(user.home.country, generator.newSession().generate(user, random, at.plusSeconds(120), 1.0).transaction.country)
        assertEquals(user.home.country, session.generate(user, random, at.plus(duration), 0.0).transaction.country)
        assertTrue(session.generate(user, random, at.plus(duration).plusSeconds(1), 0.0).transaction.country != user.home.country)
    }
}
