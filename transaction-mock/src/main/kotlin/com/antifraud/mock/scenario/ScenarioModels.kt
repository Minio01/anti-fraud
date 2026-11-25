package com.antifraud.mock.scenario

import com.antifraud.mock.generation.pattern.FraudPattern
import com.fasterxml.jackson.annotation.JsonInclude
import java.util.UUID

data class ScenarioInfo(
    val pattern: FraudPattern,
    val description: String,
    val transactions: Int,
    val durationMs: Long,
)

data class ScenarioRunRequest(val userId: String? = null)

enum class RunStatus { RUNNING, COMPLETED, FAILED }

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ScenarioTransactionResult(
    val transactionId: String,
    val expectedFraud: Boolean,
    val decision: String? = null,
    val riskScore: Double? = null,
    val reasons: List<String> = emptyList(),
)

data class ScenarioRun(
    val runId: UUID,
    val pattern: FraudPattern,
    val userId: String,
    val status: RunStatus,
    val transactions: List<ScenarioTransactionResult>,
)
