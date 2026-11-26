package com.antifraud.scoring.score.redis;

import com.antifraud.features.UserFeatureHistory;
import com.antifraud.proto.v1.ScoreResponse;
import com.antifraud.scoring.score.domain.Money;
import com.antifraud.scoring.score.domain.Transaction;
import com.antifraud.scoring.score.domain.UserId;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisAsyncCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Testcontainers(disabledWithoutDocker = true)
class FeatureStoreScriptIntegrationTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long MINUTE = 60_000L;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    private RedisClient client;
    private RedisAsyncCommands<String, String> redis;
    private String sha;
    private String tag;

    @BeforeEach
    void loadScript() throws Exception {
        var resource = getClass().getResourceAsStream("/redis/update-features.lua");
        if (resource == null) {
            throw new IOException("Не найден redis/update-features.lua");
        }
        try (resource) {
            client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
            redis = client.connect().async();
            sha = await(redis.scriptLoad(new String(resource.readAllBytes(), StandardCharsets.UTF_8)));
        }
        tag = "{" + UUID.randomUUID() + "}";
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.shutdown();
        }
    }

    @Test
    void countsWindowsWithExclusiveLowerBounds() throws Exception {
        await(update(UUID.randomUUID(), NOW - 60 * MINUTE, 50, "A"));
        await(update(UUID.randomUUID(), NOW - 10 * MINUTE, 30, "A"));
        await(update(UUID.randomUUID(), NOW - 5 * MINUTE, 70, "B"));
        await(update(UUID.randomUUID(), NOW - MINUTE, 200, "C"));

        var result = await(update(UUID.randomUUID(), NOW, 199, "A:branch"));

        assertThat(result.subList(0, 6)).containsExactly(1L, 1L, 2L, 4L, "399", 2L);
        assertThat(await(redis.zcard("win:" + tag))).isEqualTo(4);
        assertThat(await(redis.zcard("small:" + tag))).isEqualTo(2);
    }

    @Test
    void countsDistinctMerchantsUntilTheirLastSmallPaymentExpires() throws Exception {
        var first = await(update(UUID.randomUUID(), NOW, 50, "A:main"));
        var repeatMerchant = await(update(UUID.randomUUID(), NOW + MINUTE, 70, "A:main"));
        var otherMerchant = await(update(UUID.randomUUID(), NOW + 2 * MINUTE, 90, "A:branch"));
        var afterFirstMerchantExpires = await(update(UUID.randomUUID(), NOW + 11 * MINUTE, 200, "C"));
        var emptyWindow = await(update(UUID.randomUUID(), NOW + 12 * MINUTE, 200, "C"));

        assertThat(first.subList(5, 7)).containsExactly(1L, 1L);
        assertThat(repeatMerchant.subList(5, 7)).containsExactly(2L, 1L);
        assertThat(otherMerchant.subList(5, 7)).containsExactly(3L, 2L);
        assertThat(afterFirstMerchantExpires.subList(5, 7)).containsExactly(1L, 1L);
        assertThat(emptyWindow.subList(5, 7)).containsExactly(0L, 0L);
    }

    @Test
    void concurrentRetryWithChangedAmountDoesNotMutateWindows() throws Exception {
        var id = UUID.randomUUID();
        var first = update(id, NOW, 150, "A");
        try (var secondConnection = client.connect()) {
            var retry = update(secondConnection.async(), id, NOW, 190, "B");
            var firstResult = await(first);
            var retryResult = await(retry);
            assertThat(List.of(firstResult.getFirst(), retryResult.getFirst())).containsExactlyInAnyOrder(0L, 1L);
        }

        assertThat(await(redis.zcard("win:" + tag))).isEqualTo(1);
        assertThat(await(redis.zcard("small:" + tag))).isEqualTo(1);
        assertThat(await(redis.ttl("feature-seen:" + tag + ":" + id))).isBetween(86_390L, 86_400L);
    }

    @Test
    void sumsAmountsWithoutLosingIntegerPrecision() throws Exception {
        await(update(UUID.randomUUID(), NOW, Long.MAX_VALUE, "A"));

        var result = await(update(UUID.randomUUID(), NOW, Long.MAX_VALUE, "B"));

        assertThat(result.get(4)).isEqualTo("18446744073709551614");
    }

    @Test
    void readsProfileAndKeepsLatestGeoAfterLatePayment() throws Exception {
        await(redis.hset("prof:" + tag, "avg_amount_minor", "120.5"));
        await(redis.hset("prof:" + tag, "tx_count", "4"));
        await(redis.sadd("dev:" + tag, "device"));
        await(redis.sadd("cty:" + tag, "US"));
        await(update(UUID.randomUUID(), NOW, 150, "A"));

        var late = await(update(UUID.randomUUID(), NOW - MINUTE, 150, "B"));
        var next = await(update(UUID.randomUUID(), NOW + MINUTE, 150, "C"));

        assertThat(late.get(7)).isEqualTo(List.of("", "", ""));
        assertThat(next.subList(7, 13)).containsExactly(
                List.of(Long.toString(NOW), "41", "-73"), "120.5", "4", 0L, 1L, 0L);
    }

    @Test
    void matchesOfflineWindowsAndTravelSpeed() throws Exception {
        var redisProperties = new DataRedisProperties();
        redisProperties.setHost(REDIS.getHost());
        redisProperties.setPort(REDIS.getMappedPort(6379));
        var user = new UserId("offline-{parity}-" + UUID.randomUUID());
        var offline = new UserFeatureHistory();
        var settings = new RedisScoringProperties(Duration.ofSeconds(1), Duration.ofSeconds(1));
        try (var connection = new AsyncRedisConnection(redisProperties)) {
            var store = new RedisScoreStore(connection, settings);
            for (int index = 0; index < 10; index++) {
                compareWithOffline(store, offline, user, NOW + 100L * index, 195 + index, "shop:" + index % 2, 41.0);
            }
            compareWithOffline(store, offline, user, NOW + MINUTE, 100, "shop:1", 41.2);
            compareWithOffline(store, offline, user, NOW + 5 * MINUTE, 500, "shop:2", 41.15);
            compareWithOffline(store, offline, user, NOW + 10 * MINUTE, 500, "shop:1", 41.0);
            compareWithOffline(store, offline, user, NOW + 60 * MINUTE, 500, "shop:1", 41.0);
        }
    }

    private static void compareWithOffline(RedisScoreStore store, UserFeatureHistory offline, UserId user, long time,
                                           long amount, String merchant, double latitude) throws Exception {
        var tx = new Transaction(UUID.randomUUID(), user, new Money(amount, Currency.getInstance("USD")),
                Instant.ofEpochMilli(time), merchant, "5411", "device", "192.0.2.1", "US",
                OptionalDouble.of(latitude), OptionalDouble.of(-73), Transaction.Channel.ONLINE);
        var expected = offline.observe(new UserFeatureHistory.Payment(time, amount, "USD", "5411", merchant,
                "ONLINE", "US", "device", "192.0.2.1", new UserFeatureHistory.Coordinates(latitude, -73)));
        store.score(tx, snapshot -> {
            var actual = snapshot.orElseThrow();
            assertThat(actual.txCount1m()).isEqualTo(expected.txCount1m());
            assertThat(actual.txCount5m()).isEqualTo(expected.txCount5m());
            assertThat(actual.txCount60m()).isEqualTo(expected.txCount60m());
            assertThat(actual.amountSum5m()).isEqualTo(BigInteger.valueOf(expected.amountSum5m()));
            assertThat(actual.smallTxCount10m()).isEqualTo(expected.smallTxCount10m());
            assertThat(actual.smallTxDistinctMerchants10m()).isEqualTo(expected.smallTxDistinctMerchants10m());
            assertThat(actual.travelSpeedKmh()).isCloseTo(expected.travelSpeedKmh(), within(0.000001));
            return ScoreResponse.newBuilder().setTransactionId(tx.id().toString()).build();
        }).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private RedisFuture<List<Object>> update(UUID id, long time, long amount, String merchant) {
        return update(redis, id, time, amount, merchant);
    }

    private RedisFuture<List<Object>> update(
            RedisAsyncCommands<String, String> commands, UUID id, long time, long amount, String merchant
    ) {
        String[] keys = {"win:" + tag, "small:" + tag, "last:" + tag, "prof:" + tag,
                "dev:" + tag, "ip:" + tag, "cty:" + tag, "feature-seen:" + tag + ":" + id};
        return commands.evalsha(sha, ScriptOutputType.MULTI, keys, id.toString(), Long.toString(time),
                Long.toString(amount), merchant, "device", "192.0.2.1", "US", "41", "-73");
    }

    private static <T> T await(RedisFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }
}
