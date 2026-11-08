package com.discord.evs.generator.automation

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Network IP rotation for EVS.
 *
 * Modes (tried in order when enabled):
 *  1) **On-device** — mobile data toggle / Wi‑Fi bounce / root airplane (works on phone)
 *  2) **PC companion** — HTTP to host running adb/mullvad CLI (prefs.pcRotateHost)
 *  3) **Local ProcessBuilder** — adb/mullvad on PATH (rare on phone, works if present)
 *
 * Failures are soft; generation continues.
 */
object NetworkRotator {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /**
     * Full rotate used by AccountGenerator before a gen attempt.
     */
    suspend fun rotateBeforeGen(
        context: Context,
        adbEnabled: Boolean,
        adbSerial: String,
        mullvadEnabled: Boolean,
        mullvadAccount: String,
        companionHost: String,
        companionPort: Int,
        companionToken: String,
        onLog: (String) -> Unit
    ) {
        if (adbEnabled) {
            val ok = rotateIp(
                context = context,
                serial = adbSerial,
                companionHost = companionHost,
                companionPort = companionPort,
                companionToken = companionToken,
                onLog = onLog
            )
            onLog(if (ok) "✓ IP rotate OK" else "⚠ IP rotate soft-fail (continuing)")
            delay(1500)
        }
        if (mullvadEnabled) {
            val ok = rotateMullvad(
                account = mullvadAccount,
                companionHost = companionHost,
                companionPort = companionPort,
                companionToken = companionToken,
                onLog = onLog
            )
            onLog(if (ok) "✓ Mullvad rotate OK" else "⚠ Mullvad soft-fail (continuing)")
            delay(1200)
        }
    }

    /** Backward-compatible entry used by older call sites. */
    suspend fun rotateAdbIp(serial: String = "", onLog: (String) -> Unit = {}): Boolean =
        rotateIp(context = null, serial = serial, companionHost = "", companionPort = 8765, companionToken = "", onLog = onLog)

    suspend fun rotateMullvad(onLog: (String) -> Unit = {}): Boolean =
        rotateMullvad(account = "", companionHost = "", companionPort = 8765, companionToken = "", onLog = onLog)

    suspend fun rotateIp(
        context: Context?,
        serial: String,
        companionHost: String,
        companionPort: Int,
        companionToken: String,
        onLog: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        // 1) On-device native (works inside the APK)
        if (context != null) {
            if (rotateOnDevice(context, onLog)) return@withContext true
        }

        // 2) PC companion HTTP
        if (companionHost.isNotBlank()) {
            if (rotateViaCompanion(
                    host = companionHost,
                    port = companionPort,
                    token = companionToken,
                    path = "/v1/network/adb-rotate",
                    body = JSONObject().put("serial", serial).toString(),
                    onLog = onLog
                )
            ) return@withContext true
        }

        // 3) Local adb on PATH (emulator / rooted with adb binary)
        if (runAdbLocal(serial, onLog)) return@withContext true

        onLog("ADB/IP rotate: all methods failed")
        false
    }

    suspend fun rotateMullvad(
        account: String,
        companionHost: String,
        companionPort: Int,
        companionToken: String,
        onLog: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (companionHost.isNotBlank()) {
            if (rotateViaCompanion(
                    host = companionHost,
                    port = companionPort,
                    token = companionToken,
                    path = "/v1/network/mullvad-rotate",
                    body = JSONObject().put("account", account).toString(),
                    onLog = onLog
                )
            ) return@withContext true
        }
        // Local mullvad CLI
        try {
            if (account.isNotBlank()) {
                runCmd(listOf("mullvad", "account", "login", account), onLog)
            }
            val code = runCmd(listOf("mullvad", "reconnect"), onLog)
            if (code == 0) {
                onLog("Mullvad reconnect OK (local CLI)")
                return@withContext true
            }
            // alternate
            if (runCmd(listOf("mullvad", "disconnect"), onLog) == 0) {
                delay(800)
                if (runCmd(listOf("mullvad", "connect"), onLog) == 0) {
                    onLog("Mullvad disconnect/connect OK")
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            onLog("Mullvad local: ${e.message}")
        }
        false
    }

    // ── On-device strategies ──────────────────────────────────────────────

    private suspend fun rotateOnDevice(context: Context, onLog: (String) -> Unit): Boolean {
        // Root su airplane toggle — most reliable new IP on mobile data
        if (rotateViaRootAirplane(onLog)) {
            waitForNetwork(context, onLog)
            return true
        }
        // Mobile data off/on via svc (needs root or system)
        if (rotateViaSvcData(onLog)) {
            waitForNetwork(context, onLog)
            return true
        }
        // Wi‑Fi bounce (changes local IP; WAN IP only if CGNAT/wifi path differs)
        if (rotateViaWifiBounce(context, onLog)) {
            waitForNetwork(context, onLog)
            return true
        }
        onLog("On-device rotate: no method available (no root / no radio control)")
        return false
    }

    private fun rotateViaRootAirplane(onLog: (String) -> Unit): Boolean {
        return try {
            if (!hasSu()) {
                onLog("Root su not found — skip airplane root path")
                return false
            }
            val script = """
                settings put global airplane_mode_on 1
                am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
                sleep 2
                settings put global airplane_mode_on 0
                am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false
            """.trimIndent()
            val ok = suExec(script)
            onLog(if (ok) "Root airplane toggle OK" else "Root airplane toggle failed")
            ok
        } catch (e: Exception) {
            onLog("Root airplane: ${e.message}")
            false
        }
    }

    private fun rotateViaSvcData(onLog: (String) -> Unit): Boolean {
        return try {
            if (!hasSu()) return false
            val script = """
                svc data disable
                sleep 2
                svc data enable
            """.trimIndent()
            val ok = suExec(script)
            onLog(if (ok) "svc data bounce OK" else "svc data bounce failed")
            ok
        } catch (e: Exception) {
            onLog("svc data: ${e.message}")
            false
        }
    }

    private fun rotateViaWifiBounce(context: Context, onLog: (String) -> Unit): Boolean {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return false
            @Suppress("DEPRECATION")
            if (!wifi.isWifiEnabled) {
                onLog("Wi‑Fi disabled — skip wifi bounce")
                return false
            }
            @Suppress("DEPRECATION")
            wifi.isWifiEnabled = false
            Thread.sleep(2000)
            @Suppress("DEPRECATION")
            wifi.isWifiEnabled = true
            onLog("Wi‑Fi bounce toggled")
            true
        } catch (e: Exception) {
            onLog("Wi‑Fi bounce: ${e.message}")
            false
        }
    }

    private suspend fun waitForNetwork(context: Context, onLog: (String) -> Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        repeat(20) {
            val net = cm?.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val up = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            if (up) {
                onLog("Network back online")
                return
            }
            delay(500)
        }
        onLog("Network wait timed out")
    }

    private fun hasSu(): Boolean {
        for (path in listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su")) {
            if (java.io.File(path).exists()) return true
        }
        return try {
            val p = ProcessBuilder("which", "su").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor() == 0 && out.contains("su")
        } catch (_: Exception) {
            false
        }
    }

    private fun suExec(script: String): Boolean {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
        val code = p.waitFor()
        return code == 0
    }

    // ── PC companion ─────────────────────────────────────────────────────

    private fun rotateViaCompanion(
        host: String,
        port: Int,
        token: String,
        path: String,
        body: String,
        onLog: (String) -> Unit
    ): Boolean {
        return try {
            val url = "http://${host.trim()}:${port}$path"
            val req = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .apply {
                    if (token.isNotBlank()) header("Authorization", "Bearer $token")
                }
                .header("User-Agent", "EVS-NetworkRotator/6.5")
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val ok = resp.isSuccessful && (raw.contains("\"ok\":true") || raw.contains("\"ok\": true"))
                onLog("Companion $path → HTTP ${resp.code} ${raw.take(80)}")
                ok
            }
        } catch (e: Exception) {
            onLog("Companion rotate failed: ${e.message}")
            false
        }
    }

    // ── Local adb ────────────────────────────────────────────────────────

    private fun runAdbLocal(serial: String, onLog: (String) -> Unit): Boolean {
        return try {
            val target = if (serial.isNotBlank()) listOf("-s", serial) else emptyList()
            val seq = listOf(
                listOf("adb") + target + listOf("shell", "cmd", "connectivity", "airplane-mode", "enable"),
                listOf("adb") + target + listOf("shell", "settings", "put", "global", "airplane_mode_on", "1"),
                listOf("adb") + target + listOf("shell", "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE", "--ez", "state", "true"),
            )
            for (c in seq) runCmd(c, onLog)
            Thread.sleep(2500)
            val seq2 = listOf(
                listOf("adb") + target + listOf("shell", "cmd", "connectivity", "airplane-mode", "disable"),
                listOf("adb") + target + listOf("shell", "settings", "put", "global", "airplane_mode_on", "0"),
                listOf("adb") + target + listOf("shell", "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE", "--ez", "state", "false"),
            )
            for (c in seq2) runCmd(c, onLog)
            onLog("Local adb airplane cycle done")
            true
        } catch (e: Exception) {
            onLog("Local adb: ${e.message}")
            false
        }
    }

    private fun runCmd(cmd: List<String>, onLog: (String) -> Unit): Int {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            if (out.isNotBlank()) onLog(out.take(100))
            code
        } catch (e: Exception) {
            onLog("${cmd.firstOrNull()}: ${e.message}")
            -1
        }
    }
}
