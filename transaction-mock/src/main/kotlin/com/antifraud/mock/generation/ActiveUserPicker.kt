package com.antifraud.mock.generation

import kotlin.random.Random

internal class ActiveUserPicker(private val users: List<UserProfile>) {
    private val cumulativeRates = LongArray(users.size)

    init {
        require(users.isNotEmpty())
        var total = 0L
        users.forEachIndexed { index, user ->
            total += user.paymentsPerDay
            cumulativeRates[index] = total
        }
    }

    fun pick(random: Random): UserProfile {
        // Каждому пользователю принадлежит столько позиций, сколько платежей в день он ожидает.
        val ticket = random.nextLong(cumulativeRates.last()) + 1
        val match = cumulativeRates.binarySearch(ticket)
        val index = if (match >= 0) match else -match - 1
        return users[index]
    }

    fun shareOf(user: UserProfile): Double = user.paymentsPerDay.toDouble() / cumulativeRates.last()
}
