package org.autojs.autojs.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.ArrayDeque
import kotlin.math.ceil

internal object PhoneMcpPerformanceMetrics {
    private const val MAX_SAMPLES_PER_TOOL = 256
    private val samples = linkedMapOf<String, ArrayDeque<Sample>>()

    @Synchronized
    fun record(timing: PhoneMcpOperationTiming) {
        val values = samples.getOrPut(timing.tool) { ArrayDeque() }
        values.addLast(Sample(timing.durationMs, timing.status == "ok", timing.errorCode))
        while (values.size > MAX_SAMPLES_PER_TOOL) values.removeFirst()
    }

    @Synchronized
    fun snapshot(tool: String?, offset: Int, limit: Int): JsonObject {
        val entries = samples.entries
            .asSequence()
            .filter { tool == null || it.key == tool }
            .sortedBy { it.key }
            .toList()
        val items = JsonArray()
        entries.drop(offset).take(limit).forEach { (name, toolSamples) ->
            val durations = toolSamples.map(Sample::durationMs).sorted()
            val failures = toolSamples.count { !it.ok }
            items.add(JsonObject().apply {
                addProperty("tool", name)
                addProperty("count", toolSamples.size)
                addProperty("success_count", toolSamples.size - failures)
                addProperty("failure_count", failures)
                addProperty("p50_ms", percentile(durations, 0.50))
                addProperty("p90_ms", percentile(durations, 0.90))
                addProperty("p95_ms", percentile(durations, 0.95))
                addProperty("p99_ms", percentile(durations, 0.99))
                addProperty("max_ms", durations.lastOrNull() ?: 0.0)
                add("error_codes", JsonObject().apply {
                    toolSamples.mapNotNull(Sample::errorCode).groupingBy { it }.eachCount().forEach { (code, count) ->
                        addProperty(code, count)
                    }
                })
            })
        }
        val next = offset + items.size()
        return JsonObject().apply {
            addProperty("total", entries.size)
            addProperty("count", items.size())
            addProperty("offset", offset)
            add("items", items)
            addProperty("has_more", next < entries.size)
            if (next < entries.size) addProperty("next_cursor", next.toString())
            addProperty("sample_limit_per_tool", MAX_SAMPLES_PER_TOOL)
        }
    }

    @Synchronized
    internal fun clearForTests() = samples.clear()

    private fun percentile(values: List<Double>, percentile: Double): Double {
        if (values.isEmpty()) return 0.0
        return values[(ceil(values.size * percentile).toInt() - 1).coerceIn(values.indices)]
    }

    private data class Sample(val durationMs: Double, val ok: Boolean, val errorCode: String?)
}
