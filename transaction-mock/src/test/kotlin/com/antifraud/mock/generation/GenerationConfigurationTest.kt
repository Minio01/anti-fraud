package com.antifraud.mock.generation

import com.antifraud.mock.generation.pattern.AccountTakeoverGenerator
import com.antifraud.mock.generation.pattern.FraudPattern
import com.antifraud.mock.generation.pattern.PatternGenerator
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource
import java.time.Instant
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GenerationConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(GenerationConfiguration::class.java)
        .withInitializer { context ->
            YamlPropertySourceLoader().load("application", ClassPathResource("application.yml")).forEach {
                context.environment.propertySources.addLast(it)
            }
        }

    @Test
    fun `application yaml binds the chosen settings and creates all generators`() {
        contextRunner.run { context ->
            assertNull(context.startupFailure)
            val properties = context.getBean(GenerationProperties::class.java)
            assertEquals(0.75, properties.sigma)
            assertEquals(0.2, properties.accountTakeoverLargePaymentProbability)
            assertEquals(Duration.ofMinutes(30), properties.tripDuration)
            assertEquals(FraudPattern.entries.toSet(), context.getBeansOfType(PatternGenerator::class.java).values.map { it.pattern }.toSet())
            assertNotNull(context.getBean(NormalTransactionGenerator::class.java))
        }
    }

    @Test
    fun `property overrides change the actual takeover followup behavior`() {
        val user = UserProfile("user-1", HomeLocation("US", 40.7128, -74.0060), listOf("device-1"), listOf("192.0.2.1"), 2_500)
        val at = Instant.parse("2026-01-01T00:00:00Z")
        listOf(0.0 to 1, 1.0 to 2).forEach { (probability, expectedCount) ->
            contextRunner.withPropertyValues("mock.generation.account-takeover-large-payment-probability=$probability")
                .run { context ->
                    assertNull(context.startupFailure)
                    val generator = context.getBean(AccountTakeoverGenerator::class.java)
                    assertEquals(expectedCount, generator.generate(user, UserSeeds.random(42, user.userId), at).size)
                }
        }
    }

    @Test
    fun `invalid settings prevent startup`() {
        listOf("mock.generation.sigma=0", "mock.generation.account-takeover-large-payment-probability=1.1").forEach { invalid ->
            contextRunner.withPropertyValues(invalid).run { context -> assertNotNull(context.startupFailure) }
        }
    }
}
