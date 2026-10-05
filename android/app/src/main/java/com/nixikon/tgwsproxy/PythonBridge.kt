package com.nixikon.tgwsproxy

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper over the `android_entry` Python module.
 *
 * Every method blocks until Python returns, so callers must use [Jobs.run] to
 * stay off the main thread. The Python runtime is started lazily and exactly
 * once; the lock in [ensureInit] makes concurrent first calls safe.
 */
object PythonBridge {

    private val initLock = Any()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var module: PyObject? = null

    @Volatile
    private var initFailure: String? = null

    @Volatile
    var appVersion: String = ""
        private set

    val isReady: Boolean get() = module != null

    /** Set when the bridge module could not be imported; shown by the UI. */
    val initError: String? get() = initFailure

    /** Records a failure from `PyApplication.onCreate` starting the runtime. */
    fun recordRuntimeFailure(t: Throwable) {
        initFailure = "Python runtime failed to start: ${t.message ?: t.toString()}"
    }

    fun ensureInit(app: Context) {
        if (module != null) return
        synchronized(initLock) {
            if (module != null) return
            try {
                startRuntime(app)
                val m = Python.getInstance().getModule("android_entry")
                val result = JSONObject(
                    m.callAttr(
                        "init",
                        app.filesDir.absolutePath,
                        extractCaBundle(app),
                    ).toString()
                )
                appVersion = result.optString("version", "")
                module = m
                initFailure = null
            } catch (t: Throwable) {
                initFailure = t.message ?: t.toString()
                throw IllegalStateException("Python init failed: $initFailure", t)
            }
        }
    }

    /**
     * Chaquopy wants `Python.start()` to run exactly once per process, and the
     * documented location is `Application.onCreate()` on the main thread.
     * [App] extends `PyApplication`, so this is normally a no-op; the fallback
     * still hops to the main thread in case something reached us first.
     */
    private fun startRuntime(app: Context) {
        if (Python.isStarted()) return
        val context = app.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Python.start(AndroidPlatform(context))
            return
        }
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        main.post {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(context))
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(60, TimeUnit.SECONDS)) {
            throw IllegalStateException("timed out starting the Python runtime")
        }
        failure?.let { throw it }
    }

    /** Platform self-check; see `android_entry.selftest` for what it covers. */
    fun selftest(app: Context): JSONObject =
        JSONObject(mod(app).callAttr("selftest").toString())

    /**
     * Copies the CA bundle out of the APK so Python has a stable file path for
     * TLS verification. Chaquopy may flatten data files in the asset tree, so
     * several layouts are probed; an empty result simply falls back to the
     * platform trust store inside `proxy.utils.create_ssl_context`.
     */
    private fun extractCaBundle(app: Context): String {
        val target = java.io.File(app.filesDir, "cacert.pem")
        if (target.isFile && target.length() > 0L) return target.absolutePath
        for (asset in listOf(
            "chaquopy/certs/cacert.pem",
            "chaquopy/cacert.pem",
            "certs/cacert.pem",
        )) {
            try {
                app.assets.open(asset).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.length() > 0L) return target.absolutePath
            } catch (_: java.io.IOException) {
                // Try the next candidate layout.
            }
        }
        return ""
    }

    private fun mod(app: Context): PyObject {
        ensureInit(app)
        return module ?: throw IllegalStateException("Python not initialised")
    }

    fun configure(app: Context, cfg: ProxyConfig): JSONObject =
        JSONObject(mod(app).callAttr("configure", cfg.toJson().toString()).toString())

    fun start(app: Context): JSONObject =
        JSONObject(mod(app).callAttr("start").toString())

    fun stop(app: Context): JSONObject =
        JSONObject(mod(app).callAttr("stop").toString())

    fun restart(app: Context): JSONObject =
        JSONObject(mod(app).callAttr("restart").toString())

    fun state(app: Context): JSONObject =
        JSONObject(mod(app).callAttr("state").toString())

    fun statsSummary(app: Context): String =
        mod(app).callAttr("stats_summary").toString()

    /**
     * Passes the saved config so the link matches the settings that will be
     * used; without it Python would report the secret it generated at import.
     */
    fun proxyLink(app: Context): String =
        mod(app).callAttr(
            "proxy_link", ConfigStore.load(app).toJson().toString()
        ).toString()

    fun testCfproxy(app: Context, domains: List<String>, secure: Boolean): JSONObject {
        val json = org.json.JSONArray(domains).toString()
        return JSONObject(mod(app).callAttr("test_cfproxy", json, secure).toString())
    }

    fun testCfworker(app: Context, domains: List<String>, secure: Boolean): JSONObject {
        val json = org.json.JSONArray(domains).toString()
        return JSONObject(mod(app).callAttr("test_cfworker", json, secure).toString())
    }
}

/** Runs blocking work on a small pool and delivers the result on the UI thread. */
object Jobs {
    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "py-call").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, then: (Result<T>) -> Unit) {
        pool.execute {
            val result = try {
                Result.success(work())
            } catch (t: Throwable) {
                Result.failure(t)
            }
            main.post { then(result) }
        }
    }
}
