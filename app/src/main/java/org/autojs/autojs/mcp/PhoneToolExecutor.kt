package org.autojs.autojs.mcp

import android.Manifest
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Notification
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.autojs.autojs.AutoJs
import org.autojs.autojs.core.accessibility.AccessibilityService
import org.autojs.autojs.core.automator.GlobalActionAutomator
import org.autojs.autojs.core.notification.NotificationListenerService
import org.autojs.autojs.execution.ExecutionConfig
import org.autojs.autojs.execution.ScriptExecution
import org.autojs.autojs.execution.SimpleScriptExecutionListener
import org.autojs.autojs.runtime.api.augment.ocr.PaddleOcrEmbeddedEngine
import org.autojs.autojs.script.StringScriptSource
import org.autojs.plugin.paddle.ocr.api.OcrOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class PhoneToolExecutor(
    context: Context,
    private val transportStatus: () -> JsonObject,
) : PhoneToolDispatcher {

    private val context = context.applicationContext
    private val gson = Gson()
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val snapshots = LinkedHashMap<String, UiSnapshot>()
    private val snapshotLock = Any()
    private val leaseLock = Any()
    private var lease: ControlLease? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var activeToast: Toast? = null

    override fun execute(name: String, args: JsonObject, peer: JsonObject): PhoneToolExecution = when (name) {
        "phone_get_capabilities" -> execution(getCapabilities())
        "phone_get_state" -> execution(getState())
        "phone_get_transport_status" -> execution(transportStatus())
        "phone_get_permissions" -> execution(getPermissions())
        "phone_list_js_apis" -> execution(listJsApis(args))
        "phone_session_control" -> execution(sessionControl(args, peer))
        "phone_capture_screen" -> captureScreen(args)
        "phone_capture_context" -> captureContext(args)
        "phone_ui_snapshot" -> execution(captureUiSnapshot(args).json)
        "phone_ui_find" -> execution(findUi(args))
        "phone_ocr_read" -> execution(ocrRead(args))
        "phone_wait_for" -> execution(waitFor(args))
        "phone_ui_action" -> write(args, peer) { execution(uiAction(args)) }
        "phone_gesture" -> write(args, peer) { execution(gesture(args)) }
        "phone_global_action" -> write(args, peer) { execution(globalAction(args)) }
        "phone_vibrate" -> write(args, peer) { execution(vibrate(args)) }
        "phone_device_control" -> write(args, peer) { execution(deviceControl(args)) }
        "phone_audio_control" -> write(args, peer) { execution(audioControl(args)) }
        "phone_toast" -> write(args, peer) { execution(toast(args)) }
        "phone_input_text" -> write(args, peer) { execution(inputText(args)) }
        "phone_action_sequence" -> write(args, peer) { actionSequence(args, peer) }
        "phone_call_js_api" -> write(args, peer) { execution(callJsApi(args)) }
        "phone_list_apps" -> execution(listApps(args))
        "phone_get_app_info" -> execution(getAppInfo(args))
        "phone_app_control" -> write(args, peer) { execution(appControl(args)) }
        "phone_open_uri" -> write(args, peer) { execution(openUri(args)) }
        "phone_get_notifications" -> execution(getNotifications(args))
        "phone_notification_action" -> write(args, peer) { execution(notificationAction(args)) }
        "phone_get_clipboard" -> execution(getClipboard())
        "phone_set_clipboard" -> write(args, peer) { execution(setClipboard(args)) }
        "phone_list_files" -> execution(listFiles(args))
        "phone_read_file" -> execution(readFile(args))
        "phone_write_file" -> write(args, peer) { execution(writeFile(args)) }
        "phone_manage_file" -> write(args, peer) { execution(manageFile(args)) }
        "phone_get_jobs" -> execution(page(JsonArray(), 0, 20, 0))
        "phone_cancel_job" -> write(args, peer) {
            throw PhoneToolException("TARGET_NOT_FOUND", "No asynchronous job exists with id ${args.string("job_id")}")
        }
        else -> throw PhoneToolException("TOOL_NOT_FOUND", "Unknown phone tool: $name", recoverable = false)
    }

    fun shutdown() {
        screenshotExecutor.shutdownNow()
        synchronized(snapshotLock) { snapshots.clear() }
        synchronized(leaseLock) { lease = null }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        Handler(Looper.getMainLooper()).post { activeToast?.cancel(); activeToast = null }
    }

    private fun getCapabilities() = JsonObject().apply {
        val service = AccessibilityService.instance
        val metrics = context.resources.displayMetrics
        addProperty("android_version", Build.VERSION.RELEASE)
        addProperty("android_sdk", Build.VERSION.SDK_INT)
        add("screen", JsonObject().apply {
            addProperty("width", metrics.widthPixels)
            addProperty("height", metrics.heightPixels)
            addProperty("density", metrics.density)
            addProperty("rotation", context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation ?: 0)
        })
        add("capabilities", JsonObject().apply {
            addProperty("accessibility", service != null)
            addProperty("screen_capture", service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            addProperty("ocr", service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            addProperty("visual_context", service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            addProperty("js_api_bridge", true)
            addProperty("js_api_catalog", true)
            addProperty("vibration", vibrator()?.hasVibrator() == true)
            addProperty("device_control", true)
            addProperty("audio_control", context.getSystemService(AudioManager::class.java) != null)
            addProperty("toast", true)
            addProperty("notification_access", NotificationListenerService.isNotificationListenerEnabled(context))
            addProperty("root", false)
            addProperty("shizuku", false)
            addProperty("shell", "app")
            addProperty("file_scope", "app_and_shared_storage")
            addProperty("embedded_tailnet", true)
        })
    }

    private fun getState() = JsonObject().apply {
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        val audio = context.getSystemService(AudioManager::class.java)
        val root = AccessibilityService.instance?.rootInActiveWindow
        addProperty("screen_on", power?.isInteractive == true)
        addProperty("device_locked", keyguard?.isDeviceLocked == true)
        addProperty("foreground_package", root?.packageName?.toString())
        addProperty("foreground_class", root?.className?.toString())
        addProperty("battery_percent", battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
        add("battery", JsonObject().apply {
            addProperty("percent", battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            addProperty("charging", battery?.isCharging == true)
            addProperty("source", batterySource(batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0))
        })
        add("memory", JsonObject().apply {
            addProperty("available_bytes", memory.availMem)
            addProperty("total_bytes", memory.totalMem)
            addProperty("low_memory", memory.lowMemory)
        })
        add("display", JsonObject().apply {
            addProperty("brightness", runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(-1))
            addProperty("brightness_mode", runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) }.getOrDefault(-1))
        })
        add("audio", audioState(audio))
        addProperty("network", activeNetworkType())
        add("control_lease", leaseStatus())
    }

    private fun getPermissions() = JsonObject().apply {
        add("runtime", JsonObject().apply {
            listOf(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ).forEach { permission ->
                addProperty(permission, ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
            }
        })
        add("special", JsonObject().apply {
            addProperty("accessibility", AccessibilityService.instance != null)
            addProperty("notification_listener", NotificationListenerService.isNotificationListenerEnabled(context))
            addProperty("manage_external_storage", Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
            addProperty("write_settings", Settings.System.canWrite(context))
        })
    }

    private fun listJsApis(args: JsonObject): JsonObject {
        val matches = PhoneJsApiCatalog.query(args.stringOrNull("category"), args.stringOrNull("query"))
        val offset = args.cursor().coerceAtMost(matches.size)
        val limit = args.int("limit", 50).coerceIn(1, 100)
        val items = JsonArray().apply { matches.drop(offset).take(limit).forEach(::add) }
        return page(items, offset, limit, matches.size).apply {
            add("categories", PhoneJsApiCatalog.categoriesJson())
            addProperty("generic_tool", "phone_call_js_api")
        }
    }

    private fun sessionControl(args: JsonObject, peer: JsonObject): JsonObject {
        val action = args.string("action")
        val owner = peerOwner(peer)
        val now = System.currentTimeMillis()
        val ttl = args.int("ttl_seconds", 300).coerceIn(30, 900) * 1000L
        synchronized(leaseLock) {
            if (lease?.expiresAt?.let { it <= now } == true) lease = null
            when (action) {
                "acquire" -> {
                    val existing = lease
                    if (existing != null && existing.owner != owner) {
                        throw PhoneToolException("SESSION_BUSY", "Phone control is leased to another paired client until ${existing.expiresAt}")
                    }
                    lease = ControlLease(existing?.id ?: UUID.randomUUID().toString(), owner, now + ttl)
                }
                "renew" -> {
                    val existing = requireOwnedLease(args, owner)
                    lease = existing.copy(expiresAt = now + ttl)
                }
                "release" -> {
                    requireOwnedLease(args, owner)
                    lease = null
                }
                else -> throw PhoneToolException("INVALID_ARGUMENT", "Unsupported session action: $action", false)
            }
            return leaseStatus()
        }
    }

    private fun captureScreen(args: JsonObject): PhoneToolExecution {
        val bitmap = captureBitmap()
        return try {
            val capturedAt = System.currentTimeMillis()
            val encoded = encodeScreen(bitmap, args, capturedAt)
            PhoneToolExecution(encoded.metadata, JsonArray().apply { add(encoded.content) })
        } finally {
            bitmap.recycle()
        }
    }

    private fun captureContext(args: JsonObject): PhoneToolExecution {
        val bitmap = captureBitmap()
        return try {
            val capturedAt = System.currentTimeMillis()
            val snapshot = captureUiSnapshot(args)
            val ocr = ocrBitmap(bitmap, args)
            val encoded = encodeScreen(bitmap, args, capturedAt)
            val metrics = context.resources.displayMetrics
            val data = JsonObject().apply {
                addProperty("captured_at", capturedAt)
                add("screen", JsonObject().apply {
                    addProperty("width", bitmap.width)
                    addProperty("height", bitmap.height)
                    addProperty("density", metrics.density)
                    addProperty("rotation", context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation ?: 0)
                    addProperty("image_width", encoded.metadata.get("width").asInt)
                    addProperty("image_height", encoded.metadata.get("height").asInt)
                    addProperty("mime_type", encoded.metadata.get("mime_type").asString)
                    addProperty("size_bytes", encoded.metadata.get("size_bytes").asInt)
                    addProperty("coordinate_space", "physical_screen")
                })
                add("ui_snapshot", snapshot.json)
                add("ocr", ocr)
            }
            PhoneToolExecution(data, JsonArray().apply { add(encoded.content) })
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodeScreen(bitmap: Bitmap, args: JsonObject, capturedAt: Long): EncodedScreen {
        var outputBitmap = bitmap
        val maxWidth = args.intOrNull("max_width")
        if (maxWidth != null && outputBitmap.width > maxWidth) {
            val height = (bitmap.height * (maxWidth.toDouble() / bitmap.width)).roundToInt().coerceAtLeast(1)
            outputBitmap = Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
        }
        return try {
            val requestedFormat = args.stringOrNull("format") ?: "png"
            val quality = args.int("quality", 90).coerceIn(1, 100)
            val (format, mime) = when (requestedFormat) {
                "jpeg" -> Bitmap.CompressFormat.JPEG to "image/jpeg"
                "webp" -> webpFormat() to "image/webp"
                else -> Bitmap.CompressFormat.PNG to "image/png"
            }
            val bytes = ByteArrayOutputStream().use { output ->
                check(outputBitmap.compress(format, quality, output)) { "Bitmap compression failed" }
                output.toByteArray()
            }
            val metadata = JsonObject().apply {
                addProperty("width", outputBitmap.width)
                addProperty("height", outputBitmap.height)
                addProperty("mime_type", mime)
                addProperty("size_bytes", bytes.size)
                addProperty("captured_at", capturedAt)
            }
            val content = JsonObject().apply {
                addProperty("type", "image")
                addProperty("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                addProperty("mimeType", mime)
            }
            EncodedScreen(metadata, content)
        } finally {
            if (outputBitmap !== bitmap) outputBitmap.recycle()
        }
    }

    private fun captureUiSnapshot(args: JsonObject = JsonObject()): UiSnapshot {
        val service = requireAccessibility()
        val root = service.rootInActiveWindow ?: service.fastRootInActiveWindow
            ?: throw PhoneToolException("TARGET_NOT_FOUND", "The active window has no accessibility root")
        val maxNodes = args.int("max_nodes", 1000).coerceIn(1, 2000)
        val visibleOnly = args.boolean("visible_only", false)
        val id = "snap_${UUID.randomUUID()}"
        val nodes = ArrayList<SnapshotNode>()
        fun visit(node: AccessibilityNodeInfo, parentId: String?, depth: Int) {
            if (nodes.size >= maxNodes || depth > 50) return
            if (visibleOnly && !node.isVisibleToUser) return
            val nodeId = "node_${nodes.size}"
            nodes += SnapshotNode(nodeId, parentId, node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { visit(it, nodeId, depth + 1) }
                if (nodes.size >= maxNodes) break
            }
        }
        visit(root, null, 0)
        val now = System.currentTimeMillis()
        val json = JsonObject().apply {
            addProperty("snapshot_id", id)
            addProperty("created_at", now)
            addProperty("package_name", root.packageName?.toString())
            addProperty("window_count", service.windows.size)
            addProperty("truncated", nodes.size >= maxNodes)
            add("nodes", JsonArray().apply { nodes.forEach { add(it.toJson()) } })
        }
        return UiSnapshot(id, now, root.packageName?.toString(), nodes, json).also { snapshot ->
            synchronized(snapshotLock) {
                snapshots[id] = snapshot
                while (snapshots.size > 3) snapshots.remove(snapshots.keys.first())
            }
        }
    }

    private fun findUi(args: JsonObject): JsonObject {
        val snapshot = args.stringOrNull("snapshot_id")?.let(::getSnapshot) ?: captureUiSnapshot()
        val matches = matchingNodes(snapshot, args)
        val limit = args.int("limit", 20).coerceIn(1, 100)
        return JsonObject().apply {
            addProperty("snapshot_id", snapshot.id)
            addProperty("total", matches.size)
            add("items", JsonArray().apply { matches.take(limit).forEach { add(it.toJson()) } })
            addProperty("has_more", matches.size > limit)
        }
    }

    private fun ocrRead(args: JsonObject): JsonObject {
        val bitmap = captureBitmap()
        return try {
            ocrBitmap(bitmap, args)
        } finally {
            bitmap.recycle()
        }
    }

    private fun ocrBitmap(bitmap: Bitmap, args: JsonObject): JsonObject {
        val minConfidence = args.double("min_confidence", 0.0).coerceIn(0.0, 1.0).toFloat()
        val textFilter = args.stringOrNull("text")
        val options = OcrOptions().apply { scoreThreshold = minConfidence }
        val results = PaddleOcrEmbeddedEngine.detect(context, bitmap, options)
            .filter { textFilter == null || it.text.contains(textFilter, ignoreCase = true) }
        return JsonObject().apply {
            addProperty("count", results.size)
            add("items", JsonArray().apply {
                results.forEach { result ->
                    add(JsonObject().apply {
                        addProperty("text", result.text)
                        addProperty("confidence", result.confidence)
                        add("bounds", rectJson(result.bounds))
                    })
                }
            })
        }
    }

    private fun waitFor(args: JsonObject): JsonObject {
        val condition = args.string("condition")
        val timeout = args.long("timeout_ms", 10_000).coerceIn(100, 60_000)
        val deadline = System.currentTimeMillis() + timeout
        do {
            val matched = when (condition) {
                "node_exists", "node_gone" -> {
                    val selector = JsonObject().apply {
                        args.stringOrNull("text")?.let { addProperty("text", it) }
                        args.stringOrNull("resource_id")?.let { addProperty("resource_id", it) }
                    }
                    val found = matchingNodes(captureUiSnapshot(), selector).isNotEmpty()
                    if (condition == "node_exists") found else !found
                }
                "package" -> AccessibilityService.instance?.rootInActiveWindow?.packageName?.toString() == args.string("package_name")
                "screen_on" -> context.getSystemService(PowerManager::class.java)?.isInteractive == true
                "screen_off" -> context.getSystemService(PowerManager::class.java)?.isInteractive == false
                else -> throw PhoneToolException("INVALID_ARGUMENT", "Unsupported wait condition: $condition", false)
            }
            if (matched) return JsonObject().apply {
                addProperty("matched", true)
                addProperty("condition", condition)
                addProperty("matched_at", System.currentTimeMillis())
            }
            Thread.sleep(250)
        } while (System.currentTimeMillis() < deadline)
        throw PhoneToolException("TIMEOUT", "Condition '$condition' was not satisfied within $timeout ms")
    }

    private fun uiAction(args: JsonObject): JsonObject {
        val snapshot = args.stringOrNull("snapshot_id")?.let(::getSnapshot) ?: captureUiSnapshot()
        assertSnapshotFresh(snapshot)
        val node = args.stringOrNull("node_id")?.let { id -> snapshot.nodes.find { it.id == id } }
            ?: matchingNodes(snapshot, args).singleOrNull()
            ?: run {
                val count = matchingNodes(snapshot, args).size
                if (count > 1) throw PhoneToolException("AMBIGUOUS_TARGET", "Selector matched $count nodes; provide snapshot_id and node_id")
                throw PhoneToolException("TARGET_NOT_FOUND", "No accessibility node matched the target")
            }
        val actionName = args.string("action")
        val info = node.info
        val success = when (actionName) {
            "click" -> info.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            "long_click" -> info.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            "focus" -> info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            "scroll_forward" -> info.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            "scroll_backward" -> info.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            "select" -> info.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            "copy" -> info.performAction(AccessibilityNodeInfo.ACTION_COPY)
            "paste" -> info.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            "set_text", "clear_text" -> info.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        if (actionName == "clear_text") "" else args.string("value"),
                    )
                },
            )
            else -> throw PhoneToolException("INVALID_ARGUMENT", "Unsupported UI action: $actionName", false)
        }
        if (!success) throw PhoneToolException("ACTION_FAILED", "Accessibility action '$actionName' was rejected by the target node")
        return JsonObject().apply {
            addProperty("performed", true)
            addProperty("action", actionName)
            addProperty("snapshot_id", snapshot.id)
            addProperty("node_id", node.id)
        }
    }

    private fun gesture(args: JsonObject): JsonObject {
        val service = requireAccessibility()
        val automator = GlobalActionAutomator(context, Handler(Looper.getMainLooper())) { service }
        val action = args.string("action")
        val x = args.int("x")
        val y = args.int("y")
        val duration = args.long("duration_ms", if (action == "long_press") 700 else 300).coerceIn(1, 60_000)
        val success = when (action) {
            "click" -> automator.click(x, y)
            "long_press" -> automator.press(x, y, duration.toInt())
            "swipe", "drag" -> automator.swipe(x, y, args.int("end_x"), args.int("end_y"), duration)
            else -> throw PhoneToolException("INVALID_ARGUMENT", "Unsupported gesture: $action", false)
        }
        if (!success) throw PhoneToolException("ACTION_FAILED", "Gesture '$action' was cancelled")
        return JsonObject().apply { addProperty("performed", true); addProperty("action", action) }
    }

    private fun globalAction(args: JsonObject): JsonObject {
        val service = requireAccessibility()
        val automator = GlobalActionAutomator(context, Handler(Looper.getMainLooper())) { service }
        val action = args.string("action")
        val success = when (action) {
            "back" -> automator.back()
            "home" -> automator.home()
            "recents" -> automator.recents()
            "notifications" -> automator.notifications()
            "quick_settings" -> automator.quickSettings()
            "power_dialog" -> automator.powerDialog()
            "lock_screen" -> automator.lockScreen()
            "split_screen" -> automator.splitScreen()
            "take_screenshot" -> automator.takeScreenshot()
            "headset_hook" -> automator.headsethook()
            "accessibility_button" -> automator.accessibilityButton()
            "accessibility_button_chooser" -> automator.accessibilityButtonChooser()
            "accessibility_shortcut" -> automator.accessibilityShortcut()
            "accessibility_all_apps" -> automator.accessibilityAllApps()
            "dismiss_notification_shade" -> automator.dismissNotificationShade()
            else -> throw PhoneToolException("INVALID_ARGUMENT", "Unsupported global action: $action", false)
        }
        if (!success) throw PhoneToolException("ACTION_FAILED", "Global action '$action' was rejected")
        return JsonObject().apply { addProperty("performed", true); addProperty("action", action) }
    }

    private fun vibrate(args: JsonObject): JsonObject {
        val vibrator = vibrator()
            ?: throw PhoneToolException("CAPABILITY_UNAVAILABLE", "No Android vibrator service is available")
        if (!vibrator.hasVibrator()) throw PhoneToolException("CAPABILITY_UNAVAILABLE", "This device has no vibrator")
        val action = args.string("action")
        when (action) {
            "once" -> {
                val duration = args.long("duration_ms", 200).coerceIn(1, 60_000)
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            "pattern" -> {
                val timings = args.getAsJsonArray("timings_ms")
                    ?: throw PhoneToolException("INVALID_ARGUMENT", "timings_ms is required for a vibration pattern", false)
                if (timings.size() !in 1..100) {
                    throw PhoneToolException("INVALID_ARGUMENT", "A vibration pattern must contain 1 to 100 timings", false)
                }
                val values = timings.map { it.asLong.coerceIn(0, 60_000) }.toLongArray()
                val repeat = args.int("repeat_index", -1)
                if (repeat !in -1 until values.size) {
                    throw PhoneToolException("INVALID_ARGUMENT", "repeat_index must be -1 or reference a pattern element", false)
                }
                vibrator.vibrate(VibrationEffect.createWaveform(values, repeat))
            }
            "cancel" -> vibrator.cancel()
            else -> throw PhoneToolException("INVALID_ARGUMENT", "Unsupported vibration action: $action", false)
        }
        return JsonObject().apply {
            addProperty("performed", true)
            addProperty("action", action)
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceControl(args: JsonObject): JsonObject {
        val action = args.string("action")
        when (action) {
            "wake_screen", "keep_screen_on", "keep_screen_dim" -> {
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                val power = context.getSystemService(PowerManager::class.java)
                    ?: throw PhoneToolException("CAPABILITY_UNAVAILABLE", "No Android power service is available")
                val level = if (action == "keep_screen_dim") PowerManager.SCREEN_DIM_WAKE_LOCK else PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                val timeout = if (action == "wake_screen") 500L else args.long("timeout_ms", 300_000).coerceIn(100, 3_600_000)
                wakeLock = power.newWakeLock(level or PowerManager.ACQUIRE_CAUSES_WAKEUP, "AutoJs6:PhoneMcp").apply { acquire(timeout) }
            }
            "cancel_keep_awake" -> {
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                wakeLock = null
            }
            "set_brightness" -> {
                requireWriteSettings()
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, args.int("brightness").coerceIn(0, 255))
            }
            "set_brightness_mode" -> {
                requireWriteSettings()
                val mode = when (args.string("brightness_mode")) {
                    "manual" -> Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    "automatic" -> Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    else -> throw PhoneToolException("INVALID_ARGUMENT", "Unsupported brightness mode", false)
                }
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            }
            else -> throw PhoneToolException("INVALID_ARGUMENT", "Unsupported device action: $action", false)
        }
        return JsonObject().apply {
            addProperty("performed", true)
            addProperty("action", action)
            addProperty("wake_lock_held", wakeLock?.isHeld == true)
        }
    }

    private fun audioControl(args: JsonObject): JsonObject {
        val audio = context.getSystemService(AudioManager::class.java)
            ?: throw PhoneToolException("CAPABILITY_UNAVAILABLE", "No Android audio service is available")
        val stream = audioStream(args.string("stream"))
        val flags = if (args.boolean("show_ui", false)) AudioManager.FLAG_SHOW_UI else 0
        when (val action = args.string("action")) {
            "set" -> {
                if (!args.has("level")) throw PhoneToolException("INVALID_ARGUMENT", "level is required for set", false)
                audio.setStreamVolume(stream, args.int("level").coerceIn(0, audio.getStreamMaxVolume(stream)), flags)
            }
            "adjust_up" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags)
            "adjust_down" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags)
            "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
            "unmute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
            else -> throw PhoneToolException("INVALID_ARGUMENT", "Unsupported audio action: $action", false)
        }
        return JsonObject().apply {
            addProperty("performed", true)
            addProperty("stream", args.string("stream"))
            addProperty("level", audio.getStreamVolume(stream))
            addProperty("max_level", audio.getStreamMaxVolume(stream))
            addProperty("muted", audio.isStreamMute(stream))
        }
    }

    private fun toast(args: JsonObject): JsonObject {
        val action = args.string("action")
        if (action !in setOf("show", "dismiss")) {
            throw PhoneToolException("INVALID_ARGUMENT", "Unsupported toast action: $action", false)
        }
        if (action == "show" && !args.has("text")) {
            throw PhoneToolException("INVALID_ARGUMENT", "text is required for show", false)
        }
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            when (action) {
                "show" -> {
                    activeToast?.cancel()
                    activeToast = Toast.makeText(
                        context,
                        args.string("text"),
                        if (args.stringOrNull("duration") == "long") Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                    ).also(Toast::show)
                }
                "dismiss" -> {
                    activeToast?.cancel()
                    activeToast = null
                }
            }
            latch.countDown()
        }
        if (!latch.await(2, TimeUnit.SECONDS)) throw PhoneToolException("TIMEOUT", "Toast operation timed out")
        return JsonObject().apply { addProperty("performed", true); addProperty("action", action) }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else -> context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun requireWriteSettings() {
        if (!Settings.System.canWrite(context)) {
            throw PhoneToolException("PERMISSION_DENIED", "Android modify-system-settings access is required", false)
        }
    }

    private fun audioState(audio: AudioManager?) = JsonObject().apply {
        listOf("music", "notification", "alarm", "ring", "system", "voice_call").forEach { name ->
            val stream = audioStream(name)
            add(name, JsonObject().apply {
                addProperty("level", audio?.getStreamVolume(stream) ?: -1)
                addProperty("max_level", audio?.getStreamMaxVolume(stream) ?: -1)
                addProperty("muted", audio?.isStreamMute(stream) == true)
            })
        }
    }

    private fun audioStream(name: String) = when (name) {
        "music" -> AudioManager.STREAM_MUSIC
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "alarm" -> AudioManager.STREAM_ALARM
        "ring" -> AudioManager.STREAM_RING
        "system" -> AudioManager.STREAM_SYSTEM
        "voice_call" -> AudioManager.STREAM_VOICE_CALL
        else -> throw PhoneToolException("INVALID_ARGUMENT", "Unsupported audio stream: $name", false)
    }

    private fun batterySource(plugged: Int) = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
        else -> "none"
    }

    private fun inputText(args: JsonObject): JsonObject {
        val root = requireAccessibility().rootInActiveWindow
            ?: throw PhoneToolException("TARGET_NOT_FOUND", "The active window has no accessibility root")
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: throw PhoneToolException("TARGET_NOT_FOUND", "No editable accessibility node is focused")
        val incoming = args.string("text")
        val value = if (args.stringOrNull("mode") == "append") node.text?.toString().orEmpty() + incoming else incoming
        val success = node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) },
        )
        if (!success) throw PhoneToolException("ACTION_FAILED", "The focused node rejected text input")
        return JsonObject().apply { addProperty("performed", true); addProperty("length", value.length) }
    }

    private fun actionSequence(args: JsonObject, peer: JsonObject): PhoneToolExecution {
        val steps = args.array("steps")
        if (steps.size() > 50) throw PhoneToolException("INVALID_ARGUMENT", "A sequence can contain at most 50 steps", false)
        val results = JsonArray()
        val stopOnError = args.boolean("stop_on_error", true)
        val leaseId = args.string("lease_id")
        for ((index, element) in steps.withIndex()) {
            if (!element.isJsonObject) {
                throw PhoneToolException("INVALID_ARGUMENT", "Sequence step $index must be an object", false)
            }
            val step = element.asJsonObject
            val arguments = step.get("arguments")
            if (arguments != null && !arguments.isJsonObject) {
                throw PhoneToolException("INVALID_ARGUMENT", "Sequence step $index arguments must be an object", false)
            }
            val tool = step.string("tool")
            if (tool == "phone_action_sequence" || tool == "phone_session_control") {
                throw PhoneToolException("INVALID_ARGUMENT", "Nested sequences and session control are not allowed", false)
            }
            val stepArgs = step.objectOrEmpty("arguments").deepCopy().apply {
                if (!has("lease_id")) addProperty("lease_id", leaseId)
            }
            try {
                val result = execute(tool, stepArgs, peer)
                results.add(JsonObject().apply {
                    addProperty("index", index)
                    addProperty("tool", tool)
                    addProperty("ok", true)
                    add("data", result.data)
                })
            } catch (error: PhoneToolException) {
                results.add(JsonObject().apply {
                    addProperty("index", index)
                    addProperty("tool", tool)
                    addProperty("ok", false)
                    addProperty("error_code", error.code)
                    addProperty("message", error.message)
                })
                if (stopOnError) break
            }
        }
        return execution(JsonObject().apply {
            addProperty("completed", results.count { it.asJsonObject.get("ok").asBoolean })
            addProperty("total", steps.size())
            add("steps", results)
        })
    }

    private fun callJsApi(args: JsonObject): JsonObject {
        val api = args.string("api")
        val arguments = args.getAsJsonArray("arguments") ?: JsonArray()
        val resultMode = args.stringOrNull("result_mode") ?: "json"
        val timeout = args.long("timeout_ms", 10_000).coerceIn(100, 30_000)
        if (arguments.toString().toByteArray(StandardCharsets.UTF_8).size > 256 * 1024) {
            throw PhoneToolException("INVALID_ARGUMENT", "JavaScript API arguments are limited to 256 KiB", false)
        }
        val source = StringScriptSource(
            "MCP atomic API: $api",
            PhoneJsApiBridge.buildScript(api, arguments, resultMode),
        )
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val failure = AtomicReference<Throwable?>()
        val startedAt = System.currentTimeMillis()
        val scriptExecution: ScriptExecution = AutoJs.instance.scriptEngineService.execute(
            source,
            object : SimpleScriptExecutionListener() {
                override fun onSuccess(execution: ScriptExecution, value: Any?) {
                    result.set(value?.toString())
                    latch.countDown()
                }

                override fun onException(execution: ScriptExecution, error: Throwable) {
                    failure.set(error)
                    latch.countDown()
                }
            },
            ExecutionConfig(workingDirectory = context.filesDir.path),
        )
        if (!latch.await(timeout, TimeUnit.MILLISECONDS)) {
            runCatching { scriptExecution.engine?.forceStop() }
            throw PhoneToolException("TIMEOUT", "JavaScript API '$api' exceeded ${timeout}ms")
        }
        failure.get()?.let { error ->
            throw PhoneToolException("ACTION_FAILED", "JavaScript API '$api' failed: ${error.message ?: error.javaClass.simpleName}")
        }
        val payloadText = result.get()
            ?: throw PhoneToolException("ACTION_FAILED", "JavaScript API '$api' returned no result envelope")
        if (payloadText.toByteArray(StandardCharsets.UTF_8).size > 1024 * 1024) {
            throw PhoneToolException("CAPABILITY_UNAVAILABLE", "JavaScript API result exceeds the 1 MiB MCP limit")
        }
        val payload = runCatching { JsonParser.parseString(payloadText).asJsonObject }.getOrElse {
            throw PhoneToolException("ACTION_FAILED", "JavaScript API '$api' returned an invalid result envelope")
        }
        if (payload.get("ok")?.asBoolean != true) {
            throw PhoneToolException("ACTION_FAILED", "JavaScript API '$api' failed: ${payload.get("error")?.asString ?: "unknown error"}")
        }
        return payload.apply {
            remove("ok")
            addProperty("api", api)
            addProperty("duration_ms", System.currentTimeMillis() - startedAt)
        }
    }

    private fun listApps(args: JsonObject): JsonObject {
        val manager = context.packageManager
        val query = args.stringOrNull("query")?.lowercase()
        val includeSystem = args.boolean("include_system", false)
        val applications = installedApplications(manager)
            .asSequence()
            .filter { includeSystem || it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { info ->
                val label = manager.getApplicationLabel(info).toString()
                Triple(info, label, manager.getLaunchIntentForPackage(info.packageName) != null)
            }
            .filter { (info, label) -> query == null || info.packageName.lowercase().contains(query) || label.lowercase().contains(query) }
            .sortedBy { it.second.lowercase() }
            .toList()
        val offset = args.cursor()
        val limit = args.int("limit", 20).coerceIn(1, 100)
        val items = JsonArray().apply {
            applications.drop(offset).take(limit).forEach { (info, label, launchable) ->
                add(JsonObject().apply {
                    addProperty("package_name", info.packageName)
                    addProperty("label", label)
                    addProperty("enabled", info.enabled)
                    addProperty("system", info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                    addProperty("launchable", launchable)
                })
            }
        }
        return page(items, offset, limit, applications.size)
    }

    private fun getAppInfo(args: JsonObject): JsonObject {
        val packageName = args.string("package_name")
        val manager = context.packageManager
        val info = try {
            packageInfo(manager, packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            throw PhoneToolException("APP_NOT_INSTALLED", "Package is not installed: $packageName")
        }
        return JsonObject().apply {
            addProperty("package_name", packageName)
            addProperty("version_name", info.versionName)
            addProperty("version_code", info.longVersionCode)
            addProperty("enabled", info.applicationInfo?.enabled)
            addProperty("launchable", manager.getLaunchIntentForPackage(packageName) != null)
            add("permissions", JsonArray().apply {
                info.requestedPermissions?.forEachIndexed { index, permission ->
                    add(JsonObject().apply {
                        addProperty("name", permission)
                        addProperty("granted", info.requestedPermissionsFlags?.getOrNull(index)?.and(PackageInfoFlagGranted) != 0)
                    })
                }
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun installedApplications(manager: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            manager.getInstalledApplications(0)
        }

    @Suppress("DEPRECATION")
    private fun packageInfo(manager: PackageManager, packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            manager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }

    private fun appControl(args: JsonObject): JsonObject {
        val action = args.string("action")
        val packageName = args.stringOrNull("package_name") ?: context.packageName
        val intent = when (action) {
            "launch" -> context.packageManager.getLaunchIntentForPackage(packageName)
                ?: throw PhoneToolException("APP_NOT_INSTALLED", "Package is not installed or has no launcher activity: $packageName")
            "open_settings" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            "open_permissions" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            "open_accessibility_settings" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "force_stop" -> throw PhoneToolException("PRIVILEGE_REQUIRED", "Force-stop requires Shizuku or root and is not enabled")
            else -> throw PhoneToolException("INVALID_ARGUMENT", "Unsupported app action: $action", false)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return JsonObject().apply { addProperty("performed", true); addProperty("action", action); addProperty("package_name", packageName) }
    }

    private fun openUri(args: JsonObject): JsonObject {
        val uri = Uri.parse(args.string("uri"))
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https", "geo", "tel", "mailto", "sms", "smsto", "market") && scheme.isNullOrBlank()) {
            throw PhoneToolException("INVALID_ARGUMENT", "URI must contain a scheme", false)
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.stringOrNull("package_name")?.let(intent::setPackage)
        if (intent.resolveActivity(context.packageManager) == null) {
            throw PhoneToolException("TARGET_NOT_FOUND", "No installed app can open this URI")
        }
        context.startActivity(intent)
        return JsonObject().apply { addProperty("performed", true); addProperty("uri", uri.toString()) }
    }

    private fun getNotifications(args: JsonObject): JsonObject {
        val service = NotificationListenerService.instance
            ?: throw PhoneToolException("PERMISSION_REQUIRED", "Notification-listener access is not enabled")
        val packageFilter = args.stringOrNull("package_name")
        val notifications = service.activeNotifications.orEmpty().filter { packageFilter == null || it.packageName == packageFilter }
        val offset = args.cursor()
        val limit = args.int("limit", 20).coerceIn(1, 100)
        val items = JsonArray().apply {
            notifications.drop(offset).take(limit).forEach { sbn ->
                val extras = sbn.notification.extras
                add(JsonObject().apply {
                    addProperty("key", sbn.key)
                    addProperty("package_name", sbn.packageName)
                    addProperty("posted_at", sbn.postTime)
                    addProperty("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
                    addProperty("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
                    addProperty("ongoing", sbn.isOngoing)
                    addProperty("can_open", sbn.notification.contentIntent != null)
                })
            }
        }
        return page(items, offset, limit, notifications.size)
    }

    private fun notificationAction(args: JsonObject): JsonObject {
        val service = NotificationListenerService.instance
            ?: throw PhoneToolException("PERMISSION_REQUIRED", "Notification-listener access is not enabled")
        val key = args.string("key")
        val sbn = service.activeNotifications.orEmpty().find { it.key == key }
            ?: throw PhoneToolException("TARGET_NOT_FOUND", "Active notification not found: $key")
        when (val action = args.string("action")) {
            "dismiss" -> service.cancelNotification(key)
            "open" -> sbn.notification.contentIntent?.send()
                ?: throw PhoneToolException("ACTION_FAILED", "Notification has no content action")
            else -> throw PhoneToolException("INVALID_ARGUMENT", "Unsupported notification action: $action", false)
        }
        return JsonObject().apply { addProperty("performed", true); addProperty("key", key) }
    }

    private fun getClipboard(): JsonObject {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        return JsonObject().apply { addProperty("has_text", text != null); addProperty("text", text?.take(100_000)) }
    }

    private fun setClipboard(args: JsonObject): JsonObject {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
            ?: throw PhoneToolException("CAPABILITY_UNAVAILABLE", "Clipboard service is unavailable")
        if (args.boolean("clear", false)) clipboard.clearPrimaryClip()
        else clipboard.setPrimaryClip(ClipData.newPlainText("Phone MCP", args.stringOrNull("text").orEmpty()))
        return JsonObject().apply { addProperty("performed", true); addProperty("cleared", args.boolean("clear", false)) }
    }

    private fun listFiles(args: JsonObject): JsonObject {
        val directory = allowedFile(args.string("path"), mustExist = true)
        if (!directory.isDirectory) throw PhoneToolException("INVALID_ARGUMENT", "Path is not a directory: ${directory.path}")
        val files = directory.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
        val offset = args.cursor()
        val limit = args.int("limit", 20).coerceIn(1, 100)
        val items = JsonArray().apply { files.drop(offset).take(limit).forEach { add(fileJson(it)) } }
        return page(items, offset, limit, files.size)
    }

    private fun readFile(args: JsonObject): JsonObject {
        val file = allowedFile(args.string("path"), mustExist = true)
        if (!file.isFile) throw PhoneToolException("INVALID_ARGUMENT", "Path is not a file: ${file.path}")
        val maxBytes = args.int("max_bytes", 256 * 1024).coerceIn(1, 1024 * 1024)
        if (file.length() > maxBytes) throw PhoneToolException("CAPABILITY_UNAVAILABLE", "File exceeds max_bytes; use a smaller file or bounded transfer")
        val bytes = file.readBytes()
        return JsonObject().apply {
            addProperty("path", file.path)
            addProperty("size", bytes.size)
            addProperty("sha256", sha256(bytes))
            addProperty("content", bytes.toString(StandardCharsets.UTF_8))
        }
    }

    private fun writeFile(args: JsonObject): JsonObject {
        val file = allowedFile(args.string("path"), mustExist = false)
        if (file.exists() && !args.boolean("overwrite", false)) {
            throw PhoneToolException("INVALID_PRECONDITION", "File already exists; set overwrite=true to replace it")
        }
        file.parentFile?.mkdirs()
        val bytes = args.string("content").toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > 1024 * 1024) throw PhoneToolException("INVALID_ARGUMENT", "Text writes are limited to 1 MiB", false)
        file.writeBytes(bytes)
        return fileJson(file).apply { addProperty("sha256", sha256(bytes)) }
    }

    private fun manageFile(args: JsonObject): JsonObject {
        val action = args.string("action")
        val source = allowedFile(args.string("path"), mustExist = action != "mkdir")
        val destination = args.stringOrNull("destination")?.let { allowedFile(it, mustExist = false) }
        val performed = when (action) {
            "mkdir" -> source.mkdirs() || source.isDirectory
            "copy" -> {
                val target = destination ?: throw PhoneToolException("INVALID_ARGUMENT", "destination is required for copy", false)
                source.copyRecursively(target, overwrite = false)
            }
            "move" -> {
                val target = destination ?: throw PhoneToolException("INVALID_ARGUMENT", "destination is required for move", false)
                source.renameTo(target) || source.copyRecursively(target, overwrite = false).also { if (it) source.deleteRecursively() }
            }
            "delete" -> source.deleteRecursively()
            else -> throw PhoneToolException("INVALID_ARGUMENT", "Unsupported file action: $action", false)
        }
        if (!performed) throw PhoneToolException("ACTION_FAILED", "File action '$action' failed")
        return JsonObject().apply { addProperty("performed", true); addProperty("action", action); addProperty("path", source.path) }
    }

    private fun captureBitmap(): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw PhoneToolException("CAPABILITY_UNAVAILABLE", "Accessibility screenshots require Android 11 or newer")
        }
        val service = requireAccessibility()
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var errorCode: Int? = null
        service.takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    bitmap = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle()
                    screenshot.hardwareBuffer.close()
                } finally {
                    latch.countDown()
                }
            }

            override fun onFailure(error: Int) {
                errorCode = error
                latch.countDown()
            }
        })
        if (!latch.await(10, TimeUnit.SECONDS)) throw PhoneToolException("TIMEOUT", "Screen capture timed out")
        return bitmap ?: throw PhoneToolException("SCREEN_CAPTURE_DISABLED", "Accessibility screen capture failed with code ${errorCode ?: -1}")
    }

    private fun matchingNodes(snapshot: UiSnapshot, args: JsonObject): List<SnapshotNode> {
        args.stringOrNull("node_id")?.let { id -> return snapshot.nodes.filter { it.id == id } }
        val text = args.stringOrNull("text")
        val resourceId = args.stringOrNull("resource_id")
        val description = args.stringOrNull("content_description")
        val className = args.stringOrNull("class_name")
        val match = args.stringOrNull("match") ?: "exact"
        fun matches(actual: CharSequence?, expected: String?): Boolean {
            if (expected == null) return true
            val value = actual?.toString() ?: return false
            return when (match) {
                "contains" -> value.contains(expected, ignoreCase = true)
                "regex" -> runCatching { Regex(expected).containsMatchIn(value) }.getOrDefault(false)
                else -> value == expected
            }
        }
        return snapshot.nodes.filter { node ->
            val info = node.info
            matches(info.text, text) &&
                matches(info.viewIdResourceName, resourceId) &&
                matches(info.contentDescription, description) &&
                matches(info.className, className) &&
                (!args.has("clickable") || info.isClickable == args.get("clickable").asBoolean) &&
                (!args.has("editable") || info.isEditable == args.get("editable").asBoolean) &&
                (!args.has("visible") || info.isVisibleToUser == args.get("visible").asBoolean)
        }
    }

    private fun getSnapshot(id: String): UiSnapshot = synchronized(snapshotLock) {
        snapshots[id] ?: throw PhoneToolException("STALE_SNAPSHOT", "UI snapshot is missing or expired; call phone_ui_snapshot again")
    }

    private fun assertSnapshotFresh(snapshot: UiSnapshot) {
        if (System.currentTimeMillis() - snapshot.createdAt > 15_000) {
            throw PhoneToolException("STALE_SNAPSHOT", "UI snapshot is older than 15 seconds; capture a new snapshot")
        }
        val currentPackage = AccessibilityService.instance?.rootInActiveWindow?.packageName?.toString()
        if (snapshot.packageName != currentPackage) {
            throw PhoneToolException("STALE_SNAPSHOT", "Foreground package changed from ${snapshot.packageName} to $currentPackage")
        }
    }

    private fun <T> write(args: JsonObject, peer: JsonObject, block: () -> T): T {
        requireOwnedLease(args, peerOwner(peer))
        return block()
    }

    private fun requireOwnedLease(args: JsonObject, owner: String): ControlLease {
        val leaseId = args.stringOrNull("lease_id")
            ?: throw PhoneToolException("AUTHENTICATION_REQUIRED", "A lease_id from phone_session_control is required")
        val now = System.currentTimeMillis()
        synchronized(leaseLock) {
            val existing = lease
            if (existing == null || existing.expiresAt <= now) {
                lease = null
                throw PhoneToolException("AUTHENTICATION_REQUIRED", "Control lease is missing or expired; acquire a new lease")
            }
            if (existing.id != leaseId || existing.owner != owner) {
                throw PhoneToolException("POLICY_DENIED", "Control lease does not belong to this paired client", false)
            }
            return existing
        }
    }

    private fun leaseStatus(): JsonObject = synchronized(leaseLock) {
        val current = lease?.takeIf { it.expiresAt > System.currentTimeMillis() }
        JsonObject().apply {
            addProperty("active", current != null)
            current?.let {
                addProperty("lease_id", it.id)
                addProperty("expires_at", it.expiresAt)
                addProperty("owner", it.owner)
            }
        }
    }

    private fun peerOwner(peer: JsonObject): String = peer.stringOrNull("node_id")
        ?: peer.stringOrNull("source_address")?.substringBeforeLast(':')
        ?: peer.stringOrNull("transport")
        ?: "unknown"

    private fun requireAccessibility(): AccessibilityService = AccessibilityService.instance
        ?: throw PhoneToolException("ACCESSIBILITY_DISABLED", "Enable the AutoJs6 accessibility service before using this tool")

    private fun activeNetworkType(): String {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "none"
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "none"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    private fun allowedFile(rawPath: String, mustExist: Boolean): File {
        val requested = File(rawPath).let { if (it.isAbsolute) it else File(context.filesDir, rawPath) }.canonicalFile
        val roots = buildList {
            add(context.filesDir.canonicalFile)
            add(context.cacheDir.canonicalFile)
            context.getExternalFilesDir(null)?.canonicalFile?.let(::add)
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                Environment.getExternalStorageDirectory().canonicalFile.let(::add)
            }
        }
        if (roots.none { requested.path == it.path || requested.path.startsWith(it.path + File.separator) }) {
            throw PhoneToolException("POLICY_DENIED", "Path is outside allowed app and shared-storage roots", false)
        }
        if (mustExist && !requested.exists()) throw PhoneToolException("TARGET_NOT_FOUND", "Path does not exist: ${requested.path}")
        return requested
    }

    private fun fileJson(file: File) = JsonObject().apply {
        addProperty("name", file.name)
        addProperty("path", file.path)
        addProperty("directory", file.isDirectory)
        addProperty("size", if (file.isFile) file.length() else null)
        addProperty("modified_at", file.lastModified())
        addProperty("readable", file.canRead())
        addProperty("writable", file.canWrite())
    }

    private fun page(items: JsonArray, offset: Int, limit: Int, total: Int) = JsonObject().apply {
        addProperty("total", total)
        addProperty("count", items.size())
        addProperty("offset", offset)
        add("items", items)
        val next = offset + items.size()
        addProperty("has_more", next < total)
        if (next < total) addProperty("next_cursor", next.toString())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun webpFormat() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    private fun execution(data: JsonObject) = PhoneToolExecution(data)

    private data class ControlLease(val id: String, val owner: String, val expiresAt: Long)

    private data class EncodedScreen(val metadata: JsonObject, val content: JsonObject)

    private data class UiSnapshot(
        val id: String,
        val createdAt: Long,
        val packageName: String?,
        val nodes: List<SnapshotNode>,
        val json: JsonObject,
    )

    private data class SnapshotNode(val id: String, val parentId: String?, val info: AccessibilityNodeInfo) {
        fun toJson() = JsonObject().apply {
            val bounds = Rect().also(info::getBoundsInScreen)
            addProperty("node_id", id)
            addProperty("parent_id", parentId)
            addProperty("text", info.text?.toString())
            addProperty("resource_id", info.viewIdResourceName)
            addProperty("content_description", info.contentDescription?.toString())
            addProperty("class_name", info.className?.toString())
            addProperty("package_name", info.packageName?.toString())
            add("bounds", rectJson(bounds))
            addProperty("clickable", info.isClickable)
            addProperty("long_clickable", info.isLongClickable)
            addProperty("scrollable", info.isScrollable)
            addProperty("editable", info.isEditable)
            addProperty("enabled", info.isEnabled)
            addProperty("visible", info.isVisibleToUser)
            addProperty("checked", info.isChecked)
            addProperty("selected", info.isSelected)
            addProperty("focused", info.isFocused)
        }
    }

    companion object {
        private const val PackageInfoFlagGranted = PackageInfo.REQUESTED_PERMISSION_GRANTED

        private fun rectJson(rect: Rect) = JsonArray().apply {
            add(rect.left); add(rect.top); add(rect.right); add(rect.bottom)
        }
    }
}

private fun JsonObject.string(name: String): String = stringOrNull(name)
    ?: throw PhoneToolException("INVALID_ARGUMENT", "Missing required argument: $name", false)

private fun JsonObject.stringOrNull(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString

private fun JsonObject.int(name: String, default: Int? = null): Int = intOrNull(name) ?: default
    ?: throw PhoneToolException("INVALID_ARGUMENT", "Missing required integer argument: $name", false)

private fun JsonObject.intOrNull(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }?.asInt

private fun JsonObject.long(name: String, default: Long? = null): Long = get(name)?.takeUnless { it.isJsonNull }?.asLong ?: default
    ?: throw PhoneToolException("INVALID_ARGUMENT", "Missing required long argument: $name", false)

private fun JsonObject.double(name: String, default: Double): Double = get(name)?.takeUnless { it.isJsonNull }?.asDouble ?: default

private fun JsonObject.boolean(name: String, default: Boolean): Boolean = get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: default

private fun JsonObject.array(name: String): JsonArray = getAsJsonArray(name)
    ?: throw PhoneToolException("INVALID_ARGUMENT", "Missing required array argument: $name", false)

private fun JsonObject.objectOrEmpty(name: String): JsonObject = getAsJsonObject(name) ?: JsonObject()

private fun JsonObject.cursor(): Int = stringOrNull("cursor")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
