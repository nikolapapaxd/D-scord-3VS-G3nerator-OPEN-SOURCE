package com.discord.evs.generator.automation

import com.discord.evs.generator.data.Account
import com.discord.evs.generator.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-generation controller — up to 5 concurrent AccountGenerator workers.
 * Each session has its own log buffer and status for the Generator multi-tab UI.
 */
class MultiSessionManager(
    private val prefs: Prefs,
    private val factory: (sessionId: Int, onLog: (LogEntry) -> Unit, onSaved: (Account) -> Unit) -> AccountGenerator
) {
    data class SessionState(
        val id: Int,
        @Volatile var status: String = "IDLE",
        val logs: MutableList<LogEntry> = mutableListOf(),
        @Volatile var created: Int = 0,
        @Volatile var needsHuman: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<Int, SessionState>()
    private val generators = ConcurrentHashMap<Int, AccountGenerator>()
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val nextId = AtomicInteger(1)

    fun sessionCount(): Int = prefs.concurrentGenerations.coerceIn(1, 5)

    fun allSessions(): List<SessionState> =
        sessions.values.sortedBy { it.id }

    fun getSession(id: Int): SessionState? = sessions[id]

    fun startAll(targetPerSession: Int, onAnySaved: (Account) -> Unit) {
        stopAll()
        val n = sessionCount()
        for (i in 0 until n) {
            val sid = nextId.getAndIncrement()
            val state = SessionState(id = sid, status = "RUNNING")
            sessions[sid] = state
            val gen = factory(sid, { entry ->
                synchronized(state.logs) {
                    state.logs.add(entry)
                    if (state.logs.size > 400) state.logs.removeAt(0)
                }
                if (entry.showContinue) state.needsHuman = true
            }, { acc ->
                state.created++
                onAnySaved(acc)
            })
            generators[sid] = gen
            jobs[sid] = scope.launch {
                try {
                    gen.start(targetPerSession)
                    state.status = "DONE"
                } catch (e: Exception) {
                    state.status = "ERROR: ${e.message}"
                }
            }
        }
    }

    fun stopAll() {
        generators.values.forEach { runCatching { it.stop() } }
        jobs.values.forEach { it.cancel() }
        generators.clear()
        jobs.clear()
        sessions.clear()
    }

    fun continueSession(id: Int) {
        generators[id]?.onContinuePressed()
        sessions[id]?.needsHuman = false
    }

    fun activeHumanNeeded(): SessionState? =
        sessions.values.firstOrNull { it.needsHuman && it.status == "RUNNING" }
}
