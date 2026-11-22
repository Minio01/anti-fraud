package com.antifraud.mock.generation.pattern

import com.antifraud.mock.generation.UserProfile
import com.antifraud.proto.v1.Channel
import com.antifraud.proto.v1.Transaction
import com.google.protobuf.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

internal object FamiliarTransactionFactory {
    fun create(user: UserProfile, random: Random, at: Instant, amountMinor: Long): Transaction {
        require(amountMinor > 0)
        return Transaction.newBuilder()
            .setTransactionId(UUID(random.nextLong(), random.nextLong()).toString())
            .setUserId(user.userId)
            .setAmountMinor(amountMinor)
            .setCurrency("USD")
            .setTimestamp(Timestamp.newBuilder().setSeconds(at.epochSecond).setNanos(at.nano))
            .setMerchantId("merchant-${random.nextInt(1, 101)}")
            .setMerchantCategory("5411")
            .setDeviceId(user.deviceIds.random(random))
            .setIp(user.ips.random(random))
            .setCountry(user.home.country)
            .setLatitude(user.home.latitude)
            .setLongitude(user.home.longitude)
            .setChannel(Channel.CHANNEL_ONLINE)
            .build()
    }
}
