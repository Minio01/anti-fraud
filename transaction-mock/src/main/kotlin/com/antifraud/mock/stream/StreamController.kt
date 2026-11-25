package com.antifraud.mock.stream

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/v1/mock/stream")
class StreamController(private val stream: StreamService) {
    @GetMapping
    suspend fun status(): StreamStatus = stream.status()

    @PutMapping
    suspend fun configure(@RequestBody request: StreamRequest): StreamStatus {
        val rps = request.rps?.takeIf { it in 1..20_000 } ?: invalid("rps должен быть от 1 до 20 000")
        val fraudRate = request.fraudRate?.takeIf { it.isFinite() && it in 0.0..0.5 }
            ?: invalid("fraudRate должен быть от 0 до 0,5")
        val patterns = request.patterns.map { it ?: invalid("patterns не должен содержать null") }.distinct()
        return stream.configure(StreamConfig(rps, fraudRate, patterns))
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun stop() = stream.stop()

    private fun invalid(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
