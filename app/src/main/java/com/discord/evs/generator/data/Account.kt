package com.discord.evs.generator.data

import com.discord.evs.generator.BuildConfig

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val email: String,
    val password: String,
    val token: String,
    val status: String = "PENDING",
    val verified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "") val phone: String = "",
    @ColumnInfo(defaultValue = "") val notes: String = "",
    @ColumnInfo(defaultValue = "") val tags: String = "",
    @ColumnInfo(defaultValue = "0") val checkedAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val emailVerified: Boolean = false,
    @ColumnInfo(defaultValue = "NOT_ASKED") val phoneStatus: String = "NOT_ASKED",
    @ColumnInfo(defaultValue = "Manual") val captchaSolveMethod: String = "Manual",
    @ColumnInfo(defaultValue = "") val captchaSolverApi: String = "",
    @ColumnInfo(defaultValue = "0") val score: Int = 0,
    @ColumnInfo(defaultValue = "") val scoreReason: String = "",
    @ColumnInfo(defaultValue = "0") val tokenCreatedAt: Long = 0L,
    @ColumnInfo(defaultValue = "") val timeline: String = "",
    @ColumnInfo(defaultValue = "NOT_ASKED") val emailStatus: String = "NOT_ASKED"
) {
    fun toExportLine(): String = "$email:$password:$token"

    fun toExportCsv(): String = "\"$email\",\"$password\",\"$token\",\"$status\",\"$verified\",\"$phone\",\"$createdAt\""

    fun toNativeJson(): String = buildString {
        append("{")
        append("\"email\":\"${email.replace("\\", "\\\\").replace("\"", "\\\"")}\",")
        append("\"password\":\"${password.replace("\\", "\\\\").replace("\"", "\\\"")}\",")
        append("\"token\":\"${token.replace("\\", "\\\\").replace("\"", "\\\"")}\",")
        append("\"status\":\"$status\",")
        append("\"verified\":\"${verified}\",")
        append("\"emailVerified\":\"${emailVerified}\",")
        append("\"emailStatus\":\"$emailStatus\",")
        append("\"phone\":\"${phone.replace("\\", "\\\\").replace("\"", "\\\"")}\",")
        append("\"phoneStatus\":\"$phoneStatus\",")
        append("\"captchaSolveMethod\":\"$captchaSolveMethod\",")
        append("\"captchaSolverApi\":\"${captchaSolverApi.replace("\\", "\\\\").replace("\"", "\\\"")}\",")
        append("\"tags\":\"${tags.replace("\\", "\\\\").replace("\"", "\\\"")}\",")
        append("\"notes\":\"${notes.replace("\\", "\\\\").replace("\"", "\\\"")}\",")
        append("\"timeline\":\"${timeline.replace("\\", "\\\\").replace("\"", "\\\"")}\",")
        append("\"createdAt\":\"${formatDate(createdAt)}\"")
        append("}")
    }

    private fun formatDate(ts: Long): String {
        if (ts <= 0L) return "-"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))
    }

    private fun phoneLine(): String = when {
        phoneStatus == "VERIFIED" -> "$phone (Verified)"
        phoneStatus == "ASKED" && phone.isNotBlank() -> "$phone (Asked, not confirmed)"
        phoneStatus == "ASKED" -> "Asked (no number obtained)"
        phoneStatus == "FAILED" || phoneStatus == "NOT_VERIFIED_BUG" -> "Not verified (bug/error)"
        else -> "Not Asked"
    }

    fun emailLine(): String = when {
        emailVerified || emailStatus == "VERIFIED" -> "Verified"
        emailStatus == "FAILED" || emailStatus == "NOT_VERIFIED_BUG" -> "Not verified (bug/error)"
        emailStatus == "NOT_VERIFIED" -> "Not verified"
        else -> "Not asked / pending"
    }

    fun timelineLines(): List<String> {
        if (timeline.isBlank()) {
            return listOf("Created: ${formatDate(createdAt)}")
        }
        return timeline.split('|').mapNotNull { part ->
            val bits = part.split('@')
            if (bits.size < 2) return@mapNotNull part
            val label = when (bits[0]) {
                "created" -> "Account created"
                "logged_in" -> "Logged in / token captured"
                "email_ok" -> "Email verified"
                "email_fail" -> "Email verification failed"
                "email_skip" -> "Email verification not asked"
                "phone_ask" -> "Phone verification asked"
                "phone_ok" -> "Phone verified"
                "phone_fail" -> "Phone verification failed"
                "phone_skip" -> "Phone not asked"
                "captcha" -> "Captcha solved"
                "avatar" -> "Avatar set"
                "bio" -> "Bio set"
                "saved" -> "Account saved"
                "status" -> "Status updated"
                else -> bits[0]
            }
            val ts = bits[1].toLongOrNull() ?: return@mapNotNull label
            "$label: ${formatDate(ts)}"
        }
    }

    fun toNotifText(): String = buildString {
        appendLine("**EVS Generator v${BuildConfig.VERSION_NAME} — New Account**")
        appendLine()
        appendLine("📧 **Email:** `$email`")
        appendLine("🔑 **Password:** `$password`")
        appendLine("📱 **Number:** ${phoneLine()}")
        appendLine("✅ **Email:** ${emailLine()}")
        appendLine("🪙 **Token Valid:** ${if (verified) "Yes" else "No"}")
        appendLine("📋 **Status:** $status")
        appendLine("🪙 **Token:** `$token`")
        appendLine("⏰ **Created:** ${formatDate(createdAt)}")
        if (tags.isNotBlank()) appendLine("🏷 **Tags:** $tags")
        if (notes.isNotBlank()) appendLine("📝 **Notes:** $notes")
        append("🔗 **Combo:** `$email:$password:$token`")
    }


    /** UI/log safe line — never includes password or full token. */
    fun toSafeLogLine(): String {
        val tok = when {
            token.isBlank() -> "(no token)"
            token.length <= 12 -> "***"
            else -> token.take(6) + "…" + token.takeLast(4)
        }
        return "$email | status=$status | email=${emailLine()} | phone=${phoneLine()} | token=$tok | score=$score"
    }

    /** Export without secrets for sharing screenshots / support. */
    fun toRedactedExportCsv(): String =
        "\"$email\",\"********\",\"[REDACTED]\",\"$status\",\"$verified\",\"$phone\",\"$createdAt\""

    fun scoreExplanation(): String {
        val parts = mutableListOf<String>()
        parts += when {
            verified -> "+token valid"
            token.isNotBlank() -> "token present (not validated)"
            else -> "no token"
        }
        parts += when {
            emailVerified || emailStatus == "VERIFIED" -> "+email verified"
            emailStatus == "FAILED" -> "email failed"
            emailStatus == "SKIPPED" -> "email skipped"
            else -> "email pending/unknown"
        }
        parts += when {
            phoneStatus == "VERIFIED" -> "+phone verified"
            phoneStatus == "FAILED" || phoneStatus == "NOT_VERIFIED_BUG" -> "phone failed"
            phoneStatus == "ASKED" -> "phone asked"
            phoneStatus == "SKIPPED" -> "phone skipped"
            else -> "phone not asked"
        }
        if (captchaSolveMethod.isNotBlank()) parts += "captcha=$captchaSolveMethod"
        return "Score $score because: " + parts.joinToString("; ")
    }

    companion object {
        fun appendEvent(existing: String, event: String, ts: Long = System.currentTimeMillis()): String {
            val entry = "$event@$ts"
            return if (existing.isBlank()) entry else "$existing|$entry"
        }
    }
}
