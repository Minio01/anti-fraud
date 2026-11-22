package com.antifraud.mock.generation.pattern

import com.antifraud.mock.generation.UserProfile
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class AmountAnomalyGenerator : PatternGenerator {
    override val pattern = FraudPattern.AMOUNT_ANOMALY

    override fun generate(user: UserProfile, random: Random, startedAt: Instant): List<ScheduledTransaction> {
        val multiplier = random.nextInt(10, 51).toLong()
        val amountMinor = Math.multiplyExact(user.averageAmountMinor, multiplier)
        return listOf(
            ScheduledTransaction(
                offset = Duration.ZERO,
                transaction = FamiliarTransactionFactory.create(
                    user, random, startedAt, amountMinor,
                ),
                expectedFraud = true,
                pattern = pattern,
            ),
        )
    }
}
