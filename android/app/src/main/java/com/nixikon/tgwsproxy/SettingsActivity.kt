package com.nixikon.tgwsproxy

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/**
 * The settings form, ported from the desktop dialog (`ui/ctk_tray_ui.py`): same
 * sections, same fields, same defaults, same validation messages and the same
 * Cloudflare connectivity tests.
 *
 * Save/Cancel live in a fixed footer outside the scrolling area, mirroring the
 * desktop layout's `tray_settings_scroll_and_footer`, so they stay reachable
 * without scrolling through the whole form.
 *
 * The desktop "Updates" section is deliberately absent: it compared against the
 * upstream desktop releases, which say nothing about this Android fork.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** `utils/connectivity.CFPROXY_TEST_DCS` length. */
        private const val TEST_DC_COUNT = 6

        private const val DEFAULT_BUF_KB = 256
        private const val DEFAULT_POOL_SIZE = 4
        private const val DEFAULT_LOG_MB = 5.0
    }

    private lateinit var cfg: ProxyConfig

    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var secretField: EditText
    private lateinit var fakeTlsField: EditText
    private lateinit var dcField: EditText

    private lateinit var cfproxySwitch: SwitchCompat
    private lateinit var cfCustomSwitch: SwitchCompat
    private lateinit var h2Switch: SwitchCompat
    private lateinit var cfDomainField: EditText
    private lateinit var cfTestButton: MaterialButton

    private lateinit var cfWorkerSwitch: SwitchCompat
    private lateinit var cfWorkerDomainField: EditText
    private lateinit var cfWorkerTestButton: MaterialButton

    private lateinit var verboseSwitch: SwitchCompat
    private lateinit var noSecureSwitch: SwitchCompat
    private lateinit var forceTestDcSwitch: SwitchCompat
    private lateinit var bufField: EditText
    private lateinit var poolField: EditText
    private lateinit var logMbField: EditText

    private lateinit var autostartSwitch: SwitchCompat
    private lateinit var batterySwitch: SwitchCompat

    /** Guards the switch while its state is being set from the system value. */
    private var suppressBatteryListener = false

    private lateinit var languageSpinner: Spinner
    private lateinit var themeSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = ConfigStore.load(this)
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        // The exemption is system state, so re-read it after the user comes back
        // from the system dialog.
        syncBatterySwitch()
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val ctx = this
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                UiKit.dp(ctx, 16f), UiKit.dp(ctx, 12f),
                UiKit.dp(ctx, 16f), UiKit.dp(ctx, 16f),
            )
        }

        body.addView(UiKit.label(ctx, getString(R.string.settings_title), 22f, bold = true))

        // --- Interface ------------------------------------------------------
        val iface = UiKit.addSection(body, ctx, getString(R.string.section_interface))
        val langLabels = UiPrefs.languageLabels().map { it.second }
        val langIndex = UiPrefs.languageLabels().indexOfFirst { it.first == cfg.language }
        languageSpinner = UiKit.addSpinner(
            iface, ctx, getString(R.string.settings_language), langLabels,
            if (langIndex >= 0) langIndex else 1,
        )
        val appearanceLabels = UiPrefs.appearanceKeys().map {
            getString(
                when (it) {
                    "light" -> R.string.appearance_light
                    "dark" -> R.string.appearance_dark
                    else -> R.string.appearance_auto
                }
            )
        }
        themeSpinner = UiKit.addSpinner(
            iface, ctx, getString(R.string.settings_theme), appearanceLabels,
            UiPrefs.appearanceKeys().indexOf(cfg.appearance).coerceAtLeast(0),
        )

        // --- MTProto --------------------------------------------------------
        val mtproto = UiKit.addSection(body, ctx, getString(R.string.section_mtproto))
        hostField = UiKit.addField(
            mtproto, ctx, getString(R.string.label_host), cfg.host,
            getString(R.string.tip_host),
        )
        portField = UiKit.addField(
            mtproto, ctx, getString(R.string.label_port), cfg.port.toString(),
            getString(R.string.tip_port), InputType.TYPE_CLASS_NUMBER,
        )
        secretField = UiKit.addField(
            mtproto, ctx, getString(R.string.label_secret), cfg.secret,
            getString(R.string.tip_secret), monospace = true,
        )
        UiKit.compactButton(mtproto, ctx, getString(R.string.button_regenerate)) {
            secretField.setText(ProxyConfig.randomSecret())
        }
        fakeTlsField = UiKit.addField(
            mtproto, ctx, getString(R.string.label_fake_tls), cfg.fakeTlsDomain,
            getString(R.string.tip_fake_tls),
        )

        // --- Data centers ---------------------------------------------------
        val dc = UiKit.addSection(body, ctx, getString(R.string.section_dc))
        dcField = UiKit.addField(
            dc, ctx, getString(R.string.label_dc_hint),
            cfg.dcIp.joinToString("\n"),
            getString(R.string.tip_dc),
            monospace = true, multiline = true,
        )

        // --- Cloudflare proxy ----------------------------------------------
        val cf = UiKit.addSection(body, ctx, getString(R.string.section_cfproxy))
        cfproxySwitch = UiKit.addSwitch(
            cf, ctx, getString(R.string.label_cf_enable), cfg.cfproxy,
            getString(R.string.tip_cfproxy),
        )
        cfCustomSwitch = UiKit.addSwitch(
            cf, ctx, getString(R.string.label_cf_custom_domain),
            cfg.cfproxyUserDomainEnabled,
            getString(R.string.tip_cfproxy_user_domain_cb),
        )
        h2Switch = UiKit.addSwitch(
            cf, ctx, getString(R.string.label_h2_enable), cfg.h2,
            getString(R.string.tip_h2),
        )
        cfDomainField = UiKit.addField(
            cf, ctx, getString(R.string.label_cf_custom_domain),
            cfg.cfproxyUserDomain.joinToString(", "),
            getString(R.string.tip_cfproxy_domain),
        )
        val cfButtons = UiKit.buttonRow(cf, ctx, listOf(
            Triple(getString(R.string.button_test), true) { runCfproxyTest() },
            Triple(getString(R.string.button_docs), false) {
                TgLinks.openUrl(this, TgLinks.docUrl(cfg.language, "CfProxy"))
            },
        ))
        cfTestButton = cfButtons.getChildAt(0) as MaterialButton

        // --- Cloudflare worker ---------------------------------------------
        val cw = UiKit.addSection(body, ctx, getString(R.string.section_cfworker))
        cfWorkerSwitch = UiKit.addSwitch(
            cw, ctx, getString(R.string.label_cf_custom_domain),
            cfg.cfproxyWorkerEnabled,
            getString(R.string.tip_cfworker_domain),
        )
        cfWorkerDomainField = UiKit.addField(
            cw, ctx, getString(R.string.label_cfworker_domains),
            cfg.cfproxyWorkerDomain.joinToString(", "),
            getString(R.string.tip_cfworker_domain),
        )
        val cwButtons = UiKit.buttonRow(cw, ctx, listOf(
            Triple(getString(R.string.button_test), true) { runCfWorkerTest() },
            Triple(getString(R.string.button_docs), false) {
                TgLinks.openUrl(this, TgLinks.docUrl(cfg.language, "CfWorker"))
            },
        ))
        cfWorkerTestButton = cwButtons.getChildAt(0) as MaterialButton

        // --- Logs & performance --------------------------------------------
        val logs = UiKit.addSection(body, ctx, getString(R.string.section_logs))
        verboseSwitch = UiKit.addSwitch(
            logs, ctx, getString(R.string.label_verbose), cfg.verbose,
            getString(R.string.tip_verbose),
        )
        noSecureSwitch = UiKit.addSwitch(
            logs, ctx, getString(R.string.label_no_secure), cfg.noSecure,
            getString(R.string.tip_no_secure),
        )
        bufField = UiKit.addField(
            logs, ctx, getString(R.string.label_buf_kb), cfg.bufKb.toString(),
            getString(R.string.tip_buf_kb), InputType.TYPE_CLASS_NUMBER,
        )
        poolField = UiKit.addField(
            logs, ctx, getString(R.string.label_pool_size), cfg.poolSize.toString(),
            getString(R.string.tip_pool), InputType.TYPE_CLASS_NUMBER,
        )
        logMbField = UiKit.addField(
            logs, ctx, getString(R.string.label_log_max_mb), formatNumber(cfg.logMaxMb),
            getString(R.string.tip_log_mb),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
        )
        forceTestDcSwitch = UiKit.addSwitch(
            logs, ctx, getString(R.string.label_force_test_dc), cfg.forceTestDc,
            getString(R.string.tip_force_test_dc),
        )

        // --- Startup --------------------------------------------------------
        val startup = UiKit.addSection(body, ctx, getString(R.string.section_startup))
        autostartSwitch = UiKit.addSwitch(
            startup, ctx, getString(R.string.label_autostart), cfg.autostart,
            getString(R.string.label_autostart_hint),
        )
        batterySwitch = UiKit.addSwitch(
            startup, ctx, getString(R.string.label_battery_exempt), isBatteryExempt(),
            getString(R.string.label_battery_exempt_hint),
        )
        batterySwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressBatteryListener) onBatteryToggled(checked)
        }

        // --- Attribution ----------------------------------------------------
        // The donate button was removed in 1.11.0-a4: this fork does not collect
        // donations of its own.
        UiKit.buttonRow(body, ctx, listOf(
            Triple(getString(R.string.button_about), false) { showAbout() },
        ))

        // The wiring is set up after every field exists.
        syncCfFields()
        cfCustomSwitch.setOnCheckedChangeListener { _, _ -> syncCfFields() }
        cfWorkerSwitch.setOnCheckedChangeListener { _, _ -> syncCfFields() }
        // Without this the worker Test button stays disabled until the switch is
        // toggled, because only the switch used to trigger a re-check.
        UiKit.onTextChanged(cfWorkerDomainField) { syncCfFields() }
        UiKit.onTextChanged(cfDomainField) { syncCfFields() }

        return rootWithFooter(body)
    }

    /** Scrollable form plus a fixed Save/Cancel footer. */
    private fun rootWithFooter(body: LinearLayout): View {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // Keeps the form below the status bar and the footer above the
            // navigation bar under enforced edge-to-edge.
            fitsSystemWindows = true
        }

        root.addView(
            ScrollView(ctx).apply { addView(body) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ),
        )

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(
                UiKit.themeColor(ctx, com.google.android.material.R.attr.colorSurface)
            )
            setPadding(
                UiKit.dp(ctx, 16f), 0, UiKit.dp(ctx, 16f), UiKit.dp(ctx, 12f)
            )
        }
        footer.addView(
            View(ctx).apply {
                setBackgroundColor(
                    UiKit.themeColor(ctx, com.google.android.material.R.attr.colorOutline)
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 1f)),
        )
        UiKit.buttonRow(footer, ctx, listOf(
            Triple(getString(R.string.button_save), true) { save() },
            Triple(getString(R.string.button_cancel), false) { finish() },
        ))
        root.addView(footer)
        return root
    }

    /** Mirrors the desktop behaviour of disabling inputs behind their checkbox. */
    private fun syncCfFields() {
        val custom = cfCustomSwitch.isChecked
        cfDomainField.isEnabled = custom
        cfDomainField.alpha = if (custom) 1f else 0.5f

        val worker = cfWorkerSwitch.isChecked
        cfWorkerDomainField.isEnabled = worker
        cfWorkerDomainField.alpha = if (worker) 1f else 0.5f

        val workerHasDomains =
            ConfigValidator.parseDomains(cfWorkerDomainField.text.toString()).isNotEmpty()
        val workerReady = worker && workerHasDomains
        cfWorkerTestButton.isEnabled = workerReady
        cfWorkerTestButton.alpha = if (workerReady) 1f else 0.5f
    }

    // ---------------------------------------------------------------- about

    /**
     * Attribution. The original project is MIT licensed, which requires its
     * copyright notice to travel with every copy, so both the credit and the
     * full licence text are available inside the app — plus a direct link to
     * this fork's own repository.
     */
    private fun showAbout() {
        val ctx = this
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // Narrow side padding on purpose: every dp here is label width the
            // two-column button row on a 360 dp screen needs.
            setPadding(
                UiKit.dp(ctx, 8f), UiKit.dp(ctx, 8f),
                UiKit.dp(ctx, 8f), UiKit.dp(ctx, 8f),
            )
        }
        val text = TextView(ctx).apply {
            this.text = getString(R.string.about_text, BuildConfig.VERSION_NAME)
            textSize = 14f
        }
        // A fixed-height scroller keeps the dialog usable on a small screen.
        body.addView(
            ScrollView(ctx).apply { addView(text) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 300f),
            ),
        )

        var dialog: AlertDialog? = null
        UiKit.buttonRow(body, ctx, listOf(
            Triple(getString(R.string.about_original), false) {
                TgLinks.openUrl(ctx, TgLinks.originalUrl())
            },
            Triple(getString(R.string.about_fork), false) {
                TgLinks.openUrl(ctx, TgLinks.forkUrl())
            },
        ))
        UiKit.buttonRow(body, ctx, listOf(
            Triple(getString(R.string.about_license), false) {
                dialog?.dismiss()
                showLicense()
            },
            Triple(getString(R.string.button_close), false) { dialog?.dismiss() },
        ))

        dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.dialog_about_title)
            .setView(body)
            .create()
        dialog.show()
    }

    private fun showLicense() {
        val ctx = this
        // The MIT text is long enough that a plain dialog message gets clipped at
        // the bottom, so it scrolls in a view of its own.
        val text = TextView(ctx).apply {
            this.text = getString(R.string.about_license_text)
            textSize = 14f
        }
        val body = ScrollView(ctx).apply {
            setPadding(
                UiKit.dp(ctx, 20f), UiKit.dp(ctx, 8f), UiKit.dp(ctx, 20f), 0
            )
            addView(text)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.about_license)
            .setView(body)
            .setPositiveButton(R.string.button_close, null)
            .show()
    }

    // ------------------------------------------------- battery optimization
    /**
     * Whether the app is already exempt from Doze / App Standby. This is system
     * state, not a setting of ours, so it is read back rather than stored.
     */
    private fun isBatteryExempt(): Boolean {
        val manager = getSystemService(PowerManager::class.java) ?: return true
        return manager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun syncBatterySwitch() {
        if (!::batterySwitch.isInitialized) return
        suppressBatteryListener = true
        batterySwitch.isChecked = isBatteryExempt()
        suppressBatteryListener = false
    }

    /**
     * Asks the system for the exemption, or opens the list so the user can
     * revoke it — revoking cannot be requested programmatically.
     */
    private fun onBatteryToggled(checked: Boolean) {
        val intent = if (checked) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                UiKit.toast(this, getString(R.string.battery_settings_unavailable))
                syncBatterySwitch()
            }
        }
    }

    // ---------------------------------------------------------------- tests

    private fun setBusy(button: MaterialButton, busy: Boolean) {
        button.isEnabled = !busy
        button.text = getString(if (busy) R.string.button_test_loading else R.string.button_test)
        if (!busy) syncCfFields()
    }

    private fun runCfproxyTest() {
        val secure = !noSecureSwitch.isChecked
        val domains = if (cfCustomSwitch.isChecked) {
            ConfigValidator.parseDomains(cfDomainField.text.toString())
        } else {
            emptyList()
        }
        setBusy(cfTestButton, true)
        Jobs.run({ PythonBridge.testCfproxy(this, domains, secure) }) { result ->
            setBusy(cfTestButton, false)
            val data = result.getOrNull()
            if (data == null) {
                showError(result.exceptionOrNull()?.message ?: "")
                return@run
            }
            showCfproxyResults(data)
        }
    }

    private fun runCfWorkerTest() {
        val domains = ConfigValidator.parseDomains(cfWorkerDomainField.text.toString())
        if (domains.isEmpty()) {
            UiKit.toast(this, getString(R.string.connectivity_no_domains))
            return
        }
        val secure = !noSecureSwitch.isChecked
        setBusy(cfWorkerTestButton, true)
        Jobs.run({ PythonBridge.testCfworker(this, domains, secure) }) { result ->
            setBusy(cfWorkerTestButton, false)
            val data = result.getOrNull()
            if (data == null) {
                showError(result.exceptionOrNull()?.message ?: "")
                return@run
            }
            showMultiResults(
                getString(R.string.connectivity_cfworker_title),
                data.optJSONObject("per_domain") ?: JSONObject(),
                "DC",
            )
        }
    }

    private fun countOk(results: JSONObject): Int {
        var ok = 0
        val keys = results.keys()
        while (keys.hasNext()) {
            if (results.opt(keys.next()) == true) ok++
        }
        return ok
    }

    private fun errorLines(results: JSONObject, prefix: String): String {
        val lines = mutableListOf<String>()
        val keys = results.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = results.opt(key)
            if (value != true) {
                lines.add(
                    getString(
                        R.string.connectivity_error_line,
                        prefix, key.toIntOrNull() ?: 0,
                        StatusText.probeResult(this, value),
                    )
                )
            }
        }
        return lines.joinToString("\n")
    }

    private fun okDcList(results: JSONObject, prefix: String): String {
        val list = mutableListOf<String>()
        val keys = results.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (results.opt(key) == true) list.add("$prefix$key")
        }
        return list.joinToString(", ")
    }

    private fun failDcList(results: JSONObject, prefix: String): String {
        val list = mutableListOf<String>()
        val keys = results.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (results.opt(key) != true) list.add("$prefix$key")
        }
        return list.joinToString(", ")
    }

    /** Ported from `_show_connectivity_results` (single-domain / auto mode). */
    private fun showCfproxyResults(data: JSONObject) {
        val titleBase = getString(R.string.connectivity_cfproxy_title)
        val dialogTitle: String
        val message: String

        if (data.optBoolean("auto")) {
            val best = StatusText.str(data, "best_domain")
            val results = data.optJSONObject("results") ?: JSONObject()
            if (best.isNotEmpty()) {
                dialogTitle = getString(R.string.connectivity_available, titleBase)
                message = getString(
                    R.string.connectivity_auto_ok, titleBase, countOk(results), TEST_DC_COUNT
                )
            } else {
                dialogTitle = getString(R.string.connectivity_unavailable, titleBase)
                message = getString(R.string.connectivity_cf_auto_fail)
            }
        } else {
            val perDomain = data.optJSONObject("per_domain") ?: JSONObject()
            if (perDomain.length() > 1) {
                showMultiResults(titleBase, perDomain, "kws")
                return
            }
            val domain = perDomain.keys().asSequence().firstOrNull() ?: ""
            val results = perDomain.optJSONObject(domain) ?: JSONObject()
            val ok = countOk(results)
            when {
                ok == TEST_DC_COUNT -> {
                    dialogTitle = getString(R.string.connectivity_all_ok, titleBase)
                    message = getString(
                        R.string.connectivity_all_ok_domain, TEST_DC_COUNT, domain
                    )
                }
                ok == 0 -> {
                    dialogTitle = getString(R.string.connectivity_unavailable, titleBase)
                    message = getString(
                        R.string.connectivity_none_ok, domain, errorLines(results, "kws")
                    )
                }
                else -> {
                    dialogTitle = getString(R.string.connectivity_partial, titleBase)
                    message = getString(
                        R.string.connectivity_partial_detail,
                        domain, okDcList(results, "kws"), errorLines(results, "kws"),
                    )
                }
            }
        }
        showDialog(dialogTitle, message)
    }

    /** Ported from `_show_multi_connectivity_results`. */
    private fun showMultiResults(titleBase: String, perDomain: JSONObject, prefix: String) {
        var allOk = true
        var anyOk = false
        val blocks = mutableListOf<String>()

        val domains = perDomain.keys()
        while (domains.hasNext()) {
            val domain = domains.next()
            val results = perDomain.optJSONObject(domain) ?: JSONObject()
            val ok = countOk(results)
            when {
                ok == TEST_DC_COUNT -> {
                    anyOk = true
                    blocks.add(
                        getString(R.string.connectivity_multi_all_ok, domain, TEST_DC_COUNT)
                    )
                }
                ok == 0 -> {
                    allOk = false
                    blocks.add(getString(R.string.connectivity_multi_fail, domain))
                }
                else -> {
                    allOk = false
                    anyOk = true
                    blocks.add(
                        getString(
                            R.string.connectivity_multi_partial,
                            domain, okDcList(results, prefix), failDcList(results, prefix),
                        )
                    )
                }
            }
        }

        val dialogTitle = when {
            allOk && blocks.isNotEmpty() -> getString(R.string.connectivity_all_ok, titleBase)
            anyOk -> getString(R.string.connectivity_partial, titleBase)
            else -> getString(R.string.connectivity_unavailable, titleBase)
        }
        showDialog(dialogTitle, blocks.joinToString("\n\n"))
    }

    private fun showDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.button_close, null)
            .show()
    }

    private fun showError(detail: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.error_start_title)
            .setMessage(getString(R.string.error_unknown, detail))
            .setPositiveButton(R.string.button_ok, null)
            .show()
    }

    private fun showValidation(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.validation_title)
            .setMessage(message)
            .setPositiveButton(R.string.button_ok, null)
            .show()
    }

    // ---------------------------------------------------------------- save

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun save() {
        val previousLanguage = cfg.language
        val host = hostField.text.toString().trim()
        if (!ConfigValidator.isValidHost(host)) {
            showValidation(getString(R.string.validation_bad_host)); return
        }
        val port = ConfigValidator.parsePort(portField.text.toString())
        if (port == null) {
            showValidation(getString(R.string.validation_bad_port)); return
        }
        val secret = secretField.text.toString().trim()
        if (secret.length != 32) {
            showValidation(getString(R.string.validation_bad_secret_len)); return
        }
        if (!ConfigValidator.isValidSecret(secret)) {
            showValidation(getString(R.string.validation_bad_secret_hex)); return
        }

        val dcLines = dcField.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        ConfigValidator.badDcEntry(dcLines)?.let { entry ->
            val message = if (!entry.contains(':')) {
                getString(R.string.validation_dc_format, entry)
            } else {
                getString(R.string.validation_dc_invalid, entry)
            }
            showValidation(message)
            return
        }

        val language = UiPrefs.languageLabels()
            .getOrNull(languageSpinner.selectedItemPosition)?.first ?: cfg.language
        val appearance = UiPrefs.appearanceKeys()
            .getOrNull(themeSpinner.selectedItemPosition) ?: cfg.appearance

        val updated = cfg.copy(
            host = host,
            port = port,
            secret = secret,
            dcIp = dcLines.toMutableList(),
            verbose = verboseSwitch.isChecked,
            // Desktop `merge_adv_from_form` falls back to the default when a
            // numeric field cannot be parsed.
            bufKb = bufField.text.toString().trim().toIntOrNull() ?: DEFAULT_BUF_KB,
            poolSize = poolField.text.toString().trim().toIntOrNull() ?: DEFAULT_POOL_SIZE,
            logMaxMb = logMbField.text.toString().trim().toDoubleOrNull() ?: DEFAULT_LOG_MB,
            cfproxy = cfproxySwitch.isChecked,
            h2 = h2Switch.isChecked,
            cfproxyUserDomainEnabled = cfCustomSwitch.isChecked,
            cfproxyUserDomain = ConfigValidator.parseDomains(cfDomainField.text.toString()),
            cfproxyWorkerEnabled = cfWorkerSwitch.isChecked,
            cfproxyWorkerDomain = ConfigValidator.parseDomains(cfWorkerDomainField.text.toString()),
            forceTestDc = forceTestDcSwitch.isChecked,
            noSecure = noSecureSwitch.isChecked,
            fakeTlsDomain = fakeTlsField.text.toString().trim(),
            autostart = autostartSwitch.isChecked,
            appearance = appearance,
            language = language,
        )

        ConfigStore.save(this, updated)
        cfg = updated

        // Theme and language changes recreate the activity, so they are applied
        // only once the restart prompt has been answered — otherwise the dialog
        // would be torn down underneath the user.
        fun applyPrefs() {
            UiPrefs.applyAppearance(appearance)
            if (language != previousLanguage) {
                UiPrefs.applyLanguage(language)
            }
        }

        if (ProxyService.active) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_restart_title)
                .setMessage(R.string.dialog_restart_body)
                .setPositiveButton(R.string.button_restart) { _, _ ->
                    applyPrefs()
                    ProxyService.restart(this)
                    finish()
                }
                .setNegativeButton(R.string.button_cancel) { _, _ ->
                    applyPrefs()
                    finish()
                }
                .show()
        } else {
            applyPrefs()
            finish()
        }
    }
}
