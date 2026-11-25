package com.antifraud.features;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** История одного пользователя для расчёта признаков во временном порядке. */
public final class UserFeatureHistory {
    private static final long MINUTE_MS = 60_000;
    private static final long SMALL_PAYMENT_MINOR = 200;
    private static final double PROFILE_ALPHA = 0.1;
    private static final int PROFILE_LIMIT = 20;

    private final ArrayDeque<Long> minute = new ArrayDeque<>();
    private final ArrayDeque<Payment> fiveMinutes = new ArrayDeque<>();
    private final ArrayDeque<Long> hour = new ArrayDeque<>();
    private final ArrayDeque<Payment> small = new ArrayDeque<>();
    private final Map<String, Integer> smallMerchants = new HashMap<>();
    private final LinkedHashSet<String> devices = new LinkedHashSet<>();
    private final LinkedHashSet<String> ips = new LinkedHashSet<>();
    private final LinkedHashSet<String> countries = new LinkedHashSet<>();
    private Payment last;
    private long sumFiveMinutes;
    private long profileCount;
    private double averageMinor;

    public record Coordinates(double latitude, double longitude) {}

    public record Payment(
            long timeMs, long amountMinor, String currency, String merchantCategory, String merchantId,
            String channel, String country, String deviceId, String ip, Coordinates coordinates
    ) {}

    public record Features(
            long amount, double logAmount, String currency, int hourOfDay, String merchantCategory,
            String channel, String country, int txCount1m, int txCount5m, int txCount60m,
            long amountSum5m, int smallTxCount10m, int smallTxDistinctMerchants10m, double travelSpeedKmh,
            double amountToAvgRatio, long userTxCount, boolean newDevice, boolean newIp, boolean newCountry
    ) {}

    public Features observe(Payment payment) {
        long now = payment.timeMs();
        if (last != null && now < last.timeMs()) {
            throw new IllegalArgumentException("Платежи пользователя должны идти по времени");
        }

        expireTimes(minute, now - MINUTE_MS);
        expireTimes(hour, now - 60 * MINUTE_MS);
        while (!fiveMinutes.isEmpty() && fiveMinutes.getFirst().timeMs() <= now - 5 * MINUTE_MS) {
            sumFiveMinutes -= fiveMinutes.removeFirst().amountMinor();
        }
        while (!small.isEmpty() && small.getFirst().timeMs() <= now - 10 * MINUTE_MS) {
            String merchant = small.removeFirst().merchantId();
            smallMerchants.compute(merchant, (key, count) -> count == 1 ? null : count - 1);
        }

        minute.addLast(now);
        fiveMinutes.addLast(payment);
        hour.addLast(now);
        sumFiveMinutes = Math.addExact(sumFiveMinutes, payment.amountMinor());
        if (payment.amountMinor() < SMALL_PAYMENT_MINOR) {
            small.addLast(payment);
            smallMerchants.merge(payment.merchantId(), 1, Integer::sum);
        }

        // Окна включают текущий платёж, а признаки профиля описывают историю до него.
        var features = new Features(
                payment.amountMinor(), Math.log1p(payment.amountMinor()), payment.currency(),
                Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).getHour(), payment.merchantCategory(),
                payment.channel(), payment.country(), minute.size(), fiveMinutes.size(), hour.size(),
                sumFiveMinutes, small.size(), smallMerchants.size(), speed(last, payment),
                profileCount == 0 ? 1.0 : payment.amountMinor() / averageMinor, profileCount,
                !devices.contains(payment.deviceId()), !ips.contains(payment.ip()), !countries.contains(payment.country())
        );
        averageMinor = profileCount == 0 ? payment.amountMinor()
                : averageMinor + PROFILE_ALPHA * (payment.amountMinor() - averageMinor);
        profileCount++;
        remember(devices, payment.deviceId());
        remember(ips, payment.ip());
        remember(countries, payment.country());
        last = payment;
        return features;
    }

    private static void expireTimes(ArrayDeque<Long> times, long cutoff) {
        while (!times.isEmpty() && times.getFirst() <= cutoff) {
            times.removeFirst();
        }
    }

    private static void remember(LinkedHashSet<String> values, String value) {
        values.remove(value);
        values.addLast(value);
        if (values.size() > PROFILE_LIMIT) {
            values.removeFirst();
        }
    }

    private static double speed(Payment previous, Payment current) {
        if (previous == null || previous.coordinates() == null || current.coordinates() == null
                || current.timeMs() == previous.timeMs()) {
            return 0.0;
        }
        Coordinates from = previous.coordinates();
        Coordinates to = current.coordinates();
        double distance = GeoDistance.kilometers(from.latitude(), from.longitude(), to.latitude(), to.longitude());
        return distance * 3_600_000 / (current.timeMs() - previous.timeMs());
    }
}
