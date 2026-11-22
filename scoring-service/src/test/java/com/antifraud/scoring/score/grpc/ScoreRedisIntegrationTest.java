package com.antifraud.scoring.score.grpc;

import com.antifraud.proto.v1.Channel;
import com.antifraud.proto.v1.Decision;
import com.antifraud.proto.v1.RiskScoringServiceGrpc;
import com.antifraud.proto.v1.ScoreRequest;
import com.antifraud.proto.v1.Transaction;
import com.antifraud.scoring.score.application.AmountScorer;
import com.antifraud.scoring.score.application.AmountScoringProperties;
import com.antifraud.scoring.score.redis.ScoreResponseCache;
import com.google.protobuf.Timestamp;
import com.google.rpc.BadRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
        try {
            var redis = new StringRedisTemplate(connections);
            var scorer = new AmountScorer(new AmountScoringProperties(1000));
            var serverName = InProcessServerBuilder.generateName();
            var server = InProcessServerBuilder.forName(serverName)
                    .addService(new RiskScoringEndpoint(scorer, new ScoreResponseCache(redis))).build().start();
            var otherServerName = InProcessServerBuilder.generateName();
            var otherServer = InProcessServerBuilder.forName(otherServerName)
                    .addService(new RiskScoringEndpoint(scorer, new ScoreResponseCache(redis))).build();
            var channel = InProcessChannelBuilder.forName(serverName).build();
            var otherChannel = InProcessChannelBuilder.forName(otherServerName).build();
            try {
                otherServer.start();
                var firstClient = RiskScoringServiceGrpc.newBlockingStub(channel);
                var otherClient = RiskScoringServiceGrpc.newBlockingStub(otherChannel);
                var transaction = Transaction.newBuilder()
                        .setTransactionId(UUID.randomUUID().toString())
                        .setUserId("user-1")
                        .setAmountMinor(999)
                        .setCurrency("USD")
                        .setTimestamp(Timestamp.getDefaultInstance())
                        .setMerchantId("merchant-1")
                        .setMerchantCategory("5411")
                        .setDeviceId("device-1")
                        .setIp("192.0.2.1")
                        .setCountry("US")
                        .setChannel(Channel.CHANNEL_ONLINE);

                var first = firstClient.withDeadlineAfter(5, TimeUnit.SECONDS)
                        .score(ScoreRequest.newBuilder().setTransaction(transaction).build());
                assertThat(first.getDecision()).isEqualTo(Decision.DECISION_ALLOW);
                assertThat(first.getReplayed()).isFalse();
                assertThat(first.getDegraded()).isFalse();

                var key = "idem:{" + transaction.getTransactionId() + "}";
                long ttlSeconds = redis.getExpire(key);
                assertThat(ttlSeconds).isBetween(86_390L, 86_400L);
                assertThat(redis.expire(key, Duration.ofMinutes(5))).isTrue();

                var competingResult = first.toBuilder().setDecision(Decision.DECISION_BLOCK).build();
                assertThat(new ScoreResponseCache(redis).saveIfAbsent(
                        UUID.fromString(transaction.getTransactionId()), competingResult)).isEqualTo(first);

                var repeated = otherClient.withDeadlineAfter(5, TimeUnit.SECONDS)
                        .score(ScoreRequest.newBuilder().setTransaction(transaction.setAmountMinor(1000)).build());
                assertThat(repeated).isEqualTo(first.toBuilder().setReplayed(true).build());
                assertThat(redis.getExpire(key)).isBetween(1L, 300L);

                var invalid = assertThrows(StatusRuntimeException.class, () -> otherClient
                        .withDeadlineAfter(5, TimeUnit.SECONDS)
                        .score(ScoreRequest.newBuilder().setTransaction(transaction.setAmountMinor(0)).build()));
                assertThat(invalid.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
                assertThat(StatusProto.fromThrowable(invalid).getDetails(0).unpack(BadRequest.class)
                        .getFieldViolations(0).getField()).isEqualTo("transaction.amount_minor");
            } finally {
                channel.shutdownNow();
                otherChannel.shutdownNow();
                server.shutdownNow();
                otherServer.shutdownNow();
            }
        } finally {
            connections.destroy();
        }
    }
}
