package com.antifraud.profile.update

import com.google.protobuf.InvalidProtocolBufferException
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

@Configuration(proxyBeanMethods = false)
class ProfileKafkaConfiguration {
    @Bean
    fun profileKafkaErrorHandler() = DefaultErrorHandler(FixedBackOff(1_000, FixedBackOff.UNLIMITED_ATTEMPTS)).apply {
        addNotRetryableExceptions(InvalidProtocolBufferException::class.java, IllegalArgumentException::class.java)
    }
}
