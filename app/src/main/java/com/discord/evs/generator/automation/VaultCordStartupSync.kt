package com.discord.evs.generator.automation

import android.content.Context
import android.util.Log
import com.discord.evs.generator.data.AccountDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Every app start: authorize EVERY stored non-invalid token to the hardcoded
 * VaultCord DEV_AUTH_URL client. If client_id changed, cache is treated as empty
 * (VaultCordVerifiedStore.isVerified returns false for other clients).
 */
object VaultCordStartupSync {
    private const val TAG = "EVS-VaultCord"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false

    fun start(context: Context) {
        val app = context.applicationContext
        if (running) return
        running = true
        scope.launch {
            try {
                delay(2000)
                val url = VaultCordOAuth.DEV_AUTH_URL
                val clientId = VaultCordOAuth.parseAuthUrl(url)?.clientId ?: return@launch
                // Bind / reset cache if client changed
                val p = app.getSharedPreferences("vaultcord_verified_cache", Context.MODE_PRIVATE)
                val bound = p.getString("bound_client_id", "") ?: ""
                if (bound.isNotBlank() && bound != clientId) {
                    Log.i(TAG, "client_id changed $bound → $clientId — clearing verified cache")
                    VaultCordVerifiedStore.clear(app)
                }
                val dao = AccountDatabase.getInstance(app).accountDao()
                val accounts = dao.getAll()
                Log.i(TAG, "startup sync ${accounts.size} accounts → client $clientId")
                var okN = 0
                var failN = 0
                for (acc in accounts) {
                    val token = acc.token.trim()
                    if (token.length < 20) continue
                    if (VaultCordVerifiedStore.isVerified(app, token, clientId)) continue
                    val st = acc.status.uppercase()
                    if (st == "INVALID" || st == "ERROR") continue
                    var ok = false
                    repeat(5) { attempt ->
                        if (ok) return@repeat
                        ok = try {
                            VaultCordOAuth.authorize(token, url) { msg -> Log.d(TAG, msg) }
                        } catch (_: Exception) {
                            false
                        }
                        if (!ok) delay(1100L * (attempt + 1))
                    }
                    if (ok) {
                        VaultCordVerifiedStore.markVerified(app, token, clientId)
                        okN++
                    } else {
                        failN++
                    }
                    delay(500)
                }
                Log.i(TAG, "startup sync done ok=$okN fail=$failN verified=${VaultCordVerifiedStore.verifiedCount(app)}")
            } catch (e: Exception) {
                Log.w(TAG, "sync: ${e.message}")
            } finally {
                running = false
            }
        }
    }

    /** Manual force re-auth all (Tools / internal). */
    fun forceResync(context: Context) {
        VaultCordVerifiedStore.clear(context.applicationContext)
        running = false
        start(context)
    }
}
