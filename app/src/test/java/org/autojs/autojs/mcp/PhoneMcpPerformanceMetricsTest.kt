package org.autojs.autojs.mcp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PhoneMcpPerformanceMetricsTest {

    @Before
    fun setUp() = PhoneMcpPerformanceMetrics.clearForTests()

    @After
    fun tearDown() = PhoneMcpPerformanceMetrics.clearForTests()

    @Test
    fun aggregatesLatencyPercentilesAndStableErrorCodes() {
        listOf(1.0, 2.0, 10.0).forEach { duration ->
            PhoneMcpPerformanceMetrics.record(PhoneMcpOperationTiming("phone_ui_action", "ok", duration))
        }
        PhoneMcpPerformanceMetrics.record(
            PhoneMcpOperationTiming("phone_ui_action", "error", 20.0, "ACTION_FAILED"),
        )

        val item = PhoneMcpPerformanceMetrics.snapshot("phone_ui_action", 0, 20)
            .getAsJsonArray("items")[0].asJsonObject
        assertEquals(4, item.get("count").asInt)
        assertEquals(3, item.get("success_count").asInt)
        assertEquals(1, item.get("failure_count").asInt)
        assertEquals(2.0, item.get("p50_ms").asDouble, 0.0)
        assertEquals(20.0, item.get("p95_ms").asDouble, 0.0)
        assertEquals(1, item.getAsJsonObject("error_codes").get("ACTION_FAILED").asInt)
    }
}
