package com.nixikon.tgwsproxy

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile that starts and stops the proxy.
 *
 * Two things make this less trivial than it looks:
 *
 *  * A tile is not an activity, so the app may count as being in the background
 *    and a foreground-service start can be refused. The tile then opens the app
 *    with [MainActivity.ACTION_START_PROXY], and the activity starts the proxy
 *    from a foreground context where the start is always allowed.
 *  * `ProxyService.active` only flips once Python has finished starting or
 *    stopping, which takes seconds. While a toggle is settling the tile shows
 *    the state the user asked for — otherwise it would visibly flip back to "on"
 *    during shutdown before finally turning off.
 */
class ProxyTileService : TileService() {

    companion object {
        private const val POLL_MS = 1200L

        /** Long enough for a cold start, which compiles the Python sources. */
        private const val MAX_POLLS = 25

        /**
         * The state the user asked for. Kept in the companion so a recreated
         * tile instance still knows a toggle is in flight.
         */
        @Volatile
        private var wantedActive = false

        /** While `SystemClock.elapsedRealtime()` is below this, a toggle is settling. */
        @Volatile
        private var transitionDeadline = 0L

        private fun transitionInFlight(): Boolean =
            SystemClock.elapsedRealtime() < transitionDeadline

        /** Asks the system to re-read the tile after the proxy state changed. */
        fun refresh(context: Context) {
            try {
                requestListeningState(
                    context,
                    ComponentName(context, ProxyTileService::class.java),
                )
            } catch (_: Exception) {
                // No tile on this device or API level: nothing to refresh.
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pollsLeft = 0

    private val pollRunnable = object : Runnable {
        override fun run() {
            val running = ProxyService.active

            if (running == wantedActive) {
                transitionDeadline = 0
                applyState(running, pending = false)
                return
            }

            // Still settling: keep showing what the user asked for so the tile
            // does not jump back to the old state mid-transition.
            applyState(wantedActive, pending = true)

            if (pollsLeft-- > 0) {
                handler.postDelayed(this, POLL_MS)
                return
            }

            // Gave up waiting; fall back to the truth.
            transitionDeadline = 0
            applyState(running, pending = false)
            if (wantedActive) {
                AppLog.append(
                    this@ProxyTileService, "tile",
                    "proxy did not start from the tile; opening the app",
                )
                openApp()
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        if (transitionInFlight()) {
            applyState(wantedActive, pending = true)
        } else {
            applyState(ProxyService.active, pending = false)
        }
    }

    override fun onStopListening() {
        handler.removeCallbacks(pollRunnable)
        super.onStopListening()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()

        if (ProxyService.active) {
            AppLog.append(this, "tile", "stop requested")
            beginTransition(target = false)
            ProxyService.stop(this)
            return
        }

        if (ProxyService.start(this)) {
            AppLog.append(this, "tile", "start requested, accepted=true")
            beginTransition(target = true)
            refresh(this)
        } else {
            AppLog.append(this, "tile", "start refused, opening the app")
            openApp()
        }
    }

    /** Optimistically shows [target] and starts watching for it to come true. */
    private fun beginTransition(target: Boolean) {
        wantedActive = target
        transitionDeadline = SystemClock.elapsedRealtime() + MAX_POLLS * POLL_MS
        applyState(target, pending = true)
        pollsLeft = MAX_POLLS
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, POLL_MS)
    }

    private fun applyState(running: Boolean, pending: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when {
                    pending && running -> R.string.status_starting
                    pending -> R.string.status_stopping
                    running -> R.string.status_running
                    else -> R.string.status_stopped
                }
            )
        }
        tile.updateTile()
    }

    /**
     * Opens the app so it can start the proxy from the foreground. Uses
     * `startActivityAndCollapse` first, which is the documented tile API, and
     * falls back to a plain activity start.
     */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_START_PROXY
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(intent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            }
            return
        } catch (t: Throwable) {
            AppLog.append(this, "tile", "startActivityAndCollapse failed: ${t.message}")
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            AppLog.append(this, "tile", "could not open the app: ${t.message}")
        }
    }
}
