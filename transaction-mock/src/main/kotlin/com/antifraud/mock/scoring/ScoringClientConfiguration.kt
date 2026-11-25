package com.antifraud.mock.scoring

import com.antifraud.proto.v1.RiskScoringServiceGrpcKt.RiskScoringServiceCoroutineStub
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.grpc.client.GrpcChannelFactory

@Configuration(proxyBeanMethods = false)
class ScoringClientConfiguration {
    @Bean
    fun scoringGateway(channels: GrpcChannelFactory, meters: MeterRegistry): ScoringGateway =
        GrpcScoringClient(RiskScoringServiceCoroutineStub(channels.createChannel("scoring")), meters)
}
