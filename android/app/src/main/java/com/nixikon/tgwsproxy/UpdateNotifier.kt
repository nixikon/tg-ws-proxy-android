package com.nixikon.tgwsproxy

import android.content.Context

/**
 * The update check for the case where the app has no activity of its own on
 * screen.
 *
 * `MainActivity` offers a new release in a dialog, which only works when the
 * app is open. The proxy can also be started without opening the app at all —
 * from the Quick Settings tile, or by `BOOT_COMPLETED` autostart — so the check
 * runs from [ProxyService] as well and reports through a notification with a
 * *Download* action instead.
 *
 * Two details avoid being a nuisance:
 *
 *  * nothing is posted while one of our activities is visible, because the
 *    dialog is the better place for it;
 *  * each version is announced once — the version that was announced is stored,
 *    so restarting the proxy does not re-post the same notification.
 */
object UpdateNotifier {

    /** Notes longer than this are trimmed; the full text is in the dialog. */
    private const val NOTES_LIMIT = 400

    /**
     * Checks the release source in the background and posts the notification
     * when a newer build exists, the app is not visible and this version has not
     * been announced yet.
     */
    fun refresh(ctx: Context, force: Boolean = false) {
        if (!UpdateChecker.isConfigured()) return
        // The in-app dialog already covers the visible case.
        if (MainActivity.visible && !force) return

        val current = BuildConfig.VERSION_NAME
        Jobs.run({ UpdateChecker.check(current) }) { result ->
            val release = result.getOrNull()
            if (release == null) {
                if (result.isSuccess) {
                    // The running build is current: drop stale cache data.
                    UpdateChecker.clearCache(ctx)
                    Notifications.cancelUpdate(ctx)
                } else {
                    AppLog.append(
                        ctx, "update",
                        "background check failed: ${result.exceptionOrNull()?.message}",
                    )
                }
                return@run
            }

            UpdateChecker.cache(ctx, release)
            AppLog.append(
                ctx, "update", "background check: version ${release.version} is available"
            )
            if (MainActivity.visible) return@run
            if (!force && UpdateChecker.wasNotified(ctx, release.version)) return@run

            UpdateChecker.markNotified(ctx, release.version)
            Notifications.showUpdate(
                ctx,
                release.version,
                current,
                release.notes.take(NOTES_LIMIT),
            )
        }
    }
}
