package com.nixikon.tgwsproxy

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that owns the proxy lifetime.
 *
 * The proxy itself is a Python asyncio loop on its own thread; this service
 * keeps the process alive, surfaces status in the notification (the Android
 * stand-in for the desktop tray) and reports bind failures back to the UI.
 */
class ProxyService : Service() {

    companion object {
        const val ACTION_START = "com.nixikon.tgwsproxy.action.START"
        const val ACTION_STOP = "com.nixikon.tgwsproxy.action.STOP"
        const val ACTION_RESTART = "com.nixikon.tgwsproxy.action.RESTART"

        private const val POLL_MS = 3000L

        /** True while the proxy is meant to be up. */
        @Volatile
        var active: Boolean = false
            private set

        @Volatile
        private var lastErrorCode: String? = null

        @Volatile
        private var lastErrorDetail: String = ""

        /** Returns and clears the last start failure, for one-shot UI reporting. */
        fun consumeError(): Pair<String, String>? {
            val code = lastErrorCode ?: return null
            lastErrorCode = null
            return code to lastErrorDetail
        }

        /**
         * Requests a start. Returns false when the system refused to launch a
         * foreground service (background-start restrictions), which the Quick
         * Settings tile uses to fall back to opening the app.
         */
        fun start(ctx: Context): Boolean {
            val intent = Intent(ctx, ProxyService::class.java).setAction(ACTION_START)
            return try {
                ContextCompat.startForegroundService(ctx, intent)
                true
            } catch (t: Exception) {
                lastErrorCode = "start_rejected"
                lastErrorDetail = t.message ?: t.toString()
                AppLog.append(ctx, "service", "startForegroundService refused: $lastErrorDetail")
                false
            }
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, ProxyService::class.java).setAction(ACTION_STOP)
            try {
                ctx.startService(intent)
            } catch (_: Exception) {
                ctx.stopService(Intent(ctx, ProxyService::class.java))
            }
        }

        fun restart(ctx: Context): Boolean {
            val intent = Intent(ctx, ProxyService::class.java).setAction(ACTION_RESTART)
            return try {
                ContextCompat.startForegroundService(ctx, intent)
                true
            } catch (t: Exception) {
                lastErrorCode = "start_rejected"
                lastErrorDetail = t.message ?: t.toString()
                AppLog.append(ctx, "service", "restart refused: $lastErrorDetail")
                false
            }
        }
    }

    /** Single place where `active` changes, so the Quick Settings tile follows. */
    private fun markActive(value: Boolean) {
        active = value
        ProxyTileService.refresh(this)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var busy = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!active) return
            refreshStatus()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        // The proxy can be started without opening the app at all (Quick Settings
        // tile, autostart on boot). In that case there is no activity to show the
        // update dialog on, so a new release is announced by notification.
        UpdateNotifier.refresh(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                requestStop()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                promoteToForeground(getString(R.string.notif_text_starting))
                requestRestart()
                return START_STICKY
            }
            ACTION_START -> {
                promoteToForeground(getString(R.string.notif_text_starting))
                requestStart()
                return START_STICKY
            }
            else -> {
                // Recreated by the system after being killed, or a plain start.
                promoteToForeground(getString(R.string.notif_text_starting))
                requestStart()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        if (active) {
            // Fire-and-forget: the service is going away, so we cannot block the
            // main thread waiting for the asyncio loop to unwind.
            Jobs.run({
                PythonBridge.stop(this)
                true
            }) { }
            markActive(false)
        }
        super.onDestroy()
    }

    private fun promoteToForeground(text: String) {
        val notification = Notifications.build(this, text, false)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    Notifications.NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(Notifications.NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            // Without a foreground notification the service is killed almost
            // immediately, so report the reason instead of dying silently.
            lastErrorCode = "foreground_failed"
            lastErrorDetail = t.message ?: t.toString()
            AppLog.append(this, "service", "startForeground failed: $lastErrorDetail")
            markActive(false)
            stopSelf()
        }
    }

    private fun updateNotification(text: String, running: Boolean) {
        try {
            NotificationManagerCompat.from(this)
                .notify(Notifications.NOTIF_ID, Notifications.build(this, text, running))
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted: the service still runs.
        }
    }

    private fun requestStart() {
        if (busy) return
        busy = true
        val cfg = ConfigStore.load(this)
        Jobs.run({
            val res = PythonBridge.configure(this, cfg)
            if (!res.optBoolean("ok")) res else PythonBridge.start(this)
        }) { result ->
            busy = false
            val res = result.getOrNull()
            if (res == null || !res.optBoolean("ok")) {
                lastErrorCode = res?.optString("error", "")
                    ?.takeIf { it.isNotEmpty() }
                    ?: if (result.isFailure) "exception" else "unknown"
                lastErrorDetail = res?.optString("detail", "")
                    ?.takeIf { it.isNotEmpty() }
                    ?: (result.exceptionOrNull()?.message ?: "")
                AppLog.append(
                    this, "service",
                    "start failed: $lastErrorCode $lastErrorDetail",
                )
                markActive(false)
                shutdown()
            } else {
                markActive(true)
                lastErrorCode = null
                lastErrorDetail = ""
                refreshStatus()
                handler.removeCallbacks(pollRunnable)
                handler.postDelayed(pollRunnable, POLL_MS)
            }
        }
    }

    private fun requestRestart() {
        if (busy) return
        busy = true
        val cfg = ConfigStore.load(this)
        Jobs.run({
            val res = PythonBridge.configure(this, cfg)
            if (!res.optBoolean("ok")) res else PythonBridge.restart(this)
        }) { result ->
            busy = false
            val res = result.getOrNull()
            if (res == null || !res.optBoolean("ok")) {
                lastErrorCode = res?.optString("error", "")
                    ?.takeIf { it.isNotEmpty() }
                    ?: if (result.isFailure) "exception" else "unknown"
                lastErrorDetail = res?.optString("detail", "")
                    ?.takeIf { it.isNotEmpty() }
                    ?: (result.exceptionOrNull()?.message ?: "")
                markActive(false)
                shutdown()
            } else {
                markActive(true)
                lastErrorCode = null
                refreshStatus()
                handler.removeCallbacks(pollRunnable)
                handler.postDelayed(pollRunnable, POLL_MS)
            }
        }
    }

    private fun requestStop() {
        handler.removeCallbacks(pollRunnable)
        busy = true
        Jobs.run({
            PythonBridge.stop(this)
            true
        }) {
            busy = false
            markActive(false)
            shutdown()
        }
    }

    private fun shutdown() {
        handler.removeCallbacks(pollRunnable)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshStatus() {
        Jobs.run({ PythonBridge.state(this) }) { result ->
            val state = result.getOrNull() ?: return@run
            val listening = state.optBoolean("listening", false)
            val address = "${state.optString("host")}:${state.optInt("port")}"
            val text = if (listening) {
                describe(address, state.optString("stats", ""))
            } else {
                getString(R.string.notif_text_starting)
            }
            updateNotification(text, listening)
        }
    }

    private val activeRe = Regex("active=(\\d+)")
    private val upRe = Regex("up=([0-9.]+[A-Za-z]+)")
    private val downRe = Regex("down=([0-9.]+[A-Za-z]+)")

    /** Turn `stats.summary()` output into a compact notification line. */
    private fun describe(address: String, stats: String): String {
        val active = activeRe.find(stats)?.groupValues?.get(1)?.toIntOrNull()
        val up = upRe.find(stats)?.groupValues?.get(1)
        val down = downRe.find(stats)?.groupValues?.get(1)
        if (active == null || up == null || down == null) {
            return getString(R.string.notif_text_running, address)
        }
        return getString(R.string.notif_text_running_stats, address, active, up, down)
    }
}
