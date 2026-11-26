package com.antifraud.mock.scenario

import com.antifraud.mock.generation.LogNormalAmountSampler
import com.antifraud.mock.generation.pattern.PatternGenerator
import com.antifraud.mock.labels.TransactionLabels
import com.antifraud.mock.scoring.ScoringGateway
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MockRuntimeProperties::class)
class ScenarioConfiguration {
    @Bean
    fun scenarioCatalog(generators: List<PatternGenerator>) = ScenarioCatalog(generators)

    @Bean
    fun userDirectory(properties: MockRuntimeProperties, amounts: LogNormalAmountSampler) = UserDirectory(properties, amounts)

    @Bean(destroyMethod = "close")
    fun scenarioService(
        catalog: ScenarioCatalog,
        users: UserDirectory,
        scoring: ScoringGateway,
        properties: MockRuntimeProperties,
        labels: TransactionLabels,
    ) = ScenarioService(catalog, users, scoring, properties, labels = labels)
}
