package com.nixikon.tgwsproxy

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.RandomAccessFile

/**
 * In-app log viewer, replacing the desktop "Open logs" action (which handed the
 * file to the system editor). Only the tail is read so a full rotation-sized
 * file never has to be loaded into memory.
 */
class LogsActivity : AppCompatActivity() {

    companion object {
        private const val TAIL_BYTES = 256 * 1024
    }

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        load()
    }

    private fun buildLayout(): android.view.View {
        val ctx = this
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(UiKit.dp(ctx, 16f), UiKit.dp(ctx, 12f), UiKit.dp(ctx, 16f), UiKit.dp(ctx, 12f))
        }
        body.addView(UiKit.label(ctx, getString(R.string.logs_title), 22f, bold = true))

        UiKit.buttonRow(body, ctx, listOf(
            Triple(getString(R.string.button_refresh), true) { load() },
            Triple(getString(R.string.button_share), false) { share() },
        ))

        logView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextColor(UiKit.themeColor(ctx, com.google.android.material.R.attr.colorOnSurface))
        }
        val scroll = ScrollView(ctx).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = UiKit.dp(ctx, 8f) }
        }
        body.addView(scroll)
        return body
    }

    private fun logFile(): File = File(filesDir, "proxy.log")

    private fun tail(file: File): String {
        if (!file.isFile) return ""
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val start = if (length > TAIL_BYTES) length - TAIL_BYTES else 0
                raf.seek(start)
                val buffer = ByteArray((length - start).toInt())
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun load() {
        val text = tail(logFile())
        logView.text = text.ifEmpty { getString(R.string.logs_empty) }
    }

    private fun share() {
        val text = tail(logFile())
        if (text.isEmpty()) {
            UiKit.toast(this, getString(R.string.logs_empty))
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.logs_share_title))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.logs_share_title)))
    }
}
