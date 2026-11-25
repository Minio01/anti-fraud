package com.antifraud.features;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserFeatureHistoryTest {
    private static final long MINUTE = 60_000;

    @Test
    void windowsIncludeCurrentPaymentAndExpireAtTheirLowerBoundary() {
        var history = new UserFeatureHistory();
        history.observe(payment(0, 100, "a", "phone", null));
        history.observe(payment(1, 199, "a", "phone", null));
        var atMinute = history.observe(payment(MINUTE, 200, "b", "phone", null));
        assertEquals(2, atMinute.txCount1m());
        assertEquals(3, atMinute.txCount5m());
        assertEquals(499, atMinute.amountSum5m());
        assertEquals(2, atMinute.smallTxCount10m());
        assertEquals(1, atMinute.smallTxDistinctMerchants10m());

        var atFive = history.observe(payment(5 * MINUTE, 150, "b", "phone", null));
        assertEquals(549, atFive.amountSum5m());
        assertEquals(2, atFive.smallTxDistinctMerchants10m());

        var atTen = history.observe(payment(10 * MINUTE + 1, 200, "c", "phone", null));
        assertEquals(1, atTen.smallTxCount10m());
        assertEquals(1, atTen.smallTxDistinctMerchants10m());

        var atHour = history.observe(payment(60 * MINUTE, 200, "c", "phone", null));
        assertEquals(5, atHour.txCount60m());
        assertEquals(1, atHour.hourOfDay());
    }

    @Test
    void profileUsesHistoryBeforeTheCurrentPaymentAndTravelUsesPreviousCoordinates() {
        var history = new UserFeatureHistory();
        var nyc = new UserFeatureHistory.Coordinates(40.7128, -74.0060);
        var london = new UserFeatureHistory.Coordinates(51.5074, -0.1278);
        var first = history.observe(payment(0, 100, "a", "phone", nyc));
        assertEquals(0, first.userTxCount());
        assertEquals(1.0, first.amountToAvgRatio());
        assertTrue(first.newDevice());

        var second = history.observe(payment(60 * MINUTE, 200, "a", "other-phone", london));
        assertEquals(1, second.userTxCount());
        assertEquals(2.0, second.amountToAvgRatio());
        assertTrue(second.newDevice());
        assertFalse(second.newCountry());
        assertEquals(5570, second.travelSpeedKmh(), 20);

        var third = history.observe(payment(60 * MINUTE, 110, "a", "other-phone", nyc));
        assertEquals(2, third.userTxCount());
        assertEquals(1.0, third.amountToAvgRatio());
        assertFalse(third.newDevice());
        assertEquals(0.0, third.travelSpeedKmh());
    }

    @Test
    void profileRetainsOnlyTwentyMostRecentDistinctDevices() {
        var history = new UserFeatureHistory();
        history.observe(payment(0, 100, "a", "old", null));
        for (int i = 1; i <= 20; i++) {
            history.observe(payment(i, 100, "a", "device-" + i, null));
        }
        assertTrue(history.observe(payment(21, 100, "a", "old", null)).newDevice());
    }

    private static UserFeatureHistory.Payment payment(
            long at, long amount, String merchant, String device, UserFeatureHistory.Coordinates coordinates
    ) {
        return new UserFeatureHistory.Payment(at, amount, "USD", "5411", merchant, "ONLINE", "US",
                device, "192.0.2.1", coordinates);
    }
}
