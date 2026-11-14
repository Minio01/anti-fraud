package com.antifraud.scoring.score.grpc;

import com.antifraud.proto.v1.Channel;
import com.antifraud.proto.v1.ScoreRequest;
import com.antifraud.proto.v1.Transaction;
import com.google.protobuf.Timestamp;
import com.google.rpc.BadRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreRequestValidatorTest {
    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    private final ScoreRequestValidator validator = new ScoreRequestValidator();

    @Test
    void acceptsZeroCoordinateAndTimestampExactlyFiveMinutesAhead() {
        var transaction = validTransaction()
                .setLatitude(0.0)
                .setTimestamp(Timestamp.newBuilder().setSeconds(NOW.plusSeconds(300).getEpochSecond()));

        assertThat(violations(transaction)).isEmpty();
    }

    @Test
    void collectsMultipleInvalidFields() {
        var transaction = validTransaction()
                .setTransactionId("1-1-1-1-1")
                .setAmountMinor(0)
                .setIp("localhost")
                .setCurrency("ZZZ")
                .setLongitude(Double.NaN)
                .setChannelValue(99);

        assertThat(violations(transaction)).containsExactly(
                "transaction.transaction_id", "transaction.amount_minor", "transaction.currency",
                "transaction.ip", "transaction.longitude", "transaction.channel");
    }

    @Test
    void rejectsTimestampOneNanosecondPastTheLimit() {
        var transaction = validTransaction().setTimestamp(Timestamp.newBuilder()
                .setSeconds(NOW.plusSeconds(300).getEpochSecond())
                .setNanos(1));

        assertThat(violations(transaction)).containsExactly("transaction.timestamp");
    }

    @Test
    void rejectsDeviceIdWithEmptyField() {
        var transaction = validTransaction().setDeviceId("");

        assertThat(violations(transaction)).containsExactly("transaction.device_id");
    }

    @Test
    void rejectsMerchantCategoryWithThreeDigitsSequence() {
        var transaction = validTransaction().setMerchantCategory("541");

        assertThat(violations(transaction)).containsExactly("transaction.merchant_category");
    }

    private Iterable<String> violations(Transaction.Builder transaction) {
        var request = ScoreRequest.newBuilder().setTransaction(transaction).build();
        return validator.validate(request, NOW).getFieldViolationsList().stream()
                .map(BadRequest.FieldViolation::getField)
                .toList();
    }

    private static Transaction.Builder validTransaction() {
        return Transaction.newBuilder()
                .setTransactionId("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
                .setUserId("user-1")
                .setAmountMinor(100)
                .setCurrency("USD")
                .setTimestamp(Timestamp.newBuilder().setSeconds(NOW.getEpochSecond()))
                .setMerchantId("merchant-1")
                .setMerchantCategory("5411")
                .setDeviceId("device-1")
                .setIp("2001:db8::1")
                .setCountry("US")
                .setChannel(Channel.CHANNEL_ONLINE);
    }
}