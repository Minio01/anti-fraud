package com.antifraud.mock.generation.pattern

import com.antifraud.mock.generation.ForeignLocations
import com.antifraud.mock.generation.LogNormalAmountSampler
import com.antifraud.mock.generation.UserProfile
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class AccountTakeoverGenerator(
    private val largePaymentProbability: Double,
    private val amounts: LogNormalAmountSampler,
) : PatternGenerator {
    init {
        require(largePaymentProbability.isFinite() && largePaymentProbability in 0.0..1.0)
    }

    override val pattern = FraudPattern.ACCOUNT_TAKEOVER
    override val metadata = PatternMetadata(
        description = "Новое устройство/IP/страна; крупное продолжение необязательно",
        maxTransactions = if (largePaymentProbability > 0) 2 else 1,
        maxDuration = if (largePaymentProbability > 0) Duration.ofSeconds(MAX_FOLLOWUP_SECONDS) else Duration.ZERO,
    )

    override fun generate(user: UserProfile, random: Random, startedAt: Instant): List<ScheduledTransaction> {
        val foreign = ForeignLocations.farFrom(user.home)
        val deviceId = "ato-${user.userId}".let { prefix ->
            generateSequence(prefix) { "$it-x" }.first { it !in user.deviceIds }
        }
        val ip = (1..254).map { "203.0.113.$it" }.first { it !in user.ips }
        val offsets = buildList {
            add(Duration.ZERO)
            if (random.nextDouble() < largePaymentProbability) {
                add(Duration.ofSeconds(random.nextLong(MIN_FOLLOWUP_SECONDS, MAX_FOLLOWUP_SECONDS + 1)))
            }
        }
        return offsets.mapIndexed { index, offset ->
            val amount = if (index == 0) amounts.sample(user.averageAmountMinor, random)
                else random.nextLong(
                    Math.multiplyExact(user.averageAmountMinor, 3L),
                    Math.multiplyExact(user.averageAmountMinor, 6L),
                )
            val transaction = FamiliarTransactionFactory.create(user, random, startedAt.plus(offset), amount)
                .toBuilder()
                .setDeviceId(deviceId)
                .setIp(ip)
                .setCountry(foreign.country)
                .setLatitude(foreign.latitude)
                .setLongitude(foreign.longitude)
                .build()
            ScheduledTransaction(offset, transaction, expectedFraud = true, pattern = pattern)
        }
    }

    private companion object {
        const val MIN_FOLLOWUP_SECONDS = 60L
        const val MAX_FOLLOWUP_SECONDS = 180L
    }
}
