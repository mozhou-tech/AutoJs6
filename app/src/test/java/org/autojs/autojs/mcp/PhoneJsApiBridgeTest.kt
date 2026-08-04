package org.autojs.autojs.mcp

import com.google.gson.JsonArray
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

    @Test(expected = PhoneToolException::class)
    fun rejectsPrototypeTraversal() {
        PhoneJsApiBridge.buildScript("device.constructor.constructor", JsonArray(), "json")
    }

    @Test(expected = PhoneToolException::class)
    fun rejectsSourceInjectionInApiPath() {
        PhoneJsApiBridge.buildScript("getClip);shell('id')", JsonArray(), "json")
    }
}
