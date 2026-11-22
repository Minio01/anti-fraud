package com.antifraud.mock.generation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

class LogNormalAmountSampler(private val sigma: Double) {
    init {
        require(sigma.isFinite() && sigma > 0)
    }

    fun sample(meanMinor: Long, random: Random): Long {
        require(meanMinor > 0)
        val z = standardNormal(random)

        val mu = ln(meanMinor.toDouble()) - sigma.pow(2) / 2
        val value = exp(mu + sigma * z)

        if (!value.isFinite() || value >= Long.MAX_VALUE.toDouble()) {
            throw ArithmeticException("Сгенерированная сумма не помещается в Long")
        }

        return value.roundToLong().coerceAtLeast(1L)
    }

    private fun standardNormal(random: Random): Double {
        // Преобразование Бокса — Мюллера. Первый аргумент логарифма строго положителен.
        val u1 = 1.0 - random.nextDouble()
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }
}
