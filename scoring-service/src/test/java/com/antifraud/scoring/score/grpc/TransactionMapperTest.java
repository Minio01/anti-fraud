package com.antifraud.scoring.score.grpc;

import com.antifraud.proto.v1.Channel;
import com.antifraud.scoring.score.domain.Money;
import com.antifraud.scoring.score.domain.Transaction;
import com.antifraud.scoring.score.domain.UserId;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.OptionalDouble;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionMapperTest {
    @Test
    void mapsTransactionAndPreservesTimestampPrecisionAndCoordinatePresence() {
        var input = com.antifraud.proto.v1.Transaction.newBuilder()
                .setTransactionId("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
                .setUserId("user-1")
                .setAmountMinor(12345)
                .setCurrency("USD")
                .setTimestamp(Timestamp.newBuilder().setSeconds(1_800_000_000L).setNanos(123_456_789))
                .setMerchantId("merchant-1")
                .setMerchantCategory("5411")
                .setDeviceId("device-1")
                .setIp("192.0.2.1")
                .setCountry("US")
                .setLatitude(0.0)
                .setChannel(Channel.CHANNEL_POS)
                .build();

        assertThat(new TransactionMapper().toDomain(input)).isEqualTo(new Transaction(
                UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"),
                new UserId("user-1"),
                new Money(12345, Currency.getInstance("USD")),
                Instant.ofEpochSecond(1_800_000_000L, 123_456_789),
                "merchant-1", "5411", "device-1", "192.0.2.1", "US",
                OptionalDouble.of(0.0), OptionalDouble.empty(), Transaction.Channel.POS));
    }
}
