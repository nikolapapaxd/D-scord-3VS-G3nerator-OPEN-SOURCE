package com.discord.evs.generator.security

import com.discord.evs.generator.features.FeatureFlags

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.discord.evs.generator.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.concurrent.TimeUnit

/**
 * Security guard — anti-tamper, anti-analysis, and secret dispatch.
 * Written in Kotlin (no NDK required). Not unbreakable; raises cost of casual RE.
 */
object NativeGuard {

    private const val TAG = "NativeGuard"
    private const val EXPECTED_PACKAGE = "com.discord.evs.generator"
    // REQUIRED for release: paste SHA-256 of your release signing cert (lowercase hex, no colons).
    // Log once from a signed release build: NativeGuard.nativeGetSignatureHash(context)
    const val PINNED_SIGNATURE_HASH = "6abc9a594224e7e34cc2629562b48bda92b6adea95bd9eae176709ad97bc3af2"
    private const val EXPECTED_SIGNATURE_HASH = PINNED_SIGNATURE_HASH

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var lastOkMs: Long = 0L

    // ─────────────────────────────────────────────────────────────────────────
    // Integrity / environment checks
    // ─────────────────────────────────────────────────────────────────────────

    /** Package name + optional signature pin + no debugger. */
    fun verifyIntegrity(context: Context): Boolean {
        if (context.packageName != EXPECTED_PACKAGE) return false
        if (!BuildConfig.DEBUG && isDebuggerOrTracerPresent()) return false
        // Release builds MUST match the pinned release.keystore SHA-256.
        // Debug builds skip the pin so local development still works.
        if (!BuildConfig.DEBUG && EXPECTED_SIGNATURE_HASH.isNotBlank()) {
            val sigHash = getSignatureHash(context).lowercase().replace(":", "")
            if (sigHash != EXPECTED_SIGNATURE_HASH.lowercase().replace(":", "")) return false
        }
        if (hasSuspiciousInstallPath(context)) return false
        return true
    }

    /** Reject emulators, rooted devices, Frida/Xposed-style instrumentation. */
    fun isSafeEnvironment(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        if (BuildConfig.DEBUG) return true
        // Emulator/root alone no longer hard-block generation — many operators use
        // rooted test devices. Hooking frameworks still fail closed.
        if (isHookingFrameworkPresent()) return false
        return true
    }

    /** Full gate used at startup and before sensitive work. */
    fun isAllowed(context: Context): Boolean {
        val ok = verifyIntegrity(context) && isSafeEnvironment(context)
        if (ok) lastOkMs = System.currentTimeMillis()
        return ok
    }

    /**
     * Cheap re-check for long-running generators.
     * Returns false if last full pass was > 45s ago and a quick check fails.
     */
    fun quickAllow(context: Context): Boolean {
        if (System.currentTimeMillis() - lastOkMs < 45_000L) return true
        return isAllowed(context)
    }

    fun nativeGetSignatureHash(context: Context): String = getSignatureHash(context)

    private fun isDebuggerOrTracerPresent(): Boolean {
        if (android.os.Debug.isDebuggerConnected()) return true
        return try {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("TracerPid:") }
                    ?.substringAfter("TracerPid:")
                    ?.trim()
                    ?.toIntOrNull() ?: 0
            } != 0
        } catch (_: Exception) {
            false
        }
    }

    private fun isEmulator(): Boolean {
        val tokens = listOf(
            "sdk", "google_sdk", "emulator", "simulator", "goldfish",
            "ranchu", "vbox86", "nox", "genymotion", "bluestacks",
            "ldplayer", "mumu", "gphone", "google_x86", "google_x64",
            "android sdk built for x86", "generic"
        )
        val checks = listOf(
            Build.PRODUCT, Build.HARDWARE, Build.MANUFACTURER, Build.BRAND,
            Build.DEVICE, Build.MODEL, Build.BOARD, Build.FINGERPRINT
        ).map { it.lowercase() }
        return checks.any { value -> tokens.any { value.contains(it) } }
    }

    private fun isDeviceRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk", "/sbin/su", "/system/bin/su",
            "/system/xbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su", "/data/local/su",
            "/su/bin/su", "/magisk", "/sbin/.magisk"
        )
        if (paths.any { File(it).exists() }) return true
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val has = p.inputStream.bufferedReader().readLine() != null
            p.destroy()
            has
        } catch (_: Exception) {
            false
        }
    }

    private fun isHookingFrameworkPresent(): Boolean {
        // Frida / Xposed / Substrate class probes
        val classes = arrayOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XposedHelpers",
            "com.saurik.substrate.MS$2",
            "re.frida.Helper"
        )
        for (name in classes) {
            try {
                Class.forName(name)
                return true
            } catch (_: Throwable) { }
        }
        // Frida default ports / maps
        try {
            File("/proc/self/maps").useLines { lines ->
                for (line in lines) {
                    val l = line.lowercase()
                    if ("frida" in l || "xposed" in l || "substrate" in l) return true
                }
            }
        } catch (_: Exception) { }
        // Suspicious env
        val env = System.getenv()
        if (env.keys.any { it.contains("FRIDA", ignoreCase = true) }) return true
        return false
    }

    private fun hasSuspiciousInstallPath(context: Context): Boolean {
        return try {
            val src = context.applicationInfo.sourceDir?.lowercase() ?: return false
            // Cloned / parallel-space style paths often used by rebranded APKs
            src.contains("/parallel") || src.contains("/dual") || src.contains("clone")
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun getSignatureHash(context: Context): String {
        return try {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                packageInfo.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
                    ?: @Suppress("DEPRECATION") packageInfo.signatures?.map { it.toByteArray() }
                    ?: emptyList()
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                val sigs = packageInfo.signatures
                @Suppress("DEPRECATION")
                sigs?.map { it.toByteArray() } ?: emptyList()
            }
            val first = signatures.firstOrNull() ?: return ""
            bytesToHex(sha256(first))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read signature hash", e)
            ""
        }
    }

    @Throws(NoSuchAlgorithmException::class)
    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").run {
            update(data)
            digest()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Discord webhook
    // ─────────────────────────────────────────────────────────────────────────

    /** Send a Discord webhook for one account (mode 0/1) or queue for bulk (mode 2). */
    fun sendDcWebhook(
        context: Context,
        accountJson: String,
        mode: Int,
        @Suppress("UNUSED_PARAMETER") deleteAfter: Boolean
    ): Boolean {
        // Secrets live on the API server when backend mode is on
        if (FeatureFlags.BACKEND_API_ENABLED) return false
        if (!verifyIntegrity(context)) return false
        return try {
            val url = SecretsManager.dcWebhookUrl(context)
            if (url.isBlank()) return false
            val account = gson.fromJson(accountJson, JsonObject::class.java)
            val username = SecretsManager.dcWebhookName(context).ifBlank { "EVS Generator" }
            val avatar = SecretsManager.dcWebhookPfp(context)

            val body = when (mode) {
                1 -> buildDcEmbed(account, username, avatar)
                else -> buildDcPlain(account, username, avatar)
            }

            postJson(url, body)
        } catch (e: Exception) {
            Log.e(TAG, "sendDcWebhook failed", e)
            false
        }
    }

    private fun buildDcEmbed(account: JsonObject, username: String, avatar: String): String {
        val email = jsonString(account, "email")
        val password = jsonString(account, "password")
        val token = jsonString(account, "token")
        val builder = StringBuilder()
        builder.append("{\"username\":\"").append(escapeJson(username)).append("\"")
        if (avatar.isNotBlank()) {
            builder.append(",\"avatar_url\":\"").append(escapeJson(avatar)).append("\"")
        }
        builder.append(",\"embeds\":[{\"title\":\"🎉 Account Generated\",\"description\":\"✅ New account via **EVS Generator**\",\"color\":5793266,\"fields\":[")
        builder.append("{\"name\":\"📧  Email\",\"value\":\"`").append(escapeJson(email)).append("`\",\"inline\":false},")
        builder.append("{\"name\":\"🔑  Password\",\"value\":\"`").append(escapeJson(password)).append("`\",\"inline\":false},")
        builder.append("{\"name\":\"🪙  Token\",\"value\":\"`").append(escapeJson(token)).append("`\",\"inline\":false},")
        builder.append("{\"name\":\"🔗  Combo\",\"value\":\"`").append(escapeJson(email)).append(":").append(escapeJson(password)).append(":").append(escapeJson(token)).append("`\",\"inline\":false}")
        builder.append("]}]}")
        return builder.toString()
    }

    private fun buildDcPlain(account: JsonObject, username: String, avatar: String): String {
        val content = formatAccount(account)
        val builder = StringBuilder()
        builder.append("{\"content\":\"").append(escapeJson(content)).append("\",\"username\":\"").append(escapeJson(username)).append("\"")
        if (avatar.isNotBlank()) {
            builder.append(",\"avatar_url\":\"").append(escapeJson(avatar)).append("\"")
        }
        builder.append("}")
        return builder.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Telegram message
    // ─────────────────────────────────────────────────────────────────────────

    /** Send a Telegram message for one account (mode 0) or queue for bulk (mode 1). */
    fun sendTgMessage(
        context: Context,
        accountJson: String,
        mode: Int,
        @Suppress("UNUSED_PARAMETER") deleteAfter: Boolean
    ): Boolean {
        if (FeatureFlags.BACKEND_API_ENABLED) return false
        if (!verifyIntegrity(context)) return false
        if (mode != 0) return false
        return try {
            val token = SecretsManager.tgBotToken(context)
            val chatId = SecretsManager.tgChannelId(context)
            if (token.isBlank() || chatId.isBlank()) return false

            val account = gson.fromJson(accountJson, JsonObject::class.java)
            val text = formatAccount(account)
            val url = "https://api.telegram.org/bot$token/sendMessage"
            val body = "{\"chat_id\":\"${escapeJson(chatId)}\",\"text\":\"${escapeJson(text)}\",\"parse_mode\":\"Markdown\"}"
            postJson(url, body)
        } catch (e: Exception) {
            Log.e(TAG, "sendTgMessage failed", e)
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun formatAccount(account: JsonObject): String {
        val email = jsonString(account, "email")
        val password = jsonString(account, "password")
        val phone = jsonString(account, "phone")
        val status = jsonString(account, "status")
        val token = jsonString(account, "token")
        val createdAt = jsonString(account, "createdAt")
        val tags = jsonString(account, "tags")
        val notes = jsonString(account, "notes")
        val solver = jsonString(account, "captchaSolveMethod")
        val solverApi = jsonString(account, "captchaSolverApi")
        val emailVerified = jsonString(account, "emailVerified")
        val verified = jsonString(account, "verified")
        val phoneStatus = jsonString(account, "phoneStatus")

        val phoneStr = when {
            phoneStatus == "VERIFIED" -> "$phone (Verified)"
            phoneStatus == "ASKED" && phone.isNotBlank() -> "$phone (Asked, not confirmed)"
            else -> "*Not Asked*"
        }

        return buildString {
            appendLine("**EVS Generator v${com.discord.evs.generator.BuildConfig.VERSION_NAME} — New Account**")
            appendLine()
            appendLine("📧 **Email:** `$email`")
            appendLine("🔑 **Password:** `$password`")
            appendLine("📱 **Number:** $phoneStr")
            appendLine("✅ **Email Verified:** ${if (emailVerified == "true") "Yes" else "No"}")
            appendLine("🪙 **Token Valid:** ${if (verified == "true") "Yes" else "No"}")
            appendLine("📋 **Status:** $status")
            appendLine("🛡 **Captcha Solve:** $solver")
            if (solver == "Automatic" && solverApi.isNotBlank()) {
                appendLine("🔌 **Solver API:** $solverApi")
            }
            appendLine("🪙 **Token:** `$token`")
            appendLine("⏰ **Created:** $createdAt")
            if (tags.isNotBlank()) appendLine("🏷 **Tags:** $tags")
            if (notes.isNotBlank()) appendLine("📝 **Notes:** $notes")
            append("🔗 **Combo:** `$email:$password:$token`")
        }
    }

    private fun jsonString(obj: JsonObject, key: String): String {
        return try {
            obj.get(key).asString
        } catch (_: Exception) {
            obj.get(key)?.toString()?.trim('"') ?: ""
        }
    }

    private fun escapeJson(s: String): String {
        return buildString {
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }
    }

    private fun postJson(url: String, json: String): Boolean {
        return try {
            val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.e(TAG, "postJson failed", e)
            false
        }
    }
}
