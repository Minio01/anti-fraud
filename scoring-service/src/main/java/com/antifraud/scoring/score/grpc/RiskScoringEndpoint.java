package com.antifraud.scoring.score.grpc;

import com.antifraud.proto.v1.Decision;
import com.antifraud.proto.v1.Reason;
import com.antifraud.proto.v1.RiskScoringServiceGrpc;
import com.antifraud.proto.v1.ScoreRequest;
import com.antifraud.proto.v1.ScoreResponse;
import com.antifraud.scoring.score.application.AmountScorer;
import com.antifraud.scoring.score.domain.Transaction;
import com.antifraud.scoring.score.redis.ScoreResponseCache;
import com.google.protobuf.Any;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.springframework.dao.DataAccessException;
import org.springframework.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@GrpcService
final class RiskScoringEndpoint extends RiskScoringServiceGrpc.RiskScoringServiceImplBase {
    private final ScoreRequestValidator validator = new ScoreRequestValidator();
    private final TransactionMapper mapper = new TransactionMapper();
    private final AmountScorer scorer;
    private final ScoreResponseCache cache;

    RiskScoringEndpoint(AmountScorer scorer, ScoreResponseCache cache) {
        this.scorer = scorer;
        this.cache = cache;
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
        observer.onNext(scoreWithReplay(transaction, request.getTransaction().getTransactionId(), startedAt));
        observer.onCompleted();
    }

    private ScoreResponse scoreWithReplay(Transaction transaction, String originalId, long startedAt) {
        ScoreResponse saved;
        try {
            saved = cache.find(transaction.id());
        } catch (DataAccessException exception) {
            return calculate(transaction, originalId, startedAt).toBuilder().setDegraded(true).build();
        }
        if (saved != null) {
            return saved.toBuilder().setReplayed(true).build();
        }

        var calculated = calculate(transaction, originalId, startedAt);
        try {
            saved = cache.saveIfAbsent(transaction.id(), calculated);
        } catch (DataAccessException exception) {
            return calculated.toBuilder().setDegraded(true).build();
        }
        return saved == null ? calculated : saved.toBuilder().setReplayed(true).build();
    }

    private ScoreResponse calculate(Transaction transaction, String originalId, long startedAt) {
        var result = scorer.score(transaction);
        var response = ScoreResponse.newBuilder()
                .setTransactionId(originalId)
                .setRiskScore(result.riskScore())
                .setModelVersion("amount-rule-v1");
        switch (result) {
            case ALLOWED -> response.setDecision(Decision.DECISION_ALLOW);
            case AMOUNT_LIMIT_EXCEEDED -> response.setDecision(Decision.DECISION_BLOCK)
                    .addReasons(Reason.REASON_R6_AMOUNT_LIMIT);
        }
        response.setProcessingTimeUs(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startedAt));
        return response.build();
    }
}
