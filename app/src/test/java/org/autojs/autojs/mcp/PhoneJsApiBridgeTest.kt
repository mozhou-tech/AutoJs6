package org.autojs.autojs.mcp

import com.google.gson.JsonArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneJsApiBridgeTest {

    @Test
    fun buildsCallsForGlobalAndNamespacedApis() {
        val arguments = JsonArray().apply { add("hello"); add(42) }
        val script = PhoneJsApiBridge.buildScript("device.vibrate", arguments, "json")
        assertTrue(script.contains("device"))
        assertTrue(script.contains("vibrate"))
        assertTrue(script.contains("[\"hello\",42]"))
    }

    @Test
    fun publishesResultEnvelopeThroughExecutionArgumentSink() {
        val script = PhoneJsApiBridge.buildScript("base64.encode", JsonArray().apply { add("PhoneMCP") }, "json")

        assertTrue(script.contains("engines.myEngine().execArgv"))
        assertTrue(script.contains(PhoneJsApiBridge.RESULT_SINK_ARGUMENT))
        assertTrue(script.contains("resultSink.set(JSON.stringify(envelope))"))
        assertFalse(script.contains("return JSON.stringify"))
    }

    @Test(expected = PhoneToolException::class)
    fun rejectsPrototypeTraversal() {
        PhoneJsApiBridge.buildScript("device.constructor.constructor", JsonArray(), "json")
    }

    @Test(expected = PhoneToolException::class)
    fun rejectsSourceInjectionInApiPath() {
        PhoneJsApiBridge.buildScript("getClip);shell('id')", JsonArray(), "json")
    }
}
