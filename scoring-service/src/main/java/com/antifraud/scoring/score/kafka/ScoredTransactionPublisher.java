package com.antifraud.scoring.score.kafka;

import com.antifraud.proto.v1.ScoredTransaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;

@Component
public final class ScoredTransactionPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScoredTransactionPublisher.class);
    private static final int CAPACITY = 10_000;
    private static final int BATCH_SIZE = 128;
    private static final String TOPIC = "transactions-scored";
    private final KafkaTemplate<String, byte[]> kafka;
    private final Counter dropped;
    private final ArrayBlockingQueue<ScoredTransaction> queue = new ArrayBlockingQueue<>(CAPACITY);
    // Лимит включает записи, уже забранные из очереди и ожидающие подтверждения Kafka.
    private final Semaphore slots = new Semaphore(CAPACITY);
    private final Thread worker = Thread.ofVirtual().name("scoring-kafka-publisher").unstarted(this::run);

    public ScoredTransactionPublisher(KafkaTemplate<String, byte[]> kafka, MeterRegistry meters) {
        this.kafka = kafka;
        dropped = meters.counter("scoring.kafka.dropped");
    }

    public void offer(ScoredTransaction event) {
        if (slots.tryAcquire()) {
            queue.add(event);
        } else {
            dropped.increment();
        }
    }

    @PostConstruct
    void start() {
        worker.start();
    }

    @PreDestroy
    void stop() throws InterruptedException {
        worker.interrupt();
        worker.join(Duration.ofSeconds(2));
    }

    private void run() {
        var pending = new ArrayList<ScoredTransaction>(BATCH_SIZE);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                pending.add(queue.take());
                queue.drainTo(pending, BATCH_SIZE - 1);
                while (!pending.isEmpty()) {
                    if (send(pending)) {
                        Thread.sleep(Duration.ofMillis(500));
                    }
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean send(List<ScoredTransaction> pending) throws InterruptedException {
        var acknowledgments = new ArrayList<Acknowledgment>(pending.size());
        var users = new HashSet<String>();
        for (int index = 0; index < pending.size(); index++) {
            var event = pending.get(index);
            var user = event.getTransaction().getUserId();
            // Следующий вердикт пользователя ждёт, пока предыдущий подтверждён, даже после ошибки отправки.
            if (!users.add(user)) {
                continue;
            }
            try {
                acknowledgments.add(new Acknowledgment(index, kafka.send(TOPIC, user, event.toByteArray())));
            } catch (RuntimeException error) {
                acknowledgments.add(new Acknowledgment(index, CompletableFuture.failedFuture(error)));
                break;
            }
        }
        boolean retry = false;
        for (int index = acknowledgments.size() - 1; index >= 0; index--) {
            var acknowledgment = acknowledgments.get(index);
            try {
                acknowledgment.result().get();
            } catch (ExecutionException error) {
                if (!(rootCause(error) instanceof RecordTooLargeException)) {
                    retry = true;
                    continue;
                }
                dropped.increment();
                LOGGER.warn("Событие скоринга превышает предельный размер сообщения Kafka");
            }
            pending.remove(acknowledgment.index());
            slots.release();
        }
        return retry;
    }

    private static Throwable rootCause(Throwable error) {
        while (error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    private record Acknowledgment(int index, CompletableFuture<SendResult<String, byte[]>> result) {}
}
