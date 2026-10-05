package com.nixikon.tgwsproxy

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * Main screen: proxy status, start/stop controls, and the quick actions the
 * desktop tray menu offered (open in Telegram, copy link, restart, settings,
 * logs).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_OPEN_TG = "com.nixikon.tgwsproxy.action.OPEN_TG"
        const val ACTION_COPY_LINK = "com.nixikon.tgwsproxy.action.COPY_LINK"

        /**
         * Opens the app and starts the proxy immediately, without the user
         * pressing anything. Used by the Quick Settings tile when it cannot
         * start the foreground service itself.
         */
        const val ACTION_START_PROXY = "com.nixikon.tgwsproxy.action.START_PROXY"

        /** Notification tapped: show (or re-show) the update dialog. */
        const val ACTION_SHOW_UPDATE = "com.nixikon.tgwsproxy.action.SHOW_UPDATE"

        /** "Download" on the update notification: go straight to the download. */
        const val ACTION_DOWNLOAD_UPDATE = "com.nixikon.tgwsproxy.action.DOWNLOAD_UPDATE"

        private const val POLL_MS = 1000L
        private const val PREFS = "tgws_prefs"
        private const val KEY_FIRST_RUN = "first_run_done"
        private const val KEY_IPV6_WARNED = "ipv6_warned"

        /**
         * True while one of our activities is on screen. The update
         * notification is suppressed then, because the dialog says the same
         * thing better.
         */
        @Volatile
        var visible: Boolean = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var statusTitle: TextView
    private lateinit var statusAddress: TextView
    private lateinit var statusStats: TextView
    private lateinit var startStopButton: MaterialButton
    private lateinit var notificationWarning: LinearLayout
    private lateinit var notificationWarningText: TextView

    private var lastReportedError: String? = null
    private var updateChecked = false
    private var pendingRelease: UpdateChecker.Release? = null

    /** Last release the updater saw, cached or fresh. */
    private var lastRelease: UpdateChecker.Release? = null

    /** Guards against offering the same version twice per launch. */
    private var offeredVersion: String? = null

    /** A dialog of ours is on screen; queue the update offer behind it. */
    private var modalOpen = false
    private var queuedUpdate: UpdateChecker.Release? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshState()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        requestNotificationPermission()
        maybeShowFirstRun()
        // Reported right away, without the delay this used to have. A release
        // already seen on an earlier launch is known from the cache, so it shows
        // before the network has answered; the check below refreshes it.
        UpdateChecker.cached(this)
            ?.takeIf {
                UpdateChecker.compareVersions(it.version, BuildConfig.VERSION_NAME) > 0
            }
            ?.let { offerUpdate(it) }
        maybeCheckForUpdates()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        updateNotificationWarning()
        handleIntentAction(intent)

        // Returning from the "install unknown apps" screen: continue the update
        // the user already agreed to.
        pendingRelease?.let { release ->
            if (canInstallPackages()) {
                pendingRelease = null
                downloadAndInstall(release)
            }
        }
    }

    override fun onPause() {
        visible = false
        handler.removeCallbacks(pollRunnable)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentAction(intent)
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val ctx = this
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(ctx, 16f), UiKit.dp(ctx, 16f),
                UiKit.dp(ctx, 16f), UiKit.dp(ctx, 20f),
            )
        }

        // Header ------------------------------------------------------------
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(
            UiKit.label(ctx, getString(R.string.app_name), 22f, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val version = UiKit.label(ctx, "", 12f, secondary = true)
        header.addView(version)
        body.addView(header)

        // Status ------------------------------------------------------------
        val status = UiKit.addSection(body, ctx, getString(R.string.stats_title))
        statusTitle = UiKit.label(ctx, getString(R.string.status_stopped), 20f, bold = true)
        status.addView(statusTitle)
        statusAddress = UiKit.label(ctx, "", 14f, secondary = true)
        status.addView(statusAddress, UiKit.matchWrap(top = UiKit.dp(ctx, 2f)))
        statusStats = UiKit.label(ctx, "", 13f, secondary = true)
        status.addView(statusStats, UiKit.matchWrap(top = UiKit.dp(ctx, 8f)))

        // The "T" next to the clock is the proxy notification's icon, so if
        // notifications are switched off — or the channel is silenced, which some
        // ROMs treat as "no icon" — it will never appear. Say so instead of
        // leaving the user wondering whether the proxy is up.
        notificationWarning = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        notificationWarningText = UiKit.label(ctx, "", 12f, secondary = true)
        notificationWarning.addView(notificationWarningText)
        UiKit.compactButton(
            notificationWarning, ctx, getString(R.string.notif_blocked_button)
        ) { openNotificationSettings() }
        body.addView(notificationWarning, UiKit.matchWrap(top = UiKit.dp(ctx, 4f)))

        // Controls ----------------------------------------------------------
        val controls = UiKit.addSection(body, ctx, getString(R.string.section_controls))
        startStopButton = UiKit.fullButton(
            controls, ctx, getString(R.string.button_start), primary = true
        ) { toggleProxy() }

        UiKit.buttonRow(controls, ctx, listOf(
            Triple(getString(R.string.button_open_tg), false) { openInTelegram() },
            Triple(getString(R.string.button_copy_link), false) { copyLink(showDialog = true) },
        ))
        UiKit.buttonRow(controls, ctx, listOf(
            Triple(getString(R.string.button_restart), false) { ProxyService.restart(this) },
            Triple(getString(R.string.button_logs), false) {
                startActivity(Intent(this, LogsActivity::class.java))
            },
        ))
        UiKit.buttonRow(controls, ctx, listOf(
            Triple(getString(R.string.button_settings), false) {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
            Triple(getString(R.string.button_diagnostics), false) { runDiagnostics() },
        ))

        // Calls -------------------------------------------------------------
        // Not a bug of this build: an MTProto proxy carries MTProto over TCP and
        // nothing else, while call media is a separate UDP stream. Said here
        // because "calls do not work" is the first thing a user notices.
        val calls = UiKit.addSection(body, ctx, getString(R.string.section_calls))
        calls.addView(UiKit.label(ctx, getString(R.string.calls_hint), 13f, secondary = true))
        UiKit.compactButton(calls, ctx, getString(R.string.calls_more)) { showCallsHelp() }

        // Version is filled in once the Python bridge reports it.
        Jobs.run({
            PythonBridge.ensureInit(this)
            PythonBridge.appVersion
        }) { result ->
            val value = result.getOrNull()
            if (!value.isNullOrEmpty()) version.text = "v$value"
        }

        // targetSdk 35+ enforces edge-to-edge, so the root has to consume the
        // system bar insets or the header would sit under the status bar.
        return ScrollView(ctx).apply {
            fitsSystemWindows = true
            addView(body)
        }
    }

    // ---------------------------------------------------------------- state

    private fun refreshState() {
        Jobs.run({ PythonBridge.state(this) }) { result ->
            val state = result.getOrNull()

            if (state != null) {
                lastReportedError = null
                val listening = StatusText.bool(state, "listening")
                val running = StatusText.bool(state, "running")

                statusTitle.text = getString(
                    when {
                        listening -> R.string.status_running
                        running -> R.string.status_starting
                        else -> R.string.status_stopped
                    }
                )
                statusAddress.text = getString(
                    R.string.status_address,
                    "${StatusText.str(state, "host")}:${state.optInt("port")}",
                )
                statusStats.text = formatStats(StatusText.str(state, "stats"))
                startStopButton.text = getString(
                    if (listening || running) R.string.button_stop else R.string.button_start
                )
            }

            // Reported even when the state query itself failed — otherwise a
            // broken Python runtime looks exactly like a frozen UI.
            reportErrorOnce(result.exceptionOrNull())

            ProxyService.consumeError()?.let { (code, detail) ->
                showErrorDialog(
                    getString(R.string.error_start_title),
                    StatusText.startError(this, code, detail),
                )
            }
        }
    }

    /**
     * Renders `stats.summary()` as labelled lines. Every field of the upstream
     * summary is still shown; the raw one-liner remains available in the log.
     */
    private fun formatStats(raw: String): String {
        if (raw.isBlank()) return ""
        val values = HashMap<String, String>()
        for (match in Regex("(\\w+)=([^\\s]+)").findAll(raw)) {
            values[match.groupValues[1]] = match.groupValues[2]
        }
        fun value(key: String) = values[key] ?: "–"
        return listOf(
            getString(R.string.stats_connections, value("total"), value("active")),
            getString(
                R.string.stats_routing,
                value("ws"), value("tcp_fb"), value("cf"), value("front"),
            ),
            getString(
                R.string.stats_h2,
                value("h2"), value("h2_tcp"), value("h2_req"), value("h2_err"),
            ),
            getString(R.string.stats_health, value("bad"), value("masked"), value("err")),
            getString(R.string.stats_pool, value("pool"), value("cf_pool")),
            getString(R.string.stats_traffic, value("up"), value("down")),
        ).joinToString("\n")
    }

    private fun toggleProxy() {
        Jobs.run({ PythonBridge.state(this) }) { result ->
            val state = result.getOrNull()
            val up = state != null &&
                (StatusText.bool(state, "listening") || StatusText.bool(state, "running"))
            if (up) {
                ProxyService.stop(this)
            } else {
                ProxyService.start(this)
            }
            handler.postDelayed({ refreshState() }, 400)
        }
    }

    // ---------------------------------------------------------------- actions

    private fun handleIntentAction(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_TG -> {
                intent.action = null
                openInTelegram()
            }
            ACTION_COPY_LINK -> {
                intent.action = null
                copyLink(showDialog = true)
            }
            ACTION_START_PROXY -> {
                intent.action = null
                // An activity is a foreground context, so this start is always
                // allowed — unlike the one the tile attempts.
                if (!ProxyService.active) {
                    AppLog.append(this, "ui", "auto-start requested from the tile")
                    ProxyService.start(this)
                }
                handler.postDelayed({ refreshState() }, 400)
            }
            ACTION_SHOW_UPDATE -> {
                intent.action = null
                // Re-offer the release the notification was about, even if the
                // dialog was dismissed earlier. When nothing is known yet, the
                // check started in onCreate will offer it in a moment.
                lastRelease?.let { release ->
                    offeredVersion = null
                    offerUpdate(release)
                }
            }
            ACTION_DOWNLOAD_UPDATE -> {
                intent.action = null
                startUpdateFromNotification()
            }
        }
    }

    private fun copyLink(showDialog: Boolean) {
        Jobs.run({ PythonBridge.proxyLink(this) }) { result ->
            val link = result.getOrNull()
            if (link.isNullOrEmpty()) {
                showErrorDialog(
                    getString(R.string.error_start_title),
                    StatusText.startError(
                        this, "unknown",
                        result.exceptionOrNull()?.message ?: "",
                    ),
                )
                return@run
            }
            if (TgLinks.copyToClipboard(this, link)) {
                if (showDialog) {
                    showLinkDialog(getString(R.string.dialog_copy_ok, link))
                } else {
                    UiKit.toast(this, getString(R.string.toast_link_copied))
                }
            } else {
                showLinkDialog(getString(R.string.dialog_copy_fail, link))
            }
        }
    }

    private fun showLinkDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_copy_title)
            .setMessage(message)
            .setPositiveButton(R.string.button_ok, null)
            .show()
    }

    private fun openInTelegram() {
        Jobs.run({ PythonBridge.proxyLink(this) }) { result ->
            val link = result.getOrNull()
            if (link.isNullOrEmpty()) return@run
            TgLinks.openInTelegram(this, link) { detail ->
                // Same fallback the desktop build used when Telegram could not be
                // launched: put the link on the clipboard and show it.
                TgLinks.copyToClipboard(this, link)
                AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_copy_title)
                    .setMessage(
                        if (detail.isNotEmpty()) {
                            getString(R.string.dialog_open_tg_fail, detail) + "\n\n" +
                                getString(R.string.dialog_open_tg_fail_clipboard, link)
                        } else {
                            getString(R.string.dialog_open_tg_fail_clipboard, link)
                        }
                    )
                    .setPositiveButton(R.string.button_ok, null)
                    .show()
            }
        }
    }

    // ---------------------------------------------------------------- errors

    /**
     * Surfaces a Python failure once per distinct message, with the self-test
     * one tap away since the device log is not always reachable.
     */
    private fun reportErrorOnce(throwable: Throwable?) {
        val message = throwable?.let { describeThrowable(it) }
            ?: PythonBridge.initError
            ?: return
        if (message == lastReportedError) return
        lastReportedError = message
        AppLog.append(this, "ui", message)

        AlertDialog.Builder(this)
            .setTitle(R.string.error_python_title)
            .setMessage(message)
            .setPositiveButton(R.string.button_ok, null)
            .setNeutralButton(R.string.button_diagnostics) { _, _ -> runDiagnostics() }
            .show()
    }

    /** Flattens a wrapped exception chain into something worth reading. */
    private fun describeThrowable(t: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 6) {
            val message = current.message
            if (!message.isNullOrBlank() && parts.lastOrNull() != message) {
                parts.add(message)
            }
            current = current.cause
            depth++
        }
        return parts.joinToString("\n\n").ifEmpty { t.toString() }
    }

    private fun showErrorDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.button_ok, null)
            .show()
    }

    /**
     * Why calls do not work through the proxy, and what can be done about it.
     *
     * This is not something the app can fix: the proxy relays MTProto over TCP,
     * while call audio/video travels as a separate UDP (WebRTC) stream that never
     * reaches the proxy. The same limitation is documented by the upstream
     * project (FAQ, issue #389: "MTProto-proxy архитектурно не поддерживает
     * голосовые звонки и войс-чаты").
     */
    private fun showCallsHelp() {
        val ctx = this
        // A plain message would be clipped: this text is long, so it gets its own
        // scroller the way the About dialog does.
        val text = TextView(ctx).apply {
            this.text = getString(R.string.calls_help)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val body = ScrollView(ctx).apply {
            setPadding(
                UiKit.dp(ctx, 20f), UiKit.dp(ctx, 8f), UiKit.dp(ctx, 20f), 0
            )
            addView(text)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.calls_title)
            .setView(body)
            .setPositiveButton(R.string.button_close, null)
            .show()
    }

    private fun runDiagnostics() {
        Jobs.run({
            // Both parts are slow and must stay off the main thread.
            PythonBridge.selftest(this) to callPathReport()
        }) { result ->
            val pair = result.getOrNull()
            val report = pair?.first
            val callPath = pair?.second.orEmpty()
            val text = if (report == null) {
                describeThrowable(
                    result.exceptionOrNull() ?: RuntimeException("unknown error")
                )
            } else {
                buildString {
                    append("ok=").append(report.optBoolean("ok")).append("\n\n")
                    val checks = report.optJSONArray("checks")
                    for (i in 0 until (checks?.length() ?: 0)) {
                        val item = checks?.optJSONObject(i) ?: continue
                        append(if (item.optBoolean("ok")) "✓ " else "✗ ")
                        append(item.optString("name")).append(": ")
                        append(item.optString("detail")).append('\n')
                    }
                }
            }
            val full = "app ${appBuildLabel()}\nupdate source: ${UpdateChecker.source()}\n" +
                "notifications: ${notificationsDiagnostic()}\n" +
                (if (callPath.isEmpty()) "" else "call path: $callPath\n") +
                "\n" + versionSelfCheck() + "\n\n" + text
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_diagnostics_title)
                .setMessage(full)
                .setPositiveButton(R.string.button_ok, null)
                .setNeutralButton(R.string.button_copy) { _, _ ->
                    if (TgLinks.copyToClipboard(this, full)) {
                        UiKit.toast(this, getString(R.string.toast_copied))
                    }
                }
                .show()
        }
    }

    /**
     * Can the phone reach Telegram *directly*, without the proxy?
     *
     * This is the question that decides what to do about calls: the proxy cannot
     * carry call media at all (it is UDP/WebRTC, while the proxy relays MTProto
     * over TCP), so calls depend entirely on the direct path. Only TCP:443 can be
     * probed this way — the media part is UDP and simply cannot be tested from
     * here, which is why a positive result is not a promise.
     */
    private fun callPathReport(): String {
        val targets = listOf(
            "149.154.167.51" to "DC2",
            "91.105.192.100" to "DC203/call",
        )
        val parts = targets.map { (ip, label) ->
            val reachable = try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(ip, 443), 2500)
                }
                true
            } catch (_: Exception) {
                false
            }
            "$label $ip:443 ${if (reachable) "ok" else "unreachable"}"
        }
        return parts.joinToString(", ") + " (direct, TCP only; call media is UDP)"
    }

    /** Version code and name of the installed build, for support reports. */    private fun appBuildLabel(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    } catch (_: Exception) {
        "unknown"
    }

    /**
     * Exercises the updater's version comparison on the device, since that is
     * the only non-obvious logic in the update path and a wrong answer would
     * either hide a real update or offer a downgrade.
     */
    private fun versionSelfCheck(): String {
        val cases = listOf(
            Triple("1.11.0-a2", "1.11.0-a1", 1),
            Triple("1.11.0-a1", "1.11.0-a2", -1),
            Triple("1.11.0-a2", "1.11.0-a2", 0),
            Triple("1.11.0", "1.11.0-a2", 1),
            Triple("1.11.0-a2", "1.11.0", -1),
            Triple("1.11.0-a10", "1.11.0-a9", 1),
            Triple("1.12.0", "1.11.0-a9", 1),
            Triple("2.0.0", "1.99.99", 1),
            Triple("v1.11.0-a2", "1.11.0-a1", 1),
        )
        val wrong = cases.filter {
            UpdateChecker.compareVersions(it.first, it.second) != it.third
        }
        return if (wrong.isEmpty()) {
            "✓ versions: ${cases.size} сравнений верны"
        } else {
            "✗ versions: неверно для " +
                wrong.joinToString { "${it.first} vs ${it.second}" }
        }
    }

    // ---------------------------------------------------------------- updates

    /**
     * Checks the configured GitHub release once per launch and offers to install
     * a newer build. Silent when no repository was configured at build time.
     */
    private fun maybeCheckForUpdates() {
        if (updateChecked) return
        updateChecked = true
        if (!UpdateChecker.isConfigured()) {
            AppLog.append(this, "update", "no update repository configured; check skipped")
            return
        }
        val current = BuildConfig.VERSION_NAME
        Jobs.run({ UpdateChecker.check(current) }) { result ->
            val release = result.getOrNull()
            if (release == null) {
                if (result.isSuccess) {
                    // Running build is current: a cached entry is stale.
                    UpdateChecker.clearCache(this)
                    Notifications.cancelUpdate(this)
                } else {
                    result.exceptionOrNull()?.let {
                        AppLog.append(this, "update", "check failed: ${it.message}")
                    }
                }
                return@run
            }
            AppLog.append(this, "update", "version ${release.version} is available")
            UpdateChecker.cache(this, release)
            offerUpdate(release)
        }
    }

    /**
     * Shows the update dialog, or queues it behind a dialog we already own so
     * the first-run and update prompts never stack on top of each other.
     */
    private fun offerUpdate(release: UpdateChecker.Release) {
        lastRelease = release
        // The app is on screen now, so the notification has done its job.
        Notifications.cancelUpdate(this)
        if (offeredVersion == release.version) return
        if (modalOpen) {
            queuedUpdate = release
            return
        }
        offeredVersion = release.version
        showUpdateDialog(release)
    }

    private fun flushQueuedUpdate() {
        val release = queuedUpdate ?: return
        // Still busy with another dialog: its own dismiss will pick this up.
        if (modalOpen) return
        queuedUpdate = null
        if (isFinishing || isDestroyed) return
        offeredVersion = release.version
        showUpdateDialog(release)
    }

    /**
     * The notification's *Download* action: the user has already answered the
     * question the dialog asks, so go straight to the download.
     */
    private fun startUpdateFromNotification() {
        Notifications.cancelUpdate(this)
        val known = lastRelease ?: UpdateChecker.cached(this)
        if (known != null &&
            UpdateChecker.compareVersions(known.version, BuildConfig.VERSION_NAME) > 0
        ) {
            offeredVersion = known.version
            startUpdate(known)
            return
        }
        // Nothing cached (the first launch after an update, say): ask again.
        Jobs.run({ UpdateChecker.check(BuildConfig.VERSION_NAME) }) { result ->
            val release = result.getOrNull()
            if (release == null) {
                AppLog.append(
                    this, "update",
                    "download requested, no newer release: " +
                        (result.exceptionOrNull()?.message ?: "current"),
                )
                return@run
            }
            UpdateChecker.cache(this, release)
            lastRelease = release
            offeredVersion = release.version
            startUpdate(release)
        }
    }

    private fun showUpdateDialog(release: UpdateChecker.Release) {
        val message = buildString {
            append(
                getString(
                    R.string.update_available, release.version, BuildConfig.VERSION_NAME
                )
            )
            if (release.notes.isNotEmpty()) {
                append("\n\n").append(release.notes.take(600))
            }
        }
        modalOpen = true
        AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_download) { _, _ -> startUpdate(release) }
            .setNegativeButton(R.string.update_later, null)
            .setOnDismissListener { modalOpen = false; flushQueuedUpdate() }
            .show()
    }

    /**
     * Android requires the user to allow "install unknown apps" for this app
     * once. Checked before the download so the user is not asked to fetch tens
     * of megabytes only to hit a permission wall afterwards.
     */
    private fun startUpdate(release: UpdateChecker.Release) {
        Notifications.cancelUpdate(this)
        if (canInstallPackages()) {
            downloadAndInstall(release)
            return
        }
        pendingRelease = release
        modalOpen = true
        AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            .setMessage(R.string.update_need_permission)
            .setPositiveButton(R.string.update_open_settings) { _, _ ->
                openInstallPermissionSettings()
            }
            .setNegativeButton(R.string.update_later) { _, _ -> pendingRelease = null }
            .setOnDismissListener {
                modalOpen = false
                flushQueuedUpdate()
            }
            .show()
    }

    private fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    private fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
            } catch (t: Exception) {
                AppLog.append(this, "update", "cannot open install settings: ${t.message}")
            }
        }
    }

    private fun downloadAndInstall(release: UpdateChecker.Release) {
        modalOpen = true
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            .setMessage(getString(R.string.update_downloading, 0))
            .setCancelable(false)
            .setOnDismissListener {
                modalOpen = false
                flushQueuedUpdate()
            }
            .create()
        progress.show()

        Jobs.run({
            val target = File(cacheDir, "update.apk")
            // Reuse a complete previous download instead of fetching tens of
            // megabytes again.
            if (target.isFile && release.sizeBytes > 0 &&
                target.length() == release.sizeBytes
            ) {
                return@run target
            }
            if (target.exists()) target.delete()
            UpdateChecker.download(release, target) { percent ->
                runOnUiThread {
                    progress.setMessage(getString(R.string.update_downloading, percent))
                }
            }
            target
        }) { result ->
            progress.dismiss()
            val file = result.getOrNull()
            if (file == null) {
                val reason = result.exceptionOrNull()?.message ?: ""
                AppLog.append(this, "update", "download failed: $reason")
                showErrorDialog(
                    getString(R.string.update_title),
                    getString(R.string.update_failed, reason),
                )
                return@run
            }
            installApk(file)
        }
    }

    /** Hands the downloaded APK to the system installer. */
    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            AppLog.append(this, "update", "installer failed: ${t.message}")
            showErrorDialog(
                getString(R.string.update_title),
                getString(R.string.update_install_failed, t.message ?: ""),
            )
        }
    }

    // ---------------------------------------------------------------- extras

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) updateNotificationWarning()
    }

    /**
     * True when the proxy notification cannot be shown at all: the app-level
     * switch is off or our own channel has been blocked. Both hide the "T" in
     * the status bar, which is the only running indicator Android gives an app.
     */
    private fun notificationsBlocked(): Boolean =
        !NotificationManagerCompat.from(this).areNotificationsEnabled()

    /**
     * The channel exists but is set below DEFAULT. On several vendor ROMs a
     * "silent" notification gets no status-bar icon, which looks exactly like a
     * broken app.
     */
    private fun statusChannelSilenced(): Boolean =
        Notifications.statusChannelImportance(this) < NotificationManager.IMPORTANCE_DEFAULT

    /** Text for the diagnostics dialog: the state the icon depends on. */
    private fun notificationsDiagnostic(): String {
        val enabled = !notificationsBlocked()
        val importance = Notifications.statusChannelImportance(this)
        val icon = if (enabled && !statusChannelSilenced()) {
            "status-bar icon expected"
        } else {
            "status-bar icon will not show"
        }
        return "enabled=$enabled channel=${Notifications.CHANNEL_ID} " +
            "importance=$importance ($icon)"
    }

    private fun updateNotificationWarning() {
        if (!::notificationWarning.isInitialized) return
        val text = when {
            notificationsBlocked() -> getString(R.string.notif_blocked_hint)
            statusChannelSilenced() -> getString(R.string.notif_silent_hint)
            else -> null
        }
        if (text == null) {
            notificationWarning.visibility = View.GONE
            return
        }
        notificationWarningText.text = text
        notificationWarning.visibility = View.VISIBLE
    }

    private fun openNotificationSettings() {
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            appDetails
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(appDetails)
            } catch (t: Exception) {
                AppLog.append(this, "ui", "cannot open notification settings: ${t.message}")
            }
        }
    }

    /** Desktop parity: the first run explains how to point Telegram at the proxy. */
    private fun maybeShowFirstRun() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FIRST_RUN, false)) {
            maybeShowIpv6Warning(prefs)
            return
        }
        prefs.edit().putBoolean(KEY_FIRST_RUN, true).apply()
        // The instructions are about to be shown, so the update offer waits its
        // turn instead of stacking on top of them.
        modalOpen = true

        Jobs.run({
            val cfg = ConfigStore.load(this)
            PythonBridge.proxyLink(this) to cfg
        }) { result ->
            val pair = result.getOrNull()
            if (pair == null) {
                // Nothing was shown after all; let a queued update through.
                modalOpen = false
                flushQueuedUpdate()
                return@run
            }
            val (link, cfg) = pair

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    UiKit.dp(this@MainActivity, 20f), UiKit.dp(this@MainActivity, 8f),
                    UiKit.dp(this@MainActivity, 20f), 0,
                )
            }
            val instructions = TextView(this).apply {
                text = buildString {
                    // The proxy is not started automatically on Android (unlike
                    // the desktop tray app), so say so before the instructions.
                    append(getString(R.string.first_run_start_hint)).append("\n\n")
                    append(getString(R.string.first_run_how_to)).append('\n')
                    append(getString(R.string.first_run_auto)).append('\n')
                    append(getString(R.string.first_run_auto_hint)).append('\n')
                    append(getString(R.string.first_run_auto_link, link)).append("\n\n")
                    append(getString(R.string.first_run_manual)).append('\n')
                    append(getString(R.string.first_run_manual_path)).append('\n')
                    append(
                        getString(
                            R.string.first_run_manual_mtproto, cfg.host, cfg.port
                        )
                    ).append('\n')
                    append(getString(R.string.first_run_manual_secret, cfg.secret))
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                movementMethod = LinkMovementMethod.getInstance()
            }
            container.addView(instructions)

            val openNow = SwitchCompat(this).apply {
                setText(R.string.first_run_open_now)
                isChecked = true
            }
            container.addView(
                openNow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = UiKit.dp(this@MainActivity, 12f) },
            )

            AlertDialog.Builder(this)
                .setTitle(R.string.first_run_title)
                .setView(container)
                .setPositiveButton(R.string.button_start) { _, _ ->
                    if (openNow.isChecked) openInTelegram()
                }
                .setOnDismissListener {
                    modalOpen = false
                    maybeShowIpv6Warning(prefs)
                    flushQueuedUpdate()
                }
                .show()
        }
    }

    /**
     * Ported from `utils/tray_common.check_ipv6_warning`: shown once, only when
     * the device actually has a non-loopback IPv6 address.
     */
    private fun maybeShowIpv6Warning(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(KEY_IPV6_WARNED, false)) return
        if (!hasIpv6()) return
        prefs.edit().putBoolean(KEY_IPV6_WARNED, true).apply()
        modalOpen = true
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.ipv6_warning)
            .setPositiveButton(R.string.button_ok, null)
            .setOnDismissListener {
                modalOpen = false
                flushQueuedUpdate()
            }
            .show()
    }

    private fun hasIpv6(): Boolean = try {
        NetworkInterface.getNetworkInterfaces().toList().any { nif ->
            nif.isUp && nif.inetAddresses.toList().any { addr ->
                addr is Inet6Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress
            }
        }
    } catch (_: Exception) {
        false
    }
}
