package com.discord.evs.generator.automation

import android.util.Base64
import kotlin.random.Random

object TokenUtil {

    private val UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private val LOWERCASE = "abcdefghjkmnpqrstuvwxyz"
    private val DIGITS    = "23456789"
    private val SPECIAL   = "!@#\$%^&*"
    private val ALL       = UPPERCASE + LOWERCASE + DIGITS + SPECIAL

    fun isValidTokenFormat(token: String): Boolean {
        if (token.length < 50) return false
        val parts = token.split(".")
        if (parts.size != 3) return false
        if (parts[0].isEmpty()) return false
        return true
    }

    fun extractUserId(token: String): String {
        return try {
            val firstSegment = token.substringBefore(".")
            val padded = firstSegment + "=".repeat((4 - firstSegment.length % 4) % 4)
            String(Base64.decode(padded, Base64.DEFAULT or Base64.NO_WRAP))
        } catch (e: Exception) {
            ""
        }
    }

    /** Extracts the token creation timestamp (Discord epoch) from the token's second segment. */
    fun extractTokenCreatedAt(token: String): Long {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return 0L
            val second = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
            val bytes = Base64.decode(second, Base64.DEFAULT or Base64.NO_WRAP)
            var ts = 0L
            for (b in bytes) ts = (ts shl 8) or (b.toLong() and 0xFF)
            (ts + 1_293_840_000L) * 1000L
        } catch (e: Exception) {
            0L
        }
    }

    fun generateSecurePassword(length: Int): String {
        val safeLength = length.coerceAtLeast(8)
        val chars = mutableListOf(
            UPPERCASE.random(),
            LOWERCASE.random(),
            DIGITS.random(),
            SPECIAL.random()
        )
        repeat(safeLength - 4) { chars.add(ALL.random()) }
        chars.shuffle()
        return chars.joinToString("")
    }

    fun generateUsername(prefix: String = "evsuser"): String {
        val digits = (1..4).map { Random.nextInt(0, 10) }.joinToString("")
        return "${prefix}${digits}"
    }

    fun generatePassword(length: Int = 14): String = generateSecurePassword(length)

    /** Fully random username (no hardcoded prefix) like "scotterbrotterhh129932". */
    fun generateRandomUsername(): String {
        val length = (14..18).random()
        val chars = LOWERCASE + DIGITS
        return (1..length).map { chars.random() }.joinToString("")
    }

    /** Discord-safe username: 2–32 chars, [a-z0-9_], not only digits. */
    fun generateDiscordUsername(preferred: String = "", withRandomSuffix: Boolean = true): String {
        var base = preferred.lowercase().filter { it.isLetterOrDigit() || it == '_' }
        if (base.length < 2) {
            base = buildString {
                append(('a'..'z').random())
                repeat(5) { append(('a'..'z').random()) }
            }
        }
        if (base.length > 28) base = base.take(28)
        if (base.all { it.isDigit() }) base = "u$base"
        if (!withRandomSuffix) return base.take(32)
        return nextUsernameVariant(base, attempt = 0)
    }

    /**
     * Next username for a fixed prefix after a "taken" rejection.
     * attempt 0 → 2 digits, 1 → 3 digits, 2+ → 4–6 digits (broader space).
     * When [allowUnderscore] is true, 50% chance of inserting "_" before digits
     * (e.g. evsuser_199291).
     */
    fun nextUsernameVariant(
        prefix: String,
        attempt: Int = 0,
        allowUnderscore: Boolean = false,
        avoid: Set<String> = emptySet()
    ): String {
        var base = prefix.lowercase().filter { it.isLetterOrDigit() || it == '_' }
        if (base.length < 2) base = "user"
        if (base.length > 22) base = base.take(22)
        if (base.all { it.isDigit() }) base = "u$base"

        val rnd = java.security.SecureRandom()
        // Prefer wider digit space earlier so Discord "taken" is rarer
        fun digitBlock(att: Int): String {
            val len = when {
                att <= 0 -> 3   // 100–999
                att == 1 -> 4
                att == 2 -> 5
                else -> 6
            }
            val max = Math.pow(10.0, len.toDouble()).toInt()
            val min = Math.pow(10.0, (len - 1).toDouble()).toInt()
            val n = min + rnd.nextInt(max - min)
            return n.toString()
        }
        // Try several candidates so we never return a recently used name
        repeat(24) { tryIdx ->
            val digits = digitBlock(attempt + tryIdx / 8)
            val useUnderscore = allowUnderscore && rnd.nextBoolean()
            val candidate = (if (useUnderscore) "${base}_$digits" else "$base$digits").take(32)
            if (candidate !in avoid && candidate != base) return candidate
        }
        // Last resort: prefix + timestamp tail
        val tail = (System.currentTimeMillis() % 1_000_000).toString()
        return (base + tail).take(32)
    }



    /** Ensure password meets Discord-ish rules (length, upper, lower, digit). */
    fun ensureDiscordPassword(candidate: String, minLen: Int = 10): String {
        val c = candidate.trim()
        val ok = c.length >= minLen &&
            c.any { it.isUpperCase() } &&
            c.any { it.isLowerCase() } &&
            c.any { it.isDigit() }
        return if (ok) c else generateSecurePassword(minLen.coerceAtLeast(12))
    }

}
