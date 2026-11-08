package com.discord.evs.generator.ui

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

import com.discord.evs.generator.automation.CyberTempApi
import com.discord.evs.generator.automation.TokenChecker
import android.widget.RadioGroup

import com.discord.evs.generator.remote.LocalBridgeServer
import com.discord.evs.generator.features.FeatureFlags
import android.widget.Toast

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.discord.evs.generator.data.Account
import com.discord.evs.generator.data.AccountDatabase
import com.discord.evs.generator.data.Prefs
import com.discord.evs.generator.App
import com.discord.evs.generator.R
import com.discord.evs.generator.databinding.FragmentToolsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class ToolsFragment : Fragment() {

    private var _binding: FragmentToolsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs

    private var checkerValidCount = 0
    private var checkerLockedCount = 0
    private var checkerInvalidCount = 0
    private var visiblePanel: View? = null

    private val exportFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? -> uri?.let { doExportToUri(it) } }

    private val exportFileLauncherCsv = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? -> uri?.let { doExportToUri(it, "csv") } }

    private val exportFileLauncherJson = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> uri?.let { doExportToUri(it, "json") } }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentToolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiMotion.enter(view)
        UiMotion.staggerChildren(view as? ViewGroup ?: return)
        prefs = Prefs(requireContext())
        setupTabs()

        // Remove obsolete tool tabs/panels from UI
        try {
            binding.tabDecoder.text = "Email Reverify"
            binding.tabMore.visibility = View.GONE
            binding.panelMore.visibility = View.GONE
            binding.panelSms.visibility = View.GONE
            binding.panelInvites.visibility = View.GONE
            binding.panelMassdm.visibility = View.GONE
            binding.panelProfiles.visibility = View.GONE
        } catch (_: Exception) {}
        // Hide SMS / Invites / Mass DM / Profiles tabs if present
        try {
            listOf("tab_sms", "tab_invites", "tab_massdm", "tab_profiles", "tab_more").forEach { idName ->
                val id = resources.getIdentifier(idName, "id", requireContext().packageName)
                if (id != 0) view.findViewById<View>(id)?.visibility = View.GONE
            }
        } catch (_: Exception) {}

        setupChecker()
        setupDecoder()
        setupExport()
        setupImport()
        setupStats()
        setupProxyTest()
        setupConfig()
        setupPc()
        setupHealth()
        setupSmsPanel()
        // setupInvites() // removed permanently
        // setupMassDm() // removed permanently
        // setupProfiles() // removed permanently
        // setupMorePanel() // settings moved to Settings tab
    }

    // ── Tab switching ─────────────────────────────────────────────────────────

    private val tabPanels: Map<String, View> get() = mapOf(
        "joiner" to binding.root.findViewById(R.id.panel_joiner),
        "checker" to binding.panelChecker,
        "decoder" to binding.panelDecoder,
        "export"  to binding.panelExport,
        "import"  to binding.panelImport,
        "stats"   to binding.panelStats,
        "proxy"   to binding.panelProxy,
        "config"  to binding.panelConfig,
        "pc"      to binding.panelPc,
        "health"  to binding.root.findViewById(R.id.panel_health),
        "sms"     to binding.root.findViewById(R.id.panel_sms),
        "invites" to binding.root.findViewById(R.id.panel_invites),
        "massdm"  to binding.root.findViewById(R.id.panel_massdm),
        "profiles" to binding.root.findViewById(R.id.panel_profiles),
        "more"    to binding.root.findViewById(R.id.panel_more)
    )

    private fun setupTabs() {
        // Remove obsolete tool tabs/panels from UI
        try {
            binding.tabDecoder.text = "Email Reverify"
            binding.tabMore.visibility = View.GONE
            binding.panelMore.visibility = View.GONE
            binding.panelSms.visibility = View.GONE
            binding.panelInvites.visibility = View.GONE
            binding.panelMassdm.visibility = View.GONE
            binding.panelProfiles.visibility = View.GONE
        } catch (_: Exception) {}
        try {
            listOf("tab_sms", "tab_invites", "tab_massdm", "tab_profiles", "tab_more").forEach { idName ->
                val id = resources.getIdentifier(idName, "id", requireContext().packageName)
                if (id != 0) view?.findViewById<View>(id)?.visibility = View.GONE
            }
        } catch (_: Exception) {}
        val tabs = listOf(
            binding.root.findViewById<View>(R.id.tab_joiner) to "joiner",
            binding.tabChecker to "checker",
            binding.tabDecoder to "decoder",
            binding.tabExport  to "export",
            binding.tabImport  to "import",
            binding.tabStats   to "stats",
            binding.tabProxy   to "proxy",
            binding.tabConfig  to "config",
            binding.root.findViewById<View>(R.id.tab_health) to "health",
            binding.root.findViewById<View>(R.id.tab_sms) to "sms",
            binding.root.findViewById<View>(R.id.tab_invites) to "invites",
            binding.root.findViewById<View>(R.id.tab_massdm) to "massdm",
            binding.root.findViewById<View>(R.id.tab_profiles) to "profiles",
            binding.root.findViewById<View>(R.id.tab_more) to "more",
            binding.tabPc      to "pc"
        )
        tabs.forEach { (view, id) ->
            view.setOnClickListener { switchToTab(id, tabs) }
        }
        switchToTab("joiner", tabs)
    }

    private fun ensureJoinerChild() {
        val existing = childFragmentManager.findFragmentByTag("tools_joiner")
        if (existing == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.panel_joiner, JoinerFragment(), "tools_joiner")
                .commitAllowingStateLoss()
        }
    }

    private fun switchToTab(selectedId: String, tabs: List<Pair<View, String>>) {
        if (selectedId == "joiner") ensureJoinerChild()

        val primary = ContextCompat.getColor(requireContext(), R.color.primary)
        val muted = ContextCompat.getColor(requireContext(), R.color.muted_foreground)
        val selectedSurface = ContextCompat.getColor(requireContext(), R.color.primary_soft)
        tabs.forEach { (view, id) ->
            val isSelected = id == selectedId
            (view as? android.widget.TextView)?.apply {
                setTextColor(if (isSelected) primary else muted)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 18f
                    setColor(if (isSelected) selectedSurface else Color.TRANSPARENT)
                }
                animate()
                    .scaleX(if (isSelected) 1f else 0.98f)
                    .scaleY(if (isSelected) 1f else 0.98f)
                    .setDuration(160L)
                    .start()
            }
        }

        val nextPanel = tabPanels[selectedId] ?: return
        // Hide every other panel, not only the panel remembered from the last
        // click.  The old guard left panels that had been made visible by
        // restored state or a previous fragment instance on screen.
        tabPanels.forEach { (id, panel) ->
            if (id != selectedId) {
                panel.animate().cancel()
                panel.visibility = View.GONE
                panel.alpha = 1f
                panel.translationY = 0f
            }
        }
        if (visiblePanel !== nextPanel) {
            visiblePanel?.animate()?.cancel()
            visiblePanel = nextPanel
            nextPanel.visibility = View.VISIBLE
            nextPanel.alpha = 0f
            nextPanel.translationY = 12f
        }
        nextPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        // Auto-load stats when that tab is opened
        if (selectedId == "stats") refreshStats()
    }

    // ── Token Checker ─────────────────────────────────────────────────────────

    private fun setupChecker() {

        // Long-press check result / token field → open Web Controller
        binding.editCheckToken.setOnLongClickListener {
            val token = binding.editCheckToken.text.toString().trim()
            if (token.isBlank()) { toast("Paste a token first"); return@setOnLongClickListener true }
            startActivity(Intent(requireContext(), TokenWebControllerActivity::class.java).putExtra(TokenWebControllerActivity.EXTRA_TOKEN, token))
            true
        }
        binding.singleCheckResult.setOnLongClickListener {
            val token = binding.editCheckToken.text.toString().trim()
            if (token.isBlank()) return@setOnLongClickListener true
            startActivity(Intent(requireContext(), TokenWebControllerActivity::class.java).putExtra(TokenWebControllerActivity.EXTRA_TOKEN, token))
            true
        }

        binding.btnCheckSingle.setOnClickListener {
            val token = binding.editCheckToken.text.toString().trim()
            if (token.isBlank()) { toast("Enter a token first"); return@setOnClickListener }
            lifecycleScope.launch {
                binding.singleCheckResult.text = "Checking…"
                binding.singleCheckResult.setTextColor(Color.parseColor("#b5bac1"))
                val result = checkToken(token)
                val (label, color) = when {
                    result.status == "VALID"   -> "✓ VALID — ${result.username}" to "#57f287"
                    result.status == "UNVERIFIED" -> "○ UNVERIFIED — ${result.username}" to "#fee75c"
                    result.status == "LOCKED"  -> "⚠ LOCKED" to "#fee75c"
                    result.status == "INVALID" -> "✕ INVALID" to "#ed4245"
                    else                       -> "? ${result.status}" to "#b5bac1"
                }
                binding.singleCheckResult.text = label
                binding.singleCheckResult.setTextColor(Color.parseColor(color))
            }
        }

        binding.btnCheckBulk.setOnClickListener {
            lifecycleScope.launch { runBulkCheck() }
        }

        binding.btnDeleteInvalid.setOnClickListener {
            lifecycleScope.launch {
                val dao = AccountDatabase.getInstance(requireContext()).accountDao()
                dao.deleteByStatus("INVALID")
                toast("Deleted INVALID accounts")
                refreshStats()
            }
        }

        binding.btnExportValid.setOnClickListener {
            lifecycleScope.launch {
                val dao = AccountDatabase.getInstance(requireContext()).accountDao()
                val valid = dao.getValid()
                val text = valid.joinToString("\n") { it.toExportLine() }
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("valid_tokens", text))
                toast("Copied ${valid.size} valid tokens to clipboard")
            }
        }
    }

    private suspend fun runBulkCheck() = withContext(Dispatchers.Main) {
        val dao = AccountDatabase.getInstance(requireContext()).accountDao()
        val accounts = withContext(Dispatchers.IO) { dao.getAll() }
        if (accounts.isEmpty()) { toast("No accounts to check"); return@withContext }

        checkerValidCount = 0; checkerLockedCount = 0; checkerInvalidCount = 0
        binding.checkerProgress.max = accounts.size
        binding.checkerProgress.progress = 0
        binding.checkerProgress.visibility = View.VISIBLE
        binding.checkerStatus.text = "Checking 0 / ${accounts.size}…"

        accounts.forEachIndexed { index, account ->
            val result = checkToken(account.token)
            withContext(Dispatchers.IO) {
                dao.updateStatus(account.id, result.status, System.currentTimeMillis())
            }
            when (result.status) {
                "VALID"   -> checkerValidCount++
                "LOCKED"  -> checkerLockedCount++
                "INVALID" -> checkerInvalidCount++
                "ERROR"   -> checkerLockedCount++ // network/rate-limit — not VALID
            }
            withContext(Dispatchers.Main) {
                binding.checkerProgress.progress = index + 1
                binding.checkerStatus.text = "Checking ${index + 1} / ${accounts.size}…"
                binding.checkerValidCount.text = "✓ $checkerValidCount valid"
                binding.checkerLockedCount.text = "⚠ $checkerLockedCount locked"
                binding.checkerInvalidCount.text = "✕ $checkerInvalidCount invalid"
            }
            delay(prefs.checkerDelayMs.toLong())
        }

        binding.checkerProgress.visibility = View.GONE
        binding.checkerStatus.text = "Done — $checkerValidCount valid, $checkerLockedCount locked, $checkerInvalidCount invalid"
        toast("Bulk check complete")
    }

    private data class CheckResult(val status: String, val username: String = "")

    private suspend fun checkToken(token: String): CheckResult = withContext(Dispatchers.IO) {
        try {
            val status = com.discord.evs.generator.automation.TokenChecker().checkToken(token)
            when (status) {
                com.discord.evs.generator.automation.TokenChecker.TokenStatus.VALID -> {
                    val (_, email) = com.discord.evs.generator.automation.TokenChecker().isEmailVerified(token)
                    // fetch username via @me only when valid
                    var username = ""
                    try {
                        val client = OkHttpClient.Builder()
                            .connectTimeout(10, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.SECONDS)
                            .build()
                        val resp = client.newCall(
                            Request.Builder()
                                .url("https://discord.com/api/v9/users/@me")
                                .header("Authorization", token.trim())
                                .header("User-Agent", "Mozilla/5.0")
                                .build()
                        ).execute()
                        if (resp.isSuccessful) {
                            username = JSONObject(resp.body?.string() ?: "{}").optString("username", "")
                        }
                    } catch (_: Exception) {}
                    CheckResult("VALID", username.ifBlank { email ?: "unknown" })
                }
                com.discord.evs.generator.automation.TokenChecker.TokenStatus.UNVERIFIED -> CheckResult("UNVERIFIED")
                com.discord.evs.generator.automation.TokenChecker.TokenStatus.LOCKED -> CheckResult("LOCKED")
                com.discord.evs.generator.automation.TokenChecker.TokenStatus.INVALID -> CheckResult("INVALID")
                com.discord.evs.generator.automation.TokenChecker.TokenStatus.ERROR -> CheckResult("ERROR")
                com.discord.evs.generator.automation.TokenChecker.TokenStatus.PHONE_REQUIRED -> CheckResult("PHONE_REQUIRED")
            }
        } catch (_: Exception) {
            CheckResult("ERROR")
        }
    }

    // ── Token Decoder ─────────────────────────────────────────────────────────

    
    private fun setupDecoder() {
        binding.btnDecode.setOnClickListener {
            val raw = binding.editDecodeToken.text?.toString().orEmpty()
            val tokens = raw.split('\n', ',', ' ').map { it.trim() }.filter { it.length > 20 }
            if (tokens.isEmpty()) {
                binding.decodeResult.text = "Paste one or more tokens first"
                return@setOnClickListener
            }
            binding.decodeResult.text = "Email reverify running for ${tokens.size} token(s)…"
            viewLifecycleOwner.lifecycleScope.launch {
                val lines = mutableListOf<String>()
                for ((i, tok) in tokens.withIndex()) {
                    val r = emailReverifyToken(tok)
                    lines += "[${i + 1}] ${tok.take(12)}… → ${r}"
                    binding.decodeResult.text = lines.joinToString("\n")
                }
                binding.decodeResult.text = lines.joinToString("\n")
            }
        }
        try {
            binding.root.findViewById<android.view.View>(R.id.btn_reverify_phone)?.setOnClickListener {
                val raw = binding.editDecodeToken.text?.toString().orEmpty()
                val tokens = raw.split('\n', ',', ' ').map { it.trim() }.filter { it.length > 20 }
                if (tokens.isEmpty()) {
                    binding.decodeResult.text = "Paste token(s) for number reverify"
                    return@setOnClickListener
                }
                binding.decodeResult.text = "Number reverify running…"
                viewLifecycleOwner.lifecycleScope.launch {
                    val lines = mutableListOf<String>()
                    for ((i, tok) in tokens.withIndex()) {
                        val r = numberReverifyToken(tok)
                        lines += "[${i + 1}] ${tok.take(12)}… → ${r}"
                        binding.decodeResult.text = lines.joinToString("\n")
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun emailReverifyToken(token: String): String = withContext(Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient.Builder().connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS).build()
            val meReq = okhttp3.Request.Builder().url("https://discord.com/api/v9/users/@me")
                .header("Authorization", token).get().build()
            client.newCall(meReq).execute().use { resp ->
                if (resp.code != 200) return@withContext "INVALID HTTP ${resp.code}"
                val body = resp.body?.string().orEmpty()
                val obj = org.json.JSONObject(body)
                if (obj.optBoolean("verified", false)) return@withContext "already email-verified"
                val email = obj.optString("email", "")
                if (email.isBlank()) return@withContext "no email on account"
                // resend
                val resend = okhttp3.Request.Builder().url("https://discord.com/api/v9/auth/verify/resend")
                    .header("Authorization", token).post("".toRequestBody(null)).build()
                try { client.newCall(resend).execute().close() } catch (_: Exception) {}
                // poll CyberTemp for this mailbox
                val key = com.discord.evs.generator.data.Prefs(requireContext()).cyberTempApiKey.ifBlank {
                    "tk_42616ecc49e5497adff3d568f5b9d03f7723db073dd1901f14ef7b65ddc13298"
                }
                val cyber = CyberTempApi(key)
                val url = cyber.getVerificationUrl(email, 60_000L) { }
                if (url.isNullOrBlank()) return@withContext "no mail in inbox ($email)"
                // open link then try /auth/verify if token in hash
                val linkReq = okhttp3.Request.Builder().url(url).get().build()
                client.newCall(linkReq).execute().use { lr ->
                    val finalUrl = lr.request.url.toString()
                    if (finalUrl.contains("token=")) {
                        val vtok = finalUrl.substringAfter("token=").substringBefore("&").substringBefore("#")
                        val media = "application/json; charset=utf-8".toMediaType()
                        val bodyJson = org.json.JSONObject().put("token", vtok).toString()
                        val vreq = okhttp3.Request.Builder().url("https://discord.com/api/v9/auth/verify")
                            .header("Authorization", token)
                            .post(bodyJson.toRequestBody(media)).build()
                        client.newCall(vreq).execute().use { vr ->
                            if (vr.code == 200) return@withContext "VERIFIED ($email)"
                            if (vr.code == 400 && (vr.body?.string().orEmpty().contains("captcha"))) {
                                return@withContext "captcha required — solve in browser ($email)"
                            }
                            return@withContext "verify HTTP ${vr.code}"
                        }
                    }
                }
                "link opened — check status ($email)"
            }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private suspend fun numberReverifyToken(token: String): String = withContext(Dispatchers.IO) {
        try {
            val st = TokenChecker().checkToken(token)
            when (st) {
                TokenChecker.TokenStatus.LOCKED -> {
                    // attempt phone flow via API helper if enabled
                    val prefs = com.discord.evs.generator.data.Prefs(requireContext())
                    if (!prefs.phoneVerificationEnabled) return@withContext "LOCKED — enable phone verification in Settings"
                    return@withContext "LOCKED — use generator phone API (manual reverify from Settings SMS keys)"
                }
                TokenChecker.TokenStatus.VALID -> "not locked (VALID)"
                TokenChecker.TokenStatus.INVALID -> "INVALID"
                else -> st.name
            }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    private fun decodeToken(token: String): String {
        return try {
            val parts = token.trim().split(".")
            if (parts.size < 2) return "Invalid token format (expected 3 parts separated by '.')"

            // Part 0: Base64url user ID
            // padBase64() converts URL-safe alphabet (-_) → standard (+/) and adds padding.
            // We therefore use Base64.DEFAULT (not URL_SAFE) to match the converted alphabet.
            val userIdBytes = Base64.decode(parts[0].padBase64(), Base64.DEFAULT)
            val userId = String(userIdBytes).trim()

            // Part 1: Timestamp (Base64url-encoded big-endian int)
            var createdAt = "Unknown"
            try {
                val tsBytes = Base64.decode(parts[1].padBase64(), Base64.DEFAULT)
                val ts = tsBytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                val date = Date((ts + 1293840000L) * 1000L)
                createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss UTC", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(date)
            } catch (_: Exception) {}

            // Snowflake timestamp from user ID
            var snowflakeDate = "N/A"
            try {
                val snowflake = userId.toLong()
                val snowflakeTs = (snowflake shr 22) + 1420070400000L
                snowflakeDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss UTC", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(snowflakeTs))
            } catch (_: Exception) {}

            buildString {
                appendLine("User ID:         $userId")
                appendLine("Account Created: $snowflakeDate")
                appendLine("Token Created:   $createdAt")
                appendLine("Token Length:    ${token.length} chars")
                append("Signature:       ${parts.getOrElse(2) { "N/A" }.take(10)}…")
            }
        } catch (e: Exception) {
            "Decode error: ${e.message}"
        }
    }

    // Converts Base64url alphabet (-_) to standard (+/) and pads to a multiple of 4.
    // Always pair with Base64.DEFAULT (not URL_SAFE) since the chars are already converted.
    private fun String.padBase64(): String {
        val standard = replace('-', '+').replace('_', '/')
        return standard + "=".repeat((4 - standard.length % 4) % 4)
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private fun setupExport() {
        binding.rgExportFilter.setOnCheckedChangeListener { _, _ -> updateExportPreview() }
        updateExportPreview()

        binding.btnDoExport.setOnClickListener {
            val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            when (binding.rgExportFormat.checkedRadioButtonId) {
                R.id.rb_fmt_csv  -> exportFileLauncherCsv.launch("accounts_$date.csv")
                R.id.rb_fmt_json -> exportFileLauncherJson.launch("accounts_$date.json")
                else             -> exportFileLauncher.launch("accounts_$date.txt")
            }
        }
    }

    private fun updateExportPreview() {
        lifecycleScope.launch {
            val count = getFilteredAccounts().size
            binding.exportCountPreview.text = "$count account${if (count != 1) "s" else ""} will be exported"
        }
    }

    private suspend fun getFilteredAccounts(): List<Account> {
        val dao = AccountDatabase.getInstance(requireContext()).accountDao()
        return when (binding.rgExportFilter.checkedRadioButtonId) {
            R.id.rb_filter_valid_export    -> dao.getByStatus("VALID")
            R.id.rb_filter_verified_export -> dao.getAll().filter { it.verified }
            else                           -> dao.getAll()
        }
    }

    private fun doExportToUri(uri: Uri, format: String = "txt") {
        lifecycleScope.launch {
            try {
                val accounts = getFilteredAccounts()
                requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                    BufferedWriter(OutputStreamWriter(stream)).use { writer ->
                        when (format) {
                            "csv" -> {
                                writer.write("email,password,token,status,verified,phone,createdAt")
                                writer.newLine()
                                accounts.forEach { writer.write(it.toExportCsv()); writer.newLine() }
                            }
                            "json" -> {
                                val arr = JSONArray()
                                accounts.forEach { acc ->
                                    arr.put(JSONObject().apply {
                                        put("email", acc.email); put("password", acc.password)
                                        put("token", acc.token); put("status", acc.status)
                                        put("verified", acc.verified); put("phone", acc.phone)
                                        put("createdAt", acc.createdAt)
                                    })
                                }
                                writer.write(arr.toString(2))
                            }
                            else -> accounts.forEach { writer.write(it.toExportLine()); writer.newLine() }
                        }
                    }
                }
                toast("Exported ${accounts.size} accounts")
            } catch (e: Exception) {
                toast("Export failed: ${e.message}")
            }
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    private fun setupImport() {
        binding.editImportPaste.addTextChangedListener {
            val count = parseCombos(it.toString()).size
            binding.importPreviewCount.text = "$count tokens detected"
        }

        binding.btnDoImport.setOnClickListener {
            val text = binding.editImportPaste.text.toString()
            val combos = parseCombos(text)
            if (combos.isEmpty()) { toast("No valid tokens found"); return@setOnClickListener }
            lifecycleScope.launch {
                val dao = AccountDatabase.getInstance(requireContext()).accountDao()
                dao.insertAll(combos.map { (email, pass, token) ->
                    Account(email = email, password = pass, token = token, status = "PENDING")
                })
                toast("Imported ${combos.size} accounts")
                binding.editImportPaste.text?.clear()
                refreshStats()
            }
        }
    }

    private data class ComboEntry(val email: String, val pass: String, val token: String)

    private fun parseCombos(text: String): List<ComboEntry> {
        val results = mutableListOf<ComboEntry>()
        text.lines().forEach { line ->
            val t = line.trim()
            if (t.isBlank()) return@forEach
            val parts = t.split(":")
            when {
                parts.size >= 3 -> {
                    val token = parts.last().trim()
                    if (token.length > 20) results.add(ComboEntry(parts[0], parts.getOrElse(1) { "" }, token))
                }
                parts.size == 1 && t.length > 20 -> results.add(ComboEntry("", "", t))
            }
        }
        return results.distinctBy { it.token }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private fun setupStats() {
        binding.btnRefreshStats.setOnClickListener { refreshStats() }
    }

    private fun refreshStats() {
        lifecycleScope.launch {
            val dao = AccountDatabase.getInstance(requireContext()).accountDao()
            val total    = dao.count()
            val valid    = dao.countValid()
            val locked   = dao.countLocked()
            val invalid  = dao.countInvalid()
            val verified = dao.countVerified()
            val rate     = if (total > 0) (valid * 100 / total) else 0

            binding.statTotalNum.text   = "$total"
            binding.statValidNum.text   = "$valid"
            binding.statLockedNum.text  = "$locked"
            binding.statInvalidNum.text = "$invalid"
            binding.statVerifiedNum.text = "$verified"
            binding.statRateNum.text    = "$rate%"
        }
    }

    // ── Proxy Test ────────────────────────────────────────────────────────────

    
    // ── PC bridge ─────────────────────────────────────────────────────────────

    private fun setupPc() {
        if (!FeatureFlags.PC_BRIDGE_ENABLED) {
            binding.tabPc.visibility = View.GONE
            return
        }
        val prefs = Prefs(requireContext())
        val help = binding.tvPcModeHelp
        fun applyModeHelp(mode: String) {
            help.text = when (mode) {
                "pc_generation" ->
                    "PC generation: ChromeDriver on the computer fills Discord. Phone still provides the local API (email/save) when you use the companion."
                "hybrid" ->
                    "Hybrid: temp email and save go through the phone API; the browser runs on the PC."
                else ->
                    "Android backend: phone creates temp email, polls inbox, saves accounts. PC only runs the GUI and calls this phone over USB."
            }
            prefs.pcBridgeMode = mode
        }
        when (prefs.pcBridgeMode) {
            "pc_generation" -> binding.rbPcGeneration.isChecked = true
            "hybrid" -> binding.rbPcHybrid.isChecked = true
            else -> binding.rbPcAndroidBackend.isChecked = true
        }
        applyModeHelp(prefs.pcBridgeMode)
        binding.rgPcMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                binding.rbPcGeneration.id -> "pc_generation"
                binding.rbPcHybrid.id -> "hybrid"
                else -> "android_backend"
            }
            applyModeHelp(mode)
        }

        fun refreshStatus() {
            val token = LocalBridgeServer.ensureToken(prefs)
            val running = LocalBridgeServer.isRunning()
            binding.tvPcStatus.text = if (running)
                "Bridge: running on 127.0.0.1:${prefs.pcBridgePort}"
            else
                "Bridge: stopped — tap START ADB / BRIDGE"
            binding.tvPcToken.text = "Token: $token"
            binding.tvPcInstructions.text = buildString {
                append("On PC (only this):\n")
                append("1. USB cable + USB debugging\n")
                append("2. adb reverse tcp:${prefs.pcBridgePort} tcp:${prefs.pcBridgePort}\n")
                append("3. python pc-links/gui.py\n")
                append("4. Paste the token above → Test connection → Start\n\n")
                append(if (running) "Phone API is live. PC does not need extra services." else "Start the bridge first so the PC can reach this phone.")
            }
        }
        refreshStatus()

        binding.btnPcStartBridge.setOnClickListener {
            prefs.pcBridgeEnabled = true
            LocalBridgeServer.ensureToken(prefs)
            val ok = LocalBridgeServer.start(requireContext())
            refreshStatus()
            Toast.makeText(
                requireContext(),
                if (ok) "Bridge started — run adb reverse on PC, then the GUI"
                else "Could not bind 127.0.0.1:${prefs.pcBridgePort}",
                Toast.LENGTH_LONG
            ).show()
            if (ok) {
                // Soft “connection check”: server is up; real PC handshake is Test connection in GUI
                binding.tvPcStatus.text =
                    "Bridge: running — waiting for PC (adb reverse + GUI token)"
            }
        }
        binding.btnPcStopBridge.setOnClickListener {
            LocalBridgeServer.stop()
            prefs.pcBridgeEnabled = false
            refreshStatus()
            Toast.makeText(requireContext(), "Bridge stopped", Toast.LENGTH_SHORT).show()
        }
        binding.btnPcCopyToken.setOnClickListener {
            val token = LocalBridgeServer.ensureToken(prefs)
            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("evs-pc-token", token))
            Toast.makeText(requireContext(), "Token copied", Toast.LENGTH_SHORT).show()
        }
        binding.btnPcRegenToken.setOnClickListener {
            LocalBridgeServer.regenerateToken(prefs)
            // Restart if running so old token is useless
            if (LocalBridgeServer.isRunning()) {
                LocalBridgeServer.stop()
                prefs.pcBridgeEnabled = true
                LocalBridgeServer.start(requireContext())
            }
            refreshStatus()
            Toast.makeText(requireContext(), "New token generated", Toast.LENGTH_SHORT).show()
        }
        // Export PC companion scripts via SAF so user can transfer to PC
        try {
            val exportBtn = binding.root.findViewById<View>(
                resources.getIdentifier("btn_pc_export_companion", "id", requireContext().packageName)
            )
            exportBtn?.setOnClickListener { exportPcCompanionPack() }
            // Always offer export even without dedicated button: long-press copy token area
            binding.btnPcCopyToken.setOnLongClickListener {
                exportPcCompanionPack()
                true
            }
        } catch (_: Exception) {}
    }

    private fun exportPcCompanionPack() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(android.content.Intent.EXTRA_TITLE, "evs-pc-companion.zip")
            }
            @Suppress("DEPRECATION")
        startActivityForResult(intent, 4815)
            Toast.makeText(requireContext(), "Choose where to save PC companion zip", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "SAF export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("SAF result")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 4815 || resultCode != android.app.Activity.RESULT_OK) return
        val uri = data?.data ?: return
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { out ->
                java.util.zip.ZipOutputStream(out).use { zos ->
                    val am = requireContext().assets
                    val files = am.list("pc_companion") ?: emptyArray()
                    for (name in files) {
                        am.open("pc_companion/$name").use { input ->
                            zos.putNextEntry(java.util.zip.ZipEntry(name))
                            input.copyTo(zos)
                            zos.closeEntry()
                        }
                    }
                    // README
                    val readme = """
EVS PC Companion
================
1. Unzip this folder on your PC
2. pip install -r requirements.txt
3. On phone: Tools → PC Bridge → Start bridge, copy token
4. On PC: adb reverse tcp:PORT tcp:PORT  (port shown in app)
5. python gui.py  → paste token → Test connection → Start

Files: gui.py, bridge_client.py, pc_generator.py, requirements.txt
""".trimIndent().toByteArray()
                    zos.putNextEntry(java.util.zip.ZipEntry("README.txt"))
                    zos.write(readme)
                    zos.closeEntry()
                }
            }
            Toast.makeText(requireContext(), "PC companion saved — transfer zip to PC", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupConfig() {
        binding.apply {
            // ── Theme / Language ─────────────────────────────────────────────
            when (prefs.themeMode) {
                "light"  -> rgThemeMode.check(R.id.rb_theme_light)
                "dark"   -> rgThemeMode.check(R.id.rb_theme_dark)
                else     -> rgThemeMode.check(R.id.rb_theme_system)
            }
            rgThemeMode.setOnCheckedChangeListener { _, id ->
                prefs.themeMode = when (id) {
                    R.id.rb_theme_light -> "light"
                    R.id.rb_theme_dark  -> "dark"
                    else                -> "system"
                }
                App.applyTheme(requireActivity().application)
                requireActivity().recreate()
            }

            // Language dropdown (labels in English) — Config subtab
            val languages = listOf(
                "en" to "English", "es" to "Spanish", "ru" to "Russian",
                "de" to "German", "fr" to "French", "hi" to "Hindi",
                "pt" to "Portuguese", "tr" to "Turkish", "ar" to "Arabic",
                "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean",
                "it" to "Italian", "pl" to "Polish", "nl" to "Dutch",
                "id" to "Indonesian", "vi" to "Vietnamese", "th" to "Thai"
            )
            val langLabels = languages.map { it.second }
            spinnerConfigLanguage.adapter = ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, langLabels
            )
            val langIndex = languages.indexOfFirst { it.first == prefs.appLanguage }.let { if (it < 0) 0 else it }
            spinnerConfigLanguage.setSelection(langIndex)
            spinnerConfigLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    val code = languages[position].first
                    if (code != prefs.appLanguage) {
                        prefs.appLanguage = code
                        com.discord.evs.generator.locale.LocaleHelper.setLocale(requireContext(), code)
                        // Apply on Application so all future contexts pick it up
                        (requireActivity().application as? com.discord.evs.generator.App)?.let {
                            com.discord.evs.generator.locale.LocaleHelper.applyLanguage(it)
                        }
                        requireActivity().recreate()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            // PC Bridge bootstrap (long-press Tools title area not available — auto tip + ensure token)
            if (FeatureFlags.PC_BRIDGE_ENABLED) {
                // Ensure a bridge token exists for PC companion; user enables bridge from About/settings.
                LocalBridgeServer.ensureToken(prefs)
            }

            // ── Retry / Backoff ───────────────────────────────────────────────
            switchRetryBackoff.isChecked = prefs.retryBackoffEnabled
            editRetryMaxAttempts.setText(prefs.retryMaxAttempts.toString())
            editRetryBaseDelay.setText(prefs.retryBaseDelayMs.toString())
            editRetryMaxDelay.setText(prefs.retryMaxDelayMs.toString())

            switchRetryBackoff.setOnCheckedChangeListener { _, v -> prefs.retryBackoffEnabled = v }
            editRetryMaxAttempts.addTextChangedListener { prefs.retryMaxAttempts = it.toString().toIntOrNull()?.coerceAtLeast(1) ?: 5 }
            editRetryBaseDelay.addTextChangedListener { prefs.retryBaseDelayMs = it.toString().toIntOrNull()?.coerceAtLeast(500) ?: 1000 }
            editRetryMaxDelay.addTextChangedListener { prefs.retryMaxDelayMs = it.toString().toIntOrNull()?.coerceAtLeast(1000) ?: 30000 }

            // ── Fingerprint Randomization ─────────────────────────────────────
            switchFingerprintRandom.isChecked = prefs.fingerprintRandomizationEnabled
            fingerprintRandomSettings.visibility = if (prefs.fingerprintRandomizationEnabled) View.VISIBLE else View.GONE
            editFingerprintScreens.setText(prefs.fingerprintScreenSizes)
            editFingerprintTimezones.setText(prefs.fingerprintTimezones)
            switchFingerprintWebgl.isChecked = prefs.fingerprintWebglDisabled
            switchFingerprintCanvas.isChecked = prefs.fingerprintCanvasSpoof

            switchFingerprintRandom.setOnCheckedChangeListener { _, v ->
                prefs.fingerprintRandomizationEnabled = v
                fingerprintRandomSettings.visibility = if (v) View.VISIBLE else View.GONE
            }
            editFingerprintScreens.addTextChangedListener { prefs.fingerprintScreenSizes = it.toString() }
            editFingerprintTimezones.addTextChangedListener { prefs.fingerprintTimezones = it.toString() }
            switchFingerprintWebgl.setOnCheckedChangeListener { _, v -> prefs.fingerprintWebglDisabled = v }
            switchFingerprintCanvas.setOnCheckedChangeListener { _, v -> prefs.fingerprintCanvasSpoof = v }
        }
    }

    private fun setupProxyTest() {
        binding.btnRunProxyTest.setOnClickListener {
            lifecycleScope.launch { runProxyTest() }
        }
    }

    private suspend fun runProxyTest() = withContext(Dispatchers.Main) {
        val proxyList = prefs.proxyList.lines().filter { it.isNotBlank() }
        val single = prefs.proxyAddress
        val allProxies = (if (single.isNotBlank()) listOf(single) else emptyList()) + proxyList
        val distinct = allProxies.distinct().filter { it.isNotBlank() }

        if (distinct.isEmpty()) {
            binding.proxyTestResults.text = "No proxies configured. Add proxies in Settings → Proxy."
            return@withContext
        }

        binding.proxyTestResults.text = "Testing ${distinct.size} proxies…"
        val sb = StringBuilder()

        distinct.forEach { proxyStr ->
            val start = System.currentTimeMillis()
            val ok = withContext(Dispatchers.IO) { com.discord.evs.generator.automation.ProxyManager.testProxyRaw(proxyStr) }
            val latency = System.currentTimeMillis() - start
            val line = if (ok) "✓ $proxyStr — ${latency}ms" else "✕ $proxyStr — FAILED"
            sb.appendLine(line)
            binding.proxyTestResults.text = sb.toString().trim()
        }
    }

    private fun testProxy(proxyStr: String): Boolean {
        return try {
            val parsed = parseProxyLine(proxyStr) ?: return false
            val proxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(parsed.host, parsed.port))
            val builder = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
            if (parsed.user != null && parsed.pass != null) {
                val auth = okhttp3.Credentials.basic(parsed.user, parsed.pass)
                builder.proxyAuthenticator { _, response ->
                    response.request.newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                }
            }
            val client = builder.build()
            val resp = client.newCall(
                Request.Builder()
                    .url("https://discord.com/api/v9/gateway")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
            ).execute()
            resp.isSuccessful || resp.code in listOf(401, 403) // reachable
        } catch (_: Exception) { false }
    }

    private data class ParsedProxy(val host: String, val port: Int, val user: String?, val pass: String?)

    /** Supports host:port | user:pass@host:port | host:port:user:pass */
    private fun parseProxyLine(raw: String): ParsedProxy? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return try {
            if ("@" in s) {
                val (cred, hostport) = s.split("@", limit = 2)
                val (user, pass) = cred.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                val hp = hostport.split(":")
                ParsedProxy(hp[0], hp.getOrElse(1) { "8080" }.toInt(), user, pass)
            } else {
                val parts = s.split(":")
                when (parts.size) {
                    2 -> ParsedProxy(parts[0], parts[1].toInt(), null, null)
                    4 -> ParsedProxy(parts[0], parts[1].toInt(), parts[2], parts[3])
                    else -> ParsedProxy(parts[0], parts.getOrElse(1) { "8080" }.toIntOrNull() ?: 8080, null, null)
                }
            }
        } catch (_: Exception) { null }
    }

    private fun toast(msg: String) { val c = context ?: return; Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupHealth() {
        val btn = binding.root.findViewById<View>(R.id.btn_health_scan) ?: return
        val status = binding.root.findViewById<android.widget.TextView>(R.id.health_status)
        val results = binding.root.findViewById<android.widget.TextView>(R.id.health_results)
        btn.setOnClickListener {
            lifecycleScope.launch {
                val dao = AccountDatabase.getInstance(requireContext()).accountDao()
                val accounts = withContext(Dispatchers.IO) { dao.getAll() }
                if (accounts.isEmpty()) { toast("No accounts"); return@launch }
                status?.text = "Scanning ${accounts.size}…"
                val checker = com.discord.evs.generator.automation.TokenHealthChecker()
                val sb = StringBuilder()
                var v=0; var l=0; var ph=0; var em=0; var inv=0
                accounts.forEachIndexed { i, acc ->
                    val h = checker.check(acc.token)
                    withContext(Dispatchers.IO) { dao.updateStatus(acc.id, h.status, System.currentTimeMillis()) }
                    when (h.status) {
                        "VALID" -> v++
                        "LOCKED" -> l++
                        "PHONE" -> ph++
                        "EMAIL" -> em++
                        else -> inv++
                    }
                    sb.append("${i+1}. ${h.status} ${h.username} nitro=${h.nitro} guilds=${h.guildCount} phone=${h.phone.ifBlank { "-" }}\n")
                    status?.text = "Scanning ${i+1}/${accounts.size}"
                }
                status?.text = "Done — VALID=$v LOCKED=$l PHONE=$ph EMAIL=$em OTHER=$inv"
                val prov = com.discord.evs.generator.automation.ProviderHealth.summaryText()
                results?.text = sb.toString() + "\n\n— Provider health —\n" + prov
            }
        }
    }

    private fun setupSmsPanel() {
        val editProv = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_sms_provider)
        val editKey = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_sms_api_key)
        val editCountry = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_sms_country)
        val editPrice = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_sms_max_price)
        val btn = binding.root.findViewById<View>(R.id.btn_sms_save) ?: return
        editProv?.setText(prefs.smsProvider)
        editKey?.setText(prefs.smsApiKey)
        editCountry?.setText(prefs.smsCountry)
        editPrice?.setText(prefs.smsMaxPrice)
        btn.setOnClickListener {
            prefs.smsProvider = editProv?.text?.toString()?.trim() ?: "free"
            prefs.smsApiKey = editKey?.text?.toString()?.trim() ?: ""
            prefs.smsCountry = editCountry?.text?.toString()?.trim() ?: "us"
            prefs.smsMaxPrice = editPrice?.text?.toString()?.trim() ?: "0.50"
            toast("SMS settings saved")
        }
    }

    private fun setupInvites() {
        val edit = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_invite_list)
        val btn = binding.root.findViewById<View>(R.id.btn_resolve_invites) ?: return
        val out = binding.root.findViewById<android.widget.TextView>(R.id.invite_results)
        btn.setOnClickListener {
            val lines = edit?.text?.toString()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            if (lines.isEmpty()) { toast("Paste invites first"); return@setOnClickListener }
            lifecycleScope.launch {
                val scraper = com.discord.evs.generator.automation.InviteScraper()
                val sb = StringBuilder()
                lines.forEach { line ->
                    val info = scraper.resolve(line)
                    sb.append(if (info.valid) "✓ ${info.code} — ${info.guildName} (${info.members}) ver=${info.verificationLevel}\n"
                              else "✕ ${info.code} — ${info.reason}\n")
                }
                out?.text = sb.toString()
            }
        }
    }

    private fun setupMassDm() {
        val sw = binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_mass_dm)
        val tmpl = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_mass_dm_template)
        val targets = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_mass_dm_targets)
        val tokenEt = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_mass_dm_token)
        val btn = binding.root.findViewById<View>(R.id.btn_mass_dm_run) ?: return
        val log = binding.root.findViewById<android.widget.TextView>(R.id.mass_dm_log)
        sw?.isChecked = prefs.massDmEnabled
        tmpl?.setText(prefs.massDmTemplate)
        sw?.setOnCheckedChangeListener { _, v -> prefs.massDmEnabled = v }
        btn.setOnClickListener {
            if (!prefs.massDmEnabled) { toast("Enable Mass DM first"); return@setOnClickListener }
            val token = tokenEt?.text?.toString()?.trim().orEmpty()
            val msg = tmpl?.text?.toString()?.trim().orEmpty()
            val ids = targets?.text?.toString()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            if (token.isBlank() || msg.isBlank() || ids.isEmpty()) { toast("token + message + targets required"); return@setOnClickListener }
            prefs.massDmTemplate = msg
            lifecycleScope.launch {
                val tool = com.discord.evs.generator.automation.MassDmTool()
                val sb = StringBuilder()
                val cap = prefs.massDmDailyCap.coerceAtLeast(1)
                val delaySec = prefs.massDmDelaySeconds.coerceAtLeast(20)
                ids.take(cap).forEachIndexed { i, uid ->
                    val r = tool.openDmAndSend(token, uid, msg)
                    sb.append("${i+1}. $uid → ${if (r.ok) "OK" else r.detail}\n")
                    log?.text = sb.toString()
                    if (i < ids.size - 1) delay(delaySec * 1000L)
                }
                toast("Mass DM finished")
            }
        }
    }

    private fun setupProfiles() {
        val nameEt = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_profile_name)
        val listTv = binding.root.findViewById<android.widget.TextView>(R.id.profile_list)
        val statsTv = binding.root.findViewById<android.widget.TextView>(R.id.captcha_stats_view)
        val btnSave = binding.root.findViewById<View>(R.id.btn_profile_save)
        val btnLoad = binding.root.findViewById<View>(R.id.btn_profile_load)
        fun refresh() {
            val root = try { org.json.JSONObject(prefs.runProfilesJson) } catch (_: Exception) { org.json.JSONObject() }
            val names = root.keys().asSequence().toList()
            listTv?.text = if (names.isEmpty()) "No profiles" else names.joinToString("\n")
            statsTv?.text = com.discord.evs.generator.automation.CaptchaAnalytics.summary(requireContext())
        }
        refresh()
        btnSave?.setOnClickListener {
            val name = nameEt?.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) { toast("name required"); return@setOnClickListener }
            val root = try { org.json.JSONObject(prefs.runProfilesJson) } catch (_: Exception) { org.json.JSONObject() }
            val snap = org.json.JSONObject()
            snap.put("emailProvider", prefs.emailProvider)
            snap.put("smsProvider", prefs.smsProvider)
            snap.put("proxyEnabled", prefs.proxyEnabled)
            snap.put("autoCaptchaEnabled", prefs.autoCaptchaEnabled)
            snap.put("phoneVerificationEnabled", prefs.phoneVerificationEnabled)
            root.put(name, snap)
            prefs.runProfilesJson = root.toString()
            prefs.activeRunProfile = name
            refresh()
            toast("Profile saved: $name")
        }
        btnLoad?.setOnClickListener {
            val name = nameEt?.text?.toString()?.trim().orEmpty()
            val root = try { org.json.JSONObject(prefs.runProfilesJson) } catch (_: Exception) { org.json.JSONObject() }
            val snap = root.optJSONObject(name)
            if (snap == null) { toast("profile not found"); return@setOnClickListener }
            prefs.emailProvider = snap.optString("emailProvider", prefs.emailProvider)
            prefs.smsProvider = snap.optString("smsProvider", prefs.smsProvider)
            prefs.proxyEnabled = snap.optBoolean("proxyEnabled", prefs.proxyEnabled)
            prefs.autoCaptchaEnabled = snap.optBoolean("autoCaptchaEnabled", prefs.autoCaptchaEnabled)
            prefs.phoneVerificationEnabled = snap.optBoolean("phoneVerificationEnabled", prefs.phoneVerificationEnabled)
            prefs.activeRunProfile = name
            toast("Loaded profile: $name")
        }
    }

    private fun setupMorePanel() {
        val swPhone = binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_alert_phone)
        val swCap = binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_alert_captcha)
        val swSound = binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_alert_sound)
        val swCompact = binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_compact_log)
        val swSched = binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_schedule)
        val editTime = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_schedule_time)
        val swImap = binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_imap)
        val editHost = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_imap_host)
        val editUser = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_imap_user)
        val editPass = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_imap_pass)
        val swCrash = binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_crash_tg)
        val swPriv = binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_post_privacy)
        val swDms = binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_post_dms)
        val btn = binding.root.findViewById<View>(R.id.btn_more_save) ?: return
        swPhone?.isChecked = prefs.alertOnPhoneRequired
        swCap?.isChecked = prefs.alertOnCaptchaManual
        swSound?.isChecked = prefs.alertSoundEnabled
        swCompact?.isChecked = prefs.compactLogEnabled
        swSched?.isChecked = prefs.scheduleEnabled
        editTime?.setText("%02d:%02d".format(prefs.scheduleHour, prefs.scheduleMinute))
        swImap?.isChecked = prefs.imapEnabled
        editHost?.setText(prefs.imapHost)
        editUser?.setText(prefs.imapUser)
        editPass?.setText(prefs.imapPass)
        swCrash?.isChecked = prefs.crashReportTgEnabled
        swPriv?.isChecked = prefs.postGenPrivacyLock
        swDms?.isChecked = prefs.postGenDisableDms
        btn.setOnClickListener {
            prefs.alertOnPhoneRequired = swPhone?.isChecked == true
            prefs.alertOnCaptchaManual = swCap?.isChecked == true
            prefs.alertSoundEnabled = swSound?.isChecked == true
            prefs.compactLogEnabled = swCompact?.isChecked == true
            prefs.scheduleEnabled = swSched?.isChecked == true
            val t = editTime?.text?.toString()?.trim().orEmpty()
            if (t.contains(":")) {
                val parts = t.split(":")
                prefs.scheduleHour = parts[0].toIntOrNull() ?: 9
                prefs.scheduleMinute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            }
            prefs.imapEnabled = swImap?.isChecked == true
            prefs.imapHost = editHost?.text?.toString()?.trim() ?: prefs.imapHost
            prefs.imapUser = editUser?.text?.toString()?.trim() ?: ""
            prefs.imapPass = editPass?.text?.toString()?.trim() ?: ""
            prefs.crashReportTgEnabled = swCrash?.isChecked == true
            prefs.postGenPrivacyLock = swPriv?.isChecked == true
            prefs.postGenDisableDms = swDms?.isChecked == true
            toast("More settings saved")
        }
    }

}
