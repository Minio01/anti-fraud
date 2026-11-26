package com.antifraud.scoring.score.grpc;

import com.antifraud.proto.v1.Decision;
import com.antifraud.proto.v1.Reason;
import com.antifraud.proto.v1.RiskScoringServiceGrpc;
import com.antifraud.proto.v1.ScoreRequest;
import com.antifraud.proto.v1.ScoreResponse;
import com.antifraud.scoring.score.application.AmountScorer;
import com.antifraud.scoring.score.domain.Transaction;
import com.antifraud.scoring.score.redis.RedisScoreStore;
import com.google.protobuf.Any;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.grpc.Context;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

@GrpcService
final class RiskScoringEndpoint extends RiskScoringServiceGrpc.RiskScoringServiceImplBase {
    private static final Logger LOGGER = LoggerFactory.getLogger(RiskScoringEndpoint.class);

    private final ScoreRequestValidator validator = new ScoreRequestValidator();
    private final TransactionMapper mapper = new TransactionMapper();
    private final AmountScorer scorer;
    private final RedisScoreStore store;

    RiskScoringEndpoint(AmountScorer scorer, RedisScoreStore store) {
        this.scorer = scorer;
        this.store = store;
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
        store.score(transaction, features -> calculate(transaction, originalId, startedAt, features.isEmpty()))
                .whenCompleteAsync((response, error) -> {
                    if (context.isCancelled()) {
                        return;
                    }
                    if (error != null && !RedisScoreStore.isRedisUnavailable(error)) {
                        LOGGER.error("Ошибка скоринга", error);
                        observer.onError(io.grpc.Status.INTERNAL.asRuntimeException());
                        return;
                    }
                    observer.onNext(error == null ? response : calculate(transaction, originalId, startedAt, true));
                    observer.onCompleted();
                }, context.fixedContextExecutor(ForkJoinPool.commonPool()));
    }

    private ScoreResponse calculate(Transaction transaction, String originalId, long startedAt, boolean degraded) {
        var result = scorer.score(transaction);
        var response = ScoreResponse.newBuilder()
                .setTransactionId(originalId)
                .setRiskScore(result.riskScore())
                .setModelVersion("amount-rule-v1")
                .setDegraded(degraded);
        switch (result) {
            case ALLOWED -> response.setDecision(Decision.DECISION_ALLOW);
            case AMOUNT_LIMIT_EXCEEDED -> response.setDecision(Decision.DECISION_BLOCK)
                    .addReasons(Reason.REASON_R6_AMOUNT_LIMIT);
        }
        response.setProcessingTimeUs(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startedAt));
        return response.build();
    }
}
