package com.antifraud.mock.generation

data class HomeLocation(
    val country: String,
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(country.matches(Regex("[A-Z]{2}")))
        require(latitude in -90.0..90.0)
        require(longitude in -180.0..180.0)
    }
}

data class UserProfile(
    val userId: String,
    val home: HomeLocation,
    val deviceIds: List<String>,
    val ips: List<String>,
    val averageAmountMinor: Long,
    val paymentsPerDay: Int = 1,
) {
    init {
        require(userId.length in 1..64)
        require(deviceIds.size in 1..2 && deviceIds.all { it.length in 1..128 })
        require(ips.size in 1..3)
        require(averageAmountMinor > 0)
        require(paymentsPerDay in 1..20)
    }
}
