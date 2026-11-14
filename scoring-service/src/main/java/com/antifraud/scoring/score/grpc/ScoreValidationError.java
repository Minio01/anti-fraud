package com.antifraud.scoring.score.grpc;

import com.google.rpc.BadRequest;

enum ScoreValidationError {
    MISSING_TRANSACTION("transaction", "Транзакция обязательна"),
    INVALID_TRANSACTION_ID("transaction.transaction_id", "Ожидается UUID"),
    INVALID_USER_ID("transaction.user_id", "Ожидается от 1 до 64 символов"),
    INVALID_AMOUNT("transaction.amount_minor", "Сумма должна быть больше нуля"),
    INVALID_CURRENCY("transaction.currency", "Ожидается код ISO 4217 из трёх заглавных букв"),
    INVALID_TIMESTAMP("transaction.timestamp", "Ожидается корректный Timestamp"),
    FUTURE_TIMESTAMP("transaction.timestamp", "Время может быть в будущем максимум на 5 минут"),
    INVALID_MERCHANT_ID("transaction.merchant_id", "Ожидается от 1 до 64 символов"),
    INVALID_MERCHANT_CATEGORY("transaction.merchant_category", "Ожидается четырехзначное число"),
    INVALID_DEVICE_ID("transaction.device_id", "Ожидается от 1 до 128 символов"),
    INVALID_IP("transaction.ip", "Ожидается IPv4 или IPv6"),
    INVALID_COUNTRY("transaction.country", "Ожидается код ISO 3166-1 alpha-2"),
    INVALID_LATITUDE("transaction.latitude", "Широта должна быть в диапазоне от −90 до 90"),
    INVALID_LONGITUDE("transaction.longitude", "Долгота должна быть в диапазоне от −180 до 180"),
    INVALID_CHANNEL("transaction.channel", "Ожидается ONLINE, POS или ATM");

    private final BadRequest.FieldViolation violation;

    ScoreValidationError(String field, String description) {
        this.violation = BadRequest.FieldViolation.newBuilder()
                .setField(field)
                .setDescription(description)
                .build();
    }

    BadRequest.FieldViolation violation() {
        return violation;
    }
}
