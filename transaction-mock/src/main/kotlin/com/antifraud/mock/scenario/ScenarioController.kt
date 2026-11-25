package com.antifraud.mock.scenario

import com.antifraud.mock.generation.pattern.FraudPattern
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/mock/scenarios")
class ScenarioController(private val catalog: ScenarioCatalog, private val scenarios: ScenarioService) {
    @GetMapping()
    fun listScenarios(): List<ScenarioInfo> = catalog.list()

    @PostMapping("/{pattern}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(
        @PathVariable pattern: FraudPattern,
        @RequestBody(required = false) request: ScenarioRunRequest?,
    ): ScenarioRun = scenarios.start(pattern, request ?: ScenarioRunRequest())

    @GetMapping("/{pattern}/runs/{runId}")
    fun status(@PathVariable pattern: FraudPattern, @PathVariable runId: UUID): ScenarioRun = scenarios.get(pattern, runId)
}
