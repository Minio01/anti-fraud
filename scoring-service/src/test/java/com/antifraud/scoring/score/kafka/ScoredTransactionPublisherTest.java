package com.antifraud.scoring.score.kafka;

import com.antifraud.proto.v1.ScoredTransaction;
import com.antifraud.proto.v1.Transaction;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class ScoredTransactionPublisherTest {
    @Test
    void retriesBeforeSendingTheNextScoreForTheSameUser() throws InterruptedException {
        KafkaTemplate<String, byte[]> kafka = mock();
        var sent = new CountDownLatch(1);
        var first = ScoredTransaction.newBuilder()
                .setTransaction(Transaction.newBuilder().setUserId("user").setTransactionId("first"))
                .build();
        var second = first.toBuilder()
                .setTransaction(first.getTransaction().toBuilder().setTransactionId("second"))
                .build();
        when(kafka.send(eq("transactions-scored"), eq("user"), eq(first.toByteArray())))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("Kafka недоступна")))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafka.send(eq("transactions-scored"), eq("user"), eq(second.toByteArray())))
                .thenAnswer(call -> {
                    sent.countDown();
                    return CompletableFuture.completedFuture(null);
                });

        var meters = new SimpleMeterRegistry();
        var publisher = new ScoredTransactionPublisher(kafka, meters);
        publisher.offer(first);
        publisher.offer(second);
        publisher.start();
        try {
            assertThat(sent.await(5, SECONDS)).isTrue();
            var order = inOrder(kafka);
            order.verify(kafka, times(2))
                    .send(eq("transactions-scored"), eq("user"), eq(first.toByteArray()));
            order.verify(kafka).send(eq("transactions-scored"), eq("user"), eq(second.toByteArray()));
        } finally {
            publisher.stop();
            meters.close();
        }
    }

    @Test
    void retainsEventUntilKafkaAcknowledgesItAndCountsOverflow() throws InterruptedException {
        KafkaTemplate<String, byte[]> kafka = mock();
        var firstAttempt = new CountDownLatch(1);
        var retried = new CountDownLatch(1);
        var event = ScoredTransaction.newBuilder()
                .setTransaction(Transaction.newBuilder().setUserId("test-user"))
                .build();
        var failed = new CompletableFuture<SendResult<String, byte[]>>();
        when(kafka.send(eq("transactions-scored"), eq("test-user"), eq(event.toByteArray())))
                .thenAnswer(call -> {
                    firstAttempt.countDown();
                    return failed;
                })
                .thenAnswer(call -> {
                    retried.countDown();
                    return CompletableFuture.completedFuture(null);
                });
        when(kafka.send(eq("transactions-scored"), eq("other-user"), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        var meters = new SimpleMeterRegistry();
        var publisher = new ScoredTransactionPublisher(kafka, meters);
        publisher.start();
        try {
            publisher.offer(event);
            assertThat(firstAttempt.await(5, SECONDS)).isTrue();

            var otherEvent = event.toBuilder()
                    .setTransaction(event.getTransaction().toBuilder().setUserId("other-user"))
                    .build();
            for (int index = 0; index < 10_000; index++) {
                publisher.offer(otherEvent);
            }
            assertThat(meters.counter("scoring.kafka.dropped").count()).isEqualTo(1);

            failed.completeExceptionally(new TimeoutException("Брокер временно недоступен"));
            assertThat(retried.await(5, SECONDS)).isTrue();
            assertThat(meters.counter("scoring.kafka.dropped").count()).isEqualTo(1);
        } finally {
            publisher.stop();
            meters.close();
        }
    }
}
