package com.discord.evs.generator.remote

import android.content.Context
import android.util.Log
import com.discord.evs.generator.BuildConfig
import com.discord.evs.generator.automation.CyberTempApi
import com.discord.evs.generator.automation.GuerrillaMailApi
import com.discord.evs.generator.automation.MailTmApi
import com.discord.evs.generator.automation.OneSecMailApi
import com.discord.evs.generator.automation.TempMailLolApi
import com.discord.evs.generator.data.Account
import com.discord.evs.generator.data.AccountDatabase
import com.discord.evs.generator.data.Prefs
import com.discord.evs.generator.features.FeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Localhost-only HTTP bridge for the PC companion.
 * Default bind: 127.0.0.1 — use `adb reverse tcp:PORT tcp:PORT` from the PC.
 */
object LocalBridgeServer {

    private const val TAG = "PcBridge"
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mailTm = MailTmApi()
    private val oneSec = OneSecMailApi()
    private val guerrilla = GuerrillaMailApi()
    private val tempLol = TempMailLolApi()

    fun isRunning(): Boolean = running.get()

    fun ensureToken(prefs: Prefs): String {
        if (prefs.pcBridgeToken.isBlank()) {
            prefs.pcBridgeToken = randomToken()
        }
        return prefs.pcBridgeToken
    }

    fun start(context: Context): Boolean {
        if (!FeatureFlags.PC_BRIDGE_ENABLED) return false
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.pcBridgeEnabled) return false
        if (running.get()) return true
        val port = prefs.pcBridgePort
        ensureToken(prefs)
        return try {
            val ss = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            running.set(true)
            pool.execute {
                Log.i(TAG, "Listening on 127.0.0.1:$port")
                while (running.get()) {
                    try {
                        val socket = ss.accept()
                        pool.execute { handleClient(app, socket) }
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    fun regenerateToken(prefs: Prefs): String {
        prefs.pcBridgeToken = randomToken()
        return prefs.pcBridgeToken
    }

    private fun randomToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun handleClient(context: Context, socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val pathQuery = parts[1]
            val path = pathQuery.substringBefore('?')
            val query = pathQuery.substringAfter('?', "")

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read)
            } else ""

            val prefs = Prefs(context)
            val auth = headers["authorization"].orEmpty().removePrefix("Bearer").trim()
            val needsAuth = path != "/v1/status" && path != "/v1/health"
            if (needsAuth && auth != prefs.pcBridgeToken) {
                writeJson(socket.getOutputStream(), 401, JSONObject().put("ok", false).put("error", "unauthorized"))
                return
            }

            val response = route(context, prefs, method, path, query, body)
            writeJson(socket.getOutputStream(), response.first, response.second)
        } catch (e: Exception) {
            try {
                writeJson(
                    socket.getOutputStream(),
                    500,
                    JSONObject().put("ok", false).put("error", e.message ?: "error")
                )
            } catch (_: Exception) {}
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun route(
        context: Context,
        prefs: Prefs,
        method: String,
        path: String,
        @Suppress("UNUSED_PARAMETER") query: String,
        body: String
    ): Pair<Int, JSONObject> {
        val jsonBody = try {
            if (body.isNotBlank()) JSONObject(body) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }

        return when {
            path == "/v1/health" || path == "/v1/status" -> {
                200 to JSONObject()
                    .put("ok", true)
                    .put("service", "evs-pc-bridge")
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("mode", prefs.pcBridgeMode)
                    .put("running", true)
                    .put("features", JSONObject()
                        .put("email", true)
                        .put("save", true)
                        .put("config", true)
                    )
            }

            path == "/v1/config" && method == "GET" -> {
                200 to JSONObject()
                    .put("ok", true)
                    .put("emailProvider", prefs.emailProvider)
                    .put("verifyEmail", prefs.verifyEmailEnabled)
                    .put("verifyPhone", prefs.phoneVerificationEnabled)
                    .put("randomUsername", prefs.randomUsernameEnabled)
                    .put("randomDisplayName", prefs.randomDisplayNameEnabled)
                    .put("customPassword", prefs.customPassword)
                    .put("humanDelay", prefs.humanDelayEnabled)
                    .put("mode", prefs.pcBridgeMode)
                    .put("countDefault", 5)
            }

            path == "/v1/config" && method == "POST" -> {
                if (jsonBody.has("emailProvider")) prefs.emailProvider = jsonBody.optString("emailProvider")
                if (jsonBody.has("mode")) prefs.pcBridgeMode = jsonBody.optString("mode")
                if (jsonBody.has("customPassword")) prefs.customPassword = jsonBody.optString("customPassword")
                if (jsonBody.has("randomUsername")) prefs.randomUsernameEnabled = jsonBody.optBoolean("randomUsername")
                if (jsonBody.has("randomDisplayName")) prefs.randomDisplayNameEnabled = jsonBody.optBoolean("randomDisplayName")
                if (jsonBody.has("verifyEmail")) prefs.verifyEmailEnabled = jsonBody.optBoolean("verifyEmail", prefs.verifyEmailEnabled)
                200 to JSONObject().put("ok", true)
            }

            path == "/v1/email/create" && method == "POST" -> {
                val local = jsonBody.optString("localPart").ifBlank {
                    "u${(100000..999999).random()}"
                }
                val provider = jsonBody.optString("provider").ifBlank { prefs.emailProvider }
                val result = runBlocking { createEmail(provider, local) }
                if (result.address.isBlank()) {
                    502 to JSONObject().put("ok", false).put("error", "email create failed")
                } else {
                    200 to JSONObject()
                        .put("ok", true)
                        .put("email", result.address)
                        .put("domain", result.domain)
                        .put("provider", provider)
                }
            }

            path == "/v1/email/inbox" && method == "POST" -> {
                // Best-effort: providers that support link poll — return placeholder guidance
                val email = jsonBody.optString("email")
                val timeout = jsonBody.optLong("timeoutMs", 60_000L).coerceIn(5_000L, 180_000L)
                200 to JSONObject()
                    .put("ok", true)
                    .put("email", email)
                    .put("link", JSONObject.NULL)
                    .put("note", "Poll via app providers; extend with provider-specific inbox in later builds")
                    .put("timeoutMs", timeout)
            }

            path == "/v1/account/save" && method == "POST" -> {
                val email = jsonBody.optString("email")
                val password = jsonBody.optString("password")
                val token = jsonBody.optString("token")
                if (email.isBlank() && token.isBlank()) {
                    400 to JSONObject().put("ok", false).put("error", "email or token required")
                } else {
                    val account = Account(
                        email = email,
                        password = password,
                        token = token,
                        status = jsonBody.optString("status", "VALID"),
                        verified = jsonBody.optBoolean("verified", token.isNotBlank()),
                        emailVerified = jsonBody.optBoolean("emailVerified", false),
                        phone = jsonBody.optString("phone"),
                        phoneStatus = jsonBody.optString("phoneStatus", "NOT_ASKED"),
                        notes = jsonBody.optString("notes", "pc-bridge"),
                        createdAt = System.currentTimeMillis()
                    )
                    try {
                        runBlocking {
                            AccountDatabase.getInstance(context).accountDao().insert(account)
                        }
                        200 to JSONObject().put("ok", true).put("saved", true)
                    } catch (e: Exception) {
                        500 to JSONObject().put("ok", false).put("error", e.message)
                    }
                }
            }

            path == "/v1/accounts" && method == "GET" -> {
                val list = runBlocking {
                    AccountDatabase.getInstance(context).accountDao().getAll()
                }
                val arr = JSONArray()
                list.takeLast(100).forEach { a ->
                    arr.put(
                        JSONObject()
                            .put("email", a.email)
                            .put("password", a.password)
                            .put("token", a.token)
                            .put("status", a.status)
                            .put("createdAt", a.createdAt)
                    )
                }
                200 to JSONObject().put("ok", true).put("accounts", arr)
            }

            else -> 404 to JSONObject().put("ok", false).put("error", "not found")
        }
    }

    private suspend fun createEmail(provider: String, localPart: String): CyberTempApi.EmailResult {
        return when (provider.lowercase()) {
            "mail.tm", "mailtm" -> mailTm.createEmail(localPart)
            "1secmail", "1sec" -> oneSec.createEmail(localPart)
            "guerrilla", "guerrillamail" -> guerrilla.createEmail(localPart)
            "tempmail.lol", "lol" -> tempLol.createEmail(localPart)
            else -> {
                val a = mailTm.createEmail(localPart)
                if (a.address.isNotBlank()) a else guerrilla.createEmail(localPart)
            }
        }
    }

    private fun writeJson(out: OutputStream, code: Int, json: JSONObject) {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            502 -> "Bad Gateway"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        out.write(header)
        out.write(payload)
        out.flush()
    }
}
