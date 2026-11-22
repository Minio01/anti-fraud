package com.antifraud.features;

public final class GeoDistance {
    private static final double EARTH_RADIUS_KM = 6371.0;

    private GeoDistance() {
    }

    public static double kilometers(double latitude1, double longitude1, double latitude2, double longitude2) {
        if (!(latitude1 >= -90 && latitude1 <= 90 && latitude2 >= -90 && latitude2 <= 90
                && longitude1 >= -180 && longitude1 <= 180 && longitude2 >= -180 && longitude2 <= 180)) {
            throw new IllegalArgumentException("Координаты вне допустимого диапазона");
        }
        double lat1 = Math.toRadians(latitude1);
        double lat2 = Math.toRadians(latitude2);
        double sinLat = Math.sin((lat2 - lat1) / 2);
        double sinLon = Math.sin(Math.toRadians(longitude2 - longitude1) / 2);
        double a = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        a = Math.clamp(a, 0.0, 1.0);
        return 2 * EARTH_RADIUS_KM * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
