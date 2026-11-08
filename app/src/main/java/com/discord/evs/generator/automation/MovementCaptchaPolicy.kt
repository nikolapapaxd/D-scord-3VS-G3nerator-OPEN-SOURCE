package com.discord.evs.generator.automation

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * On-device movement policy for captcha gestures (NOT token APIs).
 *
 * What this is:
 * - Learns which normalized (x,y) tap/drag patterns succeed on Discord/hCaptcha WebView challenges
 * - Stores attempt outcomes locally and biases future gesture sequences toward winners
 * - Improves with use on the device (bandit / soft REINFORCE style), no cloud tokens
 *
 * What this is NOT:
 * - A fully trained vision NN that "solves any captcha in the world" out of the box
 * - That requires offline datasets + GPU training (YOLO/classifier) then ship .tflite
 * - Movement policy + heuristics + optional screenshot scan is the realistic on-device path
 *
 * Pipeline the app uses:
 * 1. Detect captcha iframe bounds in WebView
 * 2. Sample actions from this policy (taps/drags in normalized frame coords)
 * 3. Dispatch real MotionEvents (finger movement) via GestureCaptchaSolver
 * 4. Observe whether challenge UI cleared → reward / penalize action sequence
 * 5. Persist weights to filesDir/captcha_policy.json
 */
class MovementCaptchaPolicy(private val context: Context) {

    data class Action(
        val type: String, // tap | drag
        val x: Float,
        val y: Float,
        val x2: Float = x,
        val y2: Float = y
    )

    data class Pattern(
        val id: String,
        val actions: List<Action>,
        var weight: Double,
        var success: Int = 0,
        var fail: Int = 0
    )

    private val file: File = File(context.filesDir, "captcha_policy.json")
    private val patterns: MutableList<Pattern> = defaultPatterns().toMutableList()
    private val temperature = 0.65 // softmax exploration

    init {
        load()
    }

    /** Softmax sample a pattern biased by learned weights. */
    fun sampleSequence(): List<Action> {
        if (patterns.isEmpty()) return defaultPatterns().first().actions
        val maxW = patterns.maxOf { it.weight }
        val exps = patterns.map { exp((it.weight - maxW) / temperature) }
        val sum = exps.sum().coerceAtLeast(1e-9)
        var r = ThreadLocalRandom.current().nextDouble() * sum
        var idx = 0
        for (i in exps.indices) {
            r -= exps[i]
            if (r <= 0) {
                idx = i
                break
            }
            idx = i
        }
        val base = patterns[idx].actions
        // Small jitter so paths are not identical every time (more human)
        return base.map { a ->
            val jx = (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.04
            val jy = (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.04
            a.copy(
                x = (a.x + jx.toFloat()).coerceIn(0.05f, 0.95f),
                y = (a.y + jy.toFloat()).coerceIn(0.05f, 0.95f),
                x2 = (a.x2 + jx.toFloat()).coerceIn(0.05f, 0.95f),
                y2 = (a.y2 + jy.toFloat()).coerceIn(0.05f, 0.95f)
            )
        }
    }

    fun reportOutcome(actions: List<Action>, success: Boolean) {
        // Match nearest pattern by first tap cell
        val key = actions.firstOrNull()?.let { cellKey(it.x, it.y) } ?: return
        val p = patterns.find { it.id.startsWith(key) } ?: patterns.minByOrNull {
            val a = it.actions.firstOrNull() ?: return@minByOrNull 99f
            dist(a.x, a.y, actions.first().x, actions.first().y)
        } ?: return
        if (success) {
            p.success++
            p.weight += 0.35
        } else {
            p.fail++
            p.weight = max(-2.0, p.weight - 0.20)
        }
        // Soft normalize so weights don't explode
        val mean = patterns.map { it.weight }.average()
        patterns.forEach { it.weight = (it.weight - mean) * 0.98 + mean }
        save()
    }

    fun stats(): String {
        val totalS = patterns.sumOf { it.success }
        val totalF = patterns.sumOf { it.fail }
        return "policy patterns=${patterns.size} success=$totalS fail=$totalF"
    }

    private fun cellKey(x: Float, y: Float): String {
        val cx = (x * 3).toInt().coerceIn(0, 2)
        val cy = (y * 3).toInt().coerceIn(0, 2)
        return "c${cx}${cy}"
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    private fun defaultPatterns(): List<Pattern> {
        // Checkbox + grid + verify (classic hCaptcha layout inside Discord)
        fun tap(x: Float, y: Float) = Action("tap", x, y)
        fun drag(x: Float, y: Float, x2: Float, y2: Float) = Action("drag", x, y, x2, y2)
        return listOf(
            Pattern(
                "c00_checkbox_grid",
                listOf(
                    tap(0.12f, 0.08f),
                    tap(0.22f, 0.32f), tap(0.50f, 0.32f), tap(0.78f, 0.32f),
                    tap(0.50f, 0.92f)
                ),
                weight = 1.0
            ),
            Pattern(
                "c11_center_heavy",
                listOf(
                    tap(0.12f, 0.08f),
                    tap(0.50f, 0.45f), tap(0.35f, 0.55f), tap(0.65f, 0.55f),
                    tap(0.72f, 0.92f)
                ),
                weight = 0.8
            ),
            Pattern(
                "c20_right_col",
                listOf(
                    tap(0.12f, 0.08f),
                    tap(0.78f, 0.30f), tap(0.78f, 0.52f), tap(0.78f, 0.72f),
                    tap(0.50f, 0.92f)
                ),
                weight = 0.5
            ),
            Pattern(
                "c01_drag_slider",
                listOf(
                    tap(0.12f, 0.08f),
                    drag(0.18f, 0.55f, 0.82f, 0.55f),
                    tap(0.50f, 0.92f)
                ),
                weight = 0.6
            ),
            Pattern(
                "c12_full_grid",
                listOf(
                    tap(0.12f, 0.08f),
                    tap(0.2f, 0.30f), tap(0.5f, 0.30f), tap(0.8f, 0.30f),
                    tap(0.2f, 0.52f), tap(0.5f, 0.52f), tap(0.8f, 0.52f),
                    tap(0.2f, 0.72f), tap(0.5f, 0.72f), tap(0.8f, 0.72f),
                    tap(0.50f, 0.92f)
                ),
                weight = 0.4
            ),
            Pattern(
                "c22_sparse_human",
                listOf(
                    tap(0.10f, 0.10f),
                    tap(0.30f, 0.40f),
                    tap(0.70f, 0.48f),
                    tap(0.45f, 0.68f),
                    tap(0.70f, 0.90f)
                ),
                weight = 0.7
            )
        )
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("patterns") ?: return
            val loaded = mutableListOf<Pattern>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val actsArr = o.optJSONArray("actions") ?: continue
                val acts = mutableListOf<Action>()
                for (j in 0 until actsArr.length()) {
                    val a = actsArr.optJSONObject(j) ?: continue
                    acts.add(
                        Action(
                            type = a.optString("type", "tap"),
                            x = a.optDouble("x", 0.5).toFloat(),
                            y = a.optDouble("y", 0.5).toFloat(),
                            x2 = a.optDouble("x2", a.optDouble("x", 0.5)).toFloat(),
                            y2 = a.optDouble("y2", a.optDouble("y", 0.5)).toFloat()
                        )
                    )
                }
                loaded.add(
                    Pattern(
                        id = o.optString("id", "p$i"),
                        actions = acts,
                        weight = o.optDouble("weight", 0.5),
                        success = o.optInt("success", 0),
                        fail = o.optInt("fail", 0)
                    )
                )
            }
            if (loaded.isNotEmpty()) {
                patterns.clear()
                patterns.addAll(loaded)
            }
        } catch (e: Exception) {
            Log.w("MovementCaptchaPolicy", "load failed: ${e.message}")
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            patterns.forEach { p ->
                val acts = JSONArray()
                p.actions.forEach { a ->
                    acts.put(
                        JSONObject()
                            .put("type", a.type)
                            .put("x", a.x.toDouble())
                            .put("y", a.y.toDouble())
                            .put("x2", a.x2.toDouble())
                            .put("y2", a.y2.toDouble())
                    )
                }
                arr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("weight", p.weight)
                        .put("success", p.success)
                        .put("fail", p.fail)
                        .put("actions", acts)
                )
            }
            file.writeText(JSONObject().put("patterns", arr).toString())
        } catch (e: Exception) {
            Log.w("MovementCaptchaPolicy", "save failed: ${e.message}")
        }
    }
}
