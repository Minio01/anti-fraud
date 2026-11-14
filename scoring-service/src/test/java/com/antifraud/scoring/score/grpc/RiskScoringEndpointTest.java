package com.antifraud.scoring.score.grpc;

import com.antifraud.proto.v1.Channel;
import com.antifraud.proto.v1.Decision;
import com.antifraud.proto.v1.Reason;
import com.antifraud.proto.v1.ScoreRequest;
import com.antifraud.proto.v1.ScoreResponse;
import com.antifraud.proto.v1.Transaction;
import com.antifraud.scoring.score.application.AmountScorer;
import com.antifraud.scoring.score.application.AmountScoringProperties;
import com.google.protobuf.Timestamp;
import com.google.rpc.BadRequest;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class RiskScoringEndpointTest {
    private static final long THRESHOLD_MINOR = 1000;
    private final RiskScoringEndpoint endpoint = new RiskScoringEndpoint(
            new AmountScorer(new AmountScoringProperties(THRESHOLD_MINOR)));

    @Test
    void sendsBadRequestDetailsWhenTransactionIsMissing() throws Exception {
        StreamObserver<ScoreResponse> observer = mock();
        ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);

        endpoint.score(ScoreRequest.getDefaultInstance(), observer);

        verify(observer).onError(failure.capture());
        verifyNoMoreInteractions(observer);
        assertThat(Status.fromThrowable(failure.getValue()).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        var status = StatusProto.fromThrowable(failure.getValue());
        assertThat(status.getDetailsList()).hasSize(1);
        var details = status.getDetails(0).unpack(BadRequest.class);
        assertThat(details.getFieldViolationsList())
                .extracting(BadRequest.FieldViolation::getField)
                .containsExactly("transaction");
    }

    @ParameterizedTest
    @CsvSource({
            "999, USD, DECISION_ALLOW, 0.0",
            "1000, USD, DECISION_BLOCK, 1.0",
            "1001, USD, DECISION_BLOCK, 1.0",
            "1000, EUR, DECISION_ALLOW, 0.0"
    })
    void scoresUsingTheUsdAmountThreshold(long amountMinor, String currency, Decision decision, double riskScore) {
        var transaction = Transaction.newBuilder()
                .setTransactionId("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
                .setUserId("user-1")
                .setAmountMinor(amountMinor)
                .setCurrency(currency)
                .setTimestamp(Timestamp.getDefaultInstance())
                .setMerchantId("merchant-1")
                .setMerchantCategory("5411")
                .setDeviceId("device-1")
                .setIp("192.0.2.1")
                .setCountry("US")
                .setChannel(Channel.CHANNEL_ONLINE)
                .build();
        StreamObserver<ScoreResponse> observer = mock();
        var sent = ArgumentCaptor.forClass(ScoreResponse.class);

        endpoint.score(ScoreRequest.newBuilder().setTransaction(transaction).build(), observer);

        verify(observer).onNext(sent.capture());
        verify(observer).onCompleted();
        verifyNoMoreInteractions(observer);
        var response = sent.getValue();
        assertThat(response.getTransactionId()).isEqualTo(transaction.getTransactionId());
        assertThat(response.getDecision()).isEqualTo(decision);
        assertThat(response.getRiskScore()).isEqualTo(riskScore);
        assertThat(response.getModelVersion()).isEqualTo("amount-rule-v1");
        assertThat(response.getProcessingTimeUs()).isNotNegative();
        assertThat(response.getDegraded()).isFalse();
        assertThat(response.getReplayed()).isFalse();
        if (decision == Decision.DECISION_BLOCK) {
            assertThat(response.getReasonsList()).containsExactly(Reason.REASON_R6_AMOUNT_LIMIT);
        } else {
            assertThat(response.getReasonsList()).isEmpty();
        }
    }
}
