package com.antifraud.mock.stream

import com.antifraud.mock.generation.NormalTransactionGenerator
import com.antifraud.mock.scenario.MockRuntimeProperties
import com.antifraud.mock.scenario.ScenarioCatalog
import com.antifraud.mock.scenario.UserDirectory
import com.antifraud.mock.labels.TransactionLabels
import com.antifraud.mock.scoring.ScoringGateway
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class StreamConfiguration {
    @Bean(destroyMethod = "close")
    fun streamService(
        users: UserDirectory,
        normal: NormalTransactionGenerator,
        catalog: ScenarioCatalog,
        scoring: ScoringGateway,
        meters: MeterRegistry,
        properties: MockRuntimeProperties,
        labels: TransactionLabels,
    ): StreamService = StreamService(users, normal, catalog, scoring, meters, properties.maxStreamInFlight, labels = labels)
}
