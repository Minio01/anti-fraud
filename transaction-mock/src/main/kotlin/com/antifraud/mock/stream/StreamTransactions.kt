package com.antifraud.mock.stream

import com.antifraud.mock.generation.NormalTransactionGenerator
import com.antifraud.mock.generation.ActiveUserPicker
import com.antifraud.mock.generation.UserProfile
import com.antifraud.mock.generation.UserSeeds
import com.antifraud.mock.generation.pattern.FraudPattern
import com.antifraud.mock.generation.pattern.ScheduledTransaction
import com.antifraud.mock.scenario.ScenarioCatalog
import java.time.Instant
import java.util.PriorityQueue
import kotlin.random.Random

internal class StreamTransactions(
    private val users: List<UserProfile>,
    normal: NormalTransactionGenerator,
    private val catalog: ScenarioCatalog,
    private val seed: Long,
) {
    private data class Pending(val due: Instant, val event: ScheduledTransaction)

    private val pending = PriorityQueue(compareBy<Pending> { it.due }.thenBy { it.event.transaction.transactionId })
    private val selection = Random(seed)
    private val activeUsers = ActiveUserPicker(users)
    private val normalSession = normal.newSession()
    private val randomByUser = mutableMapOf<String, Random>()
    private var previousMix: Pair<Double, List<FraudPattern>>? = null
    private var fraudBudget = 0.0

    fun next(config: StreamConfig, now: Instant): ScheduledTransaction {
        val mix = config.fraudRate to config.patterns
        if (mix != previousMix) {
            pending.clear()
            fraudBudget = 0.0
            previousMix = mix
        }

        if (config.patterns.isNotEmpty()) {
            fraudBudget += config.fraudRate
            if (pending.peek()?.due?.let { it <= now } == true) return pending.remove().event
            if (fraudBudget >= 1.0) {
                val generator = catalog.generator(config.patterns.random(selection))
                if (pending.size + generator.metadata.maxTransactions <= MAX_PENDING) {
                    val user = users.random(selection)
                    val events = generator.generate(user, randomFor(user), now)
                    // Чем длиннее сценарий, тем больше его вклад в долю фродовых транзакций.
                    fraudBudget -= events.count { it.expectedFraud }
                    events.drop(1).forEach { pending.add(Pending(now.plus(it.offset), it)) }
                    return events.first()
                }
                fraudBudget = 1.0
            }
        }
        val user = activeUsers.pick(selection)
        val normalRps = if (config.patterns.isEmpty()) config.rps.toDouble() else config.rps * (1 - config.fraudRate)
        return normalSession.generate(user, randomFor(user), now, normalRps * activeUsers.shareOf(user))
    }

    private fun randomFor(user: UserProfile): Random =
        randomByUser.getOrPut(user.userId) { UserSeeds.random(seed, user.userId) }

    private companion object {
        const val MAX_PENDING = 100_000
    }
}
