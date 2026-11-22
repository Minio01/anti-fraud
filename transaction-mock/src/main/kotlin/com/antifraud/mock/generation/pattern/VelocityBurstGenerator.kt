package com.antifraud.mock.generation.pattern

import com.antifraud.mock.generation.UserProfile
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class VelocityBurstGenerator : PatternGenerator {
    override val pattern = FraudPattern.VELOCITY_BURST

    override fun generate(user: UserProfile, random: Random, startedAt: Instant): List<ScheduledTransaction> {
        val count = random.nextInt(10, 21)
        val durationMs = random.nextLong(30_000, 60_001)
        return List(count) { index ->
            val offset = Duration.ofMillis(durationMs * index / (count - 1))
            ScheduledTransaction(
                offset = offset,
                transaction = FamiliarTransactionFactory.create(
                    user, random, startedAt.plus(offset), user.averageAmountMinor,
                ),
                expectedFraud = true,
                pattern = pattern,
            )
        }
    }
}
