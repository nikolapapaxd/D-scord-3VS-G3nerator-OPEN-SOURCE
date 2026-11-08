package com.discord.evs.generator.automation

import android.content.Context
import com.discord.evs.generator.data.Prefs
import org.json.JSONObject

/** Tracks per-solver success/fail/time for auto priority. */
object CaptchaAnalytics {
    fun record(context: Context, solver: String, success: Boolean, elapsedMs: Long) {
        val prefs = Prefs(context)
        val root = try { JSONObject(prefs.captchaStatsJson) } catch (_: Exception) { JSONObject() }
        val key = solver.ifBlank { "unknown" }
        val node = root.optJSONObject(key) ?: JSONObject()
        val ok = node.optInt("ok") + if (success) 1 else 0
        val fail = node.optInt("fail") + if (success) 0 else 1
        val totalTime = node.optLong("totalMs") + elapsedMs
        node.put("ok", ok)
        node.put("fail", fail)
        node.put("totalMs", totalTime)
        node.put("avgMs", if (ok + fail > 0) totalTime / (ok + fail) else 0)
        node.put("rate", if (ok + fail > 0) ok.toDouble() / (ok + fail) else 0.0)
        root.put(key, node)
        prefs.captchaStatsJson = root.toString()
    }

    fun summary(context: Context): String {
        val prefs = Prefs(context)
        val root = try { JSONObject(prefs.captchaStatsJson) } catch (_: Exception) { return "No captcha stats yet" }
        if (root.length() == 0) return "No captcha stats yet"
        val sb = StringBuilder()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val n = root.getJSONObject(k)
            sb.append("• $k — ok=${n.optInt("ok")} fail=${n.optInt("fail")} rate=${"%.0f".format(n.optDouble("rate") * 100)}% avg=${n.optLong("avgMs")}ms\n")
        }
        return sb.toString().trim()
    }

    /** Returns solvers sorted by success rate desc. */
    fun rankedSolvers(context: Context): List<String> {
        val prefs = Prefs(context)
        val root = try { JSONObject(prefs.captchaStatsJson) } catch (_: Exception) { return emptyList() }
        val list = mutableListOf<Pair<String, Double>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            list += k to root.getJSONObject(k).optDouble("rate")
        }
        return list.sortedByDescending { it.second }.map { it.first }
    }
}
