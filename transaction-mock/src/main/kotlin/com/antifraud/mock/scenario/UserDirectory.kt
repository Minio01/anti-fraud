package com.antifraud.mock.scenario

import com.antifraud.mock.generation.LogNormalAmountSampler
import com.antifraud.mock.generation.UserPoolFactory
import com.antifraud.mock.generation.UserProfile
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.random.Random

class UserDirectory(properties: MockRuntimeProperties, amounts: LogNormalAmountSampler) {
    val users: List<UserProfile> = UserPoolFactory { random -> amounts.sample(properties.meanAmountMinor, random) }
        .create(properties.seed, properties.userPoolSize)
    private val byId = users.associateBy { it.userId }

    fun findOrChoose(userId: String?, random: Random): UserProfile = if (userId == null) users.random(random)
        else byId[userId] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Пользователь не найден")
}
