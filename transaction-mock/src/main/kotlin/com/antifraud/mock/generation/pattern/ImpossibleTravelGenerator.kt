package com.antifraud.mock.generation.pattern

import com.antifraud.mock.generation.ForeignLocations
import com.antifraud.mock.generation.UserProfile
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class ImpossibleTravelGenerator : PatternGenerator {
    override val pattern = FraudPattern.IMPOSSIBLE_TRAVEL

    override fun generate(user: UserProfile, random: Random, startedAt: Instant): List<ScheduledTransaction> {
        val foreign = ForeignLocations.farFrom(user.home)
        val delay = Duration.ofSeconds(random.nextLong(120, 301))
        return listOf(Duration.ZERO, delay).mapIndexed { index, offset ->
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
}
