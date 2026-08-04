package org.autojs.autojs.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class PhoneMcpOperationTimerTest {

    @Test
    fun successfulOperationLogsToolStatusAndElapsedTime() {
        val logs = mutableListOf<String>()
        val timer = timer(10_000_000L, 22_345_678L, logs = logs)

        assertEquals("done", timer.measure("phone_ui_action") { "done" })
        assertEquals(
            listOf("[PhoneMCP] tool=phone_ui_action status=ok duration_ms=12.346"),
            logs,
        )
    }

    @Test
    fun phoneToolFailureLogsErrorCodeAndRethrowsOriginalException() {
        val logs = mutableListOf<String>()
        val timer = timer(2_000_000L, 5_500_000L, logs = logs)
        val expected = PhoneToolException("ACTION_FAILED", "rejected")

        val actual = try {
            timer.measure("phone_gesture") { throw expected }
            fail("Expected PhoneToolException")
            null
        } catch (error: PhoneToolException) {
            error
        }

        assertSame(expected, actual)
        assertEquals(
            listOf("[PhoneMCP] tool=phone_gesture status=error error_code=ACTION_FAILED duration_ms=3.500"),
            logs,
        )
    }

    @Test
    fun unexpectedFailureLogsExceptionTypeWithoutItsMessage() {
        val logs = mutableListOf<String>()
        val timer = timer(0L, 1_000_000L, logs = logs)

        try {
            timer.measure("phone_get_state") { error("sensitive detail") }
            fail("Expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // Expected.
        }

        assertEquals(
            listOf("[PhoneMCP] tool=phone_get_state status=error error_code=IllegalStateException duration_ms=1.000"),
            logs,
        )
    }

    @Test
    fun loggingFailureDoesNotChangeToolResult() {
        val times = longArrayOf(0L, 1_000_000L).iterator()
        val timer = PhoneMcpOperationTimer(
            clockNanos = { times.nextLong() },
            sink = { error("logger unavailable") },
        )

        assertEquals("done", timer.measure("phone_get_state") { "done" })
    }

    private fun timer(vararg times: Long, logs: MutableList<String>): PhoneMcpOperationTimer {
        val iterator = times.iterator()
        return PhoneMcpOperationTimer(clockNanos = { iterator.nextLong() }, sink = logs::add)
    }
}
