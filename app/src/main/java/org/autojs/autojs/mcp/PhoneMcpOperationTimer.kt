package org.autojs.autojs.mcp

internal data class PhoneMcpOperationTiming(
    val tool: String,
    val status: String,
    val durationMs: Double,
    val errorCode: String? = null,
) {
    fun format(): String = buildString {
        append("[PhoneMCP] tool=")
        append(tool)
        append(" status=")
        append(status)
        errorCode?.let {
            append(" error_code=")
            append(it)
        }
        append(" duration_ms=")
        append("%.3f".format(java.util.Locale.US, durationMs))
    }
}

internal class PhoneMcpOperationTimer(
    private val clockNanos: () -> Long = System::nanoTime,
    private val sink: (String) -> Unit,
) {
    fun <T> measure(tool: String, operation: () -> T): T {
        val startedAt = clockNanos()
        try {
            return operation().also {
                emit(tool, "ok", startedAt)
            }
        } catch (error: Throwable) {
            emit(
                tool = tool,
                status = "error",
                startedAt = startedAt,
                errorCode = (error as? PhoneToolException)?.code ?: error.javaClass.simpleName,
            )
            throw error
        }
    }

    private fun emit(tool: String, status: String, startedAt: Long, errorCode: String? = null) {
        val elapsedNanos = (clockNanos() - startedAt).coerceAtLeast(0L)
        val timing = PhoneMcpOperationTiming(
            tool = tool,
            status = status,
            durationMs = elapsedNanos / NANOS_PER_MILLISECOND,
            errorCode = errorCode,
        )
        PhoneMcpPerformanceMetrics.record(timing)
        runCatching {
            sink(
                timing.format(),
            )
        }
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}
