package com.antifraud.mock.scenario

import com.antifraud.mock.api.MockExceptionHandler
import com.antifraud.mock.generation.LogNormalAmountSampler
import com.antifraud.mock.generation.pattern.AccountTakeoverGenerator
import com.antifraud.mock.generation.pattern.AmountAnomalyGenerator
import com.antifraud.mock.generation.pattern.CardTestingGenerator
import com.antifraud.mock.generation.pattern.FraudPattern
import com.antifraud.mock.generation.pattern.ImpossibleTravelGenerator
import com.antifraud.mock.generation.pattern.VelocityBurstGenerator
import com.antifraud.mock.scoring.ScoringGateway
import com.antifraud.proto.v1.Decision
import com.antifraud.proto.v1.ScoreResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioControllerTest {
    @Test
    fun `get list of every type of FraudPattern with metadata`() = runTest {
        withController { client ->
            val response = client.get().uri("/api/v1/mock/scenarios")
                .exchange()
                .expectStatus().isOk
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()

            response.jsonPath("$.length()").isEqualTo(FraudPattern.entries.size)

            FraudPattern.entries.forEachIndexed { index, pattern ->
                response
                    .jsonPath("$[$index].pattern").isEqualTo(pattern.name)
                    .jsonPath("$[$index].description").value<String> { assertTrue(it.isNotBlank()) }
                    .jsonPath("$[$index].transactions").value<Number> { assertTrue(it.toInt() > 0) }
                    .jsonPath("$[$index].durationMs").value<Number> { assertTrue(it.toLong() >= 0) }
            }
            val anomalyIndex = FraudPattern.entries.indexOf(FraudPattern.AMOUNT_ANOMALY)
            response.jsonPath("$[$anomalyIndex].transactions").isEqualTo(1)
                .jsonPath("$[$anomalyIndex].durationMs").isEqualTo(0)
        }
    }

    @Test
    fun `post returns accepted and status reports the completed scoring result`() = runTest {
        withController { client ->
            var runId = ""
            client.post().uri("/api/v1/mock/scenarios/AMOUNT_ANOMALY/runs")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"userId":"user-1"}""")
                .exchange().expectStatus().isAccepted
                .expectBody().jsonPath("$.status").isEqualTo("RUNNING")
                .jsonPath("$.runId").value<String> { runId = it }
            runCurrent()
            client.get().uri("/api/v1/mock/scenarios/AMOUNT_ANOMALY/runs/$runId")
                .exchange().expectStatus().isOk
                .expectBody().jsonPath("$.status").isEqualTo("COMPLETED")
                .jsonPath("$.transactions[0].decision").isEqualTo("ALLOW")
                .jsonPath("$.transactions[0].expectedFraud").isEqualTo(true)
        }
    }

    @Test
    fun `optional request body and problem responses follow the rest contract`() = runTest {
        withController { client ->
            client.post().uri("/api/v1/mock/scenarios/AMOUNT_ANOMALY/runs")
                .exchange().expectStatus().isAccepted
            runCurrent()
            client.post().uri("/api/v1/mock/scenarios/AMOUNT_ANOMALY/runs")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"userId":"unknown"}""")
                .exchange().expectStatus().isNotFound
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.status").isEqualTo(404)
            client.get().uri("/api/v1/mock/scenarios/AMOUNT_ANOMALY/runs/${UUID.randomUUID()}")
                .exchange().expectStatus().isNotFound
            client.post().uri("/api/v1/mock/scenarios/UNKNOWN/runs")
                .exchange().expectStatus().isBadRequest
            client.post().uri("/api/v1/mock/scenarios/AMOUNT_ANOMALY/runs")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("""{"userId":""}""")
                .exchange().expectStatus().isBadRequest
        }
    }

    private inline fun TestScope.withController(action: (WebTestClient) -> Unit) {
        val amounts = LogNormalAmountSampler(0.75)
        val catalog = ScenarioCatalog(listOf(
            VelocityBurstGenerator(), AccountTakeoverGenerator(0.2, amounts), CardTestingGenerator(),
            AmountAnomalyGenerator(), ImpossibleTravelGenerator(),
        ))
        val properties = MockRuntimeProperties(userPoolSize = 10)
        val gateway = ScoringGateway { tx ->
            ScoreResponse.newBuilder().setTransactionId(tx.transactionId).setDecision(Decision.DECISION_ALLOW).build()
        }
        ScenarioService(
            catalog, UserDirectory(properties, amounts), gateway, properties,
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        ).use { service ->
            val client = WebTestClient.bindToController(ScenarioController(catalog, service))
                .controllerAdvice(MockExceptionHandler()).configureClient().responseTimeout(Duration.ofSeconds(5)).build()
            action(client)
        }
    }
}
