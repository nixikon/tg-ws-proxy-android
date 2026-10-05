package com.nixikon.tgwsproxy

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** Shared helpers for the Telegram deep link, clipboard and status text. */
object TgLinks {

    private const val DOCS_BASE =
        "https://github.com/Flowseal/tg-ws-proxy/blob/main/docs"

    private const val ORIGINAL_REPO = "https://github.com/Flowseal/tg-ws-proxy"

    /** Used only if no release repository was configured at build time. */
    private const val FALLBACK_FORK_REPO = "nixikon/tg-ws-proxy-android"

    /** Mirrors `ui/ctk_tray_ui.py:_get_doc_url` (language-specific docs folder). */
    fun docUrl(language: String, doc: String): String {
        val folder = if (language == "ru") "RU" else "EN"
        return "$DOCS_BASE/$folder/$doc.md"
    }

    /** The upstream project by Flowseal. */
    fun originalUrl(): String = ORIGINAL_REPO

    /**
     * This fork's own repository. The release source doubles as its home page,
     * so the link follows `tgws.updateRepo` instead of a second hard-coded name.
     */
    fun forkUrl(): String {
        val repo = BuildConfig.UPDATE_REPO.ifBlank { FALLBACK_FORK_REPO }
        return "https://github.com/$repo"
    }

    fun copyToClipboard(ctx: Context, text: String): Boolean = try {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("tg://proxy", text))
        true
    } catch (_: Exception) {
        false
    }

    /** Opens `tg://` in Telegram; [onFail] receives the reason when no handler exists. */
    fun openInTelegram(activity: Activity, link: String, onFail: (String) -> Unit) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        } catch (e: ActivityNotFoundException) {
            onFail(e.message ?: "")
        } catch (e: Exception) {
            onFail(e.message ?: "")
        }
    }

    fun openUrl(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            UiKit.toast(activity, url)
        }
    }
}

/** Appends Kotlin-side failures to the same file the Python logger writes. */
object AppLog {
    private val format = java.text.SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
    )

    fun append(ctx: Context, tag: String, message: String) {
        try {
            val file = java.io.File(ctx.filesDir, "proxy.log")
            file.appendText(
                "${format.format(java.util.Date())}  ERROR  android/$tag  $message\n",
                Charsets.UTF_8,
            )
        } catch (_: Exception) {
            // Logging must never be the reason something fails.
        }
    }
}

/** Localisation of the machine-readable results the Python side returns. */
object StatusText {

    /** `org.json` turns JSON null into the string "null"; normalise that away. */
    fun str(obj: JSONObject, key: String): String {
        if (obj.isNull(key)) return ""
        val value = obj.optString(key, "")
        return if (value == "null") "" else value
    }

    fun bool(obj: JSONObject, key: String, fallback: Boolean = false): Boolean =
        if (obj.isNull(key)) fallback else obj.optBoolean(key, fallback)

    /** Ported from `utils/diagnostics.py` (which returns translated strings there). */
    fun startError(ctx: Context, code: String, detail: String): String = when (code) {
        "port_busy" -> ctx.getString(R.string.diagnostics_port_busy)
        "permission" -> ctx.getString(R.string.diagnostics_permission)
        "bad_address" -> ctx.getString(R.string.diagnostics_bad_address)
        "dc_config" -> ctx.getString(R.string.error_dc_config)
        // The system refused the service start for a reason other than the
        // background restriction (that one never reaches the UI). The raw
        // exception text is in the log; the dialog explains what to do.
        "start_rejected" -> ctx.getString(R.string.error_start_rejected)
        "timeout", "stop_timeout", "stopping" ->
            ctx.getString(R.string.error_unknown, "$code $detail".trim())
        else -> ctx.getString(R.string.error_unknown, detail.ifEmpty { code })
    }

    /** Result token -> user-facing text for connectivity probes. */
    fun probeResult(ctx: Context, value: Any?): String = when (value) {
        null -> ctx.getString(R.string.connectivity_no_response)
        is Boolean -> if (value) "OK" else ctx.getString(R.string.connectivity_no_response)
        else -> {
            val text = value.toString()
            when (text) {
                "timeout" -> ctx.getString(R.string.connectivity_timeout)
                "no_response" -> ctx.getString(R.string.connectivity_no_response)
                else -> text
            }
        }
    }
}
