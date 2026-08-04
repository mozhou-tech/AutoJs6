package org.autojs.autojs.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject

internal object PhoneJsApiCatalog {

    private data class Entry(
        val path: String,
        val category: String,
        val access: String,
        val nativeTool: String? = null,
        val requires: String? = null,
    )

    private val entries = listOf(
        Entry("getClip", "clipboard", "read", "phone_get_clipboard"),
        Entry("setClip", "clipboard", "write", "phone_set_clipboard"),
        Entry("currentPackage", "app", "read", "phone_get_state", "accessibility"),
        Entry("currentActivity", "app", "read", "phone_get_state", "accessibility"),
        Entry("app.getPackageName", "app", "read", "phone_list_apps"),
        Entry("app.getAppName", "app", "read", "phone_list_apps"),
        Entry("app.isInstalled", "app", "read", "phone_get_app_info"),
        Entry("app.launchPackage", "app", "write", "phone_app_control"),
        Entry("app.launchApp", "app", "write", "phone_app_control"),
        Entry("app.openUrl", "app", "write", "phone_open_uri"),
        Entry("app.startActivity", "app", "write", "phone_open_uri"),
        Entry("click", "automation", "write", "phone_gesture", "accessibility"),
        Entry("longClick", "automation", "write", "phone_gesture", "accessibility"),
        Entry("press", "automation", "write", "phone_gesture", "accessibility"),
        Entry("swipe", "automation", "write", "phone_gesture", "accessibility"),
        Entry("scrollDown", "automation", "write", "phone_ui_action", "accessibility"),
        Entry("scrollUp", "automation", "write", "phone_ui_action", "accessibility"),
        Entry("input", "automation", "write", "phone_input_text", "accessibility"),
        Entry("setText", "automation", "write", "phone_input_text", "accessibility"),
        Entry("back", "system_action", "write", "phone_global_action", "accessibility"),
        Entry("home", "system_action", "write", "phone_global_action", "accessibility"),
        Entry("recents", "system_action", "write", "phone_global_action", "accessibility"),
        Entry("notifications", "system_action", "write", "phone_global_action", "accessibility"),
        Entry("quickSettings", "system_action", "write", "phone_global_action", "accessibility"),
        Entry("powerDialog", "system_action", "write", "phone_global_action", "accessibility"),
        Entry("automator.lockScreen", "system_action", "write", "phone_global_action", "accessibility"),
        Entry("automator.splitScreen", "system_action", "write", "phone_global_action", "accessibility"),
        Entry("automator.headsethook", "system_action", "write", "phone_global_action", "accessibility"),
        Entry("automator.dismissNotificationShade", "system_action", "write", "phone_global_action", "accessibility"),
        Entry("device.summary", "device", "read", "phone_get_capabilities"),
        Entry("device.digest", "device", "read", "phone_get_capabilities"),
        Entry("device.isScreenOff", "device", "read", "phone_get_state"),
        Entry("device.isScreenPortrait", "device", "read", "phone_get_state"),
        Entry("device.isScreenLandscape", "device", "read", "phone_get_state"),
        Entry("device.getBrightness", "device", "read", "phone_get_state"),
        Entry("device.getBrightnessMode", "device", "read", "phone_get_state"),
        Entry("device.setBrightness", "device", "write", "phone_device_control", "write_settings"),
        Entry("device.setBrightnessMode", "device", "write", "phone_device_control", "write_settings"),
        Entry("device.getMusicVolume", "audio", "read", "phone_get_state"),
        Entry("device.getNotificationVolume", "audio", "read", "phone_get_state"),
        Entry("device.getAlarmVolume", "audio", "read", "phone_get_state"),
        Entry("device.setMusicVolume", "audio", "write", "phone_audio_control"),
        Entry("device.setNotificationVolume", "audio", "write", "phone_audio_control"),
        Entry("device.setAlarmVolume", "audio", "write", "phone_audio_control"),
        Entry("device.wakeUp", "device", "write", "phone_device_control"),
        Entry("device.wakeUpIfNeeded", "device", "write", "phone_device_control"),
        Entry("device.keepScreenOn", "device", "write", "phone_device_control"),
        Entry("device.keepScreenDim", "device", "write", "phone_device_control"),
        Entry("device.cancelKeepingAwake", "device", "write", "phone_device_control"),
        Entry("device.getIpAddress", "network", "read", "phone_get_state"),
        Entry("device.getIpv6Address", "network", "read", "phone_get_state"),
        Entry("device.getGatewayAddress", "network", "read", "phone_get_state"),
        Entry("device.isActiveNetworkMetered", "network", "read", "phone_get_state"),
        Entry("device.isConnectedOrConnecting", "network", "read", "phone_get_state"),
        Entry("device.isWifiAvailable", "network", "read", "phone_get_state"),
        Entry("device.vibrate", "feedback", "write", "phone_vibrate"),
        Entry("toast", "feedback", "write", "phone_toast"),
        Entry("toast.dismissAll", "feedback", "write", "phone_toast"),
        Entry("files.path", "file", "read", "phone_list_files"),
        Entry("files.join", "file", "read", "phone_list_files"),
        Entry("base64.encode", "transform", "read"),
        Entry("base64.decode", "transform", "read"),
        Entry("crypto.digest", "transform", "read"),
        Entry("colors.toInt", "transform", "read"),
        Entry("colors.toString", "transform", "read"),
        Entry("sleep", "runtime", "read"),
        Entry("random", "runtime", "read"),
        Entry("shell.execCommand", "shell", "write", requires = "shell_or_root"),
        Entry("KeyCode", "shell", "write", requires = "root"),
        Entry("VolumeUp", "shell", "write", requires = "root"),
        Entry("VolumeDown", "shell", "write", requires = "root"),
        Entry("Power", "shell", "write", requires = "root"),
        Entry("Camera", "shell", "write", requires = "root"),
    )

    val categories: List<String> = entries.map { it.category }.distinct().sorted()

    fun query(category: String?, text: String?): List<JsonObject> {
        val needle = text?.trim()?.lowercase().orEmpty()
        return entries.asSequence()
            .filter { category == null || it.category == category }
            .filter { needle.isEmpty() || it.path.lowercase().contains(needle) }
            .map { entry ->
                JsonObject().apply {
                    addProperty("api", entry.path)
                    addProperty("category", entry.category)
                    addProperty("access", entry.access)
                    addProperty("bridge_tool", "phone_call_js_api")
                    entry.nativeTool?.let { addProperty("preferred_tool", it) }
                    entry.requires?.let { addProperty("requires", it) }
                }
            }
            .toList()
    }

    fun categoriesJson() = JsonArray().apply { categories.forEach(::add) }
}
