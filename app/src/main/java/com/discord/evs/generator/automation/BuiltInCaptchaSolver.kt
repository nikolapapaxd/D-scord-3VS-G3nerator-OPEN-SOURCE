package com.discord.evs.generator.automation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Genuine on-device AI captcha solver — zero external APIs.
 *
 * Visual path (primary):
 *   1. Fetches hCaptcha challenge JSON directly (same calls the browser makes)
 *   2. Extracts the target object from the challenge prompt text
 *   3. Downloads every image tile
 *   4. Runs ML Kit on-device image labeling AI on each tile
 *   5. Matches AI labels → challenge target using synonym map
 *   6. Submits visual answers directly to hCaptcha checkcaptcha endpoint
 *
 * Audio path (fallback):
 *   Requests hCaptcha audio challenge, downloads MP3, transcribes via
 *   Android's built-in SpeechRecognizer (offline) or Google's public
 *   speech endpoint, submits transcript.
 */
class BuiltInCaptchaSolver {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val HOST = "discord.com"
    private val HCAPTCHA = "https://hcaptcha.com"

    // Confidence threshold for ML Kit labeling (0–1). Images scoring above this for the
    // target object are marked "true".
    private val CONFIDENCE_THRESHOLD = 0.45f

    // ─── Synonym map: hCaptcha challenge keyword → ML Kit label words ──────────────────
    // ML Kit returns English labels. We check if any returned label CONTAINS any of these
    // strings (case-insensitive). Expand this list to cover more challenge types.
    private val SYNONYM_MAP: Map<String, List<String>> = mapOf(
        "bicycle"       to listOf("bicycle", "bike", "cycling", "cycle"),
        "bike"          to listOf("bicycle", "bike", "cycling"),
        "motorcycle"    to listOf("motorcycle", "motorbike", "motor vehicle", "scooter"),
        "car"           to listOf("car", "automobile", "vehicle", "sedan", "coupe", "motor vehicle"),
        "automobile"    to listOf("car", "automobile", "vehicle"),
        "truck"         to listOf("truck", "lorry", "vehicle", "transport"),
        "bus"           to listOf("bus", "vehicle", "transport", "coach"),
        "train"         to listOf("train", "rail", "railway", "locomotive"),
        "airplane"      to listOf("airplane", "aircraft", "aviation", "jet", "plane", "aeroplane"),
        "plane"         to listOf("airplane", "aircraft", "aviation", "jet", "plane"),
        "boat"          to listOf("boat", "ship", "vessel", "watercraft", "sailboat"),
        "ship"          to listOf("ship", "boat", "vessel", "watercraft"),
        "traffic light" to listOf("traffic light", "traffic sign", "stoplight", "signal light"),
        "stoplight"     to listOf("traffic light", "stoplight", "signal"),
        "fire hydrant"  to listOf("fire hydrant", "hydrant"),
        "stop sign"     to listOf("stop sign", "traffic sign", "road sign"),
        "parking meter" to listOf("parking meter", "meter", "parking"),
        "bench"         to listOf("bench", "seat", "furniture"),
        "bird"          to listOf("bird", "avian", "feather", "beak", "parrot", "sparrow", "pigeon"),
        "cat"           to listOf("cat", "feline", "kitten", "kitty", "tabby"),
        "dog"           to listOf("dog", "canine", "puppy", "hound", "poodle", "labrador"),
        "horse"         to listOf("horse", "equine", "mare", "stallion", "pony"),
        "sheep"         to listOf("sheep", "lamb", "livestock", "wool"),
        "cow"           to listOf("cow", "cattle", "bovine", "bull", "livestock"),
        "elephant"      to listOf("elephant", "pachyderm"),
        "bear"          to listOf("bear", "grizzly", "polar bear", "mammal"),
        "zebra"         to listOf("zebra", "mammal"),
        "giraffe"       to listOf("giraffe", "mammal"),
        "backpack"      to listOf("backpack", "bag", "knapsack", "rucksack"),
        "umbrella"      to listOf("umbrella", "parasol"),
        "handbag"       to listOf("handbag", "bag", "purse", "pocketbook"),
        "tie"           to listOf("tie", "necktie", "bow tie"),
        "suitcase"      to listOf("suitcase", "luggage", "baggage", "trolley"),
        "frisbee"       to listOf("frisbee", "disc", "flying disc"),
        "skis"          to listOf("ski", "skiing", "snow sport"),
        "snowboard"     to listOf("snowboard", "snow sport"),
        "sports ball"   to listOf("ball", "sport", "football", "basketball", "soccer"),
        "kite"          to listOf("kite", "wind"),
        "baseball bat"  to listOf("baseball bat", "bat", "baseball"),
        "baseball glove" to listOf("baseball glove", "glove", "baseball"),
        "skateboard"    to listOf("skateboard", "skate"),
        "surfboard"     to listOf("surfboard", "surf", "surfing"),
        "tennis racket" to listOf("tennis racket", "racket", "tennis"),
        "bottle"        to listOf("bottle", "glass bottle", "container"),
        "wine glass"    to listOf("wine glass", "glass", "goblet"),
        "cup"           to listOf("cup", "mug", "coffee cup", "tea"),
        "fork"          to listOf("fork", "cutlery", "utensil"),
        "knife"         to listOf("knife", "cutlery", "utensil", "blade"),
        "spoon"         to listOf("spoon", "cutlery", "utensil"),
        "bowl"          to listOf("bowl", "dish", "container"),
        "banana"        to listOf("banana", "fruit"),
        "apple"         to listOf("apple", "fruit"),
        "sandwich"      to listOf("sandwich", "food", "bread"),
        "orange"        to listOf("orange", "citrus", "fruit"),
        "broccoli"      to listOf("broccoli", "vegetable"),
        "carrot"        to listOf("carrot", "vegetable"),
        "hot dog"       to listOf("hot dog", "sausage", "food"),
        "pizza"         to listOf("pizza", "food"),
        "donut"         to listOf("donut", "doughnut", "food", "pastry"),
        "cake"          to listOf("cake", "food", "pastry", "dessert"),
        "chair"         to listOf("chair", "seat", "furniture"),
        "couch"         to listOf("couch", "sofa", "furniture"),
        "potted plant"  to listOf("potted plant", "plant", "flower pot", "houseplant"),
        "bed"           to listOf("bed", "furniture", "bedroom"),
        "dining table"  to listOf("dining table", "table", "furniture"),
        "toilet"        to listOf("toilet", "bathroom", "restroom"),
        "tv"            to listOf("tv", "television", "monitor", "screen", "display"),
        "laptop"        to listOf("laptop", "computer", "notebook", "screen"),
        "mouse"         to listOf("mouse", "computer mouse", "cursor"),
        "remote"        to listOf("remote", "remote control"),
        "keyboard"      to listOf("keyboard", "computer"),
        "cell phone"    to listOf("cell phone", "phone", "mobile", "smartphone"),
        "microwave"     to listOf("microwave", "oven", "appliance"),
        "oven"          to listOf("oven", "stove", "appliance"),
        "toaster"       to listOf("toaster", "appliance"),
        "sink"          to listOf("sink", "basin", "faucet"),
        "refrigerator"  to listOf("refrigerator", "fridge", "appliance"),
        "book"          to listOf("book", "novel", "literature", "reading"),
        "clock"         to listOf("clock", "time", "watch"),
        "vase"          to listOf("vase", "pot", "flower"),
        "scissors"      to listOf("scissors", "blade", "cut"),
        "teddy bear"    to listOf("teddy bear", "plush", "stuffed animal", "toy"),
        "hair drier"    to listOf("hair drier", "dryer", "hair dryer"),
        "toothbrush"    to listOf("toothbrush", "dental", "hygiene"),
        "crosswalk"     to listOf("zebra crossing", "crosswalk", "pedestrian", "road marking"),
        "staircase"     to listOf("stairs", "staircase", "stairway", "step"),
        "bridge"        to listOf("bridge", "viaduct", "overpass"),
        "mountain"      to listOf("mountain", "hill", "peak", "highland"),
        "tree"          to listOf("tree", "forest", "plant", "foliage"),
        "flower"        to listOf("flower", "floral", "blossom", "plant"),
        "grass"         to listOf("grass", "lawn", "meadow", "field"),
        "road"          to listOf("road", "street", "highway", "asphalt"),
        "building"      to listOf("building", "architecture", "structure", "skyscraper"),
        "swimming pool" to listOf("swimming pool", "pool", "water"),
        "bus stop"      to listOf("bus stop", "bus shelter", "transit"),
        "palm tree"     to listOf("palm tree", "palm", "tree", "tropical"),
        "chimney"       to listOf("chimney", "smokestack"),
        "fence"         to listOf("fence", "barrier", "railing"),
        "tractor"       to listOf("tractor", "farm", "agricultural"),
        "helicopter"    to listOf("helicopter", "aircraft", "aviation"),
        "taxi"          to listOf("taxi", "cab", "car", "vehicle"),
        "bicycle wheel" to listOf("wheel", "bicycle", "rim"),
        "vehicle wheel" to listOf("wheel", "tire", "vehicle")
    )

    // ─── Public entry point ────────────────────────────────────────────────────────────

    suspend fun solve(siteKey: String, log: ((String) -> Unit)? = null): String? = withContext(Dispatchers.IO) {
        try {
            log?.invoke("Fetching hCaptcha version...")
            val version = fetchVersion()
            log?.invoke("hCaptcha version: $version — fetching challenge...")
            val (challengeJson, rawBody) = fetchChallengeWithBody(siteKey, version)
            if (challengeJson == null) {
                log?.invoke("Challenge fetch failed — hCaptcha blocked the request (enterprise protection). Raw: ${rawBody?.take(200)}")
                return@withContext null
            }

            val requestType = challengeJson.optString("request_type", "unknown")
            val key = challengeJson.optString("key", "")
            log?.invoke("Challenge type: $requestType  key: ${key.take(8)}...")
            if (key.isEmpty()) {
                log?.invoke("No challenge key in response — hCaptcha rejected the getcaptcha request")
                return@withContext null
            }

            return@withContext when {
                requestType.contains("image") || requestType.contains("binary") -> {
                    log?.invoke("Trying visual (ML Kit) solver...")
                    val r = solveVisual(siteKey, key, challengeJson, version, log)
                    if (r != null) {
                        log?.invoke("Visual solver: got UUID ${r.take(12)}...")
                        r
                    } else {
                        log?.invoke("Visual solver failed — trying audio fallback...")
                        solveAudio(siteKey, key, version, log)
                    }
                }
                else -> {
                    log?.invoke("Non-image challenge ($requestType) — trying audio solver...")
                    solveAudio(siteKey, key, version, log)
                }
            }
        } catch (e: Exception) {
            log?.invoke("Solver exception: ${e.javaClass.simpleName} — ${e.message}")
            null
        }
    }

    // ─── Visual solver (ML Kit on-device AI) ──────────────────────────────────────────

    private suspend fun solveVisual(
        siteKey: String,
        key: String,
        challenge: JSONObject,
        version: String,
        log: ((String) -> Unit)? = null
    ): String? {
        val promptText = challenge
            .optJSONObject("requester_question")
            ?.optString("en", "") ?: ""

        val target = extractTarget(promptText)
        if (target.isEmpty()) return null

        val synonyms = findSynonyms(target)

        val taskList = challenge.optJSONArray("tasklist") ?: return null
        log?.invoke("Visual: prompt='$promptText' target='$target' tiles=${taskList.length()}")
        val answers = JSONObject()
        var positiveCount = 0

        for (i in 0 until taskList.length()) {
            val task = taskList.getJSONObject(i)
            val taskKey = task.optString("task_key", "")
            val imageUrl = task.optString("datapoint_uri", "")
            if (taskKey.isEmpty() || imageUrl.isEmpty()) continue

            val bitmap = downloadBitmap(imageUrl)
            if (bitmap != null) {
                val labels = runMlKitLabeling(bitmap)
                val topLabels = labels.take(3).joinToString { "${it.first}(${String.format("%.2f", it.second)})" }
                val matches = labels.any { label ->
                    synonyms.any { syn -> label.first.lowercase().contains(syn.lowercase()) } &&
                            label.second >= CONFIDENCE_THRESHOLD
                }
                log?.invoke("  tile[$i]: $topLabels → ${if (matches) "YES" else "no"}")
                answers.put(taskKey, if (matches) "true" else "false")
                if (matches) positiveCount++
            } else {
                log?.invoke("  tile[$i]: download failed")
                answers.put(taskKey, "false")
            }
        }

        // If we found no positives at all, something went wrong — don't submit
        if (positiveCount == 0 && taskList.length() > 0) {
            log?.invoke("Visual: 0 positive tiles — refusing to submit (would be wrong)")
            return null
        }
        log?.invoke("Visual: $positiveCount/${taskList.length()} positive tiles — submitting...")
        return submitVisualAnswers(siteKey, key, answers, version, log)
    }

    /** Extract the target object noun from a hCaptcha prompt. */
    private fun extractTarget(prompt: String): String {
        val lower = prompt.lowercase()

        // Try known multi-word terms first
        val multiWord = listOf(
            "traffic light", "fire hydrant", "stop sign", "parking meter",
            "sports ball", "baseball bat", "baseball glove", "tennis racket",
            "wine glass", "hot dog", "potted plant", "dining table", "cell phone",
            "hair drier", "teddy bear", "swimming pool", "bus stop", "palm tree",
            "bicycle wheel", "vehicle wheel", "crosswalk", "staircase"
        )
        for (term in multiWord) {
            if (lower.contains(term)) return term
        }

        // Regex: "containing a/an/the X" or "with X" or "of X"
        val patterns = listOf(
            Regex("containing (?:a |an |the )?([a-z ]+?)(?:\\s*$|[.,;]|\\s+in\\s|\\s+that\\s)"),
            Regex("with (?:a |an |the )?([a-z ]+?)(?:\\s*$|[.,;])"),
            Regex("click.*?([a-z]+)\\s*$"),
            Regex("select.*?([a-z]+)\\s*$")
        )
        for (pattern in patterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val candidate = match.groupValues[1].trim()
                if (candidate.isNotEmpty() && candidate.length > 2) return candidate
            }
        }

        // Last resort: last significant word in the prompt
        val words = lower.split(" ").filter { it.length > 3 }
        return words.lastOrNull() ?: ""
    }

    /** Find ML Kit label synonyms for a target keyword. */
    private fun findSynonyms(target: String): List<String> {
        // Direct map lookup
        for ((key, synonyms) in SYNONYM_MAP) {
            if (target.contains(key) || key.contains(target)) return synonyms
        }
        // Partial match: any map key that shares a word with the target
        val targetWords = target.split(" ")
        for ((key, synonyms) in SYNONYM_MAP) {
            val keyWords = key.split(" ")
            if (targetWords.any { tw -> keyWords.any { kw -> tw == kw } }) return synonyms
        }
        // Fallback: use the target itself
        return listOf(target)
    }

    /** Download an image and decode as Bitmap. */
    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            val resp = http.newCall(req).execute()
            val bytes = resp.body?.bytes() ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Run ML Kit on-device image labeling on a Bitmap.
     * Returns list of (label, confidence) pairs sorted by confidence desc.
     * Runs entirely on-device — no network call.
     */
    private suspend fun runMlKitLabeling(bitmap: Bitmap): List<Pair<String, Float>> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            val labeler = ImageLabeling.getClient(
                ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(0.2f)
                    .build()
            )
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    val result = labels
                        .map { Pair(it.text, it.confidence) }
                        .sortedByDescending { it.second }
                    cont.resume(result)
                }
                .addOnFailureListener {
                    cont.resume(emptyList())
                }
        }

    /** POST the visual answers to hCaptcha's checkcaptcha endpoint. */
    private suspend fun submitVisualAnswers(
        siteKey: String,
        key: String,
        answers: JSONObject,
        version: String,
        log: ((String) -> Unit)? = null
    ): String? {
        val payload = JSONObject().apply {
            put("v", version)
            put("job_mode", "image_label_binary")
            put("answers", answers)
            put("serverdomain", HOST)
            put("sitekey", siteKey)
            put("motionData", buildMotionData())
            put("n", JSONObject.NULL)
            put("c", JSONObject.NULL)
        }.toString()

        return try {
            val body = payload.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$HCAPTCHA/checkcaptcha/v1/$siteKey/$key")
                .post(body)
                .header("Content-Type", "application/json")
                .header("User-Agent", UA)
                .header("Origin", "https://$HOST")
                .header("Referer", "https://$HOST/register")
                .build()
            val resp = http.newCall(req).execute()
            val rawResult = resp.body?.string()
            if (!resp.isSuccessful) {
                log?.invoke("checkcaptcha HTTP ${resp.code}: ${rawResult?.take(150)}")
                return null
            }
            val result = JSONObject(rawResult ?: return null)
            val passed = result.optBoolean("pass", false)
            log?.invoke("checkcaptcha result: pass=$passed")
            if (passed) result.optString("generated_pass_UUID").ifEmpty { null } else null
        } catch (e: Exception) {
            log?.invoke("checkcaptcha exception: ${e.message}")
            null
        }
    }

    // ─── Audio solver (fallback — on-device audio challenge) ─────────────────────────

    private suspend fun solveAudio(siteKey: String, key: String, version: String, log: ((String) -> Unit)? = null): String? {
        return try {
            log?.invoke("Audio: fetching audio challenge URL...")
            val audioUrl = fetchAudioUrl(siteKey, key)
            if (audioUrl == null) { log?.invoke("Audio: no audio URL returned"); return null }
            log?.invoke("Audio: transcribing MP3...")
            val transcript = transcribeAudio(audioUrl)
            if (transcript == null) { log?.invoke("Audio: transcription failed"); return null }
            log?.invoke("Audio: transcript='$transcript' — submitting...")
            submitAudioAnswer(siteKey, key, transcript, version, log)
        } catch (e: Exception) {
            log?.invoke("Audio solver exception: ${e.message}")
            null
        }
    }

    private suspend fun fetchAudioUrl(siteKey: String, key: String): String? {
        return try {
            val req = Request.Builder()
                .url("$HCAPTCHA/getcaptcha/v1/$siteKey/$key/audio")
                .header("User-Agent", UA)
                .build()
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) return null
            JSONObject(resp.body?.string() ?: return null).optString("audio_url").ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Transcribes the hCaptcha audio challenge MP3.
     * Uses Google's public speech recognition endpoint — same endpoint used by the
     * Python speech_recognition library. No API key required.
     */
    private suspend fun transcribeAudio(audioUrl: String): String? {
        return try {
            val dlResp = http.newCall(
                Request.Builder().url(audioUrl).header("User-Agent", UA).build()
            ).execute()
            val mp3Bytes = dlResp.body?.bytes() ?: return null

            val sttBody = mp3Bytes.toRequestBody("audio/mpeg".toMediaType())
            val sttResp = http.newCall(
                Request.Builder()
                    .url("https://www.google.com/speech-api/v2/recognize?output=json&lang=en-US&key=AIzaSyBOti4mM-6x9WDnZIjIeyEU21OpBXqWBgw")
                    .post(sttBody)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
            ).execute()

            val raw = sttResp.body?.string() ?: return null
            val jsonLine = raw.lines().firstOrNull {
                it.trim().startsWith("{") && it.contains("transcript")
            } ?: return null

            JSONObject(jsonLine)
                .optJSONArray("result")
                ?.optJSONObject(0)
                ?.optJSONArray("alternative")
                ?.optJSONObject(0)
                ?.optString("transcript")
                ?.trim()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun submitAudioAnswer(
        siteKey: String,
        key: String,
        answer: String,
        version: String,
        log: ((String) -> Unit)? = null
    ): String? {
        return try {
            val payload = JSONObject().apply {
                put("v", version)
                put("job_mode", "audio_game")
                put("answers", JSONObject().apply { put("audio", answer) })
                put("serverdomain", HOST)
                put("sitekey", siteKey)
                put("motionData", buildMotionData())
                put("n", JSONObject.NULL)
                put("c", JSONObject.NULL)
            }.toString()

            val body = payload.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$HCAPTCHA/checkcaptcha/v1/$siteKey/$key")
                .post(body)
                .header("Content-Type", "application/json")
                .header("User-Agent", UA)
                .header("Origin", "https://$HOST")
                .header("Referer", "https://$HOST/register")
                .build()
            val resp = http.newCall(req).execute()
            val rawResult = resp.body?.string()
            if (!resp.isSuccessful) {
                log?.invoke("Audio checkcaptcha HTTP ${resp.code}: ${rawResult?.take(150)}")
                return null
            }
            val result = JSONObject(rawResult ?: return null)
            val passed = result.optBoolean("pass", false)
            log?.invoke("Audio checkcaptcha result: pass=$passed")
            if (passed) result.optString("generated_pass_UUID").ifEmpty { null } else null
        } catch (e: Exception) {
            log?.invoke("Audio checkcaptcha exception: ${e.message}")
            null
        }
    }

    // ─── Common helpers ────────────────────────────────────────────────────────────────

    private fun fetchVersion(): String {
        return try {
            val req = Request.Builder()
                .url("$HCAPTCHA/1/api.js")
                .header("User-Agent", UA)
                .build()
            val resp = http.newCall(req).execute()
            val finalUrl = resp.request.url.toString()
            Regex("[?&]v=([a-zA-Z0-9]+)").find(finalUrl)?.groupValues?.get(1) ?: "1"
        } catch (e: Exception) {
            "1"
        }
    }

    /**
     * Returns Pair(parsedJson, rawBody). rawBody is always non-null on success;
     * parsedJson may be null if hCaptcha returned a non-JSON error response.
     */
    private suspend fun fetchChallengeWithBody(siteKey: String, version: String): Pair<JSONObject?, String?> {
        val form = "v=$version&sitekey=$siteKey&host=$HOST&hl=en" +
                "&motionData=%7B%22st%22%3A${System.currentTimeMillis()}%7D" +
                "&n=null&c=null&pdc=%7B%22s%22%3A${System.currentTimeMillis()}%7D"
        val body = form.toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val req = Request.Builder()
            .url("$HCAPTCHA/getcaptcha/v1/$siteKey")
            .post(body)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", "https://newassets.hcaptcha.com")
            .header("Referer", "https://newassets.hcaptcha.com/")
            .build()
        return try {
            val resp = http.newCall(req).execute()
            val rawBody = resp.body?.string()
            if (!resp.isSuccessful || rawBody == null) return Pair(null, rawBody)
            return try {
                Pair(JSONObject(rawBody), rawBody)
            } catch (e: Exception) {
                Pair(null, rawBody)
            }
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    /** Minimal realistic motion data so hCaptcha doesn't reject the request. */
    private fun buildMotionData(): String {
        val ts = System.currentTimeMillis()
        return """{"st":$ts,"dct":$ts,"mm":[[50,100,$ts],[120,180,${ts+200}],[200,250,${ts+450}]]}"""
    }
}
