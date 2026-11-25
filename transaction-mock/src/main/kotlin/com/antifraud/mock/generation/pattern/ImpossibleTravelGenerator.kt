package com.antifraud.mock.generation.pattern

import com.antifraud.mock.generation.ForeignLocations
import com.antifraud.mock.generation.UserProfile
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class ImpossibleTravelGenerator : PatternGenerator {
    override val pattern = FraudPattern.IMPOSSIBLE_TRAVEL
    override val metadata = PatternMetadata(
        description = "Платежи в далёких странах с интервалом в минуты",
        maxTransactions = TRANSACTION_COUNT,
        maxDuration = Duration.ofSeconds(MAX_DELAY_SECONDS),
    )

    override fun generate(user: UserProfile, random: Random, startedAt: Instant): List<ScheduledTransaction> {
        val foreign = ForeignLocations.farFrom(user.home)
        val delay = Duration.ofSeconds(random.nextLong(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS + 1))
        return List(TRANSACTION_COUNT) { index ->
            val offset = if (index == 0) Duration.ZERO else delay
            val builder = FamiliarTransactionFactory.create(
                user, random, startedAt.plus(offset), user.averageAmountMinor,
            ).toBuilder()
            if (index == 1) {
                builder.setCountry(foreign.country).setLatitude(foreign.latitude).setLongitude(foreign.longitude)
            }
            // Первый платёж — легитимная точка отсчёта, второй — невозможное перемещение.
            ScheduledTransaction(offset, builder.build(), expectedFraud = index == 1, pattern = pattern)
        }
    }

    private companion object {
        const val TRANSACTION_COUNT = 2
        const val MIN_DELAY_SECONDS = 120L
        const val MAX_DELAY_SECONDS = 300L
    }
}
