package com.antifraud.mock.generation

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import kotlin.random.Random

object UserSeeds {
    fun derive(runSeed: Long, userId: String): Long {
        val id = UUID.nameUUIDFromBytes("$runSeed:$userId".toByteArray(UTF_8))
        return id.mostSignificantBits xor id.leastSignificantBits
    }

    // Создаём один экземпляр на пользователя внутри запуска и сохраняем его между событиями.
    fun random(runSeed: Long, userId: String): Random = Random(derive(runSeed, userId))
}
