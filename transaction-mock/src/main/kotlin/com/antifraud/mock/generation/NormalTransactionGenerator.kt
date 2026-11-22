package com.antifraud.mock.generation

import com.antifraud.mock.generation.pattern.FamiliarTransactionFactory
import com.antifraud.mock.generation.pattern.ScheduledTransaction
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

class NormalTransactionGenerator(
    private val amounts: LogNormalAmountSampler,
    private val deviationRate: Double = 0.005,
) {
    init {
        require(deviationRate.isFinite() && deviationRate in 0.0..1.0)
    }

    fun generate(user: UserProfile, random: Random, at: Instant): ScheduledTransaction {
        val builder = FamiliarTransactionFactory.create(
            user, random, at, amounts.sample(user.averageAmountMinor, random),
        ).toBuilder()
        if (random.nextDouble() < deviationRate) {
            if (random.nextBoolean()) {
                val prefix = "new-${UUID(random.nextLong(), random.nextLong())}"
                builder.setDeviceId(generateSequence(prefix) { "$it-x" }.first { it !in user.deviceIds })
            } else {
                val destination = ForeignLocations.farFrom(user.home)
                builder.setCountry(destination.country)
                    .setLatitude(destination.latitude)
                    .setLongitude(destination.longitude)
            }
        }
        return ScheduledTransaction(Duration.ZERO, builder.build(), expectedFraud = false, pattern = null)
    }
}
