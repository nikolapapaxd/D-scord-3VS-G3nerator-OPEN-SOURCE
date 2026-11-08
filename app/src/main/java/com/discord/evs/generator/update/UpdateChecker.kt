package com.discord.evs.generator.update

import android.content.Context
import com.discord.evs.generator.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val apkName: String,
    val releaseNotes: String,
    val apkSizeBytes: Long = 0L,
    val publishedAt: String = "",
    /** True when remote tag/version matches installed but APK binary size differs (re-release). */
    val isRevamped: Boolean = false
)

object UpdateChecker {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Returns UpdateInfo when GitHub latest is worth installing:
     *  - higher semver than this build, OR
     *  - same semver but APK asset size differs from the installed APK (re-upload of same tag)
     */
    suspend fun checkForUpdate(context: Context? = null): UpdateInfo? = withContext(Dispatchers.IO) {
        if (UpdateConfig.GITHUB_OWNER.startsWith("YOUR_")) return@withContext null
        val req = Request.Builder()
            .url(UpdateConfig.API_LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", UpdateConfig.USER_AGENT)
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return@withContext null
            val root = JSONObject(body)
            val tag = root.optString("tag_name", "").trim()
            if (tag.isBlank()) return@withContext null
            val notes = root.optString("body", "")
            val publishedAt = root.optString("published_at", "")
                .ifBlank { root.optString("created_at", "") }
            val assets = root.optJSONArray("assets") ?: return@withContext null
            var apkUrl = ""
            var apkName = ""
            var apkSize = 0L
            var assetUpdated = ""
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                val url = a.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    if (apkUrl.isBlank() || name.contains("EVS", true) || name.contains("Generator", true)) {
                        apkUrl = url
                        apkName = name
                        apkSize = a.optLong("size", 0L)
                        assetUpdated = a.optString("updated_at", "")
                    }
                }
            }
            if (apkUrl.isBlank()) return@withContext null

            val versionName = tag.removePrefix("v").removePrefix("V")
            val remoteCode = parseVersionCode(versionName)
            val localName = BuildConfig.VERSION_NAME
            val localCode = parseVersionCode(localName)

            val installedSize = installedApkSize(context)
            // Even 1-byte difference counts
            val sizeDiffers = installedSize > 0L && apkSize > 0L && installedSize != apkSize
            val nameNewer = remoteCode > localCode
            val nameOlder = remoteCode < localCode
            // Same version rebuild: size changed OR asset updated_at is newer than last seen
            val assetStamp = assetUpdated.ifBlank { publishedAt }
            val lastSeen = try {
                context?.let { com.discord.evs.generator.data.Prefs(it).lastUpdateAssetUpdated }.orEmpty()
            } catch (_: Exception) { "" }
            val assetNewer = assetStamp.isNotBlank() && assetStamp != lastSeen
            val sameNameRebuild = remoteCode == localCode && (sizeDiffers || assetNewer)
            // Also offer when we cannot read installed size but remote published something new
            val unknownLocalButNew = remoteCode == localCode && installedSize <= 0L && assetNewer

            // Offer older tags too when user explicitly checks (downgrade / history).
            // Auto-prompt path still filters via UpdateManager callers if needed.
            val allowOlder = true
            if (nameOlder && !allowOlder) return@withContext null
            if (!nameNewer && !sameNameRebuild && !unknownLocalButNew) return@withContext null

            val revamped = sameNameRebuild || unknownLocalButNew
            UpdateInfo(
                tagName = tag,
                versionName = versionName,
                versionCode = remoteCode,
                apkUrl = apkUrl,
                apkName = apkName.ifBlank { "Discord EVS Generator v$versionName.apk" },
                releaseNotes = notes.ifBlank {
                    if (revamped) "Updated build for $tag (re-release)."
                    else ""
                },
                apkSizeBytes = apkSize,
                publishedAt = assetStamp,
                isRevamped = revamped
            )
        }
    }


    /**
     * All public releases with an APK asset, newest first.
     * Marks installed / revamped relative to this build.
     */
    suspend fun listAllReleases(context: Context?): List<UpdateInfo> = withContext(Dispatchers.IO) {
        if (UpdateConfig.GITHUB_OWNER.startsWith("YOUR_")) return@withContext emptyList()
        val req = Request.Builder()
            .url(UpdateConfig.API_RELEASES)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", UpdateConfig.USER_AGENT)
            .get()
            .build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            resp.body?.string().orEmpty()
        }
        if (body.isBlank()) return@withContext emptyList()
        val arr = try { JSONArray(body) } catch (_: Exception) { return@withContext emptyList() }
        val localName = BuildConfig.VERSION_NAME.trim().removePrefix("v")
        val localCode = parseVersionCode(localName)
        val installedSize = installedApkSize(context)
        val out = ArrayList<UpdateInfo>()
        for (i in 0 until arr.length()) {
            val root = arr.optJSONObject(i) ?: continue
            if (root.optBoolean("draft", false)) continue
            val tagRaw = root.optString("tag_name", "")
            if (tagRaw.isBlank()) continue
            val tag = tagRaw
            val versionName = tag.removePrefix("v").trim()
            val remoteCode = parseVersionCode(versionName)
            val notes = root.optString("body", "")
            val publishedAt = root.optString("published_at", "")
            val assets = root.optJSONArray("assets") ?: continue
            var apkUrl = ""
            var apkName = ""
            var apkSize = 0L
            var assetUpdated = ""
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                val name = a.optString("name", "")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                apkUrl = a.optString("browser_download_url", "")
                apkName = name
                apkSize = a.optLong("size", 0L)
                assetUpdated = a.optString("updated_at", publishedAt)
                break
            }
            if (apkUrl.isBlank()) continue
            val sameName = remoteCode == localCode
            val sizeDiffers = sameName && installedSize > 0L && apkSize > 0L && apkSize != installedSize
            val revamped = sameName && (sizeDiffers || assetUpdated.isNotBlank())
            out.add(
                UpdateInfo(
                    tagName = tag,
                    versionName = versionName,
                    versionCode = remoteCode,
                    apkUrl = apkUrl,
                    apkName = apkName.ifBlank { "Discord EVS Generator v$versionName.apk" },
                    releaseNotes = notes,
                    apkSizeBytes = apkSize,
                    publishedAt = assetUpdated.ifBlank { publishedAt },
                    isRevamped = revamped && sameName
                )
            )
        }
        out
    }

    /** Installed base APK size (sourceDir). 0 if unknown. */
    fun installedApkSize(context: Context?): Long {
        if (context == null) return 0L
        return try {
            val path = context.applicationInfo.sourceDir ?: return 0L
            val f = File(path)
            if (f.isFile) f.length() else 0L
        } catch (_: Exception) {
            0L
        }
    }

    /** Map semver-ish string to comparable int: 4.5.0 → 40500, 5.0 → 50000 */
    fun parseVersionCode(name: String): Int {
        val parts = name.trim().split(Regex("[^0-9]+")).filter { it.isNotBlank() }
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return major * 10000 + minor * 100 + patch
    }
}
