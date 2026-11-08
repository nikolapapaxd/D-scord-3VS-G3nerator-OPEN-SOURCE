package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Post-generation trial nitro helper.
 * Picks the shortest available Discord quest (prefer WATCH_VIDEO), enrolls, completes via API,
 * then returns a console tip for the operator.
 *
 * Logic adapted from Toji Quester quest completion endpoints.
 */
object TrialQuestTrigger {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val SUPPORTED = listOf(
        "WATCH_VIDEO",
        "WATCH_VIDEO_ON_MOBILE",
        "PLAY_ON_DESKTOP",
        "STREAM_ON_DESKTOP",
        "PLAY_ACTIVITY"
    )

    data class Result(
        val attempted: Boolean,
        val questName: String = "",
        val completed: Boolean = false,
        val message: String = ""
    )

    private fun headers(token: String): Map<String, String> = mapOf(
        "Authorization" to token,
        "Content-Type" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.9175 Chrome/128.0.6613.186 Electron/32.2.7 Safari/537.36",
        "X-Super-Properties" to "eyJvcyI6IldpbmRvd3MiLCJicm93c2VyIjoiRGlzY29yZCBDbGllbnQiLCJyZWxlYXNlX2NoYW5uZWwiOiJzdGFibGUiLCJjbGllbnRfdmVyc2lvbiI6IjEuMC45MTc1Iiwib3NfdmVyc2lvbiI6IjEwLjAuMjYxMDAiLCJvc19hcmNoIjoieDY0IiwiYXBwX2FyY2giOiJ4NjQiLCJzeXN0ZW1fbG9jYWxlIjoiZW4tVVMiLCJicm93c2VyX3VzZXJfYWdlbnQiOiJNb3ppbGxhLzUuMCAoV2luZG93cyBOVCAxMC4wOyBXaW42NDsgeDY0KSBBcHBsZVdlYktpdC81MzcuMzYgKEtIVE1MLCBsaWtlIEdlY2tvKSBkaXNjb3JkLzEuMC45MTc1IENocm9tZS8xMjguMC42NjEzLjE4NiBFbGVjdHJvbi8zMi4yLjcgU2FmYXJpLzUzNy4zNiIsImJyb3dzZXJfdmVyc2lvbiI6IjMyLjIuNyIsImNsaWVudF9idWlsZF9udW1iZXIiOjUwNDY0OSwibmF0aXZlX2J1aWxkX251bWJlciI6NTk0OTgsImNsaWVudF9ldmVudF9zb3VyY2UiOm51bGx9",
        "Origin" to "https://discord.com",
        "Referer" to "https://discord.com/channels/@me"
    )

    private fun get(path: String, token: String): Pair<Int, String> {
        val req = Request.Builder().url("https://discord.com/api/v9$path").get()
            .apply { headers(token).forEach { (k, v) -> addHeader(k, v) } }.build()
        client.newCall(req).execute().use { r -> return r.code to (r.body?.string().orEmpty()) }
    }

    private fun post(path: String, token: String, body: JSONObject): Pair<Int, String> {
        val req = Request.Builder().url("https://discord.com/api/v9$path")
            .post(body.toString().toRequestBody(JSON))
            .apply { headers(token).forEach { (k, v) -> addHeader(k, v) } }.build()
        client.newCall(req).execute().use { r -> return r.code to (r.body?.string().orEmpty()) }
    }

    private fun taskConfig(q: JSONObject): JSONObject? {
        val cfg = q.optJSONObject("config") ?: return null
        return cfg.optJSONObject("taskConfig")
            ?: cfg.optJSONObject("task_config")
            ?: cfg.optJSONObject("taskConfigV2")
            ?: cfg.optJSONObject("task_config_v2")
    }

    private fun questName(q: JSONObject): String {
        val cfg = q.optJSONObject("config") ?: return "Quest"
        val msgs = cfg.optJSONObject("messages")
        msgs?.optString("questName")?.takeIf { it.isNotBlank() }?.let { return it }
        msgs?.optString("gameTitle")?.takeIf { it.isNotBlank() }?.let { return it }
        return cfg.optJSONObject("application")?.optString("name")?.takeIf { it.isNotBlank() }
            ?: "Quest#${q.optString("id")}"
    }

    private fun taskType(q: JSONObject): String? {
        val tasks = taskConfig(q)?.optJSONObject("tasks") ?: return null
        for (t in SUPPORTED) if (tasks.has(t) && !tasks.isNull(t)) return t
        return null
    }

    private fun secondsNeeded(q: JSONObject): Int {
        val t = taskType(q) ?: return 0
        return taskConfig(q)?.optJSONObject("tasks")?.optJSONObject(t)?.optInt("target", 0) ?: 0
    }

    private fun isCompleted(q: JSONObject): Boolean {
        val us = q.optJSONObject("userStatus") ?: q.optJSONObject("user_status") ?: return false
        return us.has("completedAt") || us.has("completed_at")
    }

    private fun isEnrolled(q: JSONObject): Boolean {
        val us = q.optJSONObject("userStatus") ?: q.optJSONObject("user_status") ?: return false
        return us.has("enrolledAt") || us.has("enrolled_at")
    }

    suspend fun run(token: String, log: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        try {
            val (code, body) = get("/quests/@me", token)
            if (code == 429) {
                log("[!] Trial trigger rate-limited on quest list")
                return@withContext Result(true, message = "rate limited")
            }
            if (code != 200) {
                log("[!] Trial trigger: quest list HTTP $code")
                return@withContext Result(true, message = "HTTP $code")
            }
            val root = JSONObject(body)
            val arr = root.optJSONArray("quests") ?: org.json.JSONArray()
            val candidates = mutableListOf<Triple<JSONObject, String, Int>>()
            for (i in 0 until arr.length()) {
                val q = arr.optJSONObject(i) ?: continue
                if (isCompleted(q)) continue
                val type = taskType(q) ?: continue
                val need = secondsNeeded(q)
                if (need <= 0) continue
                candidates += Triple(q, type, need)
            }
            if (candidates.isEmpty()) {
                log("[!] Trial trigger: no completable quests on this account")
                return@withContext Result(true, message = "no quests")
            }
            // Prefer shortest video tasks
            candidates.sortWith(
                compareBy<Triple<JSONObject, String, Int>> {
                    when {
                        it.second.startsWith("WATCH_VIDEO") -> 0
                        else -> 1
                    }
                }.thenBy { it.third }
            )
            val (quest, type, need) = candidates.first()
            val qid = quest.optString("id")
            val name = questName(quest)
            log("[!] Trial trigger: selecting \"$name\" ($type, ${need}s)")

            if (!isEnrolled(quest)) {
                val enrollBody = JSONObject()
                    .put("location", 11)
                    .put("is_targeted", false)
                val (ec, _) = post("/quests/$qid/enroll", token, enrollBody)
                if (ec !in listOf(200, 201, 204)) {
                    log("[!] Trial trigger enroll failed HTTP $ec for $name")
                    return@withContext Result(true, name, false, "enroll $ec")
                }
                delay(1500)
            }

            val ok = when {
                type.startsWith("WATCH_VIDEO") -> completeVideo(token, qid, need, log)
                else -> completeHeartbeat(token, qid, type, need, log)
            }
            val tip = if (ok) {
                "[!] Trial trigger attempted on \"$name\". Please login onto this account, join one server and ping someone in any server and wait 2 days — nitro trial may trigger on the account."
            } else {
                "[!] Trial trigger ran on \"$name\" but completion was incomplete — still try login → join 1 server → ping → wait 2 days."
            }
            log(tip)
            Result(true, name, ok, tip)
        } catch (e: Exception) {
            log("[!] Trial trigger error: ${e.message}")
            Result(true, message = e.message ?: "error")
        }
    }

    private suspend fun completeVideo(token: String, qid: String, need: Int, log: (String) -> Unit): Boolean {
        var done = 0.0
        val speed = 7.0
        val enrolledTs = System.currentTimeMillis() / 1000.0
        while (done < need) {
            val maxAllowed = (System.currentTimeMillis() / 1000.0 - enrolledTs) + 10.0
            if (maxAllowed - done < speed) {
                delay(1000)
                continue
            }
            val ts = minOf(need.toDouble(), done + speed + Random.nextDouble())
            val (code, body) = post("/quests/$qid/video-progress", token, JSONObject().put("timestamp", ts))
            if (code == 429) {
                delay(5000)
                continue
            }
            if (code == 200) {
                try {
                    val j = JSONObject(body)
                    if (j.has("completed_at") || j.optString("completed_at").isNotBlank()) {
                        log("[!] Trial video quest completed")
                        return true
                    }
                } catch (_: Exception) {}
                done = ts
                if (done.toInt() % 20 < speed) log("[!] Trial video ${done.toInt()}/$need s")
            }
            if (ts >= need) break
            delay(1000)
        }
        post("/quests/$qid/video-progress", token, JSONObject().put("timestamp", need))
        return true
    }

    private suspend fun completeHeartbeat(token: String, qid: String, type: String, need: Int, log: (String) -> Unit): Boolean {
        var done = 0.0
        val pid = Random.nextInt(1000, 30000)
        val streamKey = "call:0:$pid"
        while (done < need) {
            val (code, body) = post(
                "/quests/$qid/heartbeat",
                token,
                JSONObject().put("stream_key", streamKey).put("terminal", false)
            )
            if (code == 429) {
                delay(8000)
                continue
            }
            if (code == 200) {
                try {
                    val j = JSONObject(body)
                    val prog = j.optJSONObject("progress")
                    if (prog != null && prog.has(type)) {
                        done = prog.optJSONObject(type)?.optDouble("value", done) ?: done
                    }
                    if (j.has("completed_at") || done >= need) {
                        log("[!] Trial heartbeat quest completed")
                        return true
                    }
                } catch (_: Exception) {}
                log("[!] Trial heartbeat ${done.toInt()}/$need s")
            }
            delay(20_000)
        }
        post("/quests/$qid/heartbeat", token, JSONObject().put("stream_key", streamKey).put("terminal", true))
        return true
    }

    /**
     * After trial quests complete: optionally join a target guild via invite
     * and post a ping in a channel (prefs.trialJoinInvite / trialPingChannelId / trialPingText).
     */
    suspend fun joinAndPing(
        token: String,
        inviteCode: String,
        channelId: String,
        message: String,
        log: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (inviteCode.isNotBlank()) {
                val code = inviteCode.substringAfterLast('/').trim()
                val (jc, jb) = post("/invites/$code", token, JSONObject())
                log("[!] Trial join invite HTTP $jc")
                if (jc !in listOf(200, 204) && jc != 400) {
                    // 400 often already member
                    if (jc == 429) delay(5000)
                }
            }
            if (channelId.isNotBlank() && message.isNotBlank()) {
                val body = JSONObject().put("content", message)
                val req = Request.Builder()
                    .url("https://discord.com/api/v9/channels/$channelId/messages")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .header("Authorization", token)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    log("[!] Trial ping HTTP ${resp.code}")
                    return@withContext resp.isSuccessful
                }
            }
            true
        } catch (e: Exception) {
            log("[!] Trial join/ping error: ${e.message}")
            false
        }
    }

}
