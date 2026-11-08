package com.discord.evs.generator.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.evs.generator.R
import com.discord.evs.generator.automation.ServerJoiner
import com.discord.evs.generator.data.AccountDatabase
import com.discord.evs.generator.data.Prefs
import com.discord.evs.generator.databinding.FragmentJoinerBinding
import com.discord.evs.generator.databinding.ItemInviteLinkBinding
import com.discord.evs.generator.databinding.ItemLogBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JoinerFragment : Fragment() {

    private var _binding: FragmentJoinerBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs

    private val linkAdapter = InviteLinkAdapter { position -> removeLink(position) }
    private val logAdapter = JoinLogAdapter()

    private val inviteLinks = mutableListOf<String>()
    private lateinit var joiner: ServerJoiner
    private var webView: WebView? = null
    private var joinJob: Job? = null

    private var joinedCount = 0
    private var failedCount = 0
    private var captchaCount = 0
    private var isInviteDesktopMode = false

    companion object {
        private const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentJoinerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiMotion.enter(view)
        UiMotion.staggerChildren(view as? ViewGroup ?: return)
        prefs = Prefs(requireContext())
        joiner = ServerJoiner(prefs)

        setupWebView()
        setupRecyclers()
        loadSavedLinks()
        loadPrefs()
        setupListeners()
        refreshAccountCount()
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

    private fun setupWebView() {
        webView = WebView(requireContext()).apply {
            try {
                com.discord.evs.generator.automation.ProxyManager.applyToWebView(requireContext(), this, prefs)
            } catch (_: Exception) {}
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = DESKTOP_UA
                cacheMode = WebSettings.LOAD_NO_CACHE
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Reset UA back to mobile once we navigate away from the invite page
                    if (isInviteDesktopMode && !isInviteUrl(url ?: "")) {
                        resetUserAgent(view)
                    }
                    joiner.notifyPageFinished(url ?: "")
                    // NopeCHA extension content scripts on invite / captcha pages
                    try {
                        if (com.discord.evs.generator.automation.ExtensionHost.isEnabled()) {
                            com.discord.evs.generator.automation.ExtensionHost.injectInto(view) { }
                        }
                    } catch (_: Exception) {}
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // Discord mobile invite pages immediately redirect to intent:// to open the app.
                    // Load them with a desktop UA so the WebView sees the normal invite page instead.
                    if (isInviteUrl(url)) {
                        if (!isInviteDesktopMode) {
                            loadInviteWithDesktopUA(view, url)
                            return true
                        }
                        return false
                    }
                    return handleNonHttpUrl(url, view)
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    val url = request?.url?.toString() ?: return
                    val code = error?.errorCode ?: return
                    // net::ERR_UNKNOWN_URL_SCHEME usually means an intent:// or discord:// redirect
                    if (code == WebViewClient.ERROR_UNKNOWN || code == ERROR_UNKNOWN) {
                        if (handleNonHttpUrl(url, view)) return
                    }
                }
            }
        }
        binding.webviewContainer.addView(
            webView,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        joiner.setJsListener(object : ServerJoiner.JsListener {
            override fun loadUrl(url: String) {
                activity?.runOnUiThread { webView?.loadUrl(url) }
            }

            override fun evaluateJs(js: String, onResult: ((String?) -> Unit)?) {
                activity?.runOnUiThread {
                    webView?.evaluateJavascript(js) { result ->
                        onResult?.invoke(result?.trim('"'))
                    }
                }
            }

            override fun onStateChanged(newState: ServerJoiner.State) {
                activity?.runOnUiThread { updateJoinButtons(newState) }
            }

            override fun onLog(account: String, server: String, result: ServerJoiner.JoinResult, message: String) {
                activity?.runOnUiThread {
                    val b = _binding ?: return@runOnUiThread
                    when (result) {
                        ServerJoiner.JoinResult.JOINED         -> joinedCount++
                        ServerJoiner.JoinResult.FAILED         -> failedCount++
                        ServerJoiner.JoinResult.CAPTCHA_NEEDED -> captchaCount++
                        ServerJoiner.JoinResult.ALREADY_MEMBER -> joinedCount++
                        ServerJoiner.JoinResult.INVALID_LINK   -> failedCount++
                        ServerJoiner.JoinResult.INFO           -> {}
                    }
                    logAdapter.addEntry(JoinLogEntry(account, server, result, message))
                    b.joinLogRecycler.scrollToPosition(logAdapter.itemCount - 1)
                    b.joinLogEmpty.visibility = View.GONE
                    updateStats()
                }
            }

            override fun onProgress(done: Int, total: Int) {
                activity?.runOnUiThread { updateStats() }
            }

            override fun onPageFinished(url: String) {
                // no-op: used by ServerJoiner internally via notifyPageFinished
            }

            override fun onCaptchaPrompt(onSolved: () -> Unit) {
                activity?.runOnUiThread {
                    val b = _binding ?: return@runOnUiThread
                    b.captchaBanner.visibility = View.VISIBLE
                    b.btnCaptchaSolved.setOnClickListener {
                        _binding?.captchaBanner?.visibility = View.GONE
                        joiner.notifyCaptchaSolved()
                    }
                }
            }
        })
    }

    private fun showWebview() {
        val b = _binding ?: return
        b.webviewContainer.visibility = View.VISIBLE
        b.webviewDivider.visibility = View.VISIBLE
        (b.webviewContainer.layoutParams as? android.widget.LinearLayout.LayoutParams)?.apply {
            height = 0
            weight = 7f
        }
        b.webviewContainer.requestLayout()
    }

    private fun hideWebview() {
        val b = _binding ?: return
        b.captchaBanner.visibility = View.GONE
        b.webviewContainer.visibility = View.GONE
        b.webviewDivider.visibility = View.GONE
    }

    // ── RecyclerViews ─────────────────────────────────────────────────────────

    private fun setupRecyclers() {
        binding.linksRecycler.apply {
            adapter = linkAdapter
            layoutManager = LinearLayoutManager(context)
            isNestedScrollingEnabled = false
        }
        binding.joinLogRecycler.apply {
            adapter = logAdapter
            layoutManager = LinearLayoutManager(context)
            isNestedScrollingEnabled = false
        }
    }

    // ── Settings / state ──────────────────────────────────────────────────────

    private fun loadSavedLinks() {
        try {
            val arr = JSONArray(prefs.joinerInviteLinks)
            for (i in 0 until arr.length()) inviteLinks.add(arr.getString(i))
        } catch (_: Exception) {}
        refreshLinkList()
    }

    private fun saveLinks() {
        val arr = JSONArray()
        inviteLinks.forEach { arr.put(it) }
        prefs.joinerInviteLinks = arr.toString()
    }

    private fun loadPrefs() {
        binding.switchJoinerCaptcha.isChecked = prefs.joinerCaptchaAutoSolve
        binding.switchJoinerProxy.isChecked = prefs.joinerProxyEnabled
        binding.delaySeekbar.progress = prefs.joinerDelaySeconds
        updateDelayText(prefs.joinerDelaySeconds)
    }

    private fun refreshAccountCount() {
        lifecycleScope.launch {
            val dao = AccountDatabase.getInstance(requireContext()).accountDao()
            val total = dao.count()
            val valid = dao.countValid()
            activity?.runOnUiThread {
                _binding?.genAccountCount?.text = "$total accounts in database ($valid valid)"
            }
        }
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.btnAddLink.setOnClickListener {
            val input = binding.editInviteInput.text.toString().trim()
            if (input.isBlank()) { toast("Enter an invite link first"); return@setOnClickListener }
            val code = joiner.extractCode(input)
            if (code == null) { toast("Invalid invite link format"); return@setOnClickListener }
            if (!inviteLinks.contains(code)) {
                inviteLinks.add(code)
                refreshLinkList()
                saveLinks()
                binding.editInviteInput.text?.clear()
            } else {
                toast("Link already in list")
            }
        }

        binding.btnImportLinks.setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (text.isBlank()) { toast("Clipboard is empty"); return@setOnClickListener }
            var count = 0
            text.lines().forEach { line ->
                val code = joiner.extractCode(line.trim())
                if (code != null && !inviteLinks.contains(code)) {
                    inviteLinks.add(code)
                    count++
                }
            }
            if (count > 0) { refreshLinkList(); saveLinks(); toast("Imported $count links") }
            else toast("No new valid links found")
        }

        binding.btnClearLinks.setOnClickListener {
            inviteLinks.clear()
            refreshLinkList()
            saveLinks()
        }

        binding.rgAccountSource.setOnCheckedChangeListener { _, id ->
            binding.panelGenerated.visibility = if (id == R.id.rb_source_generated) View.VISIBLE else View.GONE
            binding.panelManual.visibility = if (id == R.id.rb_source_manual) View.VISIBLE else View.GONE
        }

        binding.editManualCombos.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val tokens = parseManualCombos(s.toString())
                binding.manualComboCount.text = "${tokens.size} tokens parsed"
            }
        })

        binding.delaySeekbar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                updateDelayText(progress)
                prefs.joinerDelaySeconds = progress.coerceAtLeast(1)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.switchJoinerCaptcha.setOnCheckedChangeListener { _, v -> prefs.joinerCaptchaAutoSolve = v }
        binding.switchJoinerProxy.setOnCheckedChangeListener { _, v -> prefs.joinerProxyEnabled = v }

        
        // Joiner mode: WebView (default) vs API
        when (prefs.joinerMode.lowercase()) {
            "api" -> binding.rgJoinerMode.check(R.id.rb_joiner_api)
            "webview", "web" -> binding.rgJoinerMode.check(R.id.rb_joiner_webview)
            else -> binding.rgJoinerMode.check(R.id.rb_joiner_hybrid)
        }
        binding.rgJoinerMode.setOnCheckedChangeListener { _, id ->
            prefs.joinerMode = when (id) {
                R.id.rb_joiner_api -> "api"
                R.id.rb_joiner_webview -> "webview"
                else -> "hybrid"
            }
        }

        binding.btnStartJoin.setOnClickListener { startJoining() }
        binding.btnStopJoin.setOnClickListener {
            joiner.stop()
            joinJob?.cancel()
            joinJob = null
            hideWebview()
            logAdapter.addEntry(JoinLogEntry("", "", ServerJoiner.JoinResult.INFO, "Stopped by user"))
        }
    }

    // ── Join flow ─────────────────────────────────────────────────────────────

    private fun startJoining() {
        if (inviteLinks.isEmpty()) { toast("Add at least one invite link"); return }

        joinedCount = 0; failedCount = 0; captchaCount = 0
        logAdapter.clear()
        binding.joinLogEmpty.visibility = View.GONE
        updateStats()
        val mode = prefs.joinerMode.lowercase()
        when (mode) {
            "api" -> {
                hideWebview()
                logAdapter.addEntry(JoinLogEntry("", "", ServerJoiner.JoinResult.INFO, "API mode — token + Discord API only"))
            }
            "webview", "web" -> {
                showWebview()
                logAdapter.addEntry(JoinLogEntry("", "", ServerJoiner.JoinResult.INFO, "WebView mode — browser join path"))
            }
            else -> {
                showWebview() // hybrid may fall back to WebView
                logAdapter.addEntry(JoinLogEntry("", "", ServerJoiner.JoinResult.INFO, "Hybrid mode — API first, WebView fallback"))
            }
        }

        val isManual = binding.rbSourceManual.isChecked

        joinJob = lifecycleScope.launch {
            if (isManual) {
                val tokens = parseManualCombos(binding.editManualCombos.text.toString())
                if (tokens.isEmpty()) { toast("No valid tokens in manual input"); hideWebview(); return@launch }
                joiner.joinAllRaw(tokens, inviteLinks.toList())
            } else {
                val dao = AccountDatabase.getInstance(requireContext()).accountDao()
                val accounts = if (binding.rbGenValid.isChecked) dao.getValid() else dao.getAll()
                if (accounts.isEmpty()) { toast("No accounts in database"); hideWebview(); return@launch }
                joiner.joinAll(accounts, inviteLinks.toList())
            }
            hideWebview()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun refreshLinkList() {
        linkAdapter.setLinks(inviteLinks)
        binding.linksEmpty.visibility = if (inviteLinks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun removeLink(position: Int) {
        if (position in inviteLinks.indices) {
            inviteLinks.removeAt(position)
            refreshLinkList()
            saveLinks()
        }
    }

    private fun updateJoinButtons(state: ServerJoiner.State) {
        val b = _binding ?: return
        val running = state == ServerJoiner.State.RUNNING
        b.btnStartJoin.isEnabled = !running
        b.btnStartJoin.alpha = if (running) 0.4f else 1.0f
        b.btnStopJoin.isEnabled = running
        b.btnStopJoin.alpha = if (running) 1.0f else 0.4f
    }

    private fun updateStats() {
        val b = _binding ?: return
        b.joinStatJoined.text = "✓$joinedCount"
        b.joinStatFailed.text = "✕$failedCount"
        b.joinStatCaptcha.text = "⚠$captchaCount"
    }

    private fun updateDelayText(seconds: Int) {
        _binding?.delayValueText?.text = "$seconds second${if (seconds == 1) "" else "s"}"
    }

    private fun parseManualCombos(text: String): List<String> {
        val tokens = mutableListOf<String>()
        text.lines().forEach { line ->
            val t = line.trim()
            if (t.isBlank()) return@forEach
            val token = when {
                t.contains(":") -> t.split(":").lastOrNull()?.trim() ?: ""
                else -> t
            }
            if (token.length > 20) tokens.add(token)
        }
        return tokens.distinct()
    }

    private fun handleNonHttpUrl(url: String, view: WebView?): Boolean {
        if (url.startsWith("http://") || url.startsWith("https://")) return false
        if (url.startsWith("intent://") || url.startsWith("discord://")) {
            // Try to extract the actual Discord invite URL and keep joining in the WebView
            val inviteUrl = extractInviteFromIntent(url)
            if (inviteUrl != null) {
                logJoin("Intercepted app redirect, loading invite page: $inviteUrl")
                loadInviteWithDesktopUA(view, inviteUrl)
                return true
            }
            // No usable invite URL — try to launch the Discord app directly
            try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                startActivity(intent)
                logJoin("Opened Discord app intent")
                return true
            } catch (e: Exception) {
                // App not installed — try the Play Store fallback
                val packageName = "com.discord"
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
                    logJoin("Redirected to Play Store for Discord")
                    return true
                } catch (_: Exception) {
                    toast("Cannot open Discord app or store")
                }
            }
        }
        return false
    }

    private fun isInviteUrl(url: String): Boolean =
        url.startsWith("https://discord.com/invite/") || url.startsWith("https://discord.gg/")

    private fun loadInviteWithDesktopUA(view: WebView?, url: String) {
        view?.settings?.userAgentString = DESKTOP_UA
        isInviteDesktopMode = true
        view?.loadUrl(url)
    }

    private fun resetUserAgent(view: WebView?) {
        // Keep the joiner on desktop UA throughout the flow so Discord doesn’t switch back to mobile auth
        view?.settings?.userAgentString = DESKTOP_UA
        isInviteDesktopMode = false
    }

    private fun extractInviteFromIntent(url: String): String? {
        val regex = Regex("(?:deep_link_value|af_dp|browser_fallback_url)=([^&;#]+)")
        regex.findAll(url).forEach { match ->
            val raw = match.groupValues[1]
            // Decode once or twice — AppsFlyer sometimes double-encodes
            val once = try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }
            val twice = try { URLDecoder.decode(once, "UTF-8") } catch (_: Exception) { once }
            listOf(once, twice).forEach { candidate ->
                Regex("https?://(?:discord\\.gg|discord\\.com/invite)/[A-Za-z0-9-_]+").find(candidate)?.value?.let { return it }
            }
        }
        return null
    }

    private fun logJoin(message: String) {
        logAdapter.addEntry(JoinLogEntry("", "", ServerJoiner.JoinResult.INFO, message))
        val b = _binding ?: return
        b.joinLogRecycler.scrollToPosition(logAdapter.itemCount - 1)
        b.joinLogEmpty.visibility = View.GONE
    }

    private fun toast(msg: String) {
        val ctx = context ?: return
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        joinJob?.cancel()
        joiner.stop()
        joiner.setJsListener(null)
        webView?.destroy()
        webView = null
        super.onDestroyView()
        _binding = null
    }
}

// ─── Invite Link Adapter ──────────────────────────────────────────────────────

class InviteLinkAdapter(private val onDelete: (Int) -> Unit) : RecyclerView.Adapter<InviteLinkAdapter.VH>() {
    private val items = mutableListOf<String>()
    private val statuses = mutableMapOf<String, String>()

    fun setLinks(links: List<String>) { items.clear(); items.addAll(links); notifyDataSetChanged() }
    fun updateStatus(code: String, status: String) {
        statuses[code] = status
        val idx = items.indexOf(code)
        if (idx >= 0) notifyItemChanged(idx)
    }

    inner class VH(val b: ItemInviteLinkBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemInviteLinkBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: VH, position: Int) {
        val code = items[position]
        holder.b.linkIndex.text = "${position + 1}."
        holder.b.linkText.text = "discord.gg/$code"
        val status = statuses[code] ?: "PENDING"
        holder.b.linkStatus.text = status
        holder.b.linkStatus.setTextColor(
            Color.parseColor(when (status) {
                "DONE"    -> "#57f287"
                "FAILED"  -> "#ed4245"
                "ACTIVE"  -> "#fee75c"
                else      -> "#b5bac1"
            })
        )
        holder.b.deleteBtn.setOnClickListener { onDelete(holder.bindingAdapterPosition) }
    }
}

// ─── Join Log Entry ───────────────────────────────────────────────────────────

data class JoinLogEntry(
    val account: String,
    val server: String,
    val result: ServerJoiner.JoinResult,
    val message: String
)

// ─── Join Log Adapter (uses item_log.xml — same style as Generator tab) ───────

class JoinLogAdapter : RecyclerView.Adapter<JoinLogAdapter.VH>() {
    private val items = mutableListOf<JoinLogEntry>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun addEntry(entry: JoinLogEntry) { items.add(0, entry); notifyItemInserted(0) }
    fun clear() { items.clear(); notifyDataSetChanged() }

    inner class VH(val b: ItemLogBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.b.logTime.text = timeFmt.format(Date())
        holder.b.continueLogBtn.visibility = View.GONE

        if (entry.result == ServerJoiner.JoinResult.INFO) {
            // Step-log entry: plain gray text, no prefix/account/server
            holder.b.logText.text = entry.message
            holder.b.logText.setTextColor(Color.parseColor("#b5bac1"))
            return
        }

        val (prefix, color) = when (entry.result) {
            ServerJoiner.JoinResult.JOINED         -> "[+]" to "#57f287"
            ServerJoiner.JoinResult.ALREADY_MEMBER -> "[=]" to "#57f287"
            ServerJoiner.JoinResult.FAILED         -> "[-]" to "#ed4245"
            ServerJoiner.JoinResult.CAPTCHA_NEEDED -> "[!]" to "#fee75c"
            ServerJoiner.JoinResult.INVALID_LINK   -> "[-]" to "#ed4245"
            ServerJoiner.JoinResult.INFO           -> "   " to "#b5bac1"
        }
        holder.b.logText.setTextColor(Color.parseColor("#dcddde"))
        holder.b.logText.text = android.text.SpannableString(
            "$prefix ${entry.account} → ${entry.server} — ${entry.message}"
        ).also { sp ->
            sp.setSpan(
                android.text.style.ForegroundColorSpan(Color.parseColor(color)),
                0, prefix.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
