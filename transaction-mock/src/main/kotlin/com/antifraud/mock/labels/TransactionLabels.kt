package com.antifraud.mock.labels

import com.antifraud.mock.generation.pattern.ScheduledTransaction
import com.antifraud.proto.v1.TransactionLabel
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.apache.kafka.common.KafkaException
import org.springframework.kafka.KafkaException as SpringKafkaException
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

fun interface TransactionLabels {
    fun publish(event: ScheduledTransaction)
}

@Component
class KafkaTransactionLabels(
    private val kafka: KafkaTemplate<String, ByteArray>,
    meters: MeterRegistry,
) : TransactionLabels {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<TransactionLabel>(10_000)
    private val dropped = meters.counter("mock.labels.dropped")

    override fun publish(event: ScheduledTransaction) {
        val label = TransactionLabel.newBuilder()
            .setTransactionId(event.transaction.transactionId)
            .setExpectedFraud(event.expectedFraud)
            .setPattern(event.pattern?.name.orEmpty())
            .build()
        if (queue.trySend(label).isFailure) dropped.increment()
    }

    @PostConstruct
    fun start() {
        repeat(8) {
            scope.launch {
                for (label in queue) send(label)
            }
        }
    }

    @PreDestroy
    fun stop() {
        queue.cancel()
        scope.cancel()
    }

    private suspend fun send(label: TransactionLabel) {
        val bytes = label.toByteArray()
        while (true) {
            try {
                kafka.send("transactions-labels", label.transactionId, bytes).await()
                return
            } catch (_: KafkaException) {
                // Событие остаётся у отправителя до подтверждения Kafka.
            } catch (_: SpringKafkaException) {
            }
            delay(500)
        }
    }
}
