package com.antifraud.profile.update;

import com.antifraud.features.RedisUserTag;
import com.antifraud.features.UserFeatureHistory;
import com.antifraud.proto.v1.Decision;
import com.antifraud.proto.v1.ScoreResponse;
import com.antifraud.proto.v1.ScoredTransaction;
import com.antifraud.proto.v1.Transaction;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Testcontainers(disabledWithoutDocker = true)
class ProfileEventListenerTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    @Test
    void matchesOfflineProfileWhenCaughtUpAndIgnoresDuplicatesAndBlocks() {
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        try {
            var redis = new ReactiveStringRedisTemplate(factory);
            var worker = new ProfileEventListener(redis);
            var offline = new UserFeatureHistory();
            var user = "profile:{" + UUID.randomUUID() + "}";
            var tag = RedisUserTag.of(user);
            for (int index = 0; index < 26; index++) {
                int attribute = switch (index) {
                    case 19, 23 -> 0;
                    case 24 -> 1;
                    default -> index;
                };
                var transaction = transaction(user, attribute, 5_000L + 137L * index);
                var features = offline.observe(new UserFeatureHistory.Payment(1_800_000_000_000L + index,
                        transaction.getAmountMinor(), "USD", "1234", "ONLINE", "COMPATIBLE",
                        transaction.getCountry(), transaction.getDeviceId(), transaction.getIp(), null));
                var profile = redis.<String, String>opsForHash();
                var count = profile.get("prof:" + tag, "tx_count").defaultIfEmpty("0").block();
                var average = profile.get("prof:" + tag, "avg_amount_minor").block();
                assertThat(Long.parseLong(count)).isEqualTo(features.userTxCount());
                double ratio = average == null ? 1 : transaction.getAmountMinor() / Double.parseDouble(average);
                assertThat(ratio).isCloseTo(features.amountToAvgRatio(), within(1e-12));
                var sets = redis.opsForSet();
                assertThat(sets.isMember("dev:" + tag, transaction.getDeviceId()).block()).isEqualTo(!features.newDevice());
                assertThat(sets.isMember("ip:" + tag, transaction.getIp()).block()).isEqualTo(!features.newIp());
                assertThat(sets.isMember("cty:" + tag, transaction.getCountry()).block()).isEqualTo(!features.newCountry());

                byte[] accepted = event(transaction, index % 2 == 0 ? Decision.DECISION_ALLOW : Decision.DECISION_FLAG);
                worker.apply(accepted);
                worker.apply(accepted);
                worker.apply(event(transaction(user, 30, 999_999_999), Decision.DECISION_BLOCK));
            }
            assertThat(redis.<String, String>opsForHash().get("prof:" + tag, "tx_count").block()).isEqualTo("26");
            assertThat(redis.opsForSet().size("dev:" + tag).block()).isEqualTo(20L);
            assertThat(redis.opsForSet().size("ip:" + tag).block()).isEqualTo(20L);
            assertThat(redis.opsForSet().size("cty:" + tag).block()).isEqualTo(20L);
        } finally {
            factory.destroy();
        }
    }

    private static Transaction transaction(String user, int attribute, long amount) {
        return Transaction.newBuilder().setTransactionId(UUID.randomUUID().toString()).setUserId(user)
                .setAmountMinor(amount).setDeviceId("device-" + attribute).setIp("192.0.2." + (attribute + 1))
                .setCountry(Locale.getISOCountries()[attribute]).build();
    }

    private static byte[] event(Transaction transaction, Decision decision) {
        return ScoredTransaction.newBuilder().setTransaction(transaction)
                .setResult(ScoreResponse.newBuilder().setDecision(decision)).build().toByteArray();
    }
}
