package com.antifraud.scoring.score.ml;

import com.antifraud.features.RequestFeatures;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class OnnxContractTest {
    @Test
    void matchesPythonForBothModelsOnOneHundredRows() throws Exception {
        var stream = Objects.requireNonNull(getClass().getResourceAsStream("/models/python-scores.csv"));
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
             var full = OnnxModelSession.load(true);
             var light = OnnxModelSession.load(false)) {
            assertThat(reader.readLine()).isEqualTo("amount,log_amount,currency,hour_of_day,merchant_category,channel,"
                    + "country,tx_count_1m,tx_count_5m,tx_count_60m,amount_sum_5m,small_tx_count_10m,"
                    + "small_tx_distinct_merchants_10m,travel_speed_kmh,amount_to_avg_ratio,user_tx_count,"
                    + "is_new_device,is_new_ip,is_new_country,python_full,python_light");
            double maxFull = 0;
            double maxLight = 0;
            int count = 0;
            for (String row; (row = reader.readLine()) != null; count++) {
                var fields = row.split(",", -1);
                assertThat(fields).hasSize(21);
                var request = RequestFeatures.from(Long.parseLong(fields[0]), fields[2],
                        Instant.EPOCH.plus(Long.parseLong(fields[3]), ChronoUnit.HOURS), fields[4], fields[5], fields[6]);
                assertThat(Math.abs(request.logAmount() - Double.parseDouble(fields[1]))).isLessThan(1e-12);
                var history = new RedisFeatureSnapshot(Long.parseLong(fields[7]), Long.parseLong(fields[8]),
                        Long.parseLong(fields[9]), new BigInteger(fields[10]), Long.parseLong(fields[11]),
                        Long.parseLong(fields[12]), Double.parseDouble(fields[13]), Double.parseDouble(fields[14]),
                        Long.parseLong(fields[15]), Boolean.parseBoolean(fields[16]), Boolean.parseBoolean(fields[17]),
                        Boolean.parseBoolean(fields[18]));
                maxFull = Math.max(maxFull, Math.abs(full.predict(request, history) - Double.parseDouble(fields[19])));
                maxLight = Math.max(maxLight, Math.abs(light.predict(request, null) - Double.parseDouble(fields[20])));
            }
            System.out.printf("Java/Python max delta: full=%.9g, light=%.9g%n", maxFull, maxLight);
            assertThat(count).isEqualTo(100);
            assertThat(maxFull).isLessThanOrEqualTo(1e-5);
            assertThat(maxLight).isLessThanOrEqualTo(1e-5);
        }
    }

    @Test
    void encodesAnUnseenCategoryAsZeros() {
        var input = ModelInput.parse("amount,log_amount,currency=USD,hour_of_day,merchant_category=5411,"
                + "channel=ONLINE,country=US,country=JP", false);
        var request = RequestFeatures.from(100, "EUR", Instant.EPOCH, "0011", "POS", "FR");
        assertThat(input.encode(request, null)).containsExactly(100, (float) Math.log1p(100), 0, 0, 0, 0, 0, 0);
    }
}
