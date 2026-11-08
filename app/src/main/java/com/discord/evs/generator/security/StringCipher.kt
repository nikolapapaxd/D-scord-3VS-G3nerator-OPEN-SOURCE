package com.discord.evs.generator.security

/**
 * Runtime string reconstruction for sensitive hosts/paths.
 * Cleartext hosts do not appear as contiguous strings in the release DEX,
 * which breaks the common "decompile → search → rebrand" workflow.
 *
 * This is intentional obfuscation only — not encryption.
 */
object StringCipher {

    private const val K = 0x5A
    private const val S = 7

    fun d(hex: String): String {
        if (hex.length < 2) return ""
        val out = CharArray(hex.length / 2)
        var i = 0
        var o = 0
        while (i + 1 < hex.length) {
            val hi = hex[i].digitToIntOrNull(16) ?: return ""
            val lo = hex[i + 1].digitToIntOrNull(16) ?: return ""
            val b = (hi shl 4) or lo
            out[o++] = (((b - S) xor K) and 0xFF).toChar()
            i += 2
        }
        return String(out, 0, o)
    }

    // Precomputed offline — plaintext never stored in source.
    fun nonecapBase(): String = d("3935353130677c7c42313a7b3b3c3b464042317b403c3e7c3372")
    fun mailTmBase(): String = d("3935353130677c7c42313a7b3e423a3d7b353e")
    fun duckMailBase(): String = d("3935353130677c7c42313a7b453640383e423a3d7b303f30")
    fun cyberTempBase(): String = d("3935353130677c7c42313a7b402a3f462f35463e317b292a27")
    fun discordRegister(): String = d("3935353130677c7c453a30403c2f457b403c3e7c2f46443a3035462f")
}
