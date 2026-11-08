package com.discord.evs.generator.automation

import com.discord.evs.generator.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Real IMAP fetch over SSL for custom-mail verification links.
 * Supports Gmail (imap.gmail.com:993 + app password), Outlook, and generic IMAP.
 * No third-party mail library — raw IMAP commands over SSLSocket.
 */
class ImapMailFetcher(private val prefs: Prefs) {

    data class Message(
        val subject: String,
        val from: String,
        val body: String
    )

    suspend fun fetchVerificationUrl(
        targetEmail: String,
        timeoutMs: Long = 120_000L,
        onLog: ((String) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        if (!prefs.imapEnabled) {
            onLog?.invoke("IMAP disabled — enable in Tools → MORE")
            return@withContext null
        }
        val host = prefs.imapHost.ifBlank { "imap.gmail.com" }
        val port = prefs.imapPort.takeIf { it > 0 } ?: 993
        val user = prefs.imapUser.ifBlank { targetEmail }
        val pass = prefs.imapPass
        if (user.isBlank() || pass.isBlank()) {
            onLog?.invoke("IMAP user/pass blank")
            return@withContext null
        }

        val start = System.currentTimeMillis()
        onLog?.invoke("IMAP: connecting $host:$port as $user (search Discord mail for $targetEmail)…")
        var attempt = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            attempt++
            try {
                val msgs = searchDiscordMessages(host, port, user, pass, targetEmail, onLog)
                for (m in msgs) {
                    val url = extractDiscordVerifyUrl(m.body + "\n" + m.subject)
                    if (url != null) {
                        onLog?.invoke("IMAP: found verify link in \"${m.subject.take(60)}\"")
                        return@withContext url
                    }
                }
                if (attempt % 2 == 0) {
                    onLog?.invoke("IMAP: no Discord verify mail yet (${(System.currentTimeMillis() - start) / 1000}s)")
                }
            } catch (e: Exception) {
                onLog?.invoke("IMAP error: ${e.message}")
            }
            delay(5_000)
        }
        onLog?.invoke("IMAP: timeout — no verification link")
        null
    }

    private fun searchDiscordMessages(
        host: String,
        port: Int,
        user: String,
        pass: String,
        targetEmail: String,
        onLog: ((String) -> Unit)?
    ): List<Message> {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val socket = factory.createSocket() as SSLSocket
        socket.soTimeout = 25_000
        socket.connect(InetSocketAddress(host, port), 15_000)
        socket.startHandshake()

        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        val writer = PrintWriter(OutputStreamWriter(socket.outputStream, Charsets.UTF_8), true)

        fun readLine(): String? = reader.readLine()
        fun cmd(tag: String, command: String): List<String> {
            writer.println("$tag $command")
            writer.flush()
            val lines = mutableListOf<String>()
            while (true) {
                val line = readLine() ?: break
                lines += line
                if (line.startsWith(tag)) break
            }
            return lines
        }

        try {
            // greeting
            readLine()
            val loginLines = cmd("A1", "LOGIN ${quote(user)} ${quote(pass)}")
            if (loginLines.none { it.uppercase().contains("A1 OK") }) {
                onLog?.invoke("IMAP LOGIN failed: ${loginLines.lastOrNull()}")
                cmd("A9", "LOGOUT")
                socket.close()
                return emptyList()
            }

            cmd("A2", "SELECT INBOX")

            // Search recent UNSEEN or FROM discord / SUBJECT verify
            val searchVariants = listOf(
                "SEARCH UNSEEN SUBJECT \"Verify\"",
                "SEARCH SUBJECT \"Discord\"",
                "SEARCH FROM \"discord\"",
                "SEARCH SUBJECT \"verify\""
            )
            val ids = linkedSetOf<String>()
            for (q in searchVariants) {
                val lines = cmd("A3", q)
                for (line in lines) {
                    if (line.uppercase().startsWith("* SEARCH")) {
                        line.substringAfter("SEARCH").trim().split(" ")
                            .filter { it.isNotBlank() && it.all { c -> c.isDigit() } }
                            .forEach { ids += it }
                    }
                }
            }
            // fallback: last 8 messages
            if (ids.isEmpty()) {
                val lines = cmd("A3b", "SEARCH ALL")
                val all = mutableListOf<String>()
                for (line in lines) {
                    if (line.uppercase().startsWith("* SEARCH")) {
                        line.substringAfter("SEARCH").trim().split(" ")
                            .filter { it.isNotBlank() && it.all { c -> c.isDigit() } }
                            .forEach { all += it }
                    }
                }
                ids += all.takeLast(8)
            }

            val out = mutableListOf<Message>()
            for (id in ids.take(12)) {
                val lines = cmd("A4", "FETCH $id (BODY.PEEK[HEADER.FIELDS (SUBJECT FROM)] BODY.PEEK[TEXT])")
                val blob = lines.joinToString("\n")
                val subject = Regex("""Subject:\s*(.+)""", RegexOption.IGNORE_CASE).find(blob)?.groupValues?.get(1)?.trim().orEmpty()
                val from = Regex("""From:\s*(.+)""", RegexOption.IGNORE_CASE).find(blob)?.groupValues?.get(1)?.trim().orEmpty()
                // body is after headers in fetch response
                val body = blob
                val lower = (subject + from + body).lowercase()
                if ("discord" in lower || "verify" in lower || targetEmail.lowercase() in lower) {
                    out += Message(subject, from, body)
                }
            }
            cmd("A5", "LOGOUT")
            socket.close()
            return out
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun quote(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun extractDiscordVerifyUrl(text: String): String? {
        val patterns = listOf(
            Regex("""https?://(?:click\.)?discord(?:app)?\.com/verify[^\s"'<>]+""", RegexOption.IGNORE_CASE),
            Regex("""https?://(?:click\.)?discord(?:app)?\.com/[^\s"'<>]*verify[^\s"'<>]*""", RegexOption.IGNORE_CASE),
            Regex("""https?://click\.discord\.com/[^\s"'<>]+""", RegexOption.IGNORE_CASE)
        )
        for (re in patterns) {
            val m = re.find(text) ?: continue
            var url = m.value.trim().trimEnd('.', ')', ']', '>', '"', '\'')
            // decode common HTML entities
            url = url.replace("&amp;", "&")
            if ("discord" in url.lowercase()) return url
        }
        return null
    }
}
