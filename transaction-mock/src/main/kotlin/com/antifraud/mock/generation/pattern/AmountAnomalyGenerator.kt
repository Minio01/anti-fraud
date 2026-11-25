package com.antifraud.mock.generation.pattern

import com.antifraud.mock.generation.UserProfile
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class AmountAnomalyGenerator : PatternGenerator {
    override val pattern = FraudPattern.AMOUNT_ANOMALY
    override val metadata = PatternMetadata(
        description = "Платёж в $MIN_MULTIPLIER–$MAX_MULTIPLIER раз выше среднего чека",
        maxTransactions = 1,
        maxDuration = Duration.ZERO,
    )

    override fun generate(user: UserProfile, random: Random, startedAt: Instant): List<ScheduledTransaction> {
        val multiplier = random.nextInt(MIN_MULTIPLIER, MAX_MULTIPLIER + 1).toLong()
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

    private companion object {
        const val MIN_MULTIPLIER = 10
        const val MAX_MULTIPLIER = 50
    }
}
