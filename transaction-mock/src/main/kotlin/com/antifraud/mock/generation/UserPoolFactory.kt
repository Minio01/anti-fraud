package com.antifraud.mock.generation

import kotlin.random.Random

class UserPoolFactory(private val averageAmount: (Random) -> Long) {
    private val homes = listOf(
        HomeLocation("US", 40.7128, -74.0060),
        HomeLocation("GB", 51.5074, -0.1278),
        HomeLocation("DE", 52.5200, 13.4050),
        HomeLocation("JP", 35.6762, 139.6503),
    )

    fun create(runSeed: Long, size: Int = 10_000): List<UserProfile> {
        require(size > 0)
        return List(size) { index ->
            val userId = "user-${index + 1}"
            val random = UserSeeds.random(runSeed, userId)
            UserProfile(
                userId = userId,
                home = homes.random(random),
                deviceIds = List(random.nextInt(1, 3)) { "device-$userId-${it + 1}" },
                // Документационные адреса не содержат IP реальных пользователей.
                ips = listOf("192.0.2", "198.51.100", "203.0.113")
                    .shuffled(random)
                    .take(random.nextInt(1, 4))
                    .map { "$it.${random.nextInt(1, 255)}" },
                averageAmountMinor = averageAmount(random),
            )
        }
    }
}
