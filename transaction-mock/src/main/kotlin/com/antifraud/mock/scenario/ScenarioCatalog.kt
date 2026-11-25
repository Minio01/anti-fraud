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

    fun list(): List<ScenarioInfo> = FraudPattern.entries.map { pattern ->
        val metadata = generator(pattern).metadata
        ScenarioInfo(pattern, metadata.description, metadata.maxTransactions, metadata.maxDuration.toMillis())
    }
}
