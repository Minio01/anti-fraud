package com.antifraud.scoring.score.domain;

import java.time.Instant;
import java.util.OptionalDouble;
import java.util.UUID;

public record Transaction(
        UUID id,
        UserId userId,
        Money money,
        Instant timestamp,
        String merchantId,
        String merchantCategory,
        String deviceId,
        String ip,
        String country,
        OptionalDouble latitude,
        OptionalDouble longitude,
        Channel channel
) {
    public enum Channel {
        ONLINE, POS, ATM
    }
}
