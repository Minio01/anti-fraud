package com.antifraud.mock.generation

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LogNormalAmountSamplerTest {
    @Test
    fun `amounts are positive and the same seed reproduces the sequence`() {
        val sampler = LogNormalAmountSampler(sigma = 0.75)
        val first = Random(42)
        val second = Random(42)

        repeat(1_000) {
            val amount = sampler.sample(meanMinor = 2_500, random = first)
            assertTrue(amount > 0)
            assertEquals(amount, sampler.sample(meanMinor = 2_500, random = second))
        }
    }

    @Test
    fun `large sample preserves the requested arithmetic mean`() {
        val sampler = LogNormalAmountSampler(sigma = 0.75)
        val random = Random(42)
        val meanMinor = 2_500L
        val size = 100_000
        var sum = 0.0

        repeat(size) { sum += sampler.sample(meanMinor, random) }

        val relativeError = abs(sum / size - meanMinor) / meanMinor
        assertTrue(relativeError < 0.02, "Относительная ошибка среднего: $relativeError")
    }

    @Test
    fun `fractional cents are rounded and very small amounts become one cent`() {
        // При z = 0 и sigma = 1 сумма для среднего 100 составляет около 60,653 цента.
        assertEquals(61L, LogNormalAmountSampler(sigma = 1.0).sample(100, fixedRandom(0.0, 0.0)))
        assertEquals(1L, LogNormalAmountSampler(sigma = 2.0).sample(1, fixedRandom(0.0, 0.0)))
    }

    @Test
    fun `out of range or non finite samples throw instead of saturating`() {
        assertFailsWith<ArithmeticException> {
            LogNormalAmountSampler(sigma = 1.0).sample(Long.MAX_VALUE, fixedRandom(0.99, 0.0))
        }
        assertFailsWith<ArithmeticException> {
            LogNormalAmountSampler(sigma = Double.MAX_VALUE).sample(100, fixedRandom(0.99, 0.0))
        }
    }

    @Test
    fun `sigma and mean must be positive and sigma must be finite`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { sigma ->
            assertFailsWith<IllegalArgumentException> { LogNormalAmountSampler(sigma) }
        }
        val sampler = LogNormalAmountSampler(sigma = 0.75)
        listOf(0L, -1L).forEach { mean ->
            assertFailsWith<IllegalArgumentException> { sampler.sample(mean, Random(42)) }
        }
    }

    private fun fixedRandom(vararg values: Double): Random = object : Random() {
        private var index = 0
        override fun nextDouble(): Double = values[index++]
        override fun nextBits(bitCount: Int): Int = error("Тест ожидает только вызовы nextDouble")
    }
}
