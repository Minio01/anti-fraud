package com.antifraud.scoring.score.grpc;

import com.antifraud.proto.v1.Decision;
import com.antifraud.proto.v1.Reason;
import com.antifraud.proto.v1.RiskScoringServiceGrpc;
import com.antifraud.proto.v1.ScoreRequest;
import com.antifraud.proto.v1.ScoreResponse;
import com.antifraud.scoring.score.application.AmountScorer;
import com.google.protobuf.Any;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

@GrpcService
final class RiskScoringEndpoint extends RiskScoringServiceGrpc.RiskScoringServiceImplBase {
    private final ScoreRequestValidator validator = new ScoreRequestValidator();
    private final TransactionMapper mapper = new TransactionMapper();
    private final AmountScorer scorer;

    RiskScoringEndpoint(AmountScorer scorer) {
        this.scorer = scorer;
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
        var result = scorer.score(transaction);
        var response = ScoreResponse.newBuilder()
                .setTransactionId(request.getTransaction().getTransactionId())
                .setRiskScore(result.riskScore())
                .setModelVersion("amount-rule-v1");
        switch (result) {
            case ALLOWED -> response.setDecision(Decision.DECISION_ALLOW);
            case AMOUNT_LIMIT_EXCEEDED -> response.setDecision(Decision.DECISION_BLOCK)
                    .addReasons(Reason.REASON_R6_AMOUNT_LIMIT);
        }
        response.setProcessingTimeUs(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startedAt));
        observer.onNext(response.build());
        observer.onCompleted();
    }
}
