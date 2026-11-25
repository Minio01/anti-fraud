package com.antifraud.mock.generation.pattern

import com.antifraud.mock.generation.UserProfile
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class CardTestingGenerator : PatternGenerator {
    override val pattern = FraudPattern.CARD_TESTING
    override val metadata = PatternMetadata(
        description = "Мелкие платежи разным мерчантам, затем крупный",
        maxTransactions = MAX_SMALL_TRANSACTIONS + 1,
        maxDuration = Duration.ofMillis(MAX_DURATION_MS),
    )

    override fun generate(user: UserProfile, random: Random, startedAt: Instant): List<ScheduledTransaction> {
        val smallCount = random.nextInt(MIN_SMALL_TRANSACTIONS, MAX_SMALL_TRANSACTIONS + 1)
        val durationMs = random.nextLong(MIN_DURATION_MS, MAX_DURATION_MS + 1)
        return List(smallCount + 1) { index ->
            val offset = Duration.ofMillis(durationMs * index / smallCount)
            val amountMinor = if (index < smallCount) random.nextLong(1, 200)
                else maxOf(200L, Math.multiplyExact(user.averageAmountMinor, 3L))
            val transaction = FamiliarTransactionFactory.create(user, random, startedAt.plus(offset), amountMinor)
                .toBuilder()
                .setMerchantId("card-test-${index + 1}")
                .build()
            ScheduledTransaction(offset, transaction, expectedFraud = true, pattern = pattern)
        }
    }

    private companion object {
        const val MIN_SMALL_TRANSACTIONS = 5
        const val MAX_SMALL_TRANSACTIONS = 15
        const val MIN_DURATION_MS = 120_000L
        const val MAX_DURATION_MS = 300_000L
    }
}
