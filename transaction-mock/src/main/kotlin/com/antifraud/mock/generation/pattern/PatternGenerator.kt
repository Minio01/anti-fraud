package com.antifraud.mock.generation.pattern

import com.antifraud.mock.generation.UserProfile
import com.antifraud.proto.v1.Transaction
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

enum class FraudPattern {
    VELOCITY_BURST,
    ACCOUNT_TAKEOVER,
    CARD_TESTING,
    AMOUNT_ANOMALY,
    IMPOSSIBLE_TRAVEL,
}

data class ScheduledTransaction(
    val offset: Duration,
    val transaction: Transaction,
    val expectedFraud: Boolean,
    val pattern: FraudPattern,
)

interface PatternGenerator {
    val pattern: FraudPattern

    // Время и Random передаёт вызывающий код: генерация не зависит от настенных часов.
    fun generate(user: UserProfile, random: Random, startedAt: Instant): List<ScheduledTransaction>
}
