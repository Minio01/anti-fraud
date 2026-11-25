package com.antifraud.mock

import com.antifraud.mock.scenario.ScenarioService
import com.antifraud.mock.scoring.ScoringGateway
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import kotlin.test.Test
import kotlin.test.assertNotNull

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["mock.runtime.user-pool-size=10"],
)
class MockApplicationContextTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `application creates the spring grpc client and scenario service`() {
        assertNotNull(context.getBean(ScoringGateway::class.java))
        assertNotNull(context.getBean(ScenarioService::class.java))
    }
}
