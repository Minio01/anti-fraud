package com.antifraud.scoring.score.grpc;

import com.antifraud.proto.v1.Channel;
import com.antifraud.proto.v1.Decision;
import com.antifraud.proto.v1.RiskScoringServiceGrpc;
import com.antifraud.proto.v1.ScoreRequest;
import com.antifraud.proto.v1.ScoreResponse;
import com.antifraud.proto.v1.Transaction;
import com.antifraud.scoring.score.redis.AsyncRedisConnection;
import com.antifraud.scoring.score.kafka.ScoredTransactionPublisher;
import com.antifraud.scoring.score.redis.RedisScoreStore;
import com.antifraud.scoring.score.redis.RedisScoringProperties;
import com.google.protobuf.util.Timestamps;
import com.google.rpc.BadRequest;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class ScoreRedisIntegrationTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8").withExposedPorts(6379);

    @Test
    void replaysOriginalDecisionAcrossEndpointsWithoutRefreshingTtl() throws Exception {
        var connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet();
        try (var firstEndpoint = new Endpoint(Duration.ofSeconds(1));
             var otherEndpoint = new Endpoint(Duration.ofSeconds(1))) {
            var redis = new StringRedisTemplate(connections);
            var transaction = transaction();
            var first = firstEndpoint.score(transaction, 1_000);
            assertThat(first.getDecision()).isEqualTo(Decision.DECISION_ALLOW);
            assertThat(first.getReplayed()).isFalse();
            assertThat(first.getDegraded()).isFalse();

            var key = "idem:{" + transaction.getTransactionId() + "}";
            assertThat(redis.getExpire(key)).isBetween(86_390L, 86_400L);
            assertThat(redis.expire(key, Duration.ofMinutes(5))).isTrue();

            var repeated = otherEndpoint.score(transaction.toBuilder().setAmountMinor(1000).build(), 1_000);
            assertThat(repeated).isEqualTo(first.toBuilder().setReplayed(true).build());
            assertThat(redis.getExpire(key)).isBetween(1L, 300L);
            assertThat(redis.opsForZSet().zCard("win:{" + transaction.getUserId() + "}")).isEqualTo(1);

            var invalid = assertThrows(StatusRuntimeException.class,
                    () -> otherEndpoint.score(transaction.toBuilder().setAmountMinor(0).build(), 1_000));
            assertThat(invalid.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(StatusProto.fromThrowable(invalid).getDetails(0).unpack(BadRequest.class)
                    .getFieldViolations(0).getField()).isEqualTo("transaction.amount_minor");
        } finally {
            connections.destroy();
        }
    }

    @Test
    void reservesTheIdOnceWhenConcurrentRequestsHaveDifferentUsers() throws Exception {
        try (var firstEndpoint = new Endpoint(Duration.ofSeconds(1));
             var otherEndpoint = new Endpoint(Duration.ofSeconds(1))) {
            var firstTx = transaction();
            var otherTx = transaction().toBuilder().setTransactionId(firstTx.getTransactionId())
                    .setAmountMinor(1000).build();
            var first = RiskScoringServiceGrpc.newFutureStub(firstEndpoint.channel)
                    .score(ScoreRequest.newBuilder().setTransaction(firstTx).build());
            var other = RiskScoringServiceGrpc.newFutureStub(otherEndpoint.channel)
                    .score(ScoreRequest.newBuilder().setTransaction(otherTx).build());

            var firstResponse = first.get(5, TimeUnit.SECONDS);
            var otherResponse = other.get(5, TimeUnit.SECONDS);

            assertThat(firstResponse.getDegraded()).isFalse();
            assertThat(otherResponse.getDegraded()).isFalse();
            assertThat(firstResponse.getReplayed()).isNotEqualTo(otherResponse.getReplayed());
            assertThat(firstResponse.toBuilder().clearReplayed().build())
                    .isEqualTo(otherResponse.toBuilder().clearReplayed().build());
            assertThat(windowCount(firstTx) + windowCount(otherTx)).isEqualTo(1);
        }
    }

    @Test
    void reloadsTheFeaturesScriptAfterRedisClearsItsCache() throws Exception {
        try (var endpoint = new Endpoint(Duration.ofSeconds(1))) {
            assertThat(endpoint.score(transaction(), 1_000).getDegraded()).isFalse();
            assertThat(redis("SCRIPT", "FLUSH")).isEqualTo("OK");
            var next = transaction();
            assertThat(endpoint.score(next, 1_000).getDegraded()).isFalse();
            assertThat(windowCount(next)).isEqualTo(1);
        }
    }

    @Test
    void scoresWithFallbackWhileRedisIsStalledAndRecovers() throws Exception {
        try (var endpoint = new Endpoint(Duration.ofMillis(35))) {
            awaitHealthyScore(endpoint);
            assertThat(redis("CLIENT", "PAUSE", "1200", "ALL")).isEqualTo("OK");

            for (int request = 0; request < 8; request++) {
                var tx = transaction().toBuilder().setAmountMinor(1000).build();
                var response = endpoint.score(tx, 300);
                assertThat(response.getDegraded()).isTrue();
                assertThat(response.getDecision()).isEqualTo(Decision.DECISION_BLOCK);
                assertThat(response.getReplayed()).isFalse();
                assertThat(response.getModelVersion()).isEqualTo("test-light");
            }

            assertThat(redis("PING")).isEqualTo("PONG");
            awaitHealthyScore(endpoint);
        }
    }

    private static void awaitHealthyScore(Endpoint endpoint) throws InterruptedException {
        long expiresAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            if (!endpoint.score(transaction(), 500).getDegraded()) {
                return;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < expiresAt);
        throw new AssertionError("Скорер не вышел из degraded-режима после восстановления Redis");
    }

    private static int windowCount(Transaction tx) throws Exception {
        return Integer.parseInt(redis("ZCARD", "win:{" + tx.getUserId() + "}"));
    }

    private static String redis(String... command) throws Exception {
        String[] args = new String[command.length + 1];
        args[0] = "redis-cli";
        System.arraycopy(command, 0, args, 1, command.length);
        var result = REDIS.execInContainer(args);
        assertThat(result.getExitCode()).isZero();
        return result.getStdout().strip();
    }

    private static Transaction transaction() {
        return Transaction.newBuilder()
                .setTransactionId(UUID.randomUUID().toString())
                .setUserId("user-" + UUID.randomUUID())
                .setAmountMinor(999)
                .setCurrency("USD")
                .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()))
                .setMerchantId("merchant-1")
                .setMerchantCategory("5411")
                .setDeviceId("device-1")
                .setIp("192.0.2.1")
                .setCountry("US")
                .setChannel(Channel.CHANNEL_ONLINE)
                .build();
    }

    private static final class Endpoint implements AutoCloseable {
        private final AsyncRedisConnection connections;
        private final Server server;
        private final ManagedChannel channel;

        private Endpoint(Duration redisDeadline) throws IOException {
            var redis = new DataRedisProperties();
            redis.setHost(REDIS.getHost());
            redis.setPort(REDIS.getMappedPort(6379));
            connections = new AsyncRedisConnection(redis);

            var settings = new RedisScoringProperties(redisDeadline, Duration.ofMillis(200));
            var scorer = TestRiskScorer.create(1000);
            var name = InProcessServerBuilder.generateName();
            server = InProcessServerBuilder.forName(name)
                    .addService(new RiskScoringEndpoint(scorer, new RedisScoreStore(connections, settings),
                            org.mockito.Mockito.mock(ScoredTransactionPublisher.class)))
                    .build().start();
            channel = InProcessChannelBuilder.forName(name).build();
        }

        private ScoreResponse score(Transaction tx, long deadlineMs) {
            return RiskScoringServiceGrpc.newBlockingStub(channel).withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .score(ScoreRequest.newBuilder().setTransaction(tx).build());
        }

        @Override
        public void close() {
            channel.shutdownNow();
            server.shutdownNow();
            connections.close();
        }
    }
}
