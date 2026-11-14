package com.antifraud.scoring.score.grpc;

import com.antifraud.scoring.score.domain.Money;
import com.antifraud.scoring.score.domain.Transaction;
import com.antifraud.scoring.score.domain.UserId;

import java.time.Instant;
import java.util.Currency;
import java.util.OptionalDouble;
import java.util.UUID;

import static com.antifraud.scoring.score.domain.Transaction.Channel.*;

final class TransactionMapper {
    Transaction toDomain(com.antifraud.proto.v1.Transaction input) {
        Transaction.Channel channel = switch (input.getChannel()) {
            case CHANNEL_ONLINE -> ONLINE;
            case CHANNEL_ATM -> ATM;
            case CHANNEL_POS -> POS;
            case UNRECOGNIZED, CHANNEL_UNSPECIFIED -> throw new IllegalArgumentException();
        };

        return new Transaction(
                UUID.fromString(input.getTransactionId()),
                new UserId(input.getUserId()),
                new Money(input.getAmountMinor(), Currency.getInstance(input.getCurrency())),
                Instant.ofEpochSecond(input.getTimestamp().getSeconds(), input.getTimestamp().getNanos()),
                input.getMerchantId(),
                input.getMerchantCategory(),
                input.getDeviceId(),
                input.getIp(),
                input.getCountry(),
                input.hasLatitude() ? OptionalDouble.of(input.getLatitude()) : OptionalDouble.empty(),
                input.hasLongitude() ? OptionalDouble.of(input.getLongitude()) : OptionalDouble.empty(),
                channel
        );
    }
}
