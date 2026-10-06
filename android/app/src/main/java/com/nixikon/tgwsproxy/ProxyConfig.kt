package com.nixikon.tgwsproxy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.Locale

/**
 * Application settings.
 *
 * Field set and defaults mirror the desktop build's `utils/default_config.py`
 * so a configuration written by either front end means the same thing, and so
 * the proxy core receives exactly the keys it expects (the JSON key names in
 * [toJson] must stay in sync with `android_entry._apply_config`).
 */
data class ProxyConfig(
    var host: String = DEFAULT_HOST,
    var port: Int = DEFAULT_PORT,
    var secret: String = randomSecret(),
    var dcIp: MutableList<String> = mutableListOf("2:149.154.167.220", "4:149.154.167.220"),
    var verbose: Boolean = false,
    var logMaxMb: Double = 5.0,
    var bufKb: Int = 256,
    var poolSize: Int = 4,
    var cfproxy: Boolean = true,
    var cfproxyUserDomainEnabled: Boolean = false,
    var cfproxyUserDomain: MutableList<String> = mutableListOf(),
    var cfproxyWorkerEnabled: Boolean = false,
    var cfproxyWorkerDomain: MutableList<String> = mutableListOf(),
    /**
     * Upstream 1.11.0: multiplex media downloads over one HTTP/2 connection.
     * Upstream 1.11.1 fixed the 404 handling that made media downloads stall.
     */
    var h2: Boolean = true,
    var forceTestDc: Boolean = false,
    var noSecure: Boolean = false,
    var fakeTlsDomain: String = "",
    var autostart: Boolean = false,
    var appearance: String = "auto",
    var language: String = defaultLanguage(),
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("host", host)
        o.put("port", port)
        o.put("secret", secret)
        o.put("dc_ip", JSONArray(dcIp))
        o.put("verbose", verbose)
        o.put("log_max_mb", logMaxMb)
        o.put("buf_kb", bufKb)
        o.put("pool_size", poolSize)
        o.put("cfproxy", cfproxy)
        o.put("h2", h2)
        o.put("cfproxy_user_domain_enabled", cfproxyUserDomainEnabled)
        o.put("cfproxy_user_domain", JSONArray(cfproxyUserDomain))
        o.put("cfproxy_worker_enabled", cfproxyWorkerEnabled)
        o.put("cfproxy_worker_domain", JSONArray(cfproxyWorkerDomain))
        o.put("force_test_dc", forceTestDc)
        o.put("no_secure", noSecure)
        o.put("fake_tls_domain", fakeTlsDomain)
        o.put("autostart", autostart)
        o.put("appearance", appearance)
        o.put("language", language)
        return o
    }

    fun copy(): ProxyConfig = fromJson(toJson())

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 1443

        private val rng = SecureRandom()

        fun randomSecret(): String {
            val bytes = ByteArray(16)
            rng.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Mirrors `ui/i18n.py:detect_system_language` (catalogs: ru, en; default en). */
        fun defaultLanguage(): String {
            val tag = Locale.getDefault().language.lowercase(Locale.ROOT)
            return if (tag == "ru") "ru" else "en"
        }

        fun fromJson(o: JSONObject): ProxyConfig {
            val d = ProxyConfig()
            return ProxyConfig(
                host = o.optString("host", d.host),
                port = o.optInt("port", d.port),
                secret = o.optString("secret", d.secret),
                dcIp = o.optJSONArray("dc_ip").toStringList().ifEmpty { d.dcIp },
                verbose = o.optBoolean("verbose", d.verbose),
                logMaxMb = o.optDouble("log_max_mb", d.logMaxMb),
                bufKb = o.optInt("buf_kb", d.bufKb),
                poolSize = o.optInt("pool_size", d.poolSize),
                cfproxy = o.optBoolean("cfproxy", d.cfproxy),
                h2 = o.optBoolean("h2", d.h2),
                cfproxyUserDomainEnabled = o.optBoolean("cfproxy_user_domain_enabled", d.cfproxyUserDomainEnabled),
                cfproxyUserDomain = o.optJSONArray("cfproxy_user_domain").toStringList(),
                cfproxyWorkerEnabled = o.optBoolean("cfproxy_worker_enabled", d.cfproxyWorkerEnabled),
                cfproxyWorkerDomain = o.optJSONArray("cfproxy_worker_domain").toStringList(),
                forceTestDc = o.optBoolean("force_test_dc", d.forceTestDc),
                noSecure = o.optBoolean("no_secure", d.noSecure),
                fakeTlsDomain = o.optString("fake_tls_domain", d.fakeTlsDomain),
                autostart = o.optBoolean("autostart", d.autostart),
                appearance = o.optString("appearance", d.appearance),
                language = o.optString("language", d.language),
            )
        }
    }
}

private fun JSONArray?.toStringList(): MutableList<String> {
    if (this == null) return mutableListOf()
    val out = mutableListOf<String>()
    for (i in 0 until length()) {
        val v = optString(i, "")
        if (v.isNotEmpty()) out.add(v)
    }
    return out
}

/** Reads and writes `config.json` in the app's private storage. */
object ConfigStore {
    private const val FILE_NAME = "config.json"

    fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    fun load(ctx: Context): ProxyConfig {
        val f = file(ctx)
        if (f.isFile) {
            return try {
                ProxyConfig.fromJson(JSONObject(f.readText(Charsets.UTF_8)))
            } catch (e: Exception) {
                ProxyConfig()
            }
        }
        // First run: persist the defaults immediately. Without this every call
        // would build a fresh config with a new random secret, so the link
        // shown in the UI would not match the secret the proxy actually uses,
        // and the secret would change on every restart.
        val cfg = ProxyConfig()
        save(ctx, cfg)
        return cfg
    }

    fun save(ctx: Context, cfg: ProxyConfig) {
        try {
            file(ctx).writeText(cfg.toJson().toString(2), Charsets.UTF_8)
        } catch (_: Exception) {
            // Nothing actionable: the caller surfaces config problems elsewhere.
        }
    }
}

/** Field validation, mirroring the desktop settings dialog. */
object ConfigValidator {
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val HEX32 = Regex("^[0-9a-fA-F]{32}$")

    fun isValidIpv4(value: String): Boolean {
        val m = IPV4.matchEntire(value.trim()) ?: return false
        return m.groupValues.drop(1).all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }

    /** Accepts an IPv4 literal (as the desktop build did) or an IPv6 literal. */
    fun isValidHost(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        if (isValidIpv4(v)) return true
        if (!v.contains(':')) return false
        return try {
            java.net.InetAddress.getByName(v)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun parsePort(value: String): Int? {
        val p = value.trim().toIntOrNull() ?: return null
        return if (p in 1..65535) p else null
    }

    fun isValidSecret(value: String): Boolean = HEX32.matches(value.trim())

    /** `null` when every line is `DC:IP`, otherwise the offending entry. */
    fun badDcEntry(lines: List<String>): String? {
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx < 0) return line
            val dc = line.substring(0, idx).trim().toIntOrNull() ?: return line
            if (dc <= 0) return line
            if (!isValidIpv4(line.substring(idx + 1).trim())) return line
        }
        return null
    }

    /** Splits a user-typed domain list the way `coerce_domain_list` does. */
    fun parseDomains(value: String): MutableList<String> {
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        for (raw in value.split(',', ';', ' ', '\n', '\t', '\r')) {
            val item = raw.trim()
            if (item.isEmpty()) continue
            val key = item.lowercase(Locale.ROOT)
            if (seen.add(key)) out.add(item)
        }
        return out
    }
}
