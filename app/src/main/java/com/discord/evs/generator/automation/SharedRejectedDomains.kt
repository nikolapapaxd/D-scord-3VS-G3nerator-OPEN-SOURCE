package com.discord.evs.generator.automation

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide rejected temp-mail domains shared across all AccountGenerator /
 * MultiGenSession workers so multi-gen does not re-try Discord-banned domains.
 *
 * Seeded from public telemetry channel t.me/excludedmaildomains (DOMAIN_REJECTED).
 */
object SharedRejectedDomains {
    private val domains = ConcurrentHashMap.newKeySet<String>()

    /** Hard seeds from operator report channel — always rejected at process start. */
    private val SEED = setOf(
        "mikethe.guru",
        "1secmail.org",
        "mizuhara.my.id",
        "moonstarsun.shop",
        "happyhorsespace.shop",
        "uberip.com",
        "guerrillamailblock.com",
    )

    init {
        SEED.forEach { domains.add(it) }
    }

    fun add(domain: String) {
        val d = domain.trim().lowercase().removePrefix("@")
        if (d.isNotBlank()) domains.add(d)
    }

    fun addAll(list: Collection<String>) {
        list.forEach { add(it) }
    }

    fun contains(domain: String): Boolean {
        val d = domain.trim().lowercase().removePrefix("@")
        return d.isNotBlank() && domains.contains(d)
    }

    fun snapshot(): Set<String> = domains.toSet()

    fun clear() = domains.clear()
}
