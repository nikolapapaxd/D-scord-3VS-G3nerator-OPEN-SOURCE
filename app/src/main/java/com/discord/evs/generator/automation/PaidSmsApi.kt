package com.discord.evs.generator.automation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Paid SMS providers for Discord phone verification.
 * Providers: 5sim, sms-activate, smspool (generic activate-style).
 * Free path remains in PhoneVerificationApi.
 */
class PaidSmsApi(
    private val provider: String,
    private val apiKey: String,
    private val country: String = "us",
    private val maxPrice: String = "0.50"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    data class NumberOrder(
        val id: String,
        val phone: String,
        val provider: String
    )

    suspend fun buyNumber(onLog: (String) -> Unit): NumberOrder? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            onLog("Paid SMS: API key blank")
            return@withContext null
        }
        when (provider.lowercase()) {
            "5sim", "fivesim" -> buy5sim(onLog)
            "sms-activate", "smsactivate", "activate" -> buySmsActivate(onLog)
            "smspool" -> buySmsPool(onLog)
            else -> {
                onLog("Paid SMS: unknown provider $provider — try 5sim | sms-activate | smspool")
                null
            }
        }
    }

    suspend fun waitCode(order: NumberOrder, timeoutMs: Long = 180_000L, onLog: (String) -> Unit): String? =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            onLog("Paid SMS: waiting code for ${order.phone} (${order.provider})…")
            while (System.currentTimeMillis() - start < timeoutMs) {
                val code = when (order.provider) {
                    "5sim" -> poll5sim(order.id)
                    "sms-activate" -> pollSmsActivate(order.id)
                    "smspool" -> pollSmsPool(order.id)
                    else -> null
                }
                if (code != null) {
                    onLog("✓ Paid SMS code: $code")
                    return@withContext code
                }
                delay(3_500)
            }
            onLog("Paid SMS: timeout — cancelling order")
            cancel(order)
            null
        }

    suspend fun cancel(order: NumberOrder) = withContext(Dispatchers.IO) {
        try {
            when (order.provider) {
                "5sim" -> get("https://5sim.net/v1/user/cancel/${order.id}")
                "sms-activate" -> get("https://api.sms-activate.org/stubs/handler_api.php?api_key=${enc(apiKey)}&action=setStatus&status=8&id=${order.id}")
                "smspool" -> get("https://api.smspool.net/sms/cancel?key=${enc(apiKey)}&orderid=${order.id}")
                else -> { /* unknown provider */ }
            }
        } catch (_: Exception) { }
    }

    private fun buy5sim(onLog: (String) -> Unit): NumberOrder? {
        // country: usa, england, … product: discord
        val c = map5simCountry(country)
        val url = "https://5sim.net/v1/user/buy/activation/$c/any/discord"
        val body = getAuth(url, apiKey) ?: return null
        return try {
            val o = JSONObject(body)
            val id = o.optString("id")
            val phone = o.optString("phone")
            if (id.isBlank() || phone.isBlank()) {
                onLog("5sim buy failed: ${body.take(160)}")
                null
            } else {
                onLog("5sim number: +$phone (order $id)")
                NumberOrder(id, if (phone.startsWith("+")) phone else "+$phone", "5sim")
            }
        } catch (e: Exception) {
            onLog("5sim error: ${e.message}")
            null
        }
    }

    private fun poll5sim(id: String): String? {
        val body = getAuth("https://5sim.net/v1/user/check/$id", apiKey) ?: return null
        return try {
            val o = JSONObject(body)
            val sms = o.optJSONArray("sms") ?: return null
            if (sms.length() == 0) return null
            val text = sms.getJSONObject(0).optString("code").ifBlank {
                sms.getJSONObject(0).optString("text")
            }
            Regex("""\d{5,8}""").find(text)?.value
        } catch (_: Exception) { null }
    }

    private fun buySmsActivate(onLog: (String) -> Unit): NumberOrder? {
        // service=ds (Discord), country numeric
        val c = mapActivateCountry(country)
        val url = "https://api.sms-activate.org/stubs/handler_api.php?api_key=${enc(apiKey)}&action=getNumber&service=ds&country=$c&maxPrice=$maxPrice"
        val body = get(url) ?: return null
        // ACCESS_NUMBER:id:phone
        if (!body.startsWith("ACCESS_NUMBER")) {
            onLog("sms-activate: $body")
            return null
        }
        val parts = body.split(":")
        if (parts.size < 3) return null
        val id = parts[1]
        val phone = parts[2]
        onLog("sms-activate number: +$phone (id $id)")
        return NumberOrder(id, if (phone.startsWith("+")) phone else "+$phone", "sms-activate")
    }

    private fun pollSmsActivate(id: String): String? {
        val body = get("https://api.sms-activate.org/stubs/handler_api.php?api_key=${enc(apiKey)}&action=getStatus&id=$id") ?: return null
        // STATUS_OK:code
        if (body.startsWith("STATUS_OK")) {
            return body.substringAfter(":").trim().takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun buySmsPool(onLog: (String) -> Unit): NumberOrder? {
        val c = country.uppercase()
        val url = "https://api.smspool.net/purchase/sms?key=${enc(apiKey)}&country=$c&service=discord&max_price=$maxPrice"
        val body = get(url) ?: return null
        return try {
            val o = JSONObject(body)
            val id = o.optString("order_id", o.optString("orderid"))
            val phone = o.optString("phonenumber", o.optString("number"))
            if (id.isBlank() || phone.isBlank()) {
                onLog("smspool: ${body.take(160)}")
                null
            } else {
                onLog("smspool number: $phone (order $id)")
                NumberOrder(id, if (phone.startsWith("+")) phone else "+$phone", "smspool")
            }
        } catch (e: Exception) {
            onLog("smspool error: ${e.message}")
            null
        }
    }

    private fun pollSmsPool(id: String): String? {
        val body = get("https://api.smspool.net/sms/check?key=${enc(apiKey)}&orderid=$id") ?: return null
        return try {
            val o = JSONObject(body)
            val code = o.optString("sms", o.optString("code"))
            Regex("""\d{5,8}""").find(code)?.value
        } catch (_: Exception) { null }
    }

    private fun map5simCountry(c: String) = when (c.lowercase()) {
        "us", "usa", "united states" -> "usa"
        "uk", "gb", "england" -> "england"
        "ru", "russia" -> "russia"
        "de", "germany" -> "germany"
        "nl", "netherlands" -> "netherlands"
        "id", "indonesia" -> "indonesia"
        else -> "usa"
    }

    private fun mapActivateCountry(c: String) = when (c.lowercase()) {
        "us", "usa" -> "12"
        "uk", "gb" -> "16"
        "ru" -> "0"
        "de" -> "43"
        "nl" -> "48"
        "id" -> "6"
        else -> "12"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? {
        return try {
            val req = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(req).execute().use { it.body?.string() }
        } catch (_: Exception) { null }
    }

    private fun getAuth(url: String, key: String): String? {
        return try {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { it.body?.string() }
        } catch (_: Exception) { null }
    }
}
