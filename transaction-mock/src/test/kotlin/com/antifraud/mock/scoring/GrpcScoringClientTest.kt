package com.antifraud.mock.scoring

import com.antifraud.proto.v1.Decision
import com.antifraud.proto.v1.RiskScoringServiceGrpcKt.RiskScoringServiceCoroutineImplBase
import com.antifraud.proto.v1.RiskScoringServiceGrpcKt.RiskScoringServiceCoroutineStub
import com.antifraud.proto.v1.ScoreRequest
import com.antifraud.proto.v1.ScoreResponse
import com.antifraud.proto.v1.Transaction
import io.grpc.Context
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GrpcScoringClientTest {
    private val transaction = Transaction.newBuilder().setTransactionId("test-id").build()

    @Test
    fun `sends the transaction with a fresh deadline and records latency`() = runBlocking {
        var receivedDeadlineMillis = 0L
        val service = object : RiskScoringServiceCoroutineImplBase() {
            override suspend fun score(request: ScoreRequest): ScoreResponse {
                receivedDeadlineMillis = Context.current().deadline.timeRemaining(TimeUnit.MILLISECONDS)
                return ScoreResponse.newBuilder().setTransactionId(request.transaction.transactionId)
                    .setDecision(Decision.DECISION_ALLOW).build()
            }
        }
        withClient(service) { client, meters ->
            repeat(2) {
                val response = client.score(transaction)
                assertEquals(transaction.transactionId, response.transactionId)
                assertEquals(Decision.DECISION_ALLOW, response.decision)
                assertTrue(receivedDeadlineMillis in 1..100)
            }
            assertEquals(2L, meters.get("mock.scoring.duration").timer().count())
        }
    }

    @Test
    fun `deadline terminates slow scoring and still records latency`() = runBlocking {
        val service = object : RiskScoringServiceCoroutineImplBase() {
            override suspend fun score(request: ScoreRequest): ScoreResponse {
                if (request.transaction.transactionId == "warmup") return ScoreResponse.getDefaultInstance()
                delay(1_000)
                return ScoreResponse.getDefaultInstance()
            }
        }
        withClient(service) { client, meters ->
            val failure = assertFailsWith<StatusException> { client.score(transaction) }
            assertEquals(Status.Code.DEADLINE_EXCEEDED, failure.status.code)
            assertEquals(1L, meters.get("mock.scoring.duration").timer().count())
        }
    }

    @Test
    fun `caller cancellation cancels the rpc and records latency`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val service = object : RiskScoringServiceCoroutineImplBase() {
            override suspend fun score(request: ScoreRequest): ScoreResponse {
                if (request.transaction.transactionId == "warmup") return ScoreResponse.getDefaultInstance()
                entered.complete(Unit)
                try {
                    delay(10_000)
                    return ScoreResponse.getDefaultInstance()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        withClient(service) { client, meters ->
            val call = launch { client.score(transaction) }
            withTimeout(2_000) { entered.await() }
            call.cancelAndJoin()
            withTimeout(2_000) { cancelled.await() }
            assertTrue(call.isCancelled)
            assertEquals(1L, meters.get("mock.scoring.duration").timer().count())
        }
    }

    private suspend fun withClient(
        service: RiskScoringServiceCoroutineImplBase,
        action: suspend (GrpcScoringClient, SimpleMeterRegistry) -> Unit,
    ) {
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        val meters = SimpleMeterRegistry()
        try {
            val stub = RiskScoringServiceCoroutineStub(channel)
            stub.withDeadlineAfter(5, TimeUnit.SECONDS).score(
                ScoreRequest.newBuilder().setTransaction(Transaction.newBuilder().setTransactionId("warmup")).build(),
            )
            action(GrpcScoringClient(stub, meters), meters)
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            meters.close()
        }
    }
}
