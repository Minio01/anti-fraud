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
    private val tripDuration: Duration = Duration.ofMinutes(30),
) {
    private val tripDurationSeconds = tripDuration.seconds + tripDuration.nano / 1_000_000_000.0

    init {
        require(deviationRate.isFinite() && deviationRate in 0.0..1.0)
        require(tripDuration > Duration.ZERO)
    }

    fun newSession(): Session = Session()

    private data class Trip(val destination: HomeLocation, val endsAt: Instant)

    inner class Session internal constructor() {
        private val trips = mutableMapOf<String, Trip>()

        fun generate(
            user: UserProfile,
            random: Random,
            at: Instant,
            expectedPaymentsPerSecond: Double,
        ): ScheduledTransaction {
            val builder = FamiliarTransactionFactory.create(
                user, random, at, amounts.sample(user.averageAmountMinor, random),
            ).toBuilder()
            val trip = trips[user.userId]
            if (trip != null && at >= trip.endsAt) {
                trips.remove(user.userId)
            } else {
                val destination = trip?.destination ?: run {
                    val draw = random.nextDouble()
                    val deviceProbability = deviationRate / 2
                    // Чем больше платежей ожидается в поездке, тем реже она должна начинаться.
                    val expectedTripPayments = 1 + expectedPaymentsPerSecond * tripDurationSeconds
                    val tripProbability = deviceProbability / expectedTripPayments
                    when {
                        draw < deviceProbability -> {
                            val prefix = "new-${UUID(random.nextLong(), random.nextLong())}"
                            builder.setDeviceId(generateSequence(prefix) { "$it-x" }.first { it !in user.deviceIds })
                            null
                        }
                        draw < deviceProbability + tripProbability -> {
                            val foreign = ForeignLocations.farFrom(user.home)
                            trips[user.userId] = Trip(foreign, at.plus(tripDuration))
                            foreign
                        }
                        else -> null
                    }
                }
                if (destination != null) {
                    builder.setCountry(destination.country)
                        .setLatitude(destination.latitude)
                        .setLongitude(destination.longitude)
                }
            }
            return ScheduledTransaction(Duration.ZERO, builder.build(), expectedFraud = false, pattern = null)
        }
    }
}
