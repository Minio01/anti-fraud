package com.antifraud.mock.scenario

import com.antifraud.mock.generation.pattern.FraudPattern
import com.antifraud.mock.generation.pattern.PatternGenerator

class ScenarioCatalog(generators: List<PatternGenerator>) {
    private val generators = generators.associateBy { it.pattern }

    init {
        require(this.generators.keys == FraudPattern.entries.toSet())
        require(generators.size == this.generators.size)
    }

    fun generator(pattern: FraudPattern): PatternGenerator = generators.getValue(pattern)

    fun list(): List<ScenarioInfo> = listOf(
        ScenarioInfo(FraudPattern.VELOCITY_BURST, "Частые платежи одного пользователя", 20, 60_000),
        ScenarioInfo(FraudPattern.ACCOUNT_TAKEOVER, "Новое устройство/IP/страна; крупное продолжение необязательно", 2, 180_000),
        ScenarioInfo(FraudPattern.CARD_TESTING, "Мелкие платежи разным мерчантам, затем крупный", 16, 300_000),
        ScenarioInfo(FraudPattern.AMOUNT_ANOMALY, "Платёж в 10–50 раз выше среднего чека", 1, 0),
        ScenarioInfo(FraudPattern.IMPOSSIBLE_TRAVEL, "Платежи в далёких странах с интервалом в минуты", 2, 300_000),
    )
}
