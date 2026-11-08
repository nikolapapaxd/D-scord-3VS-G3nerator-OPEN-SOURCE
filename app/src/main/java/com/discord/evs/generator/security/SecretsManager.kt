package com.discord.evs.generator.security

import android.content.Context
import android.util.Log
import com.discord.evs.generator.BuildConfig
import com.discord.evs.generator.R
import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Loads and decrypts secrets.dat from raw resources.
 *
 * Encryption: AES-256-CBC + HMAC-SHA256 (Encrypt-then-MAC).
 * Key derivation: PBKDF2-HMAC-SHA256 with 100 000 rounds.
 * File format: salt(16) || iv(16) || ciphertext || hmac(32).
 *
 * The passphrase is not stored as a literal string. It is reconstructed at
 * runtime by XORing two byte arrays. This makes trivial string extraction from
 * the DEX somewhat harder, but it is still obfuscation, not true security.
 *
 * Obfuscation, not true security: if the app can decrypt the file, a
 * determined reverse engineer can eventually recover the key. The only way to
 * keep secrets truly secret is to never ship them in the app at all (fetch from
 * a server at runtime).
 */
object SecretsManager {

    private const val TAG = "SecretsManager"
    private const val ITERATIONS = 100_000

    // XOR-obfuscated passphrase bytes. The actual passphrase is recovered at
    // runtime by XORing ENC with KEY. The plaintext is never stored as a literal
    // string in the DEX.
    private val PASSPHRASE_KEY = intArrayOf(
        132, 155, 70, 225, 77, 62, 170, 154,
        5, 79, 225, 3, 17, 83, 168, 133,
        204, 175, 60, 163, 138, 80, 97, 88,
        165
    )

    private val PASSPHRASE_ENC = intArrayOf(
        225, 237, 53, 204, 34, 78, 207, 244,
        118, 32, 148, 113, 114, 54, 133, 246,
        169, 204, 78, 198, 254, 35, 76, 46,
        148
    )

    init {
        require(PASSPHRASE_KEY.size == PASSPHRASE_ENC.size)
    }

    internal data class Secrets(
        val dcWebhookUrl: String,
        val dcWebhookName: String,
        val dcWebhookPfp: String,
        val tgBotToken: String,
        val tgChannelId: String,
        val tgInstallBotToken: String = "",
        val tgInstallChannelId: String = "",
        val githubDispatchToken: String = "",
        val tgReportChannelId: String = "",
        val tgReportBotToken: String = ""
    )

    private var cachedSecrets: Secrets? = null

    private fun isDebugged(): Boolean {
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

    private fun buildPassphrase(): CharArray {
        val len = PASSPHRASE_KEY.size
        val chars = CharArray(len)
        for (i in 0 until len) {
            chars[i] = (PASSPHRASE_KEY[i] xor PASSPHRASE_ENC[i]).toChar()
        }
        return chars
    }

    private fun decrypt(blob: ByteArray): String? {
        // Anti-debug: if a debugger is attached in a release build, refuse to decrypt.
        if (!BuildConfig.DEBUG && isDebugged()) {
            Log.e(TAG, "Debugger detected — refusing to decrypt secrets")
            return null
        }

        val passphrase = buildPassphrase()
        return try {
            if (blob.size < 64) {
                Log.e(TAG, "secrets.dat too small")
                return null
            }
            val salt = blob.sliceArray(0..15)
            val iv = blob.sliceArray(16..31)
            val ctAndMac = blob.sliceArray(32 until blob.size)
            val ct = ctAndMac.sliceArray(0 until ctAndMac.size - 32)
            val expectedMac = ctAndMac.sliceArray(ctAndMac.size - 32 until ctAndMac.size)

            val derived = pbkdf2(passphrase, salt, 64)
            val encKey = derived.sliceArray(0..31)
            val macKey = derived.sliceArray(32..63)

            val actualMac = hmacSha256(macKey, salt + iv + ct)
            if (!MessageDigest.isEqual(expectedMac, actualMac)) {
                Log.e(TAG, "HMAC verification failed — secrets.dat is corrupt or passphrase mismatch")
                return null
            }

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt secrets", e)
            null
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private fun pbkdf2(passphrase: CharArray, salt: ByteArray, dkLen: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, dkLen * 8)
        return factory.generateSecret(spec).encoded
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun parse(text: String): Secrets {
        val map = mutableMapOf<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if ("=" !in line) continue
            val (k, v) = line.split("=", limit = 2)
            map[k.trim()] = v.trim()
        }
        return Secrets(
            dcWebhookUrl = map["dc_webhook_url"] ?: "",
            dcWebhookName = map["dc_webhook_name"] ?: "EVS Generator",
            dcWebhookPfp = map["dc_webhook_pfp"] ?: "",
            tgBotToken = map["tg_bot_token"] ?: "",
            tgChannelId = map["tg_channel_id"] ?: "",
            tgInstallBotToken = map["tg_install_bot_token"] ?: "",
            tgInstallChannelId = map["tg_install_channel_id"] ?: "",
            githubDispatchToken = map["github_dispatch_token"] ?: "",
            tgReportChannelId = map["tg_report_channel_id"] ?: "",
            tgReportBotToken = map["tg_report_bot_token"] ?: ""
        )
    }

    @Synchronized
    internal fun load(context: Context): Secrets {
        cachedSecrets?.let { return it }
        val bytes = context.resources.openRawResource(R.raw.secrets).use { it.readBytes() }
        val plaintext = decrypt(bytes)
            ?: throw IllegalStateException("Could not decrypt secrets.dat. Run encrypt_secrets.py after filling secrets/secrets.txt.")
        val secrets = parse(plaintext)
        cachedSecrets = secrets
        return secrets
    }

    fun dcWebhookUrl(context: Context): String = load(context).dcWebhookUrl
    fun dcWebhookName(context: Context): String = load(context).dcWebhookName
    fun dcWebhookPfp(context: Context): String = load(context).dcWebhookPfp
    fun tgBotToken(context: Context): String = load(context).tgBotToken
    fun tgChannelId(context: Context): String = load(context).tgChannelId

    fun tgReportChannelId(context: Context): String = load(context).tgReportChannelId

    fun tgReportBotToken(context: Context): String {
        val s = load(context)
        return s.tgReportBotToken.ifBlank { s.tgBotToken }
    }

    fun tgInstallBotToken(context: Context): String = load(context).tgInstallBotToken
    fun tgInstallChannelId(context: Context): String = load(context).tgInstallChannelId

    fun githubDispatchToken(context: Context): String = load(context).githubDispatchToken

    /** Call this if you need to force a reload (e.g. after app update). */
    fun clearCache() { cachedSecrets = null }
}
