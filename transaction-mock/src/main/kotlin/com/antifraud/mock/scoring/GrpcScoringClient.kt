package com.antifraud.mock.scoring

import com.antifraud.proto.v1.RiskScoringServiceGrpcKt.RiskScoringServiceCoroutineStub
import com.antifraud.proto.v1.ScoreRequest
import com.antifraud.proto.v1.ScoreResponse
import com.antifraud.proto.v1.Transaction
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

class GrpcScoringClient(
    private val stub: RiskScoringServiceCoroutineStub,
    private val meters: MeterRegistry,
) : ScoringGateway {
    private val duration = meters.timer("mock.scoring.duration")

    override suspend fun score(transaction: Transaction): ScoreResponse {
        val sample = Timer.start(meters)
        try {
            // Дедлайн создаётся для каждого вызова; метки генератора в запрос не входят (TM-8).
            return stub.withDeadlineAfter(100, TimeUnit.MILLISECONDS)
                .score(ScoreRequest.newBuilder().setTransaction(transaction).build())
        } finally {
            sample.stop(duration)
        }
    }
}
