package com.antifraud.mock.generation

import com.antifraud.features.GeoDistance

internal object ForeignLocations {
    private val locations = listOf(
        HomeLocation("US", 40.7128, -74.0060),
        HomeLocation("GB", 51.5074, -0.1278),
        HomeLocation("JP", 35.6762, 139.6503),
        HomeLocation("AU", -33.8688, 151.2093),
    )

    fun farFrom(home: HomeLocation): HomeLocation = locations
        .filter { it.country != home.country }
        .maxBy { GeoDistance.kilometers(home.latitude, home.longitude, it.latitude, it.longitude) }
}
