package com.discord.evs.generator.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.random.Random

object Humanizer {
    data class Profile(
        val displayName: String,
        val usernameStem: String,
        val bio: String,
        val pronouns: String,
        val customStatus: String,
        val hypeHouseId: Int,
        val avatarBase64Png: String?
    )

    private var names: List<String> = emptyList()
    private var bios: List<String> = emptyList()
    private var pronouns: List<String> = emptyList()
    private var statuses: List<String> = emptyList()
    private var usernames: List<String> = emptyList()
    private var avatarPaths: List<String> = emptyList()
    @Volatile private var loaded = false

    private val GENZ_NAMES = listOf(
        "kai", "luna", "nova", "zay", "mila", "jax", "aria", "leo", "zoe", "nyx",
        "remi", "sloane", "knox", "ivy", "ash", "river", "skye", "orion", "hazel", "finn",
        "vex", "kira", "odin", "ember", "sage", "ryu", "cleo", "jaxx", "wren", "theo",
        "lowkey", "aura", "drip king", "quiet kid", "night owl", "sigma", "soft launch"
    )
    private val GENZ_USERNAMES = listOf(
        "notkai", "luvnova", "zzzay", "onlymila", "jaxcore", "ariawav", "le0nly",
        "zo3vibes", "nyxvoid", "remixx", "sloanesoft", "knoxed", "ivyloop", "ashr0t",
        "riverrun", "skyeline", "orionx", "hazelnut", "finnix", "vexed", "kiraa",
        "odinly", "emberlit", "sagecore", "ryurun", "cle0", "jaxxed", "wrentxt",
        "theonly", "lowk3y", "nocapx", "itsgiving", "mcenergy", "softlnch",
        "auramaxx", "dripkit", "qtkid", "niteowl", "doomscrl", "brnrot", "sigmode",
        "y2kvibes", "corecore", "delulu", "rizzler", "gyatt", "skibidi", "ohio",
        "mewing", "fanum", "grindset", "glowup", "npcmode", "realone", "vibeonly"
    )

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val am = context.assets
            fun readLines(path: String): List<String> = try {
                am.open(path).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
            } catch (_: Exception) {
                emptyList()
            }
            val loadedNames = readLines("humanizer_data/names.txt")
            val loadedUsers = readLines("humanizer_data/usernames.txt")
            names = if (loadedNames.isEmpty()) GENZ_NAMES else (loadedNames + GENZ_NAMES).distinct()
            usernames = if (loadedUsers.isEmpty()) GENZ_USERNAMES else (loadedUsers + GENZ_USERNAMES).distinct()
            bios = readLines("humanizer_data/bios.txt")
            if (bios.isEmpty()) bios = listOf(
                "just chilling",
                "discord enjoyer",
                "lowkey vibing"
            )
            pronouns = readLines("humanizer_data/pronouns.txt")
            statuses = readLines("humanizer_data/statuses.txt")
            avatarPaths = try {
                am.list("humanizer_avatars")?.map { "humanizer_avatars/$it" }?.toList().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            loaded = true
        }
    }

    fun randomProfile(context: Context, includeAvatar: Boolean = true): Profile {
        ensureLoaded(context)
        return Profile(
            displayName = names.randomOrNull() ?: GENZ_NAMES.random(),
            usernameStem = usernames.randomOrNull() ?: GENZ_USERNAMES.random(),
            bio = bios.randomOrNull() ?: "",
            pronouns = pronouns.randomOrNull() ?: "they/them",
            customStatus = statuses.randomOrNull() ?: "chilling",
            hypeHouseId = listOf(1, 2, 3).random(),
            avatarBase64Png = if (includeAvatar) randomAvatarPngBase64(context) else null
        )
    }

    fun randomDisplayName(context: Context): String {
        ensureLoaded(context)
        return names.ifEmpty { GENZ_NAMES }.random()
    }

    fun randomUsernameStem(context: Context): String {
        ensureLoaded(context)
        return usernames.ifEmpty { GENZ_USERNAMES }.random()
    }

    fun randomAvatarPngBase64(context: Context): String? {
        ensureLoaded(context)
        if (avatarPaths.isEmpty()) return null
        return try {
            val path = avatarPaths.random()
            context.assets.open(path).use { input ->
                val raw = BitmapFactory.decodeStream(input) ?: return null
                // Downscale large assets so Discord accepts the PATCH body
                val maxSide = 256  // Shinchan-style square target
                val scale = minOf(1f, maxSide.toFloat() / maxOf(raw.width, raw.height))
                val bmp = if (scale < 1f) {
                    android.graphics.Bitmap.createScaledBitmap(raw, 256, 256, true)
                } else raw
                val out = ByteArrayOutputStream()
                // JPEG is smaller and more reliable for Discord avatar PATCH
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun hypeHouseName(id: Int): String = when (id) {
        1 -> "Bravery"
        2 -> "Brilliance"
        3 -> "Balance"
        else -> "House$id"
    }
}
