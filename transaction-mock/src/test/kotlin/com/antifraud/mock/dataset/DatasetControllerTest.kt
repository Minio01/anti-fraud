package com.antifraud.mock.dataset

import com.antifraud.mock.api.MockExceptionHandler
import com.antifraud.mock.generation.LogNormalAmountSampler
import com.antifraud.mock.generation.NormalTransactionGenerator
import com.antifraud.mock.generation.pattern.AccountTakeoverGenerator
import com.antifraud.mock.generation.pattern.AmountAnomalyGenerator
import com.antifraud.mock.generation.pattern.CardTestingGenerator
import com.antifraud.mock.generation.pattern.ImpossibleTravelGenerator
import com.antifraud.mock.generation.pattern.VelocityBurstGenerator
import com.antifraud.mock.scenario.MockRuntimeProperties
import com.antifraud.mock.scenario.ScenarioCatalog
import com.antifraud.mock.scenario.UserDirectory
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DatasetControllerTest {
    private val amounts = LogNormalAmountSampler(0.75)
    private val catalog = ScenarioCatalog(listOf(
        VelocityBurstGenerator(), AccountTakeoverGenerator(0.2, amounts), CardTestingGenerator(),
        AmountAnomalyGenerator(), ImpossibleTravelGenerator(),
    ))
    private val service = DatasetService(UserDirectory(MockRuntimeProperties(userPoolSize = 20), amounts),
        NormalTransactionGenerator(amounts), catalog)
    private val client = WebTestClient.bindToController(DatasetController(service))
        .controllerAdvice(MockExceptionHandler()).build()
    private val csv = MediaType.parseMediaType("text/csv")

    @Test
    fun `exports exactly the requested rows and replays the same dataset by seed`() {
        val first = download(42, 0.2)
        assertEquals(first, download(42, 0.2))
        assertNotEquals(first, download(43, 0.2))
        val rows = first.trimEnd('\n').lines()
        assertEquals(1_001, rows.size)
        val header = rows.first().split(',')
        assertEquals(21, header.size)
        assertEquals(listOf("expected_fraud", "pattern"), header.takeLast(2))
        val data = rows.drop(1).map { it.split(',') }
        assertTrue(data.all { it.size == header.size })
        assertTrue(data.any { it[19] == "true" && it[20].isNotEmpty() })
        assertTrue(data.any { it[19] == "false" && it[20].isEmpty() })
        assertTrue(data.any { it[header.indexOf("user_tx_count")].toLong() > 0 })
    }

    @Test
    fun `zero fraud rate produces ordinary payments and invalid input returns 400`() {
        assertTrue(download(42, 0.0).lineSequence().drop(1).filter { it.isNotEmpty() }
            .all { it.endsWith(",false,") })
        listOf("{}", """{"size":999}""", """{"size":5000001}""", """{"size":1000,"fraudRate":0.6}""")
            .forEach { body ->
                client.post().uri("/api/v1/mock/datasets").contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body).exchange().expectStatus().isBadRequest
            }
    }

    private fun download(seed: Long, fraudRate: Double): String {
        val result = client.post().uri("/api/v1/mock/datasets").contentType(MediaType.APPLICATION_JSON)
            .accept(csv).bodyValue("""{"size":1000,"fraudRate":$fraudRate,"seed":$seed}""")
            .exchange().expectStatus().isOk.expectHeader().contentTypeCompatibleWith(csv)
            .expectHeader().valueEquals("X-Dataset-Seed", seed.toString())
            .expectBody(String::class.java).returnResult()
        return assertNotNull(result.responseBody)
    }
}
