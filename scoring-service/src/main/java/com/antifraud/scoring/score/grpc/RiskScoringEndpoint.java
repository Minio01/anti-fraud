package com.antifraud.scoring.score.grpc;

import com.antifraud.proto.v1.RiskScoringServiceGrpc;
import com.antifraud.proto.v1.ScoreRequest;
import com.antifraud.proto.v1.ScoreResponse;
import com.antifraud.proto.v1.ScoredTransaction;
import com.antifraud.scoring.score.application.RiskScorer;
import com.antifraud.scoring.score.domain.Transaction;
import com.antifraud.scoring.score.kafka.ScoredTransactionPublisher;
import com.antifraud.scoring.score.redis.RedisFeatureSnapshot;
import com.antifraud.scoring.score.redis.RedisScoreStore;
import com.google.protobuf.Any;
import com.google.protobuf.util.Timestamps;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.grpc.Context;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@GrpcService
final class RiskScoringEndpoint extends RiskScoringServiceGrpc.RiskScoringServiceImplBase {
    private static final Logger LOGGER = LoggerFactory.getLogger(RiskScoringEndpoint.class);

    private final ScoreRequestValidator validator = new ScoreRequestValidator();
    private final TransactionMapper mapper = new TransactionMapper();
    private final RiskScorer scorer;
    private final RedisScoreStore store;
    private final ScoredTransactionPublisher publisher;

    RiskScoringEndpoint(RiskScorer scorer, RedisScoreStore store, ScoredTransactionPublisher publisher) {
        this.scorer = scorer;
        this.store = store;
        this.publisher = publisher;
    }

    @Override
    public void score(ScoreRequest request, StreamObserver<ScoreResponse> observer) {
        long startedAt = System.nanoTime();
        var errors = validator.validate(request, Instant.now());
        if (errors.getFieldViolationsCount() > 0) {
            var status = Status.newBuilder()
                    .setCode(Code.INVALID_ARGUMENT_VALUE)
                    .setMessage("Невалидная транзакция")
                    .addDetails(Any.pack(errors))
                    .build();
            observer.onError(StatusProto.toStatusRuntimeException(status));
            return;
        }

        var transaction = mapper.toDomain(request.getTransaction());
        var originalId = request.getTransaction().getTransactionId();
        var context = Context.current();
        store.score(transaction, history -> calculate(transaction, originalId, startedAt, history))
                .exceptionallyCompose(error -> RedisScoreStore.isRedisUnavailable(error)
                        ? calculate(transaction, originalId, startedAt, Optional.empty())
                        : CompletableFuture.failedFuture(error))
                .whenCompleteAsync((response, error) -> {
                    if (error != null) {
                        if (!context.isCancelled()) {
                            observer.onError(grpcError(error));
                        }
                        return;
                    }
                    try {
                        if (!context.isCancelled()) {
                            observer.onNext(response);
                            observer.onCompleted();
                        }
                    } finally {
                        if (!response.getReplayed()) {
                            publisher.offer(ScoredTransaction.newBuilder()
                                    .setTransaction(request.getTransaction())
                                    .setResult(response)
                                    .setScoredAt(Timestamps.fromMillis(System.currentTimeMillis()))
                                    .build());
                        }
                    }
                }, context.fixedContextExecutor(ForkJoinPool.commonPool()));
    }

    private CompletionStage<ScoreResponse> calculate(
            Transaction transaction, String originalId, long startedAt, Optional<RedisFeatureSnapshot> history
    ) {
        return scorer.score(transaction, history).thenApply(result -> ScoreResponse.newBuilder()
                .setTransactionId(originalId)
                .setDecision(result.decision())
                .setRiskScore(result.riskScore())
                .addAllReasons(result.reasons())
                .setModelVersion(result.modelVersion())
                .setDegraded(result.degraded())
                .setProcessingTimeUs(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startedAt))
                .build());
    }

    private static Throwable grpcError(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        if (error instanceof RejectedExecutionException) {
            return io.grpc.Status.RESOURCE_EXHAUSTED.withDescription("Очередь инференса заполнена").asRuntimeException();
        }
        LOGGER.error("Ошибка скоринга", error);
        return io.grpc.Status.INTERNAL.asRuntimeException();
    }
}
