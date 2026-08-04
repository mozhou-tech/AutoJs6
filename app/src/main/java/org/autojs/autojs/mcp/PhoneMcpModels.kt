package org.autojs.autojs.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject

data class PhoneToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
    val destructive: Boolean = false,
    val idempotent: Boolean = false,
    val openWorld: Boolean = false,
) {
    fun toJson() = JsonObject().apply {
        addProperty("name", name)
        addProperty("description", description)
        add("inputSchema", inputSchema)
        add("annotations", JsonObject().apply {
            addProperty("readOnlyHint", readOnly)
            addProperty("destructiveHint", destructive)
            addProperty("idempotentHint", idempotent)
            addProperty("openWorldHint", openWorld)
        })
    }
}

data class PhoneToolExecution(
    val data: JsonObject,
    val additionalContent: JsonArray = JsonArray(),
    val textSummary: String? = null,
)

fun interface PhoneToolDispatcher {
    fun execute(name: String, args: JsonObject, peer: JsonObject): PhoneToolExecution
}

class PhoneToolException(
    val code: String,
    override val message: String,
    val recoverable: Boolean = true,
) : Exception(message)

object PhoneToolSpecs {

    private val lease = "lease_id" to string("Control lease returned by phone_session_control")

    val all: List<PhoneToolSpec> = listOf(
        read("phone_get_capabilities", "Get Android, display, permission, accessibility, OCR and transport capabilities."),
        read("phone_get_state", "Get screen, foreground window, battery, memory, brightness, audio, network and control-session state."),
        read("phone_get_transport_status", "Get secret-free embedded Tailscale/Headscale transport status."),
        read("phone_get_permissions", "Get Android runtime and special permission status."),
        read(
            "phone_list_js_apis",
            "List verified JSON-callable PhoneMCP atomic JavaScript APIs and their preferred native MCP tools.",
            paginationSchema(
                "category" to enum(*PhoneJsApiCatalog.categories.toTypedArray()),
                "query" to string("Case-insensitive API path filter"),
            ),
        ),
        PhoneToolSpec(
            "phone_session_control",
            "Acquire, renew or release the exclusive phone write-control lease.",
            schema(
                "action" to enum("acquire", "renew", "release"),
                "lease_id" to string("Lease ID for renew or release"),
                "ttl_seconds" to integer("Requested lease duration", 30, 900),
                required = arrayOf("action"),
            ),
            readOnly = false,
            idempotent = false,
        ),
        read(
            "phone_capture_screen",
            "Capture the current display through the accessibility service and return an MCP image.",
            schema(
                "format" to enum("png", "jpeg", "webp"),
                "quality" to integer("Image quality for lossy formats", 1, 100),
                "max_width" to integer("Optional maximum output width", 64, 4096),
            ),
        ),
        read(
            "phone_capture_context",
            "Capture one VLM-ready context containing an MCP image, screen metadata, accessibility nodes and OCR results.",
            schema(
                "format" to enum("png", "jpeg", "webp"),
                "quality" to integer("Image quality for lossy formats", 1, 100),
                "max_width" to integer("Optional maximum image width; coordinates remain in physical screen space", 64, 4096),
                "max_nodes" to integer("Maximum accessibility nodes returned", 1, 2000),
                "visible_only" to bool("Only include nodes visible to the user"),
                "min_confidence" to number("Minimum OCR recognition confidence", 0.0, 1.0),
            ),
        ),
        read(
            "phone_ui_snapshot",
            "Capture the active accessibility node tree and return a snapshot_id with stable temporary node IDs.",
            schema(
                "max_nodes" to integer("Maximum nodes returned", 1, 2000),
                "visible_only" to bool("Only include nodes visible to the user"),
                "semantic_only" to bool("Only include nodes with semantics or actionable state"),
            ),
        ),
        read(
            "phone_ui_find",
            "Find accessibility nodes by node ID, text, resource ID, description, class and state.",
            selectorSchema(),
        ),
        read(
            "phone_ocr_read",
            "Run embedded PP-OCRv6 on the current screen and return text, confidence and bounds.",
            schema(
                "min_confidence" to number("Minimum recognition confidence", 0.0, 1.0),
                "text" to string("Optional substring filter"),
            ),
        ),
        read(
            "phone_wait_for",
            "Wait for a UI selector, foreground package or screen state without client polling.",
            schema(
                "condition" to enum("node_exists", "node_gone", "package", "package_installed", "package_removed", "screen_on", "screen_off"),
                "timeout_ms" to integer("Maximum wait time", 100, 60000),
                "text" to string(),
                "resource_id" to string(),
                "package_name" to string(),
                required = arrayOf("condition"),
            ),
        ),
        write(
            "phone_ui_action",
            "Perform a semantic accessibility action on exactly one node.",
            schema(
                lease,
                "action" to enum("click", "long_click", "set_text", "clear_text", "focus", "scroll_forward", "scroll_backward", "select", "copy", "paste"),
                "snapshot_id" to string(),
                "node_id" to string(),
                "text" to string(),
                "resource_id" to string(),
                "content_description" to string(),
                "class_name" to string(),
                "ancestor_text" to string(),
                "ancestor_resource_id" to string(),
                "descendant_text" to string(),
                "descendant_resource_id" to string(),
                "value" to string("Text used by set_text"),
                "expect_package_name" to string("Expected foreground package after the action"),
                "expect_text" to string("Text expected to appear after the action"),
                "expect_text_gone" to string("Text expected to disappear after the action"),
                "verification_timeout_ms" to integer("Post-action verification timeout", 100, 60000),
                required = arrayOf("lease_id", "action"),
            ),
        ),
        write(
            "phone_gesture",
            "Perform a coordinate click, long press, swipe or drag gesture.",
            schema(
                lease,
                "action" to enum("click", "long_press", "swipe", "drag"),
                "x" to integer(), "y" to integer(),
                "end_x" to integer(), "end_y" to integer(),
                "duration_ms" to integer("Gesture duration", 1, 60000),
                "expect_package_name" to string("Expected foreground package after the gesture"),
                "expect_text" to string("Text expected to appear after the gesture"),
                "expect_text_gone" to string("Text expected to disappear after the gesture"),
                "verification_timeout_ms" to integer("Post-gesture verification timeout", 100, 60000),
                required = arrayOf("lease_id", "action", "x", "y"),
            ),
        ),
        write(
            "phone_global_action",
            "Perform an Android global action such as back, home, recents or opening notifications.",
            schema(
                lease,
                "action" to enum(
                    "back", "home", "recents", "notifications", "quick_settings", "power_dialog", "lock_screen",
                    "split_screen", "take_screenshot", "headset_hook", "accessibility_button",
                    "accessibility_button_chooser", "accessibility_shortcut", "accessibility_all_apps",
                    "dismiss_notification_shade",
                ),
                required = arrayOf("lease_id", "action"),
            ),
        ),
        write(
            "phone_vibrate",
            "Vibrate once, play a timing pattern, or cancel active vibration.",
            schema(
                lease,
                "action" to enum("once", "pattern", "cancel"),
                "duration_ms" to integer("One-shot duration", 1, 60000),
                "timings_ms" to array(integer("Alternating off/on durations", 0, 60000)),
                "repeat_index" to integer("Pattern repeat index; -1 disables repetition", -1, 99),
                required = arrayOf("lease_id", "action"),
            ),
        ),
        write(
            "phone_device_control",
            "Wake or keep the display awake, release the MCP wake lock, or change screen brightness.",
            schema(
                lease,
                "action" to enum("wake_screen", "keep_screen_on", "keep_screen_dim", "cancel_keep_awake", "set_brightness", "set_brightness_mode"),
                "timeout_ms" to integer("Wake-lock timeout", 100, 3600000),
                "brightness" to integer("Screen brightness", 0, 255),
                "brightness_mode" to enum("manual", "automatic"),
                required = arrayOf("lease_id", "action"),
            ),
        ),
        write(
            "phone_audio_control",
            "Set or adjust an Android audio stream volume, including mute and unmute.",
            schema(
                lease,
                "action" to enum("set", "adjust_up", "adjust_down", "mute", "unmute"),
                "stream" to enum("music", "notification", "alarm", "ring", "system", "voice_call"),
                "level" to integer("Absolute volume for set", 0, 100),
                "show_ui" to bool("Show Android's volume overlay"),
                required = arrayOf("lease_id", "action", "stream"),
            ),
        ),
        write(
            "phone_toast",
            "Show a short Android toast or dismiss the toast last shown through MCP.",
            schema(
                lease,
                "action" to enum("show", "dismiss"),
                "text" to string("Toast text"),
                "duration" to enum("short", "long"),
                required = arrayOf("lease_id", "action"),
            ),
        ),
        write(
            "phone_input_text",
            "Replace or append text in the currently focused editable accessibility node.",
            schema(lease, "text" to string(), "mode" to enum("replace", "append"), required = arrayOf("lease_id", "text")),
        ),
        write(
            "phone_action_sequence",
            "Execute up to 50 phone tool calls sequentially using the same control lease.",
            schema(
                lease,
                "steps" to array(JsonObject().apply { addProperty("type", "object") }, "Tool call steps containing tool and arguments"),
                "stop_on_error" to bool(),
                required = arrayOf("lease_id", "steps"),
            ),
        ),
        PhoneToolSpec(
            "phone_call_js_api",
            "Call any PhoneMCP atomic JavaScript API by dotted path with JSON arguments. Examples: getClip, setClip, home, press, device.vibrate.",
            schema(
                lease,
                "api" to string("Global function or dotted namespace method"),
                "arguments" to array(JsonObject(), "JSON-serializable positional arguments"),
                "result_mode" to enum("json", "string", "discard"),
                "timeout_ms" to integer("Maximum execution time", 100, 30000),
                required = arrayOf("lease_id", "api"),
            ),
            readOnly = false,
            destructive = true,
            idempotent = false,
            openWorld = true,
        ),
        read(
            "phone_list_apps",
            "List installed launchable applications with pagination and filtering.",
            paginationSchema("query" to string(), "include_system" to bool()),
        ),
        read(
            "phone_get_app_info",
            "Get package version, enabled state, launch intent and granted permissions.",
            schema("package_name" to string(), required = arrayOf("package_name")),
        ),
        write(
            "phone_app_control",
            "Launch an app or open its Android settings and permission pages.",
            schema(lease, "action" to enum("launch", "open_settings", "open_permissions", "open_accessibility_settings", "force_stop"), "package_name" to string(), required = arrayOf("lease_id", "action")),
        ),
        write(
            "phone_open_uri",
            "Open an http, https, geo, tel or application deep-link URI using Android intent resolution.",
            schema(lease, "uri" to string(), "package_name" to string(), required = arrayOf("lease_id", "uri")),
            openWorld = true,
        ),
        write(
            "phone_install_app",
            "Install an APK from allowed storage through PackageInstaller and return an asynchronous job.",
            schema(lease, "path" to string("APK path in allowed storage"), required = arrayOf("lease_id", "path")),
            destructive = true,
            openWorld = true,
        ),
        read(
            "phone_get_notifications",
            "List active Android notifications when notification-listener access is enabled.",
            paginationSchema("package_name" to string()),
        ),
        write(
            "phone_notification_action",
            "Open or dismiss an active notification.",
            schema(lease, "action" to enum("open", "dismiss"), "key" to string(), required = arrayOf("lease_id", "action", "key")),
            openWorld = true,
        ),
        read("phone_get_clipboard", "Read the current clipboard text."),
        write(
            "phone_set_clipboard",
            "Replace or clear clipboard text.",
            schema(lease, "text" to string(), "clear" to bool(), required = arrayOf("lease_id")),
        ),
        read(
            "phone_list_files",
            "List an allowed app or shared-storage directory with pagination.",
            paginationSchema("path" to string(), required = arrayOf("path")),
        ),
        read(
            "phone_read_file",
            "Read a bounded UTF-8 text file from allowed storage.",
            schema("path" to string(), "max_bytes" to integer("Maximum bytes", 1, 1048576), required = arrayOf("path")),
        ),
        write(
            "phone_write_file",
            "Create or replace a UTF-8 text file in allowed storage.",
            schema(lease, "path" to string(), "content" to string(), "overwrite" to bool(), required = arrayOf("lease_id", "path", "content")),
            destructive = true,
        ),
        write(
            "phone_manage_file",
            "Create a directory, copy, move, rename or delete an allowed file.",
            schema(lease, "action" to enum("mkdir", "copy", "move", "delete"), "path" to string(), "destination" to string(), required = arrayOf("lease_id", "action", "path")),
            destructive = true,
        ),
        read(
            "phone_get_jobs",
            "List asynchronous Phone MCP jobs.",
            paginationSchema("status" to string()),
        ),
        read(
            "phone_get_performance_metrics",
            "Get secret-free per-tool latency and outcome aggregates for recent PhoneMCP calls.",
            paginationSchema("tool" to string()),
        ),
        write(
            "phone_cancel_job",
            "Cancel an asynchronous Phone MCP job.",
            schema(lease, "job_id" to string(), required = arrayOf("lease_id", "job_id")),
        ),
    )

    private fun read(name: String, description: String, schema: JsonObject = schema()) =
        PhoneToolSpec(name, description, schema, readOnly = true, idempotent = true)

    private fun write(
        name: String,
        description: String,
        schema: JsonObject,
        destructive: Boolean = false,
        openWorld: Boolean = false,
    ) = PhoneToolSpec(name, description, schema, readOnly = false, destructive = destructive, openWorld = openWorld)

    private fun selectorSchema() = schema(
        "snapshot_id" to string(), "node_id" to string(),
        "text" to string(), "resource_id" to string(),
        "content_description" to string(), "class_name" to string(),
        "ancestor_text" to string(), "ancestor_resource_id" to string(),
        "descendant_text" to string(), "descendant_resource_id" to string(),
        "match" to enum("exact", "contains", "regex"),
        "clickable" to bool(), "scrollable" to bool(), "editable" to bool(), "enabled" to bool(), "visible" to bool(),
        "limit" to integer("Maximum matches", 1, 100),
    )

    private fun paginationSchema(vararg properties: Pair<String, JsonObject>, required: Array<String> = emptyArray()) =
        schema(
            *properties,
            "limit" to integer("Page size", 1, 100),
            "cursor" to string("Opaque numeric cursor"),
            required = required,
        )

    private fun schema(vararg properties: Pair<String, JsonObject>, required: Array<String> = emptyArray()) = JsonObject().apply {
        addProperty("type", "object")
        addProperty("additionalProperties", false)
        add("properties", JsonObject().apply { properties.forEach { (name, value) -> add(name, value) } })
        if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach(::add) })
    }

    private fun string(description: String? = null) = JsonObject().apply {
        addProperty("type", "string")
        description?.let { addProperty("description", it) }
    }

    private fun bool(description: String? = null) = JsonObject().apply {
        addProperty("type", "boolean")
        description?.let { addProperty("description", it) }
    }

    private fun integer(description: String? = null, minimum: Int? = null, maximum: Int? = null) = JsonObject().apply {
        addProperty("type", "integer")
        description?.let { addProperty("description", it) }
        minimum?.let { addProperty("minimum", it) }
        maximum?.let { addProperty("maximum", it) }
    }

    private fun number(description: String? = null, minimum: Double? = null, maximum: Double? = null) = JsonObject().apply {
        addProperty("type", "number")
        description?.let { addProperty("description", it) }
        minimum?.let { addProperty("minimum", it) }
        maximum?.let { addProperty("maximum", it) }
    }

    private fun enum(vararg values: String) = string().apply {
        add("enum", JsonArray().apply { values.forEach(::add) })
    }

    private fun array(items: JsonObject, description: String? = null) = JsonObject().apply {
        addProperty("type", "array")
        add("items", items)
        description?.let { addProperty("description", it) }
    }
}
