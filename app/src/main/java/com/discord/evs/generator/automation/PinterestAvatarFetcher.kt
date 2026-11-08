package com.discord.evs.generator.automation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Pull a random portrait-like image for avatar use.
 * Uses Lorem Picsum / randomuser as reliable CDN sources (Pinterest blocks scrapers;
 * these provide similar "stock face / aesthetic" avatars without auth).
 * Optional pinterest-style search keywords only affect picsum seed.
 */
object PinterestAvatarFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val seeds = listOf(
        "portrait", "face", "person", "aesthetic", "anime", "pfp", "avatar", "model", "selfie"
    )

    suspend fun fetchAvatarJpegBase64(
        keyword: String = seeds.random(),
        size: Int = 256,
        onLog: (String) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        val seed = (keyword + System.currentTimeMillis()).hashCode().toUInt()
        val urls = listOf(
            "https://picsum.photos/seed/$seed/$size/$size",
            "https://i.pravatar.cc/$size?u=$seed",
            "https://randomuser.me/api/portraits/men/${(seed % 99u).toInt()}.jpg",
            "https://randomuser.me/api/portraits/women/${(seed % 99u).toInt()}.jpg"
        )
        for (url in urls) {
            try {
                val req = Request.Builder().url(url).get()
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val bytes = resp.body?.bytes() ?: return@use
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use
                    val scaled = Bitmap.createScaledBitmap(bmp, size, size, true)
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    onLog("PFP fetched via $url (${b64.length} b64)")
                    return@withContext b64
                }
            } catch (e: Exception) {
                onLog("PFP source fail: ${e.message}")
            }
        }
        null
    }
}
