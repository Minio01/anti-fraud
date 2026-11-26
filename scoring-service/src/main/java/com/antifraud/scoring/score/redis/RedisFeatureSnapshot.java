package com.antifraud.scoring.score.redis;

import com.antifraud.features.GeoDistance;
import com.antifraud.scoring.score.domain.Transaction;

import java.math.BigInteger;
import java.util.List;

public record RedisFeatureSnapshot(
        long txCount1m, long txCount5m, long txCount60m, BigInteger amountSum5m,
        long smallTxCount10m, long smallTxDistinctMerchants10m, double travelSpeedKmh,
        double amountToAvgRatio, long userTxCount, boolean newDevice, boolean newIp, boolean newCountry
) {
    static RedisFeatureSnapshot decode(List<Object> result, Transaction transaction) {
        var count = Long.parseLong((String) result.get(9));
        var average = (String) result.get(8);
        return new RedisFeatureSnapshot(
                (Long) result.get(1), (Long) result.get(2), (Long) result.get(3),
                new BigInteger((String) result.get(4)), (Long) result.get(5), (Long) result.get(6),
                travelSpeed((List<?>) result.get(7), transaction),
                count == 0 || average.isEmpty() ? 1.0 : transaction.money().amountMinor() / Double.parseDouble(average),
                count, (Long) result.get(10) == 1, (Long) result.get(11) == 1, (Long) result.get(12) == 1);
    }

    private static double travelSpeed(List<?> previous, Transaction current) {
        if (((String) previous.getFirst()).isEmpty() || current.latitude().isEmpty() || current.longitude().isEmpty()) {
            return 0;
        }
        var elapsedMs = current.timestamp().toEpochMilli() - Long.parseLong((String) previous.getFirst());
        var distance = GeoDistance.kilometers(Double.parseDouble((String) previous.get(1)),
                Double.parseDouble((String) previous.get(2)), current.latitude().getAsDouble(),
                current.longitude().getAsDouble());
        return distance * 3_600_000 / elapsedMs;
    }
}
