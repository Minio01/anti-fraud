package com.antifraud.features;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeoDistanceTest {
    @Test
    void measuresSamePointAndNewYorkToLondon() {
        assertEquals(0, GeoDistance.kilometers(40.7128, -74.0060, 40.7128, -74.0060));
        assertEquals(5570, GeoDistance.kilometers(40.7128, -74.0060, 51.5074, -0.1278), 20);
    }

    @Test
    void handlesAntipodesAndCrossingTheDateLine() {
        assertEquals(Math.PI * 6371, GeoDistance.kilometers(0, 0, 0, 180), 0.01);
        assertEquals(222.39, GeoDistance.kilometers(0, 179, 0, -179), 0.01);
    }

    @Test
    void rejectsInvalidCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> GeoDistance.kilometers(Double.NaN, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> GeoDistance.kilometers(91, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> GeoDistance.kilometers(0, 0, 0, 181));
    }
}
