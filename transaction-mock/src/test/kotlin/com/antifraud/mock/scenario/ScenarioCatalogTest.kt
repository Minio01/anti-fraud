package com.antifraud.mock.scenario

import com.antifraud.mock.generation.HomeLocation
import com.antifraud.mock.generation.LogNormalAmountSampler
import com.antifraud.mock.generation.UserProfile
import com.antifraud.mock.generation.UserSeeds
import com.antifraud.mock.generation.pattern.AccountTakeoverGenerator
import com.antifraud.mock.generation.pattern.AmountAnomalyGenerator
import com.antifraud.mock.generation.pattern.CardTestingGenerator
import com.antifraud.mock.generation.pattern.FraudPattern
import com.antifraud.mock.generation.pattern.ImpossibleTravelGenerator
import com.antifraud.mock.generation.pattern.PatternGenerator
import com.antifraud.mock.generation.pattern.VelocityBurstGenerator
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScenarioCatalogTest {
    private val amounts = LogNormalAmountSampler(0.75)
    private val generators = listOf(
        VelocityBurstGenerator(), AccountTakeoverGenerator(0.2, amounts), CardTestingGenerator(),
        AmountAnomalyGenerator(), ImpossibleTravelGenerator(),
    )

    @Test
    fun `catalog picks up changed generator metadata and keeps enum order`() {
        val original = generators.first()
        val changed = object : PatternGenerator by original {
            override val metadata = original.metadata.copy(
                description = "Изменённое описание",
                maxTransactions = 25,
                maxDuration = Duration.ofSeconds(90),
            )
        }
        val catalog = ScenarioCatalog((listOf(changed) + generators.drop(1)).reversed())

        val entries = catalog.list()

        assertEquals(FraudPattern.entries, entries.map { it.pattern })
        val entry = entries.first { it.pattern == changed.pattern }
        assertEquals("Изменённое описание", entry.description)
        assertEquals(25, entry.transactions)
        assertEquals(90_000L, entry.durationMs)
    }

    @Test
    fun `generated events stay within the advertised limits`() {
        val user = UserProfile(
            "user-1", HomeLocation("US", 40.7128, -74.0060),
            listOf("device-1"), listOf("192.0.2.1"), 2_500,
        )
        val at = Instant.parse("2026-01-01T00:00:00Z")
        val variants = generators + listOf(AccountTakeoverGenerator(0.0, amounts), AccountTakeoverGenerator(1.0, amounts))
        variants.forEach { generator ->
            repeat(100) { seed ->
                val events = generator.generate(user, UserSeeds.random(seed.toLong(), user.userId), at)
                assertTrue(events.size in 1..generator.metadata.maxTransactions, generator.pattern.name)
                assertTrue(events.all { it.offset >= Duration.ZERO && it.offset <= generator.metadata.maxDuration })
            }
        }
    }

    @Test
    fun `takeover metadata accounts for disabled followup`() {
        val withoutFollowup = AccountTakeoverGenerator(0.0, amounts)
        val catalog = ScenarioCatalog(generators.map { if (it.pattern == withoutFollowup.pattern) withoutFollowup else it })

        val entry = catalog.list().first { it.pattern == FraudPattern.ACCOUNT_TAKEOVER }

        assertEquals(1, entry.transactions)
        assertEquals(0L, entry.durationMs)
    }

    @Test
    fun `missing or duplicate generators cannot create a misleading catalog`() {
        assertFailsWith<IllegalArgumentException> { ScenarioCatalog(generators.drop(1)) }
        assertFailsWith<IllegalArgumentException> { ScenarioCatalog(generators + generators.first()) }
    }
}
