package com.discord.evs.generator.ui

import android.content.Intent

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.discord.evs.generator.automation.TokenChecker
import com.discord.evs.generator.data.Account
import com.discord.evs.generator.data.AccountDao
import com.discord.evs.generator.data.AccountDatabase
import com.discord.evs.generator.data.Prefs
import com.discord.evs.generator.databinding.FragmentResultsBinding
import com.discord.evs.generator.databinding.ItemAccountBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

class ResultsFragment : Fragment() {
    private var _binding: FragmentResultsBinding? = null
    private val binding get() = _binding!!
    private val accountAdapter = AccountAdapter(
        onLongClick = { enterSelectionMode() },
        onCheckedChange = { updateSelectionCount() },
        onItemClick = { acc -> showAccountDetail(acc) },
        onSwipeDelete = { acc -> confirmSwipeDelete(acc) }
    )

    private var allAccounts: List<Account> = emptyList()
    private var displayedAccounts: List<Account> = emptyList()
    private var currentFilter = "ALL"
    private var currentSort = "DATE"
    private var searchQuery = ""
    private var searchDebounce: Job? = null

    private var pendingExportAccounts: List<Account> = emptyList()

    private val createFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? -> uri?.let { exportToUri(it) } }

    private lateinit var dao: AccountDao

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentResultsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiMotion.enter(binding.root)
        UiMotion.staggerChildren(binding.root as? ViewGroup ?: return)

        binding.accountRecycler.apply {
            adapter = accountAdapter
            layoutManager = LinearLayoutManager(context)
        }

        dao = AccountDatabase.getInstance(requireContext()).accountDao()
        setupSwipeAndRefresh()
        dao.getAllLive().observe(viewLifecycleOwner) { accounts ->
            allAccounts = accounts
            applyFiltersAndSort()
            updateStats(accounts)
        }

        setupSearch()
        setupFilters()
        setupSortButton()
        setupBatchActions()

        binding.exportFab.setOnClickListener {
            val prefs = Prefs(requireContext())
            val initialSelection = when (prefs.exportFilter) {
                "VALID" -> 1
                "VALID_PHONE" -> 2
                else -> 0
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Export — what to include?")
                .setSingleChoiceItems(
                    arrayOf("All accounts", "VALID only", "VALID + Phone verified"),
                    initialSelection
                ) { dialog, which ->
                    prefs.exportFilter = when (which) { 1 -> "VALID"; 2 -> "VALID_PHONE"; else -> "ALL" }
                    pendingExportAccounts = when (which) {
                        1 -> allAccounts.filter { it.status == "VALID" }
                        2 -> allAccounts.filter { it.status == "VALID" && it.phoneStatus == "VERIFIED" }
                        else -> allAccounts
                    }
                    dialog.dismiss()
                    val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    createFileLauncher.launch("accounts_$date.txt")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.clearAllBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Clear all accounts?")
                .setMessage("Are you sure you want to do this? This action cannot be undone — all saved accounts will be permanently deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear all") { _, _ ->
                    lifecycleScope.launch {
                        dao.deleteAll()
                        toast("All accounts cleared")
                    }
                }
                .show()
        }

        try {
            // Optional: long-press valid stat opens score help
            binding.statValid.setOnLongClickListener { showScoreHelp(); true }
            binding.statValid.setOnClickListener { showScoreHelp() }
        } catch (_: Exception) {}
        try {
            binding.root.findViewWithTag<android.view.View>("score_help")
        } catch (_: Exception) {}
        // Visible score help: long-press any status stat or the Valid count
        try {
            binding.statLocked.setOnLongClickListener { showScoreHelp(); true }
            binding.statInvalid.setOnLongClickListener { showScoreHelp(); true }
        } catch (_: Exception) {}
        binding.checkAllBtn.setOnClickListener {
            checkAccounts(allAccounts)
        }
    }

    private fun setupSearch() {
        binding.searchEdit.addTextChangedListener {
            searchDebounce?.cancel()
            searchDebounce = lifecycleScope.launch {
                delay(200)
                searchQuery = it.toString()
                applyFiltersAndSort()
            }
        }
    }

    private fun setupFilters() {
        val filterViews = listOf(
            binding.filterAll to "ALL",
            binding.filterValid to "VALID",
            binding.filterLocked to "LOCKED",
            binding.filterInvalid to "INVALID",
            binding.filterUnverified to "UNVERIFIED"
        )
        filterViews.forEach { (view, filter) ->
            view.setOnClickListener {
                currentFilter = filter
                filterViews.forEach { (v, f) ->
                    v.setBackgroundColor(if (f == filter) Color.parseColor("#5865F2") else Color.parseColor("#383a40"))
                    (v as? android.widget.TextView)?.setTextColor(
                        if (f == filter) Color.WHITE else Color.parseColor("#b5bac1")
                    )
                }
                applyFiltersAndSort()
            }
        }
    }

    private fun setupSortButton() {
        binding.sortBtn.setOnClickListener {
            currentSort = when (currentSort) {
                "DATE" -> "STATUS"
                "STATUS" -> "SCORE"
                "SCORE" -> "EMAIL"
                else -> "DATE"
            }
            binding.sortBtn.text = when (currentSort) {
                "SCORE" -> "↕ SCORE"
                else -> "↕ $currentSort"
            }
            applyFiltersAndSort()
        }
    }

    private fun setupBatchActions() {
        binding.batchSelectAll.setOnClickListener {
            val allSelected = accountAdapter.selectedIds.size == displayedAccounts.size
            if (allSelected) accountAdapter.clearSelection() else accountAdapter.selectAll(displayedAccounts.map { it.id })
            updateSelectionCount()
        }
        binding.batchCheck.setOnClickListener {
            checkAccounts(accountAdapter.selectedItems)
            exitSelectionMode()
        }
        binding.batchExport.setOnClickListener {
            pendingExportAccounts = accountAdapter.selectedItems
            if (pendingExportAccounts.isEmpty()) { toast("No accounts selected"); return@setOnClickListener }
            val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            createFileLauncher.launch("accounts_$date.txt")
            exitSelectionMode()
        }
        binding.batchTag.setOnClickListener {
            showTagDialog(accountAdapter.selectedIds.toList())
        }
        binding.batchDelete.setOnClickListener {
            val selected = accountAdapter.selectedIds.toList()
            if (selected.isEmpty()) { toast("No accounts selected"); return@setOnClickListener }
            AlertDialog.Builder(requireContext())
                .setTitle("Delete ${selected.size} accounts?")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        dao.deleteByIds(selected)
                        exitSelectionMode()
                        toast("Deleted ${selected.size} accounts")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.batchCancel.setOnClickListener { exitSelectionMode() }
    }

    private fun enterSelectionMode() {
        binding.batchActionBar.visibility = View.VISIBLE
        accountAdapter.selectionMode = true
        updateSelectionCount()
    }

    private fun exitSelectionMode() {
        binding.batchActionBar.visibility = View.GONE
        accountAdapter.selectionMode = false
        accountAdapter.clearSelection()
    }

    private fun updateSelectionCount() {
        val count = accountAdapter.selectedIds.size
        binding.batchSelectAll.text = if (count == displayedAccounts.size) "NONE" else "ALL"
        binding.batchCheck.text = "CHECK ($count)"
        binding.batchExport.text = "EXPORT ($count)"
        binding.batchTag.text = "TAG ($count)"
        binding.batchDelete.text = "DELETE ($count)"
    }

    private fun showTagDialog(ids: List<Long>) {
        if (ids.isEmpty()) { toast("No accounts selected"); return }
        val input = android.widget.EditText(requireContext()).apply {
            hint = "tag1, tag2"
            setTextColor(Color.parseColor("#f2f3f5"))
            setHintTextColor(Color.parseColor("#b5bac1"))
            setBackgroundColor(Color.parseColor("#383a40"))
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Tag ${ids.size} accounts")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val tag = input.text.toString().trim()
                lifecycleScope.launch {
                    ids.forEach { id -> dao.updateTags(id, tag) }
                    exitSelectionMode()
                    toast("Tagged ${ids.size} accounts")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAccounts(accounts: List<Account>) {
        if (accounts.isEmpty()) { toast("No accounts to check"); return }
        val checker = TokenChecker()
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Checking ${accounts.size} tokens…")
            .setView(android.widget.ProgressBar(requireContext()))
            .setCancelable(false)
            .create()
        dialog.show()
        lifecycleScope.launch {
            var checked = 0
            accounts.forEach { acc ->
                val result = withContext(Dispatchers.IO) {
                    val st = checker.checkToken(acc.token)
                    val (emailOk, _) = checker.isEmailVerified(acc.token)
                    Triple(st, emailOk, System.currentTimeMillis())
                }
                val (st, emailOk, ts) = result
                val emailVerified = emailOk == true
                val emailStatus = when (emailOk) {
                    true -> "VERIFIED"
                    false -> "NOT_VERIFIED"
                    null -> acc.emailStatus.ifBlank { "NOT_ASKED" }
                }
                val display = when {
                    st.name == "UNVERIFIED" -> "UNVERIFIED"
                    st.name == "VALID" && !emailVerified -> "UNVERIFIED"
                    st.name == "VALID" -> "VALID"
                    st.name == "ERROR" && !emailVerified -> "UNVERIFIED"
                    else -> st.name
                }
                var score = 0
                val reasons = mutableListOf<String>()
                when (display) {
                    "VALID" -> { score += 50; reasons.add("VALID") }
                    "LOCKED" -> { score += 20; reasons.add("LOCKED") }
                    "UNVERIFIED" -> { score += 30; reasons.add("UNVERIFIED") }
                    else -> { score += 5; reasons.add(display) }
                }
                if (emailVerified) { score += 20; reasons.add("email verified") }
                if (acc.phoneStatus == "VERIFIED") { score += 20; reasons.add("phone verified") }
                val timeline = Account.appendEvent(acc.timeline, "status", ts)
                dao.updateCheckResult(
                    id = acc.id,
                    status = display,
                    verified = st == TokenChecker.TokenStatus.VALID,
                    emailVerified = emailVerified,
                    emailStatus = emailStatus,
                    phoneStatus = acc.phoneStatus,
                    score = score.coerceIn(0, 100),
                    scoreReason = reasons.joinToString(", "),
                    timeline = timeline,
                    ts = ts
                )
                checked++
                dialog.setTitle("Checked $checked / ${accounts.size}…")
            }
            dialog.dismiss()
            toast("✓ Checked $checked tokens")
        }
    }

    private fun applyFiltersAndSort() {
        var filtered = allAccounts

        filtered = when (currentFilter) {
            "VALID" -> filtered.filter { it.status == "VALID" }
            "LOCKED" -> filtered.filter { it.status == "LOCKED" }
            "INVALID" -> filtered.filter { it.status == "INVALID" }
            "UNVERIFIED" -> filtered.filter { !it.verified }
            else -> filtered
        }

        if (searchQuery.isNotBlank()) {
            val q = searchQuery.lowercase()
            filtered = filtered.filter {
                it.email.lowercase().contains(q) ||
                it.token.lowercase().contains(q) ||
                it.notes.lowercase().contains(q) ||
                it.tags.lowercase().contains(q)
            }
        }

        filtered = when (currentSort) {
            "STATUS" -> filtered.sortedWith(compareBy { statusOrder(it.status) })
            "EMAIL" -> filtered.sortedBy { it.email }
            "SCORE" -> filtered.sortedByDescending { it.score }
            else -> filtered.sortedByDescending { it.createdAt }
        }

        displayedAccounts = filtered
        accountAdapter.submit(filtered)
        binding.emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        updateSelectionCount()
    }

    private fun statusOrder(status: String) = when (status) {
        "VALID" -> 0; "LOCKED" -> 1; "INVALID" -> 2; else -> 3
    }

    private fun updateStats(accounts: List<Account>) {
        binding.countText.text = "${accounts.size} total"
        binding.statValid.text = "✓ ${accounts.count { it.status == "VALID" }} valid"
        binding.statLocked.text = "⚠ ${accounts.count { it.status == "LOCKED" }} locked"
        binding.statInvalid.text = "✕ ${accounts.count { it.status == "INVALID" }} invalid"
    }

    private fun exportToUri(uri: Uri) {
        val accounts = pendingExportAccounts.ifEmpty { displayedAccounts }
        lifecycleScope.launch {
            try {
                requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                    BufferedWriter(OutputStreamWriter(stream)).use { writer ->
                        accounts.forEach { acc ->
                            writer.write(acc.toExportLine())
                            writer.newLine()
                        }
                    }
                }
                toast("Exported ${accounts.size} accounts")
            } catch (e: Exception) {
                toast("Export failed: ${e.message}")
            } finally {
                pendingExportAccounts = emptyList()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()


    private fun showAccountDetail(acc: Account) {
        val msg = buildString {
            appendLine("Email: ${acc.email}")
            appendLine("Pass: ${acc.password}")
            appendLine("Token: ${acc.token}")
            appendLine("Status: ${acc.status}")
            appendLine("Phone: ${acc.phone.ifBlank { "-" }} (${acc.phoneStatus})")
            appendLine("Email: ${acc.emailLine()} (${acc.emailStatus})")
            appendLine("Score: ${acc.score}/100 — ${acc.scoreReason}")
            appendLine("Tags: ${acc.tags}")
            appendLine("Notes: ${acc.notes}")
            appendLine("Created: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(acc.createdAt))}")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Account detail")
            .setMessage(msg)
            .setPositiveButton("Copy token") { _, _ -> UiHelpers.copy(requireContext(), "token", acc.token) }
            .setNeutralButton("Web Controller") { _, _ ->
                startActivity(Intent(requireContext(), TokenWebControllerActivity::class.java).putExtra(TokenWebControllerActivity.EXTRA_TOKEN, acc.token))
            }
            .setNeutralButton("Live logs") { _, _ -> showAccountTimeline(acc) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showAccountTimeline(acc: Account) {
        val lines = acc.timelineLines().joinToString("\n")
        AlertDialog.Builder(requireContext())
            .setTitle("Account timeline")
            .setMessage(lines.ifBlank { "No events recorded yet." })
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ -> UiHelpers.copy(requireContext(), "timeline", lines) }
            .show()
    }

    private fun showScoreHelp() {
        val msg = """
Score is 0–100. Higher = more valuable account.

Base (token status):
• VALID → +50
• UNVERIFIED (token works, email not confirmed) → +30
• LOCKED → +20
• INVALID / other → +5

Bonuses:
• Email verified → +20
• Phone verified → +20
• Fresh token (<1 day) → +5
• Recent token (<7 days) → +3
• Automatic captcha solve → +5

Examples:
• VALID + email + phone ≈ 90–100
• VALID + email only ≈ 70–75
• UNVERIFIED (email pending/failed) ≈ 30–50
• LOCKED ≈ 20–40

Phone status values:
• NOT_ASKED — Discord never requested phone
• ASKED — phone gate shown, not completed
• VERIFIED — SMS code accepted
• NOT_VERIFIED_BUG / FAILED — automation error or timeout

Email status values:
• NOT_ASKED — verify step skipped/disabled
• NOT_VERIFIED — asked but not confirmed
• VERIFIED — confirmed with Discord
• NOT_VERIFIED_BUG — check/poll failed
""".trimIndent()
        AlertDialog.Builder(requireContext())
            .setTitle("Learn about score")
            .setMessage(msg)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun confirmSwipeDelete(acc: Account) {
        val prefs = Prefs(requireContext())
        if (!prefs.uxSwipeDelete) return
        AlertDialog.Builder(requireContext())
            .setTitle("Delete account?")
            .setMessage(acc.email.ifBlank { acc.token.take(20) })
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    dao.deleteByIds(listOf(acc.id))
                    toast("Deleted")
                    // snackbar-style undo would need view ref; reload list via existing flow
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupSwipeAndRefresh() {
        val prefs = Prefs(requireContext())
        if (prefs.uxSwipeDelete) {
            val cb = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
                override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
                override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                    val pos = vh.bindingAdapterPosition
                    if (pos >= 0 && pos < displayedAccounts.size) {
                        confirmSwipeDelete(displayedAccounts[pos])
                        accountAdapter.notifyItemChanged(pos)
                    }
                }
            }
            ItemTouchHelper(cb).attachToRecyclerView(binding.accountRecycler)
        }
        // Pull refresh if SwipeRefreshLayout present in hierarchy
        try {
            val srlId = resources.getIdentifier("swipe_refresh", "id", requireContext().packageName)
            if (srlId != 0) {
                val srl = binding.root.findViewById<SwipeRefreshLayout>(srlId)
                srl?.setOnRefreshListener {
                    lifecycleScope.launch {
                        allAccounts = withContext(Dispatchers.IO) { dao.getAll() }
                        applyFiltersAndSort()
                        srl.isRefreshing = false
                    }
                }
            }
        } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class AccountAdapter(
    private val onLongClick: () -> Unit,
    private val onCheckedChange: () -> Unit,
    private val onItemClick: (Account) -> Unit = {},
    private val onSwipeDelete: (Account) -> Unit = {}
) : RecyclerView.Adapter<AccountAdapter.VH>() {
    private val items = mutableListOf<Account>()
    private val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.US)

    var selectionMode = false
        set(value) { field = value; notifyDataSetChanged() }
    val selectedIds = mutableSetOf<Long>()
    val selectedItems: List<Account> get() = items.filter { it.id in selectedIds }

    fun submit(list: List<Account>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    fun selectAll(ids: List<Long>) {
        selectedIds.clear(); selectedIds.addAll(ids); notifyDataSetChanged(); onCheckedChange()
    }

    fun clearSelection() {
        selectedIds.clear(); notifyDataSetChanged(); onCheckedChange()
    }

    inner class VH(val binding: ItemAccountBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val acc = items[position]
        holder.binding.apply {
            emailText.text = acc.email.ifEmpty { "(no email)" }
            tokenText.text = "${acc.token.take(28)}…"
            dateText.text = sdf.format(Date(acc.createdAt))
            scoreText.text = "Score ${acc.score}"
            scoreText.setOnClickListener {
                android.app.AlertDialog.Builder(it.context)
                    .setTitle("Learn about score")
                    .setMessage(
                        "Score is 0–100. Higher = more valuable.\n\n" +
                        "Base:\n• VALID +50\n• UNVERIFIED +30\n• LOCKED +20\n• INVALID +5\n\n" +
                        "Bonuses:\n• Email verified +20\n• Phone verified +20\n• Fresh token +5\n• Auto captcha +5\n\n" +
                        "This account: ${acc.score}/100 — ${acc.scoreReason}"
                    )
                    .setPositiveButton("Close", null)
                    .show()
            }
            scoreText.visibility = if (acc.score > 0) View.VISIBLE else View.GONE

            if (acc.phone.isNotBlank()) {
                phoneText.text = acc.phone
                phoneText.visibility = View.VISIBLE
            } else {
                phoneText.visibility = View.GONE
            }

            if (acc.notes.isNotBlank() || acc.tags.isNotBlank()) {
                notesText.text = buildString {
                    if (acc.tags.isNotBlank()) append("🏷 ${acc.tags}  ")
                    if (acc.notes.isNotBlank()) append("📝 ${acc.notes}")
                }
                notesText.visibility = View.VISIBLE
            } else {
                notesText.visibility = View.GONE
            }

            val statusColor = when (acc.status) {
                "VALID" -> "#57f287"
                "LOCKED" -> "#fee75c"
                "INVALID" -> "#ed4245"
                else -> "#b5bac1"
            }
            statusBadge.text = acc.status
            statusBadge.setTextColor(Color.parseColor(statusColor))
            statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(statusColor))
            verifiedBadge.visibility = if (acc.verified) View.VISIBLE else View.GONE

            selectCheckbox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            selectCheckbox.isChecked = acc.id in selectedIds
            selectCheckbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedIds.add(acc.id) else selectedIds.remove(acc.id)
                onCheckedChange()
            }

            root.setOnLongClickListener {
                if (!selectionMode) onLongClick()
                true
            }
            root.setOnClickListener {
                if (selectionMode) {
                    selectCheckbox.isChecked = !selectCheckbox.isChecked
                } else {
                    onItemClick(acc)
                }
            }
            // Status pill + copy-on-tap email
            try {
                val prefs = Prefs(root.context)
                if (prefs.uxStatusPills) {
                    statusBadge.setBackgroundColor(UiHelpers.statusColor(acc.status))
                    statusBadge.setTextColor(android.graphics.Color.BLACK)
                    statusBadge.setPadding(12, 4, 12, 4)
                }
                if (prefs.uxCopyOnTap) {
                    emailText.setOnClickListener { UiHelpers.copy(it.context, "email", acc.email) }
                    tokenText.setOnClickListener { UiHelpers.copy(it.context, "token", acc.token) }
                }
            } catch (_: Exception) {}

            copyBtn.setOnClickListener {
                val cm = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("token", acc.token))
                Toast.makeText(it.context, "Token copied", Toast.LENGTH_SHORT).show()
            }

            copyComboBtn.setOnClickListener {
                val cm = it.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("combo", acc.toExportLine()))
                Toast.makeText(it.context, "Combo copied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
