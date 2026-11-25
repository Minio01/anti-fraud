package com.antifraud.mock.generation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserPoolFactoryTest {
    // Здесь проверяем сборку профилей; распределение сумм проверяется отдельно в задании 2.2.
    private val factory = UserPoolFactory { random -> random.nextLong(500, 20_001) }

    @Test
    fun `default pool has ten thousand users with stable profile attributes`() {
        val users = factory.create(runSeed = 42)

        assertEquals(10_000, users.size)
        assertEquals(users.size, users.map { it.userId }.toSet().size)
        assertEquals(users.sumOf { it.deviceIds.size }, users.flatMap { it.deviceIds }.toSet().size)
        users.forEach { user ->
            assertTrue(user.deviceIds.size in 1..2)
            assertTrue(user.deviceIds.all { it.length in 1..128 })
            assertTrue(user.ips.size in 1..3)
            assertEquals(user.ips.size, user.ips.toSet().size)
            assertTrue(user.ips.all { address ->
                val parts = address.split('.')
                parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
            })
            assertTrue(user.home.country in setOf("US", "GB", "DE", "JP"))
            assertTrue(user.averageAmountMinor in 500L..20_000L)
            assertTrue(user.paymentsPerDay in 1..20)
        }
        assertEquals((1..20).toSet(), users.map { it.paymentsPerDay }.toSet())
    }

    @Test
    fun `same seed reproduces profiles and increasing pool size preserves existing users`() {
        val first = factory.create(runSeed = 42, size = 20)
        val repeated = factory.create(runSeed = 42, size = 20)
        val expanded = factory.create(runSeed = 42, size = 40)

        assertEquals(first, repeated)
        assertEquals(first, expanded.take(first.size))
    }

    @Test
    fun `pool accepts lognormal average amounts and reproduces the profiles`() {
        val sampler = LogNormalAmountSampler(sigma = 0.75)
        val logNormalFactory = UserPoolFactory { random -> sampler.sample(2_500, random) }

        val first = logNormalFactory.create(runSeed = 42, size = 100)

        assertEquals(first, logNormalFactory.create(runSeed = 42, size = 100))
        assertTrue(first.all { it.averageAmountMinor > 0 })
        assertTrue(first.map { it.averageAmountMinor }.toSet().size > 1)
    }
}
