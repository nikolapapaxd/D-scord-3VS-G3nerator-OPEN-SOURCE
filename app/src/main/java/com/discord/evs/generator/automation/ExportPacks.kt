package com.discord.evs.generator.automation

import com.discord.evs.generator.data.Account

object ExportPacks {
    fun tokensOnly(accounts: List<Account>): String =
        accounts.map { it.token }.filter { it.isNotBlank() }.joinToString("\n")

    fun emailPass(accounts: List<Account>): String =
        accounts.filter { it.email.isNotBlank() && it.password.isNotBlank() }
            .joinToString("\n") { "${it.email}:${it.password}" }

    fun fullyVerified(accounts: List<Account>): String =
        accounts.filter {
            it.token.isNotBlank() &&
                (it.emailVerified || it.emailStatus.equals("VERIFIED", true)) &&
                it.phoneStatus.equals("VERIFIED", true)
        }.joinToString("\n") { "${it.email}:${it.password}:${it.token}" }

    fun phoneVerified(accounts: List<Account>): String =
        accounts.filter { it.phoneStatus.equals("VERIFIED", true) && it.token.isNotBlank() }
            .joinToString("\n") { "${it.email}:${it.password}:${it.token}" }

    fun partialPending(accounts: List<Account>): String =
        accounts.filter {
            val e = it.emailStatus.uppercase()
            val ph = it.phoneStatus.uppercase()
            e.contains("PEND") || ph.contains("PEND") || ph.contains("ASKED") || e == "NOT_ASKED" || ph == "NOT_ASKED"
        }.joinToString("\n") {
            "${it.email}|email=${it.emailStatus}|phone=${it.phoneStatus}|token=${it.token.take(12).ifBlank { "-" }}"
        }
}
