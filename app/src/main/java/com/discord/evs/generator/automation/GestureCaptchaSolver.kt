package com.discord.evs.generator.automation

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Native WebView gesture / vision captcha path.
 *
 * Free solvers:
 *  - builtin_geo  — geometric taps (checkbox + Verify zone on largest hCaptcha frame)
 *  - builtin_scan — same + simple bottom-band density heuristic on a screenshot
 *
 * Optional:
 *  - vision_http  — POST screenshot to user-configured URL; expects JSON actions
 *
 * Cross-origin iframe DOM is not accessible from page JS. Real presses use
 * [MotionEvent] on the WebView at screen/local coordinates reported by JS.
 */
class GestureCaptchaSolver(
    private val log: (String, LogLevel) -> Unit,
    private val appContext: android.content.Context? = null
) {

    data class FrameRect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    ) {
        val area: Float get() = width * height
        fun point(nx: Float, ny: Float): Pair<Float, Float> =
            left + width * nx.coerceIn(0f, 1f) to top + height * ny.coerceIn(0f, 1f)
    }

    data class GestureAction(
        val type: String, // tap | drag
        val x: Float,
        val y: Float,
        val x2: Float = x,
        val y2: Float = y,
        val normalized: Boolean = true // if true, x/y are 0..1 relative to captcha frame
    )

    /**
     * Run free geometric + optional vision sequence on [webView].
     * @return true if challenge iframe appears gone afterward (best-effort).
     */
    suspend fun solve(
        webView: WebView,
        injectJs: (String) -> Unit,
        provider: String,
        visionApiUrl: String,
        aiApiKey: String = "",
        aiBaseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        aiModel: String = "gemini-2.0-flash",
        aiProvider: String = "gemini",
        timeoutMs: Long = 90_000L
    ): Boolean = withContext(Dispatchers.Main) {
        log("Gesture solver ($provider) starting…", LogLevel.CAPTCHA)
        val deadline = SystemClock.uptimeMillis() + timeoutMs

        // 1) Wait for captcha UI — page/challenge can still be loading
        var frame: FrameRect? = null
        val waitDeadline = minOf(deadline, SystemClock.uptimeMillis() + 45_000L)
        var attempt = 0
        while (SystemClock.uptimeMillis() < waitDeadline) {
            attempt++
            val found = queryLargestCaptchaFrame(webView, injectJs)
            frame = found
            if (found != null && found.area >= 5_000f) break

            // Checkbox-only state: tap likely checkbox zones, then re-query
            if (attempt == 1 || attempt % 3 == 0) {
                log("Waiting for captcha frame (try $attempt) — checkbox heuristics", LogLevel.INFO)
                val w = webView.width.toFloat().coerceAtLeast(1f)
                val h = webView.height.toFloat().coerceAtLeast(1f)
                // Common Discord register checkbox positions
                listOf(
                    0.10f to 0.52f,
                    0.12f to 0.58f,
                    0.08f to 0.62f,
                    0.15f to 0.48f
                ).forEach { (nx, ny) ->
                    tapWebView(webView, w * nx, h * ny)
                    delay(350)
                }
            }
            delay(900)
            val found2 = queryLargestCaptchaFrame(webView, injectJs)
            frame = found2
            if (found2 != null && found2.area >= 5_000f) break
        }

        val captchaFrame = frame
        if (captchaFrame == null || captchaFrame.area < 5_000f) {
            log("Gesture solver: no captcha frame after wait (${attempt} tries)", LogLevel.WARNING)
            return@withContext false
        }

        log(
            "Captcha frame ~ ${captchaFrame.width.toInt()}×${captchaFrame.height.toInt()} @ (${captchaFrame.left.toInt()},${captchaFrame.top.toInt()})",
            LogLevel.INFO
        )

        when (provider.lowercase().trim()) {
            "ai_vision", "ai", "openai", "gpt" -> {
                if (aiApiKey.isBlank()) {
                    log("AI Vision selected but API key empty — falling back to builtin_geo", LogLevel.WARNING)
                    runBuiltinGeo(webView, captchaFrame)
                } else {
                    runAiVision(webView, injectJs, captchaFrame, aiApiKey, aiBaseUrl, aiModel, aiProvider)
                }
            }
            "vision_http", "vision" -> {
                if (visionApiUrl.isBlank()) {
                    log("vision_http selected but URL empty — falling back to builtin_geo", LogLevel.WARNING)
                    runBuiltinGeo(webView, captchaFrame)
                } else {
                    runVisionHttp(webView, injectJs, captchaFrame, visionApiUrl)
                }
            }
            "builtin_scan", "scan" -> {
                runBuiltinScan(webView, captchaFrame)
            }
            else -> {
                // builtin_geo (default free)
                runBuiltinGeo(webView, captchaFrame)
            }
        }

        // Wait for frame to shrink / vanish
        while (SystemClock.uptimeMillis() < deadline) {
            delay(700)
            val now = queryLargestCaptchaFrame(webView, injectJs)
            if (now == null || now.area < captchaFrame.area * 0.35f || now.height < 80f) {
                log("Gesture solver: challenge UI looks cleared", LogLevel.SUCCESS)
                lastPolicy?.let { pol -> lastPolicyActions?.let { pol.reportOutcome(it, true) } }
                return@withContext true
            }
            // Extra Verify-zone taps while still visible
            val (vx, vy) = now.point(0.5f, 0.90f)
            tapWebView(webView, vx, vy)
        }
        log("Gesture solver: timed out (challenge may still be visible)", LogLevel.WARNING)
        lastPolicy?.let { pol -> lastPolicyActions?.let { pol.reportOutcome(it, false) } }
        false
    }

    private suspend fun runBuiltinGeo(webView: WebView, frame: FrameRect) {
        log("Free solver: movement policy (on-device learned gestures)", LogLevel.INFO)
        val ctx = appContext ?: webView.context.applicationContext
        val policy = MovementCaptchaPolicy(ctx)
        val seq = policy.sampleSequence()
        log("Policy sample: ${seq.size} gestures · ${policy.stats()}", LogLevel.INFO)
        for (a in seq) {
            when (a.type) {
                "drag" -> {
                    val (x1, y1) = frame.point(a.x, a.y)
                    val (x2, y2) = frame.point(a.x2, a.y2)
                    dragWebView(webView, x1, y1, x2, y2)
                }
                else -> {
                    tapAt(webView, frame, a.x, a.y)
                }
            }
            delay(280L + (Math.random() * 180).toLong())
        }
        // Always finish with Verify-zone taps
        tapAt(webView, frame, 0.50f, 0.92f)
        delay(350)
        tapAt(webView, frame, 0.72f, 0.92f)
        delay(500)
        lastPolicyActions = seq
        lastPolicy = policy
    }

    // Used to reinforce policy after solve() observes challenge cleared / failed
    private var lastPolicyActions: List<MovementCaptchaPolicy.Action>? = null
    private var lastPolicy: MovementCaptchaPolicy? = null


    private suspend fun runBuiltinScan(webView: WebView, frame: FrameRect) {
        log("Free solver: builtin_scan (screenshot bottom-band + geo)", LogLevel.INFO)
        val bmp = captureWebView(webView)
        if (bmp != null) {
            try {
                val taps = findBrightBottomButtons(bmp, frame, webView.width, webView.height)
                taps.forEach { (x, y) ->
                    tapWebView(webView, x, y)
                    delay(350)
                }
            } finally {
                bmp.recycle()
            }
        }
        runBuiltinGeo(webView, frame)
    }

    /**
     * Multi-provider vision:
     *  - gemini (free tier) — Google AI Studio
     *  - openai / chatgpt — OpenAI Chat Completions
     *  - claude — Anthropic Messages API
     *  - openrouter — OpenAI-compatible gateway (free models available)
     *  - groq — OpenAI-compatible
     *  - custom — OpenAI-compatible at user base URL
     *
     * All ask the model for:
     * { "actions": [ { "type":"tap", "x":0.3, "y":0.4, "normalized":true }, ... ] }
     */
    private suspend fun runAiVision(
        webView: WebView,
        @Suppress("UNUSED_PARAMETER") injectJs: (String) -> Unit,
        frame: FrameRect,
        apiKey: String,
        baseUrl: String,
        model: String,
        aiProvider: String
    ) {
        log("Capturing captcha for AI Vision ($aiProvider / $model)…", LogLevel.INFO)
        val bmp = captureWebView(webView) ?: run {
            log("Screenshot failed — geo fallback", LogLevel.WARNING)
            runBuiltinGeo(webView, frame)
            return
        }
        try {
            val b64 = bitmapToJpegBase64(bmp, 72)
            val aspect = if (bmp.height > 0) bmp.width.toFloat() / bmp.height else 1f
            log("Calling AI Vision ($aiProvider)…", LogLevel.INFO)
            val actions = withContext(Dispatchers.IO) {
                callVisionProvider(aiProvider, b64, frame, aspect, apiKey, baseUrl, model)
            }
            if (actions.isEmpty()) {
                log("AI Vision returned no actions — geo fallback", LogLevel.WARNING)
                runBuiltinGeo(webView, frame)
                return
            }
            log("AI Vision: ${actions.size} action(s)", LogLevel.SUCCESS)
            for (a in actions) {
                when (a.type.lowercase()) {
                    "drag", "swipe" -> {
                        val (x1, y1) = if (a.normalized) frame.point(a.x, a.y) else a.x to a.y
                        val (x2, y2) = if (a.normalized) frame.point(a.x2, a.y2) else a.x2 to a.y2
                        dragWebView(webView, x1, y1, x2, y2)
                    }
                    else -> {
                        val (x, y) = if (a.normalized) frame.point(a.x, a.y) else a.x to a.y
                        tapWebView(webView, x, y)
                    }
                }
                delay(380)
            }
            tapAt(webView, frame, 0.5f, 0.92f)
        } finally {
            bmp.recycle()
        }
    }

    private fun buildVisionPrompt(frame: FrameRect, aspect: Float): String = """
You are a captcha-solving assistant controlling a mobile WebView.
I attached a screenshot of an hCaptcha / Discord captcha challenge.
Captcha frame (CSS px relative to page): left=${frame.left}, top=${frame.top}, width=${frame.width}, height=${frame.height}.
Image aspect ratio (w/h): $aspect.

Return ONLY valid JSON (no markdown) in this exact format:
{"actions":[{"type":"tap","x":0.3,"y":0.4,"normalized":true}]}

Rules:
- Coordinates with normalized=true are 0..1 relative to the CAPTCHA FRAME (not the full screenshot).
- Prefer 1–6 taps that select the correct grid cells / checkbox / verify button.
- type may be "tap" or "drag" (drag may include x2,y2).
- If you only see a checkbox, tap near (0.08, 0.5) then verify near (0.5, 0.92).
- Do not invent extra commentary.
""".trim()

    private fun parseActionsFromModelText(contentRaw: String): List<GestureAction> {
        var content = contentRaw.trim()
        if (content.startsWith("```")) {
            content = content.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
                .removeSuffix("```").trim()
        }
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start >= 0 && end > start) content = content.substring(start, end + 1)
        return try {
            val parsed = JSONObject(content)
            val arr: JSONArray = when {
                parsed.has("actions") -> parsed.getJSONArray("actions")
                parsed.has("taps") -> parsed.getJSONArray("taps")
                else -> JSONArray()
            }
            val out = mutableListOf<GestureAction>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += GestureAction(
                    type = o.optString("type", "tap"),
                    x = o.optDouble("x", 0.5).toFloat(),
                    y = o.optDouble("y", 0.5).toFloat(),
                    x2 = o.optDouble("x2", o.optDouble("x", 0.5)).toFloat(),
                    y2 = o.optDouble("y2", o.optDouble("y", 0.5)).toFloat(),
                    normalized = o.optBoolean("normalized", true)
                )
            }
            out
        } catch (e: Exception) {
            log("Failed to parse actions JSON: ${e.message}", LogLevel.WARNING)
            emptyList()
        }
    }

    private fun httpPostJson(
        url: String,
        body: ByteArray,
        headers: Map<String, String>,
        connectMs: Int = 25_000,
        readMs: Int = 90_000
    ): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectMs
            readTimeout = readMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            BufferedOutputStream(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            code to text
        } catch (e: Exception) {
            -1 to (e.message ?: "error")
        } finally {
            conn.disconnect()
        }
    }

    private fun callVisionProvider(
        provider: String,
        imageB64: String,
        frame: FrameRect,
        aspect: Float,
        apiKey: String,
        baseUrl: String,
        model: String
    ): List<GestureAction> {
        val prov = provider.lowercase().trim()
        val prompt = buildVisionPrompt(frame, aspect)
        return when (prov) {
            "claude", "anthropic" -> callClaudeVision(imageB64, prompt, apiKey, baseUrl, model)
            "gemini", "google" -> callGeminiVision(imageB64, prompt, apiKey, baseUrl, model)
            "openrouter" -> callOpenAiCompat(
                imageB64, prompt, apiKey,
                baseUrl.ifBlank { "https://openrouter.ai/api/v1" },
                model.ifBlank { "google/gemini-2.0-flash-exp:free" },
                extraHeaders = mapOf(
                    "HTTP-Referer" to "https://evs-generator.local",
                    "X-Title" to "EVS Generator"
                )
            )
            "groq" -> callOpenAiCompat(
                imageB64, prompt, apiKey,
                baseUrl.ifBlank { "https://api.groq.com/openai/v1" },
                model.ifBlank { "meta-llama/llama-4-scout-17b-16e-instruct" }
            )
            "openai", "chatgpt", "gpt" -> callOpenAiCompat(
                imageB64, prompt, apiKey,
                baseUrl.ifBlank { "https://api.openai.com/v1" },
                model.ifBlank { "gpt-4o-mini" }
            )
            else -> callOpenAiCompat(
                imageB64, prompt, apiKey,
                baseUrl.ifBlank { "https://api.openai.com/v1" },
                model.ifBlank { "gpt-4o-mini" }
            )
        }
    }

    /** OpenAI Chat Completions style (OpenAI, OpenRouter, Groq, custom). */
    private fun callOpenAiCompat(
        imageB64: String,
        prompt: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): List<GestureAction> {
        val base = baseUrl.trim().trimEnd('/')
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val bodyJson = JSONObject()
            .put("model", model)
            .put("temperature", 0.1)
            .put("max_tokens", 500)
            .put("messages", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", prompt))
                        .put(JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject()
                                .put("url", "data:image/jpeg;base64,$imageB64")
                                .put("detail", "high")
                            )
                        )
                    )
            ))
        val headers = mutableMapOf("Authorization" to "Bearer $apiKey")
        headers.putAll(extraHeaders)
        val (code, text) = httpPostJson(url, bodyJson.toString().toByteArray(Charsets.UTF_8), headers)
        if (code !in 200..299) {
            log("AI Vision HTTP $code: ${text.take(220)}", LogLevel.WARNING)
            return emptyList()
        }
        return try {
            val root = JSONObject(text)
            val content = root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
            parseActionsFromModelText(content)
        } catch (e: Exception) {
            log("AI Vision parse error: ${e.message}", LogLevel.WARNING)
            emptyList()
        }
    }

    /** Anthropic Claude Messages API (vision). */
    private fun callClaudeVision(
        imageB64: String,
        prompt: String,
        apiKey: String,
        baseUrl: String,
        model: String
    ): List<GestureAction> {
        val base = baseUrl.trim().trimEnd('/').ifBlank { "https://api.anthropic.com" }
        val url = when {
            base.endsWith("/v1/messages") -> base
            base.endsWith("/v1") -> "$base/messages"
            else -> "$base/v1/messages"
        }
        val bodyJson = JSONObject()
            .put("model", model.ifBlank { "claude-3-5-haiku-latest" })
            .put("max_tokens", 500)
            .put("messages", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", JSONArray()
                        .put(JSONObject()
                            .put("type", "image")
                            .put("source", JSONObject()
                                .put("type", "base64")
                                .put("media_type", "image/jpeg")
                                .put("data", imageB64)
                            )
                        )
                        .put(JSONObject().put("type", "text").put("text", prompt))
                    )
            ))
        val headers = mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to "2023-06-01"
        )
        val (code, text) = httpPostJson(url, bodyJson.toString().toByteArray(Charsets.UTF_8), headers)
        if (code !in 200..299) {
            log("Claude HTTP $code: ${text.take(220)}", LogLevel.WARNING)
            return emptyList()
        }
        return try {
            val root = JSONObject(text)
            val contentArr = root.getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until contentArr.length()) {
                val block = contentArr.getJSONObject(i)
                if (block.optString("type") == "text") sb.append(block.optString("text"))
            }
            parseActionsFromModelText(sb.toString())
        } catch (e: Exception) {
            log("Claude parse error: ${e.message}", LogLevel.WARNING)
            emptyList()
        }
    }

    /** Google Gemini generateContent (free tier available). */
    private fun callGeminiVision(
        imageB64: String,
        prompt: String,
        apiKey: String,
        baseUrl: String,
        model: String
    ): List<GestureAction> {
        val mod = model.ifBlank { "gemini-2.0-flash" }
        val base = baseUrl.trim().trimEnd('/').ifBlank {
            "https://generativelanguage.googleapis.com/v1beta"
        }
        // Accept either full URL or base; always append model path + key
        val url = when {
            base.contains(":generateContent") -> {
                if (base.contains("key=")) base else "$base${if ('?' in base) '&' else '?'}key=$apiKey"
            }
            else -> {
                val root = if (base.contains("/models")) base.substringBefore("/models") else base
                "$root/models/$mod:generateContent?key=$apiKey"
            }
        }
        val bodyJson = JSONObject()
            .put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", imageB64)
                    ))
                )
            ))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.1)
                .put("maxOutputTokens", 500)
            )
        val (code, text) = httpPostJson(url, bodyJson.toString().toByteArray(Charsets.UTF_8), emptyMap())
        if (code !in 200..299) {
            log("Gemini HTTP $code: ${text.take(220)}", LogLevel.WARNING)
            return emptyList()
        }
        return try {
            val root = JSONObject(text)
            val parts = root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text"))
            }
            parseActionsFromModelText(sb.toString())
        } catch (e: Exception) {
            log("Gemini parse error: ${e.message}", LogLevel.WARNING)
            emptyList()
        }
    }

    private suspend fun runVisionHttp(
        webView: WebView,
        @Suppress("UNUSED_PARAMETER") injectJs: (String) -> Unit,
        frame: FrameRect,
        apiUrl: String
    ) {
        log("Calling vision API…", LogLevel.INFO)
        val bmp = captureWebView(webView) ?: run {
            log("Screenshot failed — geo fallback", LogLevel.WARNING)
            runBuiltinGeo(webView, frame)
            return
        }
        try {
            val actions = withContext(Dispatchers.IO) {
                postVisionSolve(apiUrl, bmp, frame)
            }
            if (actions.isEmpty()) {
                log("Vision API returned no actions — geo fallback", LogLevel.WARNING)
                runBuiltinGeo(webView, frame)
                return
            }
            log("Vision API: ${actions.size} action(s)", LogLevel.SUCCESS)
            for (a in actions) {
                when (a.type.lowercase()) {
                    "drag" -> {
                        val (x1, y1) = if (a.normalized) frame.point(a.x, a.y) else a.x to a.y
                        val (x2, y2) = if (a.normalized) frame.point(a.x2, a.y2) else a.x2 to a.y2
                        dragWebView(webView, x1, y1, x2, y2)
                    }
                    else -> {
                        val (x, y) = if (a.normalized) frame.point(a.x, a.y) else a.x to a.y
                        tapWebView(webView, x, y)
                    }
                }
                delay(320)
            }
            // Always finish with Verify zone
            tapAt(webView, frame, 0.5f, 0.92f)
        } finally {
            bmp.recycle()
        }
    }

    private fun findBrightBottomButtons(
        bmp: Bitmap,
        frame: FrameRect,
        vw: Int,
        vh: Int
    ): List<Pair<Float, Float>> {
        // Map frame (CSS px) roughly onto bitmap pixels
        val scaleX = bmp.width.toFloat() / max(vw, 1)
        val scaleY = bmp.height.toFloat() / max(vh, 1)
        val top = (frame.top * scaleY).toInt().coerceIn(0, bmp.height - 1)
        val bot = ((frame.top + frame.height) * scaleY).toInt().coerceIn(top + 1, bmp.height)
        val left = (frame.left * scaleX).toInt().coerceIn(0, bmp.width - 1)
        val right = ((frame.left + frame.width) * scaleX).toInt().coerceIn(left + 1, bmp.width)
        val bandTop = (top + (bot - top) * 0.82f).toInt().coerceIn(top, bot - 1)
        var bestX = (left + right) / 2
        var bestScore = -1
        val step = max(2, (right - left) / 40)
        var x = left
        while (x < right) {
            var score = 0
            for (y in bandTop until bot step 2) {
                val c = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                // Prefer saturated / non-gray UI chrome
                score += (r + g + b) / 3
            }
            if (score > bestScore) {
                bestScore = score
                bestX = x
            }
            x += step
        }
        val cssX = bestX / scaleX
        val cssY = (frame.top + frame.height * 0.90f)
        return listOf(cssX to cssY, frame.point(0.5f, 0.92f))
    }

    private fun postVisionSolve(apiUrl: String, bmp: Bitmap, frame: FrameRect): List<GestureAction> {
        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        val b64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        val body = JSONObject()
            .put("image_b64", b64)
            .put("frame", JSONObject()
                .put("left", frame.left).put("top", frame.top)
                .put("width", frame.width).put("height", frame.height))
            .put("format", "jpeg")
            .toString()
            .toByteArray(Charsets.UTF_8)

        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            BufferedOutputStream(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                log("Vision API HTTP $code: ${text.take(160)}", LogLevel.WARNING)
                return emptyList()
            }
            val root = JSONObject(text)
            val arr: JSONArray = when {
                root.has("actions") -> root.getJSONArray("actions")
                root.has("taps") -> root.getJSONArray("taps")
                else -> JSONArray()
            }
            val out = mutableListOf<GestureAction>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += GestureAction(
                    type = o.optString("type", "tap"),
                    x = o.optDouble("x", 0.5).toFloat(),
                    y = o.optDouble("y", 0.5).toFloat(),
                    x2 = o.optDouble("x2", o.optDouble("x", 0.5)).toFloat(),
                    y2 = o.optDouble("y2", o.optDouble("y", 0.5)).toFloat(),
                    normalized = o.optBoolean("normalized", true)
                )
            }
            return out
        } catch (e: Exception) {
            log("Vision API error: ${e.message}", LogLevel.WARNING)
            return emptyList()
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun tapAt(webView: WebView, frame: FrameRect, nx: Float, ny: Float) {
        val (x, y) = frame.point(nx, ny)
        tapWebView(webView, x, y)
    }

    /** CSS pixel coords relative to WebView top-left. */
    fun tapWebView(webView: WebView, cssX: Float, cssY: Float) {
        val sx = cssX * webView.scaleX
        val sy = cssY * webView.scaleY
        val down = SystemClock.uptimeMillis()
        val eDown = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, sx, sy, 0)
        val eUp = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, sx, sy, 0)
        webView.dispatchTouchEvent(eDown)
        webView.dispatchTouchEvent(eUp)
        eDown.recycle()
        eUp.recycle()
    }

    fun dragWebView(webView: WebView, cssX1: Float, cssY1: Float, cssX2: Float, cssY2: Float) {
        val x1 = cssX1 * webView.scaleX
        val y1 = cssY1 * webView.scaleY
        val x2 = cssX2 * webView.scaleX
        val y2 = cssY2 * webView.scaleY
        val down = SystemClock.uptimeMillis()
        val eDown = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x1, y1, 0)
        webView.dispatchTouchEvent(eDown)
        eDown.recycle()
        val steps = 8
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val x = x1 + (x2 - x1) * t
            val y = y1 + (y2 - y1) * t
            val et = down + i * 16L
            val eMove = MotionEvent.obtain(down, et, MotionEvent.ACTION_MOVE, x, y, 0)
            webView.dispatchTouchEvent(eMove)
            eMove.recycle()
        }
        val eUp = MotionEvent.obtain(down, down + steps * 16L + 40, MotionEvent.ACTION_UP, x2, y2, 0)
        webView.dispatchTouchEvent(eUp)
        eUp.recycle()
    }

    private fun captureWebView(webView: WebView): Bitmap? {
        return try {
            val w = webView.width
            val h = webView.height
            if (w <= 0 || h <= 0) return null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            webView.draw(canvas)
            bmp
        } catch (e: Exception) {
            log("WebView capture failed: ${e.message}", LogLevel.WARNING)
            null
        }
    }

    private fun bitmapToJpegBase64(bmp: Bitmap, quality: Int = 72): String {
        val stream = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 95), stream)
        return android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun queryLargestCaptchaFrame(webView: WebView, @Suppress("UNUSED_PARAMETER") injectJs: (String) -> Unit): FrameRect? {
        val result = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val js = """
            (function(){
              var best = null, bestA = 0;
              var sel = 'iframe[src*="hcaptcha"],iframe[src*="recaptcha"],iframe[src*="arkoselabs"],iframe[src*="funcaptcha"]';
              document.querySelectorAll(sel).forEach(function(f){
                var r = f.getBoundingClientRect();
                var a = r.width * r.height;
                if (a > bestA && r.width > 40 && r.height > 40) {
                  bestA = a;
                  best = {left:r.left, top:r.top, width:r.width, height:r.height};
                }
              });
              return best ? JSON.stringify(best) : '';
            })();
        """.trimIndent()
        try {
            webView.evaluateJavascript(js) { value ->
                result.set(value)
                latch.countDown()
            }
        } catch (e: Exception) {
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        var raw = result.get()?.trim().orEmpty()
        if (raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        if (raw.isBlank() || raw == "null") return null
        return try {
            val o = JSONObject(raw)
            FrameRect(
                left = o.getDouble("left").toFloat(),
                top = o.getDouble("top").toFloat(),
                width = o.getDouble("width").toFloat(),
                height = o.getDouble("height").toFloat()
            )
        } catch (_: Exception) {
            null
        }
    }
}
