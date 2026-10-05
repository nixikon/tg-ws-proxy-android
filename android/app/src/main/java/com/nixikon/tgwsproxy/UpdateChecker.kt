package com.nixikon.tgwsproxy

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Checks a GitHub release for a newer APK and downloads it.
 *
 * Two sources are used, in order:
 *
 *  1. the REST API (`releases/latest`), which lists assets with sizes and
 *     SHA-256 digests, so the download can be verified;
 *  2. the public releases feed (`releases.atom`) as a fallback. The
 *     unauthenticated API allows only 60 requests per hour per address, and that
 *     limit is shared by everyone behind the same NAT, so the check must not
 *     depend on it. The feed is not rate limited, but it carries no asset list —
 *     the asset name therefore follows the convention the release script uses,
 *     `TgWsProxy-<version>-android.apk`.
 *
 * The repository comes from `BuildConfig` (`tgws.updateRepo` in
 * gradle.properties). A token is only needed for a private repository; a public
 * one needs no credentials at all.
 */
object UpdateChecker {

    data class Release(
        val version: String,
        val notes: String,
        val assetName: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    private const val API = "https://api.github.com/repos"
    private const val WEB = "https://github.com"

    fun isConfigured(): Boolean = BuildConfig.UPDATE_REPO.isNotBlank()

    /** Repository as configured at build time, for diagnostics. */
    fun source(): String = BuildConfig.UPDATE_REPO.ifEmpty { "(not configured)" }

    /** Returns the newer release, or null when the running version is current. */
    fun check(currentVersion: String): Release? {
        var apiError: Throwable? = null
        try {
            return checkViaApi(currentVersion)
        } catch (t: Throwable) {
            apiError = t
        }
        try {
            return checkViaFeed(currentVersion)
        } catch (t: Throwable) {
            throw IllegalStateException(
                "API: ${apiError?.message ?: "?"}; feed: ${t.message ?: "?"}"
            )
        }
    }

    // ------------------------------------------------------------- sources

    private fun checkViaApi(currentVersion: String): Release? {
        val json = JSONObject(get("$API/${BuildConfig.UPDATE_REPO}/releases/latest"))
        val version = json.optString("tag_name").trim().trimStart('v', 'V')
        if (version.isEmpty()) return null
        if (compareVersions(version, currentVersion) <= 0) return null

        val assets = json.optJSONArray("assets") ?: return null
        var best: Release? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue

            val candidate = Release(
                version = version,
                notes = json.optString("body").trim(),
                assetName = name,
                downloadUrl = "$API/${BuildConfig.UPDATE_REPO}/releases/assets/${asset.optLong("id")}",
                sizeBytes = asset.optLong("size"),
                sha256 = asset.optString("digest").removePrefix("sha256:"),
            )
            // Prefer the Android build when several APKs are attached.
            if (best == null || name.contains("android", ignoreCase = true)) best = candidate
            if (name.contains("android", ignoreCase = true)) break
        }
        return best
    }

    private fun checkViaFeed(currentVersion: String): Release? {
        val xml = get("$WEB/${BuildConfig.UPDATE_REPO}/releases.atom")
        val entry = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: return null
        val rawTag = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(entry)?.groupValues?.get(1) ?: return null

        val tag = unescape(rawTag).trim()
        val version = tag.trimStart('v', 'V')
        if (version.isEmpty()) return null
        if (compareVersions(version, currentVersion) <= 0) return null

        val notes = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
            .find(entry)?.groupValues?.get(1)
            ?.let { stripHtml(unescape(it)) }?.trim().orEmpty()

        val assetName = "TgWsProxy-$version-android.apk"
        return Release(
            version = version,
            notes = notes.take(2000),
            assetName = assetName,
            downloadUrl = "$WEB/${BuildConfig.UPDATE_REPO}/releases/download/$tag/$assetName",
            sizeBytes = 0,
            sha256 = "",
        )
    }

    // -------------------------------------------------------------- caching

    private const val PREFS = "tgws_update"
    private const val KEY_RELEASE = "cached_release"
    private const val KEY_NOTIFIED = "notified_version"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Remembers the last release seen, so the next launch can report it the
     * moment the screen appears instead of waiting for the network — and so a
     * notification can offer the download without re-checking first.
     */
    fun cache(ctx: Context, release: Release) {
        val json = JSONObject().apply {
            put("version", release.version)
            put("notes", release.notes)
            put("assetName", release.assetName)
            put("downloadUrl", release.downloadUrl)
            put("sizeBytes", release.sizeBytes)
            put("sha256", release.sha256)
        }
        prefs(ctx).edit().putString(KEY_RELEASE, json.toString()).apply()
    }

    /** Known release, if any. Still has to be compared with the running version. */
    fun cached(ctx: Context): Release? {
        val raw = prefs(ctx).getString(KEY_RELEASE, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val version = json.optString("version")
            val url = json.optString("downloadUrl")
            if (version.isEmpty() || url.isEmpty()) return null
            Release(
                version = version,
                notes = json.optString("notes"),
                assetName = json.optString("assetName"),
                downloadUrl = url,
                sizeBytes = json.optLong("sizeBytes"),
                sha256 = json.optString("sha256"),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Forgets everything: used when a check proves the running build is current. */
    fun clearCache(ctx: Context) {
        prefs(ctx).edit().remove(KEY_RELEASE).remove(KEY_NOTIFIED).apply()
    }

    /** True when this version has already been announced by a notification. */
    fun wasNotified(ctx: Context, version: String): Boolean =
        prefs(ctx).getString(KEY_NOTIFIED, null) == version

    fun markNotified(ctx: Context, version: String) {
        prefs(ctx).edit().putString(KEY_NOTIFIED, version).apply()
    }

    // ------------------------------------------------------------ download

    /** Downloads [release] to [target], reporting 0..100 progress. */
    fun download(release: Release, target: File, onProgress: (Int) -> Unit) {
        val viaApiAsset = release.downloadUrl.contains("/releases/assets/")
        val connection = open(release.downloadUrl).apply {
            setRequestProperty(
                "Accept", if (viaApiAsset) "application/octet-stream" else "*/*"
            )
        }
        connection.connect()

        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("HTTP $code while downloading ${release.assetName}")
        }

        val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
        connection.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                var lastPercent = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total > 0) {
                        val percent = ((copied * 100) / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        }
        connection.disconnect()

        if (release.sha256.isNotEmpty()) {
            val actual = sha256(target)
            if (!actual.equals(release.sha256, ignoreCase = true)) {
                target.delete()
                throw IllegalStateException(
                    "SHA-256 mismatch: expected ${release.sha256}, got $actual"
                )
            }
        }
    }

    // ------------------------------------------------------------ versions

    // A leading "v" is tolerated so callers may pass either a tag or a version.
    private val VERSION_RE =
        Regex("^[vV]?(\\d+(?:\\.\\d+)*)(?:[-_.]?([A-Za-z]+)(\\d+)?)?$")

    /**
     * Compares version strings such as `1.11.0-a2`.
     *
     * Numeric segments are compared first. When the numeric part is equal a
     * labelled build is older than the plain release, so
     * `1.11.0-a1 < 1.11.0-a2 < 1.11.0`.
     */
    fun compareVersions(a: String, b: String): Int {
        val left = VERSION_RE.find(a.trim())
        val right = VERSION_RE.find(b.trim())
        if (left == null || right == null) return a.trim().compareTo(b.trim())

        val leftBase = left.groupValues[1].split('.').map { it.toIntOrNull() ?: 0 }
        val rightBase = right.groupValues[1].split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(leftBase.size, rightBase.size)) {
            val x = leftBase.getOrElse(i) { 0 }
            val y = rightBase.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }

        val leftLabel = left.groupValues[2]
        val rightLabel = right.groupValues[2]
        if (leftLabel.isEmpty() && rightLabel.isEmpty()) return 0
        if (leftLabel.isEmpty()) return 1
        if (rightLabel.isEmpty()) return -1

        val byLabel = leftLabel.compareTo(rightLabel, ignoreCase = true)
        if (byLabel != 0) return if (byLabel > 0) 1 else -1
        val leftNumber = left.groupValues[3].toIntOrNull() ?: 0
        val rightNumber = right.groupValues[3].toIntOrNull() ?: 0
        return leftNumber.compareTo(rightNumber)
    }

    // ------------------------------------------------------------- helpers

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "tg-ws-proxy-android")
        if (BuildConfig.UPDATE_TOKEN.isNotEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.UPDATE_TOKEN}")
        }
        return connection
    }

    private fun get(url: String): String {
        val connection = open(url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream
                    ?.bufferedReader()?.readText().orEmpty().take(200)
                throw IllegalStateException(
                    "HTTP $code${if (detail.isNotEmpty()) ": $detail" else ""}"
                )
            }
            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun stripHtml(text: String): String = text
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\n{3,}"), "\n\n")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
