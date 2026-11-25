package com.antifraud.mock.generation

import com.antifraud.mock.generation.pattern.AccountTakeoverGenerator
import com.antifraud.mock.generation.pattern.AmountAnomalyGenerator
import com.antifraud.mock.generation.pattern.CardTestingGenerator
import com.antifraud.mock.generation.pattern.ImpossibleTravelGenerator
import com.antifraud.mock.generation.pattern.VelocityBurstGenerator
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GenerationProperties::class)
class GenerationConfiguration {
    @Bean
    fun logNormalAmountSampler(properties: GenerationProperties) = LogNormalAmountSampler(properties.sigma)

    @Bean
    fun normalTransactionGenerator(amounts: LogNormalAmountSampler) = NormalTransactionGenerator(amounts)

    @Bean
    fun accountTakeoverGenerator(properties: GenerationProperties, amounts: LogNormalAmountSampler) =
        AccountTakeoverGenerator(properties.accountTakeoverLargePaymentProbability, amounts)

    @Bean
    fun amountAnomalyGenerator() = AmountAnomalyGenerator()

    @Bean
    fun cardTestingGenerator() = CardTestingGenerator()

    @Bean
    fun impossibleTravelGenerator() = ImpossibleTravelGenerator()

    @Bean
    fun velocityBurstGenerator() = VelocityBurstGenerator()
}
