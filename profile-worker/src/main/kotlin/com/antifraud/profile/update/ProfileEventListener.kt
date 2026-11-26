package com.antifraud.profile.update

import com.antifraud.features.RedisUserTag
import com.antifraud.proto.v1.Decision
import com.antifraud.proto.v1.ScoredTransaction
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ProfileEventListener(private val redis: ReactiveStringRedisTemplate) {
    private val update = RedisScript.of(ClassPathResource("redis/update-profile.lua"), Long::class.javaObjectType)

    @KafkaListener(topics = ["transactions-scored"])
    fun apply(payload: ByteArray) {
        val event = ScoredTransaction.parseFrom(payload)
        require(event.hasTransaction() && event.hasResult()) { "В событии нет транзакции или вердикта" }
        when (event.result.decision) {
            Decision.DECISION_BLOCK -> return
            Decision.DECISION_ALLOW, Decision.DECISION_FLAG -> Unit
            else -> throw IllegalArgumentException("Неизвестный вердикт скоринга")
        }

        val transaction = event.transaction
        val transactionId = UUID.fromString(transaction.transactionId)
        require(transaction.userId.isNotEmpty() && transaction.amountMinor > 0 &&
            transaction.deviceId.isNotEmpty() && transaction.ip.isNotEmpty() && transaction.country.isNotEmpty()
        ) { "Неполные данные профиля" }
        val tag = RedisUserTag.of(transaction.userId)
        val keys = listOf("prof:$tag", "dev:$tag", "ip:$tag", "cty:$tag",
            "dev-recent:$tag", "ip-recent:$tag", "cty-recent:$tag", "profile-seen:$tag:$transactionId")
        val args = listOf(transaction.amountMinor.toString(), transaction.deviceId, transaction.ip, transaction.country)

        // Поток Kafka ждёт Redis до подтверждения offset и сохраняет порядок событий в партиции.
        redis.execute(update, keys, args).single().block()
    }
}
