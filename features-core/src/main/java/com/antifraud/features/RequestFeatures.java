package com.antifraud.features;

import java.time.Instant;
import java.time.ZoneOffset;

public record RequestFeatures(
        long amount, double logAmount, String currency, int hourOfDay,
        String merchantCategory, String channel, String country
) {
    public static RequestFeatures from(long amountMinor, String currency, Instant timestamp,
                                       String merchantCategory, String channel, String country) {
        return new RequestFeatures(amountMinor, Math.log1p(amountMinor), currency,
                timestamp.atOffset(ZoneOffset.UTC).getHour(), merchantCategory, channel, country);
    }
}
