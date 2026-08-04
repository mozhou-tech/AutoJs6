package org.autojs.autojs.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.autojs.autojs.AutoJs
import org.autojs.autojs6.R
import phonetailnet.Bridge
import phonetailnet.Phonetailnet
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PhoneMcpService : Service() {

    private val worker = Executors.newSingleThreadExecutor()
    private val startQueued = AtomicBoolean(false)
    private var bridge: Bridge? = null
    private var toolExecutor: PhoneToolExecutor? = null

    override fun onCreate() {
        super.onCreate()
        PhoneMcpRuntime.running = true
        PhoneMcpRuntime.lastError = null
        startForegroundNotification(getString(R.string.phone_mcp_status_starting))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PhoneMcpPreferences.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (bridge == null && startQueued.compareAndSet(false, true)) {
            worker.execute {
                try {
                    startBridge()
                } finally {
                    startQueued.set(false)
                }
            }
        }
        return START_STICKY
    }

    private fun startBridge() {
        val localOnly = PhoneMcpPreferences.localOnly(this)
        val controlUrl = PhoneMcpPreferences.controlUrl(this)
        if (!localOnly && controlUrl.isBlank()) {
            fail(getString(R.string.phone_mcp_error_server_required))
            return
        }
        val pairingToken = PhoneMcpPreferences.pairingToken(this)
        val authKey = PhoneMcpPreferences.authKey(this)
        val newBridge = Phonetailnet.newBridge()
        val executor = PhoneToolExecutor(
            context = this,
            transportStatus = {
                runCatching {
                    JsonParser.parseString(newBridge.statusJSON()).asJsonObject
                }.getOrElse {
                    JsonObject().apply {
                        addProperty("running", PhoneMcpRuntime.running)
                        addProperty("last_error", PhoneMcpRuntime.lastError)
                    }
                }
            },
            operationLogSink = { message ->
                Log.i(TAG, message)
                runCatching { AutoJs.instance.globalConsole.info(message) }
            },
        )
        val protocol = PhoneMcpProtocol(executor)
        val config = JsonObject().apply {
            addProperty("control_url", controlUrl)
            addProperty("hostname", PhoneMcpPreferences.hostname(this@PhoneMcpService))
            addProperty("state_dir", File(noBackupFilesDir, "phone-tailnet").absolutePath)
            addProperty("auth_key", authKey)
            addProperty("pairing_token", pairingToken)
            addProperty("tailnet_port", PhoneMcpPreferences.tailnetPort(this@PhoneMcpService))
            addProperty("local_port", PhoneMcpPreferences.localPort(this@PhoneMcpService))
            addProperty("local_only", localOnly)
        }
        try {
            newBridge.start(config.toString(), protocol, PhoneTailnetStateStore(this))
            bridge = newBridge
            toolExecutor = executor
            PhoneMcpRuntime.statusJson = newBridge.statusJSON()
            PhoneMcpRuntime.lastError = null
            if (!localOnly && authKey.isNotBlank()) PhoneMcpPreferences.clearAuthKey(this)
            updateNotification(statusText())
        } catch (error: Throwable) {
            runCatching { newBridge.stop() }
            executor.shutdown()
            fail(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun fail(message: String) {
        PhoneMcpRuntime.lastError = message.take(300)
        PhoneMcpRuntime.statusJson = JsonObject().apply {
            addProperty("running", false)
            addProperty("last_error", PhoneMcpRuntime.lastError)
        }.toString()
        PhoneMcpPreferences.setEnabled(this, false)
        updateNotification(getString(R.string.phone_mcp_status_error, PhoneMcpRuntime.lastError))
        stopSelf()
    }

    private fun statusText(): String {
        val status = runCatching { JsonParser.parseString(bridge?.statusJSON()).asJsonObject }.getOrNull()
        val ips = status?.getAsJsonArray("tailnet_ips")?.joinToString { it.asString }.orEmpty()
        val localPort = PhoneMcpPreferences.localPort(this)
        return if (PhoneMcpPreferences.localOnly(this)) {
            getString(R.string.phone_mcp_status_local, localPort)
        } else {
            getString(R.string.phone_mcp_status_online, ips.ifBlank { "Tailnet" }, PhoneMcpPreferences.tailnetPort(this))
        }
    }

    private fun startForegroundNotification(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else 0
            if (type != 0) startForeground(NOTIFICATION_ID, notification, type) else startForeground(NOTIFICATION_ID, notification)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun buildNotification(content: String): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.phone_mcp_title), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.phone_mcp_summary)
                },
            )
        }
        val settingsIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PhoneMcpSettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PhoneMcpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.autojs6_status_bar_icon)
            .setContentTitle(getString(R.string.phone_mcp_title))
            .setContentText(content)
            .setContentIntent(settingsIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(R.string.phone_mcp_stop), stopIntent)
            .build()
    }

    override fun onDestroy() {
        runCatching { bridge?.stop() }
        bridge = null
        toolExecutor?.shutdown()
        toolExecutor = null
        worker.shutdownNow()
        PhoneMcpRuntime.running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "PhoneMcpService"
        private const val CHANNEL_ID = "phone_mcp_server"
        private const val NOTIFICATION_ID = 0x4D43
        private const val ACTION_STOP = "org.autojs.autojs.mcp.STOP"

        fun start(context: Context) {
            val intent = Intent(context, PhoneMcpService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PhoneMcpService::class.java))
        }
    }
}

object PhoneMcpRuntime {
    @Volatile var running: Boolean = false
    @Volatile var lastError: String? = null
    @Volatile var statusJson: String = "{\"running\":false}"
}
