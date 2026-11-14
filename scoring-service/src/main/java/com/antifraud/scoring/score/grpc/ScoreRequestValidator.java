package com.antifraud.scoring.score.grpc;

import com.antifraud.proto.v1.ScoreRequest;
import com.google.protobuf.util.Timestamps;
import com.google.rpc.BadRequest;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class ScoreRequestValidator {
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}");
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");
    private static final Pattern MCC = Pattern.compile("[0-9]{4}");
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    private static final Set<String> COUNTRIES = Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2);

    BadRequest validate(ScoreRequest request, Instant now) {
        var errors = BadRequest.newBuilder();
        if (!request.hasTransaction()) {
            return errors.addFieldViolations(ScoreValidationError.MISSING_TRANSACTION.violation()).build();
        }

        var transaction = request.getTransaction();
        if (!UUID.matcher(transaction.getTransactionId()).matches()) {
            errors.addFieldViolations(ScoreValidationError.INVALID_TRANSACTION_ID.violation());
        }
        if (!hasValidLength(transaction.getUserId(), 64)) {
            errors.addFieldViolations(ScoreValidationError.INVALID_USER_ID.violation());
        }
        if (transaction.getAmountMinor() <= 0) {
            errors.addFieldViolations(ScoreValidationError.INVALID_AMOUNT.violation());
        }
        if (!isCurrency(transaction.getCurrency())) {
            errors.addFieldViolations(ScoreValidationError.INVALID_CURRENCY.violation());
        }

        var timestamp = transaction.getTimestamp();
        if (!transaction.hasTimestamp() || !Timestamps.isValid(timestamp)) {
            errors.addFieldViolations(ScoreValidationError.INVALID_TIMESTAMP.violation());
        } else if (Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()).isAfter(now.plus(MAX_FUTURE_SKEW))) {
            errors.addFieldViolations(ScoreValidationError.FUTURE_TIMESTAMP.violation());
        }

        if (!hasValidLength(transaction.getMerchantId(), 64)) {
            errors.addFieldViolations(ScoreValidationError.INVALID_MERCHANT_ID.violation());
        }

        if (!MCC.matcher(transaction.getMerchantCategory()).matches()) {
            errors.addFieldViolations(ScoreValidationError.INVALID_MERCHANT_CATEGORY.violation());
        }

        if (!hasValidLength(transaction.getDeviceId(), 128)) {
            errors.addFieldViolations(ScoreValidationError.INVALID_DEVICE_ID.violation());
        }

        if (!isIpLiteral(transaction.getIp())) {
            errors.addFieldViolations(ScoreValidationError.INVALID_IP.violation());
        }
        if (!COUNTRIES.contains(transaction.getCountry())) {
            errors.addFieldViolations(ScoreValidationError.INVALID_COUNTRY.violation());
        }
        if (transaction.hasLatitude() && !inRange(transaction.getLatitude(), 90)) {
            errors.addFieldViolations(ScoreValidationError.INVALID_LATITUDE.violation());
        }
        if (transaction.hasLongitude() && !inRange(transaction.getLongitude(), 180)) {
            errors.addFieldViolations(ScoreValidationError.INVALID_LONGITUDE.violation());
        }
        switch (transaction.getChannel()) {
            case CHANNEL_ONLINE, CHANNEL_POS, CHANNEL_ATM -> { }
            case CHANNEL_UNSPECIFIED, UNRECOGNIZED ->
                    errors.addFieldViolations(ScoreValidationError.INVALID_CHANNEL.violation());
        }
        return errors.build();
    }

    private static boolean hasValidLength(String value, int maxLength) {
        return !value.isEmpty() && value.codePointCount(0, value.length()) <= maxLength;
    }

    private static boolean isCurrency(String value) {
        if (!CURRENCY_CODE.matcher(value).matches()) {
            return false;
        }
        try {
            Currency.getInstance(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static boolean isIpLiteral(String value) {
        if (value.indexOf('%') >= 0 || value.startsWith("[")) {
            return false;
        }
        try {
            InetAddress.ofLiteral(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static boolean isOnlyLetters(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return str.chars().allMatch(Character::isLetter);
    }


    private static boolean inRange(double value, double limit) {
        return value >= -limit && value <= limit;
    }
}
