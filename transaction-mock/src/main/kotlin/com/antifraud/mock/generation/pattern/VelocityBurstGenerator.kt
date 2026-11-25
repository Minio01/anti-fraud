package com.antifraud.mock.generation.pattern

import com.antifraud.mock.generation.UserProfile
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class VelocityBurstGenerator : PatternGenerator {
    override val pattern = FraudPattern.VELOCITY_BURST
    override val metadata = PatternMetadata(
        description = "Частые платежи одного пользователя",
        maxTransactions = MAX_TRANSACTIONS,
        maxDuration = Duration.ofMillis(MAX_DURATION_MS),
    )

    override fun generate(user: UserProfile, random: Random, startedAt: Instant): List<ScheduledTransaction> {
        val count = random.nextInt(MIN_TRANSACTIONS, MAX_TRANSACTIONS + 1)
        val durationMs = random.nextLong(MIN_DURATION_MS, MAX_DURATION_MS + 1)
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

    private companion object {
        const val MIN_TRANSACTIONS = 10
        const val MAX_TRANSACTIONS = 20
        const val MIN_DURATION_MS = 30_000L
        const val MAX_DURATION_MS = 60_000L
    }
}
