package com.nixikon.tgwsproxy

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.chaquo.python.android.PyApplication

/**
 * Extends Chaquopy's [PyApplication] so that `Python.start()` happens exactly
 * once, on the main thread, as the Chaquopy documentation requires.
 */
class App : PyApplication() {

    override fun onCreate() {
        try {
            // Starts the Python runtime on the main thread, as Chaquopy requires.
            super.onCreate()
        } catch (t: Throwable) {
            // Keep the app alive so the UI can report the reason instead of
            // dying at launch with an opaque stack trace.
            PythonBridge.recordRuntimeFailure(t)
        }

        Notifications.createChannel(this)

        val cfg = ConfigStore.load(this)
        UiPrefs.applyAppearance(cfg.appearance)
        UiPrefs.applyLanguage(cfg.language)

        // Import the bridge module off the main thread so the first user action
        // does not pay for it. A failure is kept in PythonBridge.initError and
        // surfaced by the UI instead of disappearing.
        Jobs.run({
            PythonBridge.ensureInit(this)
            true
        }) { }
    }
}

/** Theme and per-app language, both stored in the app config. */
object UiPrefs {

    fun applyAppearance(mode: String) {
        val target = when (mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }

    fun applyLanguage(language: String) {
        val tag = if (language == "ru") "ru" else "en"
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.isEmpty || current.toLanguageTags() != tag) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(tag)
            )
        }
    }

    /** Display labels for the language selector, in the same order as the desktop UI. */
    fun languageLabels(): List<Pair<String, String>> = listOf(
        "ru" to "Русский",
        "en" to "English",
    )

    fun appearanceKeys(): List<String> = listOf("auto", "light", "dark")
}
