package org.autojs.autojs.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMcpProtocolTest {

    private val peer = """{"transport":"loopback","source_address":"127.0.0.1"}"""

    @Test
    fun initializeNegotiatesCurrentVersion() {
        val response = protocol().request(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"unsupported"}}""",
        )
        assertEquals("2025-11-25", response.result().get("protocolVersion").asString)
        assertEquals("autojs6-phone-mcp", response.result().getAsJsonObject("serverInfo").get("name").asString)
    }

    @Test
    fun toolsListHasStableSchemasAndAnnotations() {
        val tools = protocol().request("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
            .result().getAsJsonArray("tools")
        assertEquals(31, tools.size())
        tools.forEach { element ->
            val tool = element.asJsonObject
            assertTrue(tool.get("name").asString.startsWith("phone_"))
            assertEquals("object", tool.getAsJsonObject("inputSchema").get("type").asString)
            assertTrue(tool.has("annotations"))
        }
        val captureContext = tools.map { it.asJsonObject }.single { it.get("name").asString == "phone_capture_context" }
        val properties = captureContext.getAsJsonObject("inputSchema").getAsJsonObject("properties")
        assertTrue(properties.has("max_width"))
        assertTrue(properties.has("max_nodes"))
        assertTrue(properties.has("min_confidence"))
        assertTrue(captureContext.getAsJsonObject("annotations").get("readOnlyHint").asBoolean)
        val jsApi = tools.map { it.asJsonObject }.single { it.get("name").asString == "phone_call_js_api" }
        assertTrue(jsApi.getAsJsonObject("annotations").get("destructiveHint").asBoolean)
        assertTrue(jsApi.getAsJsonObject("annotations").get("openWorldHint").asBoolean)
    }

    @Test
    fun emptyBatchAndNonObjectParamsAreProtocolErrors() {
        val empty = JsonParser.parseString(protocol().handle("[]", peer)).asJsonObject
        assertEquals(-32600, empty.getAsJsonObject("error").get("code").asInt)

        val params = protocol().request("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":[]}""")
        assertEquals(-32602, params.getAsJsonObject("error").get("code").asInt)
    }

    @Test
    fun toolArgumentsAreValidatedBeforeDispatch() {
        var dispatched = false
        val server = protocol { _, _, _ ->
            dispatched = true
            PhoneToolExecution(JsonObject())
        }
        val unknown = server.request(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"phone_get_state","arguments":{"extra":true}}}""",
        )
        assertTrue(unknown.result().get("isError").asBoolean)
        assertEquals("INVALID_ARGUMENT", unknown.result().getAsJsonObject("structuredContent").getAsJsonObject("error").get("code").asString)
        assertFalse(dispatched)

        val wrongType = server.request(
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"phone_list_apps","arguments":{"limit":"many"}}}""",
        )
        assertTrue(wrongType.result().get("isError").asBoolean)
        assertFalse(dispatched)
    }

    @Test
    fun successfulToolCallReturnsTextAndStructuredContent() {
        val server = protocol { name, _, _ ->
            PhoneToolExecution(JsonObject().apply { addProperty("tool", name) })
        }
        val response = server.request(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"phone_get_state","arguments":{}}}""",
        ).result()
        assertFalse(response.get("isError").asBoolean)
        assertEquals("phone_get_state", response.getAsJsonObject("structuredContent").get("tool").asString)
        assertEquals("text", response.getAsJsonArray("content")[0].asJsonObject.get("type").asString)
    }

    private fun protocol(dispatcher: PhoneToolDispatcher = PhoneToolDispatcher { _, _, _ -> PhoneToolExecution(JsonObject()) }) =
        PhoneMcpProtocol(dispatcher)

    private fun PhoneMcpProtocol.request(json: String): JsonObject =
        JsonParser.parseString(handle(json, peer)).asJsonObject

    private fun JsonObject.result(): JsonObject = getAsJsonObject("result")
}
