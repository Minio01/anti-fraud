package com.antifraud.mock.dataset

import com.antifraud.mock.generation.NormalTransactionGenerator
import com.antifraud.mock.scenario.ScenarioCatalog
import com.antifraud.mock.scenario.UserDirectory
import kotlinx.coroutines.reactor.asFlux
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.random.Random

data class DatasetRequest(val size: Int?, val fraudRate: Double? = 0.015, val seed: Long? = null)

@RestController
class DatasetController(private val datasets: DatasetService) {
    @PostMapping("/api/v1/mock/datasets", produces = ["text/csv;charset=UTF-8"])
    fun export(@RequestBody request: DatasetRequest, response: ServerHttpResponse): Mono<Void> {
        val size = request.size?.takeIf { it in 1_000..5_000_000 }
            ?: invalid("size должен быть от 1000 до 5000000")
        val fraudRate = request.fraudRate?.takeIf { it.isFinite() && it in 0.0..0.5 }
            ?: invalid("fraudRate должен быть от 0 до 0,5")
        val seed = request.seed ?: Random.nextLong()
        response.headers.set(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
        response.headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=transactions.csv")
        response.headers.set("X-Dataset-Seed", seed.toString())
        return response.writeWith(datasets.export(size, fraudRate, seed).asFlux()
            .map { response.bufferFactory().wrap(it.toByteArray(UTF_8)) })
    }

    private fun invalid(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}

@Configuration(proxyBeanMethods = false)
class DatasetConfiguration {
    @Bean
    fun datasetService(users: UserDirectory, normal: NormalTransactionGenerator, catalog: ScenarioCatalog) =
        DatasetService(users, normal, catalog)
}
