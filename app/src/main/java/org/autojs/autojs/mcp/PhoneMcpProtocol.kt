package org.autojs.autojs.mcp

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.autojs.autojs6.BuildConfig
import phonetailnet.Handler

class PhoneMcpProtocol(private val executor: PhoneToolDispatcher) : Handler {

    private val gson = Gson()

    override fun handle(requestJSON: String, clientJSON: String): String {
        val peer = runCatching { JsonParser.parseString(clientJSON).asJsonObject }.getOrElse { JsonObject() }
        val input = try {
            JsonParser.parseString(requestJSON)
        } catch (_: Throwable) {
            return gson.toJson(error(JsonNull.INSTANCE, -32700, "Parse error"))
        }
        return if (input.isJsonArray) {
            if (input.asJsonArray.isEmpty) {
                return gson.toJson(error(JsonNull.INSTANCE, -32600, "Invalid Request: an empty batch is not allowed"))
            }
            val responses = JsonArray()
            input.asJsonArray.forEach { element ->
                process(element, peer)?.let(responses::add)
            }
            if (responses.isEmpty) "" else gson.toJson(responses)
        } else {
            process(input, peer)?.let(gson::toJson).orEmpty()
        }
    }

    private fun process(element: JsonElement, peer: JsonObject): JsonObject? {
        if (!element.isJsonObject) return error(JsonNull.INSTANCE, -32600, "Invalid Request")
        val request = element.asJsonObject
        val jsonrpc = request.get("jsonrpc")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        val methodElement = request.get("method")
        if (jsonrpc != "2.0" || methodElement == null || !methodElement.isJsonPrimitive || !methodElement.asJsonPrimitive.isString) {
            return error(request.get("id") ?: JsonNull.INSTANCE, -32600, "Invalid Request")
        }
        val id = request.get("id")
        val notification = id == null || id.isJsonNull
        val method = methodElement.asString
        if (notification) {
            if (method == "notifications/initialized" || method.startsWith("notifications/")) return null
            return null
        }
        return try {
            val result = when (method) {
                "initialize" -> initialize(objectParams(request))
                "ping" -> JsonObject()
                "tools/list" -> listTools()
                "tools/call" -> callTool(objectParams(request), peer)
                else -> return error(id, -32601, "Method not found: $method")
            }
            success(id, result)
        } catch (error: ProtocolInvalidParamsException) {
            error(id, -32602, error.message ?: "Invalid params")
        } catch (error: PhoneToolException) {
            if (method == "tools/call" && error.code != "TOOL_NOT_FOUND") {
                success(id, toolError(error))
            } else {
                error(id, -32602, error.message, JsonObject().apply { addProperty("code", error.code) })
            }
        } catch (error: Throwable) {
            Log.e(TAG, "MCP method failed: $method", error)
            error(id, -32603, "Internal error")
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = params.get("protocolVersion")?.asString
        val selected = if (requested in SupportedVersions) requested!! else CurrentVersion
        return JsonObject().apply {
            addProperty("protocolVersion", selected)
            add("capabilities", JsonObject().apply {
                add("tools", JsonObject().apply { addProperty("listChanged", false) })
            })
            add("serverInfo", JsonObject().apply {
                addProperty("name", "autojs6-phone-mcp")
                addProperty("version", BuildConfig.VERSION_NAME)
                addProperty("description", "Control this Android device through accessibility, OCR, apps, notifications and files.")
            })
            addProperty(
                "instructions",
                "Call phone_get_capabilities and phone_get_state first. Use phone_capture_context when visual reasoning needs a synchronized image, accessibility tree and OCR. Acquire a lease with phone_session_control before every write workflow. Prefer phone_ui_snapshot and phone_ui_action over coordinates; use phone_ocr_read and phone_gesture only as fallbacks.",
            )
        }
    }

    private fun listTools() = JsonObject().apply {
        add("tools", JsonArray().apply { PhoneToolSpecs.all.forEach { add(it.toJson()) } })
    }

    private fun callTool(params: JsonObject, peer: JsonObject): JsonObject {
        val nameElement = params.get("name")
        if (nameElement == null || !nameElement.isJsonPrimitive || !nameElement.asJsonPrimitive.isString) {
            throw PhoneToolException("INVALID_ARGUMENT", "tools/call requires a string tool name", false)
        }
        val name = nameElement.asString
        val spec = PhoneToolSpecs.all.find { it.name == name }
            ?: throw PhoneToolException("TOOL_NOT_FOUND", "Unknown phone tool: $name", false)
        val arguments = params.get("arguments")
        if (arguments != null && !arguments.isJsonObject) {
            throw PhoneToolException("INVALID_ARGUMENT", "tools/call arguments must be an object", false)
        }
        val args = arguments?.asJsonObject ?: JsonObject()
        PhoneToolArgumentValidator.validate(spec.inputSchema, args)
        val execution = executor.execute(name, args, peer)
        return JsonObject().apply {
            add("content", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", execution.textSummary ?: gson.toJson(execution.data))
                })
                execution.additionalContent.forEach(::add)
            })
            add("structuredContent", execution.data)
            addProperty("isError", false)
        }
    }

    private fun toolError(error: PhoneToolException) = JsonObject().apply {
        val structured = JsonObject().apply {
            addProperty("ok", false)
            add("error", JsonObject().apply {
                addProperty("code", error.code)
                addProperty("message", error.message)
                addProperty("recoverable", error.recoverable)
            })
        }
        add("content", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", "${error.code}: ${error.message}")
            })
        })
        add("structuredContent", structured)
        addProperty("isError", true)
    }

    private fun objectParams(request: JsonObject): JsonObject {
        val params = request.get("params") ?: return JsonObject()
        if (!params.isJsonObject) throw ProtocolInvalidParamsException("params must be an object")
        return params.asJsonObject
    }

    private fun success(id: JsonElement, result: JsonObject) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String, data: JsonObject? = null) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
            data?.let { add("data", it) }
        })
    }

    companion object {
        private const val TAG = "PhoneMcpProtocol"
        const val CurrentVersion = "2025-11-25"
        val SupportedVersions = setOf(CurrentVersion, "2025-06-18")
    }
}

private class ProtocolInvalidParamsException(message: String) : Exception(message)
