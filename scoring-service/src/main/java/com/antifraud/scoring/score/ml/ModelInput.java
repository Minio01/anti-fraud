package com.antifraud.scoring.score.ml;

import com.antifraud.features.RequestFeatures;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

record ModelInput(List<Column> columns) {
    private static final EnumSet<Feature> REQUEST = EnumSet.range(Feature.AMOUNT, Feature.COUNTRY);
    private static final EnumSet<Feature> CATEGORICAL = EnumSet.of(
            Feature.CURRENCY, Feature.MERCHANT_CATEGORY, Feature.CHANNEL, Feature.COUNTRY);

    static ModelInput parse(String descriptor, boolean full) {
        var names = Arrays.asList(descriptor.split(",", -1));
        var columns = names.stream().map(Column::parse).toList();
        var actual = EnumSet.noneOf(Feature.class);
        columns.forEach(column -> actual.add(column.feature()));
        var expected = full ? EnumSet.allOf(Feature.class) : REQUEST;
        if (!actual.equals(expected) || new HashSet<>(columns).size() != columns.size()) {
            throw new IllegalArgumentException("Модель содержит неожиданный набор признаков");
        }
        return new ModelInput(columns);
    }

    int size() {
        return columns.size();
    }

    float[] encode(RequestFeatures request, RedisFeatureSnapshot history) {
        var input = new float[size()];
        for (int i = 0; i < input.length; i++) {
            input[i] = columns.get(i).value(request, history);
        }
        return input;
    }

    record Column(Feature feature, String category) {
        static Column parse(String name) {
            var separator = name.indexOf('=');
            var field = separator < 0 ? name : name.substring(0, separator);
            var feature = Feature.valueOf(field.toUpperCase(Locale.ROOT));
            var category = separator < 0 ? "" : name.substring(separator + 1);
            if (CATEGORICAL.contains(feature) != (separator >= 0)
                    || separator >= 0 && (category.isBlank() || category.contains("="))) {
                throw new IllegalArgumentException("Неверное описание признака модели: " + name);
            }
            return new Column(feature, category);
        }

        float value(RequestFeatures request, RedisFeatureSnapshot history) {
            return switch (feature) {
                case AMOUNT -> request.amount();
                case LOG_AMOUNT -> (float) request.logAmount();
                case CURRENCY -> flag(category.equals(request.currency()));
                case HOUR_OF_DAY -> request.hourOfDay();
                case MERCHANT_CATEGORY -> flag(category.equals(request.merchantCategory()));
                case CHANNEL -> flag(category.equals(request.channel()));
                case COUNTRY -> flag(category.equals(request.country()));
                case TX_COUNT_1M -> history.txCount1m();
                case TX_COUNT_5M -> history.txCount5m();
                case TX_COUNT_60M -> history.txCount60m();
                case AMOUNT_SUM_5M -> history.amountSum5m().floatValue();
                case SMALL_TX_COUNT_10M -> history.smallTxCount10m();
                case SMALL_TX_DISTINCT_MERCHANTS_10M -> history.smallTxDistinctMerchants10m();
                case TRAVEL_SPEED_KMH -> (float) history.travelSpeedKmh();
                case AMOUNT_TO_AVG_RATIO -> (float) history.amountToAvgRatio();
                case USER_TX_COUNT -> history.userTxCount();
                case IS_NEW_DEVICE -> flag(history.newDevice());
                case IS_NEW_IP -> flag(history.newIp());
                case IS_NEW_COUNTRY -> flag(history.newCountry());
            };
        }

        private static float flag(boolean value) {
            return value ? 1 : 0;
        }
    }

    enum Feature {
        AMOUNT, LOG_AMOUNT, CURRENCY, HOUR_OF_DAY, MERCHANT_CATEGORY, CHANNEL, COUNTRY,
        TX_COUNT_1M, TX_COUNT_5M, TX_COUNT_60M, AMOUNT_SUM_5M, SMALL_TX_COUNT_10M,
        SMALL_TX_DISTINCT_MERCHANTS_10M, TRAVEL_SPEED_KMH, AMOUNT_TO_AVG_RATIO, USER_TX_COUNT,
        IS_NEW_DEVICE, IS_NEW_IP, IS_NEW_COUNTRY
    }
}
