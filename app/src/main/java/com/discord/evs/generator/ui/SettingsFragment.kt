package com.discord.evs.generator.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.addTextChangedListener
import android.graphics.Color
import android.view.animation.DecelerateInterpolator
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import com.google.android.material.snackbar.Snackbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.discord.evs.generator.R
import com.discord.evs.generator.data.Prefs
import com.discord.evs.generator.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private val importMailsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val raw = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@registerForActivityResult
            val prefs = Prefs(requireContext())
            val api = com.discord.evs.generator.automation.CustomMailApi(requireContext())
            val added = api.importEmails(raw)
            val edit = view?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_custom_mails)
            edit?.setText(prefs.customMails)
            val tv = view?.findViewById<android.widget.TextView>(R.id.tv_custom_mail_count)
            val n = com.discord.evs.generator.automation.CustomMailApi.parseEmails(prefs.customMails).size
            tv?.text = "$n emails in pool"
            android.widget.Toast.makeText(requireContext(), "Imported $added new emails (pool=$n)", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(requireContext(), "Import failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs
    private var passwordVisible = false
    private val saveFeedbackHandler = Handler(Looper.getMainLooper())
    private var saveFeedbackReset: Runnable? = null

    private val avatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { /* URI doesn't support persist — ok */ }
        prefs.avatarUri = uri.toString()
        toast("✓ Avatar image selected")
        updateAvatarPreview()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // filled below; validate button hooked at end of setup

        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        UiMotion.enter(binding.root)
        UiMotion.staggerChildren(binding.root as? ViewGroup ?: return)
        loadSettings()
        setupListeners()
        // Re-bind Auth Verifier expand AFTER setupListeners so nothing steals the switch listener
        try { setupAuthVerifierLinksUi() } catch (_: Exception) {}
        refreshCustomPrefixSections()
        updateMultiGenVisibility()
        refreshConditionalSections()
        setupPresetsAndSearch()
        setupSettingsSearchUi()
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private fun loadSettings() {
        try {
            binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_vaultcord_enabled)?.isChecked = prefs.vaultcordEnabled
            binding.root.findViewById<android.widget.EditText>(R.id.edit_vaultcord_auth_url)?.setText(prefs.vaultcordAuthUrl)
            setupAuthVerifierLinksUi()
        } catch (_: Exception) {}
        try {
            val ext = binding.root.findViewById<android.widget.RadioButton>(R.id.rb_nopecha_extension)
            val api = binding.root.findViewById<android.widget.RadioButton>(R.id.rb_nopecha_api)
            if (prefs.nopechaMode.equals("extension", true)) ext?.isChecked = true else api?.isChecked = true
            binding.root.findViewById<android.widget.RadioGroup>(R.id.rg_nopecha_mode)?.setOnCheckedChangeListener { _, id ->
                prefs.nopechaMode = if (id == R.id.rb_nopecha_extension) "extension" else "api"
                if (id == R.id.rb_nopecha_extension) {
                    prefs.autoCaptchaEnabled = true
                    prefs.captchaSolverProvider = "nopecha"
                    try { binding.switchAutoCaptcha.isChecked = true } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        binding.apply {
            // ── Generation ────────────────────────────────────────────────────
            editTargetCount.setText(prefs.targetCount.toString())
            try {
                val sw = root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_multi_gen)
                val editN = root.findViewById<android.widget.EditText>(R.id.edit_concurrent_generations)
                val section = root.findViewById<android.view.View>(R.id.section_multi_gen_count)
                val multiOn = prefs.concurrentGenerations > 1
                sw?.isChecked = multiOn
                editN?.setText(prefs.concurrentGenerations.coerceIn(1, 5).toString())
                section?.visibility = if (multiOn) android.view.View.VISIBLE else android.view.View.GONE
                sw?.setOnCheckedChangeListener { _, checked ->
                    if (!checked) {
                        prefs.concurrentGenerations = 1
                        section?.visibility = android.view.View.GONE
                    } else {
                        section?.visibility = android.view.View.VISIBLE
                        var n = editN?.text?.toString()?.toIntOrNull() ?: 2
                        if (n < 1) n = 1
                        if (n > 5) n = 5
                        prefs.concurrentGenerations = n
                        editN?.setText(n.toString())
                    }
                }
                editN?.addTextChangedListener {
                    if (sw?.isChecked == true) {
                        var n = it?.toString()?.toIntOrNull() ?: 1
                        if (n < 1) n = 1
                        if (n > 5) n = 5
                        prefs.concurrentGenerations = n
                    }
                }
            } catch (_: Exception) {}
            editCooldown.setText(prefs.cooldownSeconds.toString())
            editRetryCount.setText(prefs.genRetryCount.toString())
            editGenTimeout.setText(prefs.genTimeoutSeconds.toString())
            editDobMin.setText(prefs.dobMinAge.toString())
            editDobMax.setText(prefs.dobMaxAge.toString())

            switchCustomPassword.isChecked = prefs.customPasswordEnabled
            editCustomPassword.hint = "User"
            // Migrate stale stored branding into the field
            val cp = prefs.customPassword.trim()
            if (cp.isNotBlank() && !cp.equals("User", true)) {
                editCustomPassword.setText(cp)
            } else {
                editCustomPassword.setText("")
                if (cp.equals("User", true)) {
                    prefs.customPassword = ""
                }
            }
            editCustomPassword.isEnabled = true
            switchRandomEmailPrefix.isChecked = prefs.randomEmailPrefixEnabled
            try {
                editEmailPrefix.hint = "User"
                val ep = prefs.emailPrefix.trim()
                if (ep.isNotBlank() && !ep.equals("User", true)) {
                    editEmailPrefix.setText(ep)
                } else {
                    editEmailPrefix.setText("")
                    if (ep.equals("User", true)) {
                        prefs.emailPrefix = ""
                    }
                }
            } catch (_: Exception) {}
            try {
                editUsernamePrefix.hint = "User"
                val up = prefs.usernamePrefix.trim()
                if (up.isNotBlank() && !up.equals("User", true)) {
                    editUsernamePrefix.setText(up)
                } else {
                    editUsernamePrefix.setText("")
                    if (up.equals("User", true)) {
                        prefs.usernamePrefix = ""
                    }
                }
            } catch (_: Exception) {}
            try {
                editCustomDisplayName.hint = "User"
                val dn = prefs.customDisplayName.trim()
                if (dn.isNotBlank() && !dn.equals("User", true)) {
                    editCustomDisplayName.setText(dn)
                } else {
                    editCustomDisplayName.setText("")
                    if (dn.equals("User", true)) {
                        prefs.customDisplayName = ""
                    }
                }
            } catch (_: Exception) {}
            switchVerifyEmail.isChecked = prefs.verifyEmailEnabled
            switchRandomUsername.isChecked = prefs.randomUsernameEnabled
            switchRandomDisplayName.isChecked = prefs.randomDisplayNameEnabled

            // Per-field source radios
            try {
                root.findViewById<android.widget.RadioButton>(R.id.rb_email_precoded)?.isChecked = prefs.emailPrefixSourceMode != "custom"
                root.findViewById<android.widget.RadioButton>(R.id.rb_email_custom_prefix)?.isChecked = prefs.emailPrefixSourceMode == "custom"
                root.findViewById<android.widget.RadioGroup>(R.id.rg_email_source)?.setOnCheckedChangeListener { _, id ->
                    prefs.emailPrefixSourceMode = if (id == R.id.rb_email_custom_prefix) "custom" else "precoded"
                    refreshCustomPrefixSections()
                }
                root.findViewById<android.widget.RadioButton>(R.id.rb_password_precoded)?.isChecked = prefs.passwordSourceMode != "custom"
                root.findViewById<android.widget.RadioButton>(R.id.rb_password_custom_prefix)?.isChecked = prefs.passwordSourceMode == "custom"
                root.findViewById<android.widget.RadioGroup>(R.id.rg_password_source)?.setOnCheckedChangeListener { _, id ->
                    prefs.passwordSourceMode = if (id == R.id.rb_password_custom_prefix) "custom" else "precoded"
                    refreshCustomPrefixSections()
                }
                root.findViewById<android.widget.RadioButton>(R.id.rb_username_precoded)?.isChecked = prefs.usernameSourceMode != "custom"
                root.findViewById<android.widget.RadioButton>(R.id.rb_username_custom_prefix)?.isChecked = prefs.usernameSourceMode == "custom"
                root.findViewById<android.widget.RadioGroup>(R.id.rg_username_source)?.setOnCheckedChangeListener { _, id ->
                    prefs.usernameSourceMode = if (id == R.id.rb_username_custom_prefix) "custom" else "precoded"
                    refreshCustomPrefixSections()
                }
                root.findViewById<android.widget.RadioButton>(R.id.rb_display_precoded)?.isChecked = prefs.displayNameSourceMode != "custom"
                root.findViewById<android.widget.RadioButton>(R.id.rb_display_custom_prefix)?.isChecked = prefs.displayNameSourceMode == "custom"
                root.findViewById<android.widget.RadioGroup>(R.id.rg_display_source)?.setOnCheckedChangeListener { _, id ->
                    prefs.displayNameSourceMode = if (id == R.id.rb_display_custom_prefix) "custom" else "precoded"
                    refreshCustomPrefixSections()
                }
            } catch (_: Exception) {}
            refreshCustomPrefixSections()

            switchSaveOnlyValid.isChecked = prefs.saveOnlyValid
            switchShowWebview.isChecked = prefs.showWebViewEnabled
            switchHumanDelay.isChecked = prefs.humanDelayEnabled
switchDarkConsole.isChecked = prefs.darkConsole

            // ── Captcha ───────────────────────────────────────────────────────
            switchAutoCaptcha.isChecked = prefs.autoCaptchaEnabled
            switchAutoCaptcha.isEnabled = true
            switchAdvancedInAppSolver.isChecked = false
            switchAdvancedInAppSolver.isEnabled = false
            editNonecapKey.setText(prefs.nonecapApiKey)
            editNopechaKey.setText(prefs.nopechaApiKey)
            editCaptcha2Key.setText(prefs.captcha2Key)
            editCapmonsterKey.setText(prefs.capmonsterKey)
            editCapsolverKey.setText(prefs.capsolverKey)
            editAzcaptchaKey.setText(prefs.azcaptchaKey)
            editCaptchaTimeout.setText(prefs.captchaTimeoutSeconds.toString())
            updateNopechaHint()
            updatePasswordToggleIcon()

            // ── Email ─────────────────────────────────────────────────────────
            val builtInKey = "tk_42616ecc49e5497adff3d568f5b9d03f7723db073dd1901f14ef7b65ddc13298"
            val rawKey = prefs.cyberTempApiKey
            val hasOwnKey = rawKey.isNotBlank() && rawKey != builtInKey && !rawKey.startsWith("tk_●") && !rawKey.contains("enter", true)
            editCyberTempKey.isEnabled = hasOwnKey
            try {
                if (hasOwnKey) {
                    editCyberTempKey.setText(prefs.cyberTempApiKey)
                    tvCyberTempHint.text = "✓ Using your own CyberTemp API key. Tap button to restore built-in."
                    btnAddOwnApi.text = "↩ Use built-in key"
                } else {
                    editCyberTempKey.setText("tk_●●●●●●●●●●●●●●●●●●●●●●●●●●●●")
                    tvCyberTempHint.text = "🔒 Using built-in CyberTemp key (default). Tap 'Add own API' only if you have your own tk_ key."
                    btnAddOwnApi.text = "🔑 Add own API"
                    if (prefs.cyberTempApiKey.isBlank() || prefs.cyberTempApiKey.startsWith("tk_●")) {
                        prefs.cyberTempApiKey = builtInKey
                    }
                }
            } catch (_: Exception) {}
            editVerifyTimeout.setText(prefs.verifyTimeoutSeconds.toString())
            editDuckmailKey.setText(prefs.duckMailApiKey)
            try { editMailcowUrl.setText(prefs.mailcowBaseUrl) } catch (_: Exception) {}
            try { editMailcowKey.setText(prefs.mailcowApiKey) } catch (_: Exception) {}
            try { editMailcowDomain.setText(prefs.mailcowDomain) } catch (_: Exception) {}
            when (prefs.emailProvider) {
                "cybertemp", "mailgw", "tempmail" -> rgEmailProvider.check(R.id.rb_email_cybertemp)
                "duckmail", "duck" -> rgEmailProvider.check(R.id.rb_email_duckmail)
                "1secmail", "1sec" -> rgEmailProvider.check(R.id.rb_email_1secmail)
                "guerrilla", "guerrillamail" -> rgEmailProvider.check(R.id.rb_email_guerrilla)
                "tempmail.lol", "tempmaillol", "lol" -> rgEmailProvider.check(R.id.rb_email_tempmaillol)
                "mailcow" -> rgEmailProvider.check(R.id.rb_email_mailcow)
                "rotate" -> rgEmailProvider.check(R.id.rb_email_rotate)
                "yopmail", "yo" -> try { rgEmailProvider.check(R.id.rb_email_yopmail) } catch (_: Exception) { rgEmailProvider.check(R.id.rb_email_mailtm) }
                "crowmail", "crow" -> try { rgEmailProvider.check(R.id.rb_email_crowmail) } catch (_: Exception) { rgEmailProvider.check(R.id.rb_email_1secmail) }
                "hotmail007", "hotmail", "hm007" -> try { rgEmailProvider.check(R.id.rb_email_crowmail) } catch (_: Exception) { rgEmailProvider.check(R.id.rb_email_duckmail) }
                "draxono", "draxon" -> try { rgEmailProvider.check(R.id.rb_email_mailcow) } catch (_: Exception) { rgEmailProvider.check(R.id.rb_email_duckmail) }
                "dropmail" -> try { rgEmailProvider.check(R.id.rb_email_dropmail) } catch (_: Exception) { rgEmailProvider.check(R.id.rb_email_mailtm) }
                "maildrop" -> try { rgEmailProvider.check(R.id.rb_email_maildrop) } catch (_: Exception) { rgEmailProvider.check(R.id.rb_email_mailtm) }
                "tempmail_org", "tempmail.org" -> try { rgEmailProvider.check(R.id.rb_email_tempmail_org) } catch (_: Exception) { rgEmailProvider.check(R.id.rb_email_mailtm) }
                "mail.tm", "mailtm", "mail_tm" -> rgEmailProvider.check(R.id.rb_email_mailtm)
                "custom", "custommail", "own" -> try { rgEmailProvider.check(R.id.rb_email_custom) } catch (_: Exception) { rgEmailProvider.check(R.id.rb_email_cybertemp) }
                else -> rgEmailProvider.check(R.id.rb_email_duckmail) // duckmail public primary
            }

            // ── Proxy ─────────────────────────────────────────────────────────
            switchProxy.isChecked = prefs.proxyEnabled
            editProxyAddress.setText(prefs.proxyAddress)
            editProxyList.setText(prefs.proxyList)
            switchProxyHealthCheck.isChecked = prefs.proxyHealthCheckEnabled
            when (prefs.proxyRotationMode) {
                "none"   -> rgProxyRotation.check(R.id.rb_proxy_none)
                "random" -> rgProxyRotation.check(R.id.rb_proxy_random)
                else     -> rgProxyRotation.check(R.id.rb_proxy_sequential)
            }

            // ── Phone / SMS ───────────────────────────────────────────────────
            switchVerifyPhone.isChecked = prefs.phoneVerificationEnabled
            editFiveSimKey.setText(prefs.fiveSimKey)
            editSmsActivateKey.setText(prefs.smsActivateKey)
            editSmsPoolKey.setText(prefs.smsPoolKey)
            editPhoneCountry.setText(prefs.phoneCountry)
            editSmsTimeout.setText(prefs.smsTimeoutSeconds.toString())
            when (prefs.smsProvider) {
                "5sim"       -> rgSmsProvider.check(R.id.rb_sms_5sim)
                "smsactivate"-> rgSmsProvider.check(R.id.rb_sms_smsactivate)
                "smspool"    -> rgSmsProvider.check(R.id.rb_sms_smspool)
                else         -> rgSmsProvider.check(R.id.rb_sms_free)
            }
            updateSmsPaidVisibility(prefs.smsProvider != "free")

            // ── DC Webhook ────────────────────────────────────────────────────
            switchDcWebhook.isChecked = prefs.dcWebhookEnabled
            dcWebhookSettings.visibility = if (prefs.dcWebhookEnabled) View.VISIBLE else View.GONE
            editDcWebhookUrl.setText(prefs.dcWebhookUrl)
            editDcWebhookUrl.hint = ""
            editDcWebhookName.setText(prefs.dcWebhookName)
            editDcWebhookName.hint = "Bot display name (optional)"
            editDcWebhookPfp.setText(prefs.dcWebhookPfp)
            editDcWebhookPfp.hint = "Avatar image URL (optional)"
            when (prefs.dcWebhookMode) {
                1    -> rgDcMode.check(R.id.rb_dc_mode_embed)
                2    -> rgDcMode.check(R.id.rb_dc_mode_file)
                else -> rgDcMode.check(R.id.rb_dc_mode_message)
            }
            editDcBulkCount.setText(prefs.dcWebhookBulkCount.toString())
            dcBulkRow.visibility = if (prefs.dcWebhookMode == 2) View.VISIBLE else View.GONE
            switchDcDelete.isChecked = prefs.dcWebhookDeleteAfterSend

            // ── Telegram ──────────────────────────────────────────────────────
            switchTgNotif.isChecked = prefs.tgEnabled
            tgSettings.visibility = if (prefs.tgEnabled) View.VISIBLE else View.GONE
            editTgBotToken.setText(prefs.tgBotToken)
            editTgBotToken.hint = "Telegram bot token from @BotFather"
            editTgChannelId.setText(prefs.tgChannelId)
            editTgChannelId.hint = "Channel / chat id (e.g. -100...)"
            when (prefs.tgMode) {
                1    -> rgTgMode.check(R.id.rb_tg_mode_file)
                else -> rgTgMode.check(R.id.rb_tg_mode_message)
            }
            editTgBulkCount.setText(prefs.tgBulkCount.toString())
            tgBulkRow.visibility = if (prefs.tgMode == 1) View.VISIBLE else View.GONE
            switchTgDelete.isChecked = prefs.tgDeleteAfterSend

            // ── Fingerprinting ────────────────────────────────────────────────
            switchUaRotation.isChecked = prefs.userAgentRotationEnabled
            editCustomUa.setText(prefs.customUserAgent)
            uaFieldContainer.visibility = if (prefs.userAgentRotationEnabled) View.VISIBLE else View.GONE
            switchSpoofXsp.isChecked = prefs.spoofXSuperProperties
            switchSpoofLocale.isChecked = prefs.spoofLocale
            switchDisableWebrtc.isChecked = prefs.webRtcDisabled

            // ── App / Security ────────────────────────────────────────────────
            editAccentColor.setText(prefs.accentColor)
            editLogMaxLines.setText(prefs.logMaxLines.toString())
            switchPinLock.isChecked = prefs.pinLockEnabled
            editPinCode.setText(prefs.pinCode)
            pinCodeRow.visibility = if (prefs.pinLockEnabled) View.VISIBLE else View.GONE
            switchScreenshotPrevention.isChecked = prefs.screenshotPrevention
            switchAutoBackup.isChecked = prefs.autoBackupEnabled

            // ── New prefs ─────────────────────────────────────────────────────
            switchWebhookOnlyValid.isChecked = prefs.webhookOnlyValid
            switchAutoExportOnComplete.isChecked = prefs.autoExportOnComplete
            when (prefs.exportFilter) {
                "VALID"       -> rbExportValid.isChecked = true
                "VALID_PHONE" -> rbExportValidPhone.isChecked = true
                else          -> rbExportAll.isChecked = true
            }
            switchAvatarEnabled.isChecked = prefs.avatarEnabled
            try {
                root.findViewById<android.view.View>(R.id.section_avatar_options)?.visibility =
                    if (prefs.avatarEnabled) android.view.View.VISIBLE else android.view.View.GONE
            } catch (_: Exception) {}
            try {
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_humanizer)?.isChecked = prefs.humanizerEnabled
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_hype_badge)?.isChecked = prefs.hypeBadgeEnabled
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_trial_trigger)?.isChecked = prefs.trialTriggerEnabled
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_background_generation)?.isChecked = prefs.backgroundGeneration
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_notify_human)?.isChecked = prefs.notifyHumanRequired
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_notify_updates)?.isChecked = prefs.notifyUpdates
                refreshCustomPrefixSections()
            } catch (_: Exception) {}
            when (prefs.avatarFit) {
                "contain" -> rbAvatarContain.isChecked = true
                "fill"    -> rbAvatarFill.isChecked = true
                else      -> rbAvatarCover.isChecked = true
            }
            switchAutoDeleteInvalid.isChecked = prefs.autoDeleteInvalid
            updateAvatarPreview()
        }
    }

    private fun updateAvatarPreview() {
        binding.apply {
            val uri = prefs.avatarUri
            if (uri.isBlank()) {
                avatarPreviewHint.text = "No image selected"
                return
            }
            try {
                val bmp = android.graphics.BitmapFactory.decodeStream(
                    requireContext().contentResolver.openInputStream(Uri.parse(uri))
                )
                if (bmp != null) {
                    avatarPreviewHint.text = "Selected: ${bmp.width}×${bmp.height}px" +
                        if (bmp.width != bmp.height) " ⚠ not square (will be cropped)" else " ✓"
                } else {
                    avatarPreviewHint.text = "⚠ Could not load image preview"
                }
            } catch (_: Exception) {
                avatarPreviewHint.text = "Image selected (tap Pick to change)"
            }
        }
        // Always re-apply gated section visibility after loading switch values
        try {
            refreshCustomPrefixSections()
            updateMultiGenVisibility()
            refreshConditionalSections()
        } catch (_: Exception) {}
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.apply {

            // ── Generation ────────────────────────────────────────────────────
            editTargetCount.addTextChangedListener { prefs.targetCount = it.toString().toIntOrNull() ?: 20 }
            editCooldown.addTextChangedListener { prefs.cooldownSeconds = it.toString().toIntOrNull()?.coerceAtLeast(1) ?: 8 }
            editRetryCount.addTextChangedListener { prefs.genRetryCount = it.toString().toIntOrNull() ?: 3 }
            editGenTimeout.addTextChangedListener { prefs.genTimeoutSeconds = it.toString().toIntOrNull() ?: 120 }
            editDobMin.addTextChangedListener { prefs.dobMinAge = it.toString().toIntOrNull() ?: 18 }
            editDobMax.addTextChangedListener { prefs.dobMaxAge = it.toString().toIntOrNull() ?: 30 }

            switchCustomPassword.setOnCheckedChangeListener { _, checked ->
                prefs.customPasswordEnabled = checked
                refreshCustomPrefixSections()
                try { refreshConditionalSections() } catch (_: Exception) {}
                editCustomPassword.isEnabled = checked
            }
            editCustomPassword.addTextChangedListener { prefs.customPassword = it.toString() }
            switchRandomEmailPrefix.setOnCheckedChangeListener { _, v ->
                prefs.randomEmailPrefixEnabled = v
                try { refreshConditionalSections() } catch (_: Exception) {}
            }
            try { editEmailPrefix.addTextChangedListener { prefs.emailPrefix = it?.toString()?.trim() ?: "" } } catch (_: Exception) {}
            try { editUsernamePrefix.addTextChangedListener { prefs.usernamePrefix = it?.toString()?.trim() ?: "" } } catch (_: Exception) {}
            try { editCustomDisplayName.addTextChangedListener { prefs.customDisplayName = it?.toString()?.trim() ?: "" } } catch (_: Exception) {}

            switchVerifyEmail.setOnCheckedChangeListener { _, v -> prefs.verifyEmailEnabled = v }
            try {
                // Auth Verifier switch is owned by setupAuthVerifierLinksUi (expand body).
                // Do not replace its listener here — only bind the URL field.
                binding.root.findViewById<android.widget.EditText>(R.id.edit_vaultcord_auth_url)
                    ?.addTextChangedListener { prefs.vaultcordAuthUrl = it?.toString()?.trim().orEmpty() }
            } catch (_: Exception) {}


            // v4 feature toggles
            try {
                fun bindSw(id: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
                    val sw = binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(id) ?: return
                    sw.isChecked = get()
                    sw.setOnCheckedChangeListener { _, v -> set(v) }
                }
                bindSw(R.id.switch_ux_swipe, { prefs.uxSwipeDelete }, { prefs.uxSwipeDelete = it })
                bindSw(R.id.switch_ux_copy_tap, { prefs.uxCopyOnTap }, { prefs.uxCopyOnTap = it })
                bindSw(R.id.switch_ux_pills, { prefs.uxStatusPills }, { prefs.uxStatusPills = it })
                bindSw(R.id.switch_ux_haptics, { prefs.uxHaptics }, { prefs.uxHaptics = it })
                bindSw(R.id.switch_ux_confetti, { prefs.uxConfetti }, { prefs.uxConfetti = it })
                bindSw(R.id.switch_gen_adaptive, { prefs.genAdaptiveDelay }, { prefs.genAdaptiveDelay = it })
                bindSw(R.id.switch_gen_backoff, { prefs.genBackoff429 }, { prefs.genBackoff429 = it })
                bindSw(R.id.switch_gen_sticky_fp, { prefs.genStickyFingerprint }, { prefs.genStickyFingerprint = it })
                bindSw(R.id.switch_gen_cf, { prefs.genDetectCloudflare }, { prefs.genDetectCloudflare = it })
                bindSw(R.id.switch_gen_prewarm, { prefs.genPrewarmEmail }, { prefs.genPrewarmEmail = it })
                bindSw(R.id.switch_gen_soft_cap, { prefs.genCaptchaSoftTimeout }, { prefs.genCaptchaSoftTimeout = it })
                bindSw(R.id.switch_gen_sitekey, { prefs.genSitekeyCache }, { prefs.genSitekeyCache = it })
                bindSw(R.id.switch_gen_desktop_ua, { prefs.genDesktopUaMode }, { prefs.genDesktopUaMode = it })
                bindSw(R.id.switch_joiner_rules, { prefs.joinerRulesAccept }, { prefs.joinerRulesAccept = it })
                bindSw(R.id.switch_joiner_captcha, { prefs.joinerCaptchaSolve }, { prefs.joinerCaptchaSolve = it })
                bindSw(R.id.switch_mail_spam, { prefs.mailSearchSpam }, { prefs.mailSearchSpam = it })
                bindSw(R.id.switch_sms_ban, { prefs.smsBanRejectedNumbers }, { prefs.smsBanRejectedNumbers = it })
                bindSw(R.id.switch_sec_root, { prefs.securityRootWarn }, { prefs.securityRootWarn = it })
                bindSw(R.id.switch_sec_emu, { prefs.securityEmulatorWarn }, { prefs.securityEmulatorWarn = it })
                bindSw(R.id.switch_sec_panic, { prefs.securityPanicWipe }, { prefs.securityPanicWipe = it })
            } catch (_: Exception) {}
            switchRandomUsername.setOnCheckedChangeListener { _, v ->
                prefs.randomUsernameEnabled = v
                refreshCustomPrefixSections()
                refreshCustomPrefixSections()
                try { refreshConditionalSections() } catch (_: Exception) {}
            }
            switchRandomDisplayName.setOnCheckedChangeListener { _, v ->
                prefs.randomDisplayNameEnabled = v
                refreshCustomPrefixSections()
                refreshCustomPrefixSections()
                try { refreshConditionalSections() } catch (_: Exception) {}
            }
            switchSaveOnlyValid.setOnCheckedChangeListener { _, v -> prefs.saveOnlyValid = v }
            switchShowWebview.setOnCheckedChangeListener { _, v -> prefs.showWebViewEnabled = v }
            switchHumanDelay.setOnCheckedChangeListener { _, v -> prefs.humanDelayEnabled = v }
            switchDarkConsole.setOnCheckedChangeListener { _, v -> prefs.darkConsole = v }

            // ── Captcha ───────────────────────────────────────────────────────
            switchAutoCaptcha.setOnCheckedChangeListener { _, checked ->
                prefs.autoCaptchaEnabled = checked
                refreshConditionalSections()
                updateNopechaHint()
                toast(if (checked) "AutoCaptcha ON — enter NoneCap or NopeCHA key below" else "AutoCaptcha disabled")
            
                try { refreshConditionalSections() } catch (_: Exception) {}
            }


            // Solve mode: API tokens vs Gesture / AI Vision
            try {
                when (prefs.captchaSolveMode.lowercase()) {
                    "gesture" -> rgCaptchaSolveMode.check(R.id.rb_mode_gesture)
                    else -> rgCaptchaSolveMode.check(R.id.rb_mode_api)
                }
                rgCaptchaSolveMode.setOnCheckedChangeListener { _, id ->
                    prefs.captchaSolveMode = if (id == R.id.rb_mode_gesture) "gesture" else "api"
                    try { refreshConditionalSections() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            try {
                when (prefs.gestureSolverProvider.lowercase()) {
                    "builtin_scan", "scan" -> rgGestureSolver.check(R.id.rb_gesture_scan)
                    "vision_http", "vision" -> rgGestureSolver.check(R.id.rb_gesture_vision)
                    "ai_vision", "ai", "openai" -> rgGestureSolver.check(R.id.rb_gesture_ai)
                    else -> rgGestureSolver.check(R.id.rb_gesture_geo)
                }
                rgGestureSolver.setOnCheckedChangeListener { _, id ->
                    prefs.gestureSolverProvider = when (id) {
                        R.id.rb_gesture_scan -> "builtin_scan"
                        R.id.rb_gesture_vision -> "vision_http"
                        R.id.rb_gesture_ai -> "ai_vision"
                        else -> "builtin_geo"
                    }
                    try { refreshConditionalSections() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            try {
                editGestureVisionUrl.setText(prefs.gestureVisionApiUrl)
                editGestureVisionUrl.addTextChangedListener { prefs.gestureVisionApiUrl = it?.toString()?.trim() ?: "" }
            } catch (_: Exception) {}
            try {
                editAiVisionKey.setText(prefs.aiVisionApiKey)
                editAiVisionKey.addTextChangedListener { prefs.aiVisionApiKey = it?.toString()?.trim() ?: "" }
                editAiVisionBase.setText(prefs.aiVisionBaseUrl)
                editAiVisionBase.addTextChangedListener { prefs.aiVisionBaseUrl = it?.toString()?.trim() ?: "" }
                editAiVisionModel.setText(prefs.aiVisionModel)
                editAiVisionModel.addTextChangedListener { prefs.aiVisionModel = it?.toString()?.trim() ?: "" }

                fun applyAiProviderDefaults(id: String, forceDefaults: Boolean) {
                    prefs.aiVisionProvider = id
                    val (base, model) = when (id) {
                        "gemini" -> "https://generativelanguage.googleapis.com/v1beta" to "gemini-2.0-flash"
                        "openrouter" -> "https://openrouter.ai/api/v1" to "google/gemini-2.0-flash-exp:free"
                        "groq" -> "https://api.groq.com/openai/v1" to "meta-llama/llama-4-scout-17b-16e-instruct"
                        "openai", "chatgpt" -> "https://api.openai.com/v1" to "gpt-4o-mini"
                        "claude" -> "https://api.anthropic.com" to "claude-3-5-haiku-latest"
                        else -> prefs.aiVisionBaseUrl to prefs.aiVisionModel
                    }
                    if (forceDefaults || id != "custom") {
                        if (id != "custom") {
                            prefs.aiVisionBaseUrl = base
                            prefs.aiVisionModel = model
                            try { editAiVisionBase.setText(base) } catch (_: Exception) {}
                            try { editAiVisionModel.setText(model) } catch (_: Exception) {}
                        }
                    }
                }

                when (prefs.aiVisionProvider.lowercase()) {
                    "openrouter" -> rgAiVisionProvider.check(R.id.rb_ai_openrouter)
                    "groq" -> rgAiVisionProvider.check(R.id.rb_ai_groq)
                    "openai", "chatgpt", "gpt" -> rgAiVisionProvider.check(R.id.rb_ai_openai)
                    "claude", "anthropic" -> rgAiVisionProvider.check(R.id.rb_ai_claude)
                    "custom" -> rgAiVisionProvider.check(R.id.rb_ai_custom)
                    else -> rgAiVisionProvider.check(R.id.rb_ai_gemini)
                }
                rgAiVisionProvider.setOnCheckedChangeListener { _, rid ->
                    val id = when (rid) {
                        R.id.rb_ai_openrouter -> "openrouter"
                        R.id.rb_ai_groq -> "groq"
                        R.id.rb_ai_openai -> "openai"
                        R.id.rb_ai_claude -> "claude"
                        R.id.rb_ai_custom -> "custom"
                        else -> "gemini"
                    }
                    applyAiProviderDefaults(id, forceDefaults = true)
                }
            } catch (_: Exception) {}

            try {
                when (prefs.captchaSolverProvider.lowercase()) {
                    "nopecha" -> rgCaptchaProvider.check(R.id.rb_solver_nopecha)
                    "2captcha", "2cap" -> rgCaptchaProvider.check(R.id.rb_solver_2captcha)
                    "capmonster" -> rgCaptchaProvider.check(R.id.rb_solver_capmonster)
                    "capsolver" -> rgCaptchaProvider.check(R.id.rb_solver_capsolver)
                    "az", "azcaptcha" -> rgCaptchaProvider.check(R.id.rb_solver_az)
                    else -> rgCaptchaProvider.check(R.id.rb_solver_nonecap)
                }
                rgCaptchaProvider.setOnCheckedChangeListener { _, id ->
                    prefs.captchaSolverProvider = when (id) {
                        R.id.rb_solver_nopecha -> "nopecha"
                        R.id.rb_solver_2captcha -> "2captcha"
                        R.id.rb_solver_capmonster -> "capmonster"
                        R.id.rb_solver_capsolver -> "capsolver"
                        R.id.rb_solver_az -> "az"
                        else -> "nonecap"
                    }
                    refreshConditionalSections()
                }
            } catch (_: Exception) {}
            refreshConditionalSections()

            switchAdvancedInAppSolver.setOnCheckedChangeListener { _, _ -> switchAdvancedInAppSolver.isChecked = false }
            editNonecapKey.addTextChangedListener { prefs.nonecapApiKey = it.toString().trim(); updateNopechaHint() }
            editNopechaKey.addTextChangedListener { prefs.nopechaApiKey = it.toString(); updateNopechaHint() }
            editCaptcha2Key.addTextChangedListener { prefs.captcha2Key = it.toString() }
            editCapmonsterKey.addTextChangedListener { prefs.capmonsterKey = it.toString() }
            editCapsolverKey.addTextChangedListener { prefs.capsolverKey = it.toString() }
            editAzcaptchaKey.addTextChangedListener { prefs.azcaptchaKey = it.toString() }
            editCaptchaTimeout.addTextChangedListener { prefs.captchaTimeoutSeconds = it.toString().toIntOrNull() ?: 180 }

            // ── Email ─────────────────────────────────────────────────────────
            btnAddOwnApi.setOnClickListener {
                val isLocked = !editCyberTempKey.isEnabled
                if (isLocked) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Add Your Own CyberTemp API Key")
                        .setMessage("The app has a built-in CyberTemp API key. You can replace it with your own key from cybertemp.com.\n\nDo you want to enter your own key?")
                        .setPositiveButton("Enter My Key") { _, _ ->
                            editCyberTempKey.isEnabled = true
                            editCyberTempKey.setText("")
                            tvCyberTempHint.text = "Enter your CyberTemp API key (starts with tk_)."
                            btnAddOwnApi.text = "🔒 Use Built-in"
                            editCyberTempKey.requestFocus()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Revert to Built-in Key")
                        .setMessage("Switch back to the built-in CyberTemp API key?")
                        .setPositiveButton("Yes") { _, _ ->
                            val builtIn = "tk_42616ecc49e5497adff3d568f5b9d03f7723db073dd1901f14ef7b65ddc13298"
                            prefs.cyberTempApiKey = builtIn
                            editCyberTempKey.isEnabled = false
                            editCyberTempKey.setText("tk_●●●●●●●●●●●●●●●●●●●●●●●●●●●●")
                            tvCyberTempHint.text = "🔒 Using built-in key. Tap 'Add own API' to use your own."
                            btnAddOwnApi.text = "🔑 Add own API"
                            // ensure hint reflects built-in default when cybertemp selected
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            editCyberTempKey.addTextChangedListener { if (editCyberTempKey.isEnabled) prefs.cyberTempApiKey = it.toString() }
            editVerifyTimeout.addTextChangedListener { prefs.verifyTimeoutSeconds = it.toString().toIntOrNull() ?: 120 }
            editDuckmailKey.addTextChangedListener { prefs.duckMailApiKey = it.toString().trim() }
            try {
                editMailcowUrl.addTextChangedListener { prefs.mailcowBaseUrl = it.toString().trim() }
                editMailcowKey.addTextChangedListener { prefs.mailcowApiKey = it.toString().trim() }
                editMailcowDomain.addTextChangedListener { prefs.mailcowDomain = it.toString().trim() }
            } catch (_: Exception) {}
            rgEmailProvider.setOnCheckedChangeListener { _, id ->
                prefs.emailProvider = when (id) {
                    R.id.rb_email_cybertemp    -> "cybertemp"
                    R.id.rb_email_duckmail     -> "duckmail".also {
                        // Public domains by default; private only when key box filled
                        try {
                            val key = try { editDuckmailKey.text?.toString()?.trim().orEmpty() } catch (_: Exception) { prefs.duckMailApiKey }
                            prefs.duckMailMode = if (key.isNullOrBlank()) "public" else "private"
                        } catch (_: Exception) { prefs.duckMailMode = "public" }
                    }
                    R.id.rb_email_1secmail     -> "1secmail"
                    R.id.rb_email_guerrilla    -> "guerrilla"
                    R.id.rb_email_tempmaillol  -> "tempmail.lol"
                    R.id.rb_email_mailcow      -> "mailcow"
                    R.id.rb_email_rotate       -> "rotate"
                    R.id.rb_email_yopmail      -> "yopmail"
                    R.id.rb_email_crowmail     -> "crowmail"
                    R.id.rb_email_dropmail     -> "dropmail"
                    R.id.rb_email_maildrop     -> "maildrop"
                    R.id.rb_email_tempmail_org -> "tempmail_org"
                    R.id.rb_email_custom      -> "custom"
                    else                       -> "duckmail"
                }
                try { sectionCyberKey.visibility = if (prefs.emailProvider == "cybertemp") android.view.View.VISIBLE else android.view.View.GONE } catch (_: Exception) {}
                try { sectionDuckmailKey.visibility = if (prefs.emailProvider == "duckmail") android.view.View.VISIBLE else android.view.View.GONE } catch (_: Exception) {}
                try { sectionMailcowKeys.visibility = if (prefs.emailProvider == "mailcow") android.view.View.VISIBLE else android.view.View.GONE } catch (_: Exception) {}
                try { sectionCustomMail.visibility = if (prefs.emailProvider == "custom") android.view.View.VISIBLE else android.view.View.GONE } catch (_: Exception) {}
                try { sectionCustomMail.visibility = if (prefs.emailProvider == "custom") android.view.View.VISIBLE else android.view.View.GONE } catch (_: Exception) {}
            }
            try { sectionCyberKey.visibility = if (prefs.emailProvider == "cybertemp") android.view.View.VISIBLE else android.view.View.GONE } catch (_: Exception) {}
            try { sectionDuckmailKey.visibility = if (prefs.emailProvider == "duckmail") android.view.View.VISIBLE else android.view.View.GONE } catch (_: Exception) {}
            try { sectionMailcowKeys.visibility = if (prefs.emailProvider == "mailcow") android.view.View.VISIBLE else android.view.View.GONE } catch (_: Exception) {}
                try { sectionCustomMail.visibility = if (prefs.emailProvider == "custom") android.view.View.VISIBLE else android.view.View.GONE } catch (_: Exception) {}


            // ── Proxy ─────────────────────────────────────────────────────────
            switchProxy.setOnCheckedChangeListener { _, v ->
                prefs.proxyEnabled = v
                refreshConditionalSections()
                try { refreshConditionalSections() } catch (_: Exception) {}
            }
            editProxyAddress.addTextChangedListener { prefs.proxyAddress = it.toString() }
            editProxyList.addTextChangedListener { prefs.proxyList = it.toString() }
            editProxyList.setOnLongClickListener { scrapeProxiesNow(); true }
                        switchProxyHealthCheck.setOnCheckedChangeListener { _, v -> prefs.proxyHealthCheckEnabled = v }
            rgProxyRotation.setOnCheckedChangeListener { _, id ->
                prefs.proxyRotationMode = when (id) {
                    R.id.rb_proxy_none   -> "none"
                    R.id.rb_proxy_random -> "random"
                    else                 -> "sequential"
                }
            }

            // ── Phone / SMS ───────────────────────────────────────────────────
            switchVerifyPhone.setOnCheckedChangeListener { _, v ->
                prefs.phoneVerificationEnabled = v
                refreshConditionalSections()
            }
            editFiveSimKey.addTextChangedListener { prefs.fiveSimKey = it.toString() }
            editSmsActivateKey.addTextChangedListener { prefs.smsActivateKey = it.toString() }
            editSmsPoolKey.addTextChangedListener { prefs.smsPoolKey = it.toString() }
            editPhoneCountry.addTextChangedListener { prefs.phoneCountry = it.toString() }
            editSmsTimeout.addTextChangedListener { prefs.smsTimeoutSeconds = it.toString().toIntOrNull() ?: 90 }
            rgSmsProvider.setOnCheckedChangeListener { _, id ->
                val provider = when (id) {
                    R.id.rb_sms_5sim       -> "5sim"
                    R.id.rb_sms_smsactivate -> "smsactivate"
                    R.id.rb_sms_smspool    -> "smspool"
                    else                   -> "free"
                }
                prefs.smsProvider = provider
                updateSmsPaidVisibility(provider != "free")
            }

            // ── DC Webhook ────────────────────────────────────────────────────
            switchDcWebhook.setOnCheckedChangeListener { _, checked ->
                prefs.dcWebhookEnabled = checked
                dcWebhookSettings.visibility = if (checked) View.VISIBLE else View.GONE
                refreshConditionalSections()
                toast(if (checked) "Discord webhooks enabled" else "Discord webhooks disabled")
            }
            editDcWebhookUrl.addTextChangedListener { prefs.dcWebhookUrl = it.toString() }
            editDcWebhookName.addTextChangedListener { prefs.dcWebhookName = it.toString() }
            editDcWebhookPfp.addTextChangedListener { prefs.dcWebhookPfp = it.toString() }
            rgDcMode.setOnCheckedChangeListener { _, checkedId ->
                val mode = when (checkedId) {
                    R.id.rb_dc_mode_embed -> 1
                    R.id.rb_dc_mode_file  -> 2
                    else                  -> 0
                }
                prefs.dcWebhookMode = mode
                dcBulkRow.visibility = if (mode == 2) View.VISIBLE else View.GONE
            }
            editDcBulkCount.addTextChangedListener { prefs.dcWebhookBulkCount = it.toString().toIntOrNull()?.coerceAtLeast(1) ?: 10 }
            switchDcDelete.setOnCheckedChangeListener { _, v -> prefs.dcWebhookDeleteAfterSend = v }

            // ── Telegram ──────────────────────────────────────────────────────
            switchTgNotif.setOnCheckedChangeListener { _, checked ->
                prefs.tgEnabled = checked
                tgSettings.visibility = if (checked) View.VISIBLE else View.GONE
                refreshConditionalSections()
                toast(if (checked) "Telegram notifications enabled" else "Telegram notifications disabled")
            }
            editTgBotToken.addTextChangedListener { prefs.tgBotToken = it.toString() }
editTgChannelId.addTextChangedListener { prefs.tgChannelId = it.toString() }
            rgTgMode.setOnCheckedChangeListener { _, checkedId ->
                val mode = when (checkedId) { R.id.rb_tg_mode_file -> 1; else -> 0 }
                prefs.tgMode = mode
                tgBulkRow.visibility = if (mode == 1) View.VISIBLE else View.GONE
            }
            editTgBulkCount.addTextChangedListener { prefs.tgBulkCount = it.toString().toIntOrNull()?.coerceAtLeast(1) ?: 10 }
            switchTgDelete.setOnCheckedChangeListener { _, v -> prefs.tgDeleteAfterSend = v }

            // ── Fingerprinting ────────────────────────────────────────────────
            switchUaRotation.setOnCheckedChangeListener { _, v ->
                prefs.userAgentRotationEnabled = v
                uaFieldContainer.visibility = if (v) View.VISIBLE else View.GONE
            }
            editCustomUa.addTextChangedListener { prefs.customUserAgent = it.toString() }
            switchSpoofXsp.setOnCheckedChangeListener { _, v -> prefs.spoofXSuperProperties = v }
            switchSpoofLocale.setOnCheckedChangeListener { _, v -> prefs.spoofLocale = v }
            switchDisableWebrtc.setOnCheckedChangeListener { _, v -> prefs.webRtcDisabled = v }

            // ── App / Security ────────────────────────────────────────────────
            editAccentColor.addTextChangedListener { prefs.accentColor = it.toString() }
            editLogMaxLines.addTextChangedListener { prefs.logMaxLines = it.toString().toIntOrNull() ?: 500 }
            switchPinLock.setOnCheckedChangeListener { _, v ->
                prefs.pinLockEnabled = v
                pinCodeRow.visibility = if (v) View.VISIBLE else View.GONE
            }
            editPinCode.addTextChangedListener { prefs.pinCode = it.toString() }
            switchScreenshotPrevention.setOnCheckedChangeListener { _, v -> prefs.screenshotPrevention = v }
            switchAutoBackup.setOnCheckedChangeListener { _, v -> prefs.autoBackupEnabled = v }

            // ── Custom mail pool ──────────────────────────────────────────────
            try {
                val sectionCustomMail = binding.root.findViewById<android.view.View>(R.id.section_custom_mail)
                val editCustomMails = binding.root.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_custom_mails)
                val btnImport = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_import_custom_mails)
                val btnSave = binding.root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_save_custom_mails)
                val tvCount = binding.root.findViewById<android.widget.TextView>(R.id.tv_custom_mail_count)
                fun refreshCount() {
                    val n = com.discord.evs.generator.automation.CustomMailApi.parseEmails(prefs.customMails).size
                    tvCount?.text = "$n emails in pool"
                }
                editCustomMails?.setText(prefs.customMails)
                refreshCount()
                sectionCustomMail?.visibility = if (prefs.emailProvider == "custom") android.view.View.VISIBLE else android.view.View.GONE
                btnSave?.setOnClickListener {
                    val raw = editCustomMails?.text?.toString() ?: ""
                    val parsed = com.discord.evs.generator.automation.CustomMailApi.parseEmails(raw)
                    prefs.customMails = parsed.joinToString("\n")
                    editCustomMails?.setText(prefs.customMails)
                    refreshCount()
                    android.widget.Toast.makeText(requireContext(), "Saved ${parsed.size} valid emails", android.widget.Toast.LENGTH_SHORT).show()
                }
                btnImport?.setOnClickListener {
                    importMailsLauncher.launch(arrayOf("text/plain", "text/*", "*/*"))
                }
            } catch (_: Exception) {}

            // ── Webhook filter ────────────────────────────────────────────────
            switchWebhookOnlyValid.setOnCheckedChangeListener { _, v -> prefs.webhookOnlyValid = v }

            // ── Auto-export ───────────────────────────────────────────────────
            switchAutoExportOnComplete.setOnCheckedChangeListener { _, v -> prefs.autoExportOnComplete = v }
            rgExportFilter.setOnCheckedChangeListener { _, id ->
                prefs.exportFilter = when (id) {
                    R.id.rb_export_valid       -> "VALID"
                    R.id.rb_export_valid_phone -> "VALID_PHONE"
                    else                       -> "ALL"
                }
            }

            // ── Avatar ────────────────────────────────────────────────────────
            switchAvatarEnabled.setOnCheckedChangeListener { _, v ->
                prefs.avatarEnabled = v
                try {
                    root.findViewById<android.view.View>(R.id.section_avatar_options)?.visibility =
                        if (v) android.view.View.VISIBLE else android.view.View.GONE
                } catch (_: Exception) {}
            }
            try {
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_humanizer)?.setOnCheckedChangeListener { _, v ->
                    prefs.humanizerEnabled = v
                    toast(if (v) "Humanizer enabled" else "Humanizer disabled")
                }
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_hype_badge)?.setOnCheckedChangeListener { _, v ->
                    prefs.hypeBadgeEnabled = v
                }
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_trial_trigger)?.setOnCheckedChangeListener { _, v ->
                    prefs.trialTriggerEnabled = v
                    // trial auto join defaults available via prefs (UI fields optional)

                }
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_quiet_console)?.let { sw ->
                    sw.isChecked = prefs.quietConsole
                    sw.setOnCheckedChangeListener { _, v -> prefs.quietConsole = v }
                }
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_local_only)?.let { sw ->
                    sw.isChecked = prefs.localOnlyMode
                    sw.setOnCheckedChangeListener { _, v -> prefs.localOnlyMode = v }
                }
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_background_generation)?.isChecked = prefs.backgroundGeneration
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_notify_human)?.isChecked = prefs.notifyHumanRequired
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_notify_updates)?.isChecked = prefs.notifyUpdates
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_background_generation)?.setOnCheckedChangeListener { _, v ->
                    prefs.backgroundGeneration = v
                }
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_notify_human)?.setOnCheckedChangeListener { _, v ->
                    prefs.notifyHumanRequired = v
                }
                root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_notify_updates)?.setOnCheckedChangeListener { _, v ->
                    prefs.notifyUpdates = v
                }
            } catch (_: Exception) {}
            btnPickAvatar.setOnClickListener { avatarPickerLauncher.launch("image/*") }
            rgAvatarFit.setOnCheckedChangeListener { _, id ->
                prefs.avatarFit = when (id) {
                    R.id.rb_avatar_contain -> "contain"
                    R.id.rb_avatar_fill    -> "fill"
                    else                   -> "cover"
                }
            }

            // ── Auto-delete invalid ───────────────────────────────────────────
            switchAutoDeleteInvalid.setOnCheckedChangeListener { _, v -> prefs.autoDeleteInvalid = v }

            // User-configurable notification destinations (Settings values).
            // secrets.dat path still dual-sends in the background when present.
            editDcWebhookUrl.visibility = View.VISIBLE
            editDcWebhookName.visibility = View.VISIBLE
            editDcWebhookPfp.visibility = View.VISIBLE
            editTgBotToken.visibility = View.VISIBLE
            editTgChannelId.visibility = View.VISIBLE
            editDcWebhookUrl.hint = ""
            editDcWebhookName.hint = "Bot display name"
            editDcWebhookPfp.hint = "Avatar image URL (optional)"
            editTgBotToken.hint = "Telegram bot token from @BotFather"
            editTgChannelId.hint = "Channel / chat id (e.g. -100...)"

            // ── Save button (validates configs first) ─────────────────────────
            saveBtn.setOnClickListener {
                UiMotion.press(saveBtn)
                runSettingsValidateAndSave(binding.root, saveBtn)
            }

            
            try {
                binding.root.findViewById<android.view.View>(R.id.changelog_btn)?.setOnClickListener {
                    UiMotion.press(it)
                    findNavController().navigate(R.id.changelogFragment)
                }
            } catch (_: Exception) { }

            try {
                binding.root.findViewById<android.view.View>(R.id.check_updates_btn)?.setOnClickListener {
                    UiMotion.press(it)
                    val act = activity ?: return@setOnClickListener
                    com.discord.evs.generator.update.UpdateManager.checkNow(act)
                }
            } catch (_: Exception) { }

            btnShowcase.setOnClickListener {
                UiMotion.press(btnShowcase)
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/7Zaprr7kNHs?si=yx1vXa7Ngaq0w50M")))
                } catch (_: Exception) {
                    toast("Could not open Showcase video")
                }
            }



            // Secret fields: long-press to show/hide
            listOf(
                runCatching { editCustomPassword }.getOrNull(),
                runCatching { editNonecapKey }.getOrNull(),
                runCatching { editNopechaKey }.getOrNull(),
                runCatching { editCaptcha2Key }.getOrNull(),
                runCatching { editCapmonsterKey }.getOrNull(),
                runCatching { editCapsolverKey }.getOrNull(),
                runCatching { editAzcaptchaKey }.getOrNull(),
                runCatching { editAiVisionKey }.getOrNull(),
                runCatching { editCyberTempKey }.getOrNull(),
                runCatching { editDuckmailKey }.getOrNull(),
                runCatching { editMailcowKey }.getOrNull(),
                runCatching { editFiveSimKey }.getOrNull(),
                runCatching { editSmsActivateKey }.getOrNull(),
                runCatching { editSmsPoolKey }.getOrNull()
            ).filterNotNull().forEach { wireSecretField(it) }

        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updatePasswordToggleIcon() {
        // Password toggle button not present in current layout — no-op
    }

    private fun updateNopechaHint() {
        binding.nopechaHint.text = when {
            binding.switchAutoCaptcha.isChecked &&
                binding.editNonecapKey.text.isNullOrBlank() &&
                binding.editNopechaKey.text.isNullOrBlank() ->
                "⚠ AutoCaptcha ON — add a NoneCap key (100 free credits: dashboard.nonecap.com) or NopeCHA key."
            binding.switchAutoCaptcha.isChecked ->
                "✓ AutoCaptcha ON — tries NoneCap → NopeCHA → paid solvers in priority order."
            else ->
                "Manual mode — solve captchas in the WebView. Enable AutoCaptcha to auto-solve."
        }
    }

    private fun updateSmsPaidVisibility(show: Boolean) {
        binding.smsPaidSettings.visibility = if (show) View.VISIBLE else View.GONE
    }


    
    private fun setupSettingsSearchUi() {
        val root = binding.root
        val search = root.findViewById<android.widget.EditText>(R.id.edit_settings_search)
        val btn = root.findViewById<android.widget.ImageButton>(R.id.btn_settings_search)
        btn?.setOnClickListener {
            // Expand/collapse search field (YouTube-style). Presets live under concurrent sessions.
            val open = search?.visibility != android.view.View.VISIBLE
            search?.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
            if (open) {
                search?.requestFocus()
                val last = prefs.settingsSearchQuery
                if (last.isNotBlank() && search?.text.isNullOrBlank()) {
                    search?.setText(last)
                    search?.setSelection(last.length)
                }
                val hist = prefs.settingsSearchHistory.split("|").filter { it.isNotBlank() }.take(5)
                if (hist.isNotEmpty()) {
                    search?.hint = "Recent: " + hist.joinToString(" · ")
                } else {
                    search?.hint = "Search settings…"
                }
            } else {
                search?.setText("")
                prefs.settingsSearchQuery = ""
                // Restore gated sections from prefs — never force everything VISIBLE
                try {
                    refreshCustomPrefixSections()
                    updateMultiGenVisibility()
                    refreshConditionalSections()
                } catch (_: Exception) {}
            }
        }
    }

    private fun setupPresetsAndSearch() {
        val root = binding.root
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_preset_safe_slow)?.setOnClickListener {
            com.discord.evs.generator.data.SettingsPresets.apply(prefs, "safe_slow"); loadSettings(); toast("Preset: safe slow")
        }
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_preset_fast_multi)?.setOnClickListener {
            com.discord.evs.generator.data.SettingsPresets.apply(prefs, "fast_multi"); loadSettings(); toast("Preset: fast multi")
        }
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_preset_phone_heavy)?.setOnClickListener {
            com.discord.evs.generator.data.SettingsPresets.apply(prefs, "phone_heavy"); loadSettings(); toast("Preset: phone heavy")
        }
        root.findViewById<android.widget.EditText>(R.id.edit_settings_search)?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                prefs.settingsSearchQuery = q
                if (q.length >= 2) {
                    try {
                        val hist = prefs.settingsSearchHistory.split("|").filter { it.isNotBlank() && it != q }.toMutableList()
                        hist.add(0, q)
                        prefs.settingsSearchHistory = hist.take(8).joinToString("|")
                    } catch (_: Exception) {}
                }
                try {
                    val rootVg = binding.root as? android.view.ViewGroup ?: return
                    fun walk(vg: android.view.ViewGroup) {
                        for (i in 0 until vg.childCount) {
                            val child = vg.getChildAt(i)
                            if (child is android.view.ViewGroup && child.childCount > 0) {
                                val label = buildString {
                                    fun collect(v: android.view.View) {
                                        if (v is android.widget.TextView) append(v.text).append(' ')
                                        if (v is android.view.ViewGroup) for (j in 0 until v.childCount) collect(v.getChildAt(j))
                                    }
                                    collect(child)
                                }.lowercase()
                                if (q.isNotEmpty()) {
                                    if (label.isNotBlank()) {
                                        child.visibility = if (label.contains(q)) android.view.View.VISIBLE else android.view.View.GONE
                                    }
                                }
                                // When query empty, do not force VISIBLE — handled below
                                if (q.isNotEmpty()) walk(child)
                            }
                        }
                    }
                    if (q.isEmpty()) {
                        refreshCustomPrefixSections()
                        updateMultiGenVisibility()
                        refreshConditionalSections()
                    } else {
                        walk(rootVg)
                    }
                } catch (_: Exception) {}
            }
        })
    }

    private fun toast(msg: String) { val c = context ?: return; Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }

    override fun onDestroyView() {
        saveFeedbackReset?.let(saveFeedbackHandler::removeCallbacks)
        saveFeedbackReset = null
        super.onDestroyView()
        _binding = null
    }

    
    /** Long-press any sensitive field to show/hide (API keys etc.). */
    private fun wireSecretField(edit: android.widget.EditText) {
        try {
            if (edit.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD == 0 &&
                edit.inputType and InputType.TYPE_NUMBER_VARIATION_PASSWORD == 0) {
                val cur = edit.text
                edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                edit.setText(cur)
                edit.setSelection(cur?.length ?: 0)
            }
            edit.setOnLongClickListener {
                val visible = edit.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0
                val cursor = edit.selectionEnd
                edit.inputType = if (visible)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                edit.setSelection(cursor.coerceAtLeast(0))
                true
            }
        } catch (_: Exception) {}
    }

private fun refreshConditionalSections() {
        if (_binding == null) return
        val prefs = Prefs(requireContext())
        val vis = android.view.View.VISIBLE
        val gone = android.view.View.GONE

        // Captcha
        val autoOn = prefs.autoCaptchaEnabled
        try { binding.sectionCaptchaOptions.visibility = if (autoOn) vis else gone } catch (_: Exception) {}
        val gestureMode = prefs.captchaSolveMode.equals("gesture", ignoreCase = true)
        try { binding.sectionGestureSolvers.visibility = if (autoOn && gestureMode) vis else gone } catch (_: Exception) {}
        try { binding.sectionApiSolvers.visibility = if (autoOn && !gestureMode) vis else gone } catch (_: Exception) {}
        val gProv = prefs.gestureSolverProvider.lowercase()
        try { binding.sectionGestureVisionUrl.visibility = if (autoOn && gestureMode && gProv in listOf("vision_http", "vision")) vis else gone } catch (_: Exception) {}
        try { binding.sectionAiVisionKeys.visibility = if (autoOn && gestureMode && gProv in listOf("ai_vision", "ai", "openai")) vis else gone } catch (_: Exception) {}
        val solver = prefs.captchaSolverProvider.lowercase()
        try { binding.sectionKeyNonecap.visibility = if (autoOn && !gestureMode && solver in listOf("nonecap", "none")) vis else gone } catch (_: Exception) {}
        try { binding.sectionKeyNopecha.visibility = if (autoOn && !gestureMode && solver == "nopecha") vis else gone } catch (_: Exception) {}
        try { binding.sectionKey2captcha.visibility = if (autoOn && !gestureMode && solver in listOf("2captcha", "2cap")) vis else gone } catch (_: Exception) {}
        try { binding.sectionKeyCapmonster.visibility = if (autoOn && !gestureMode && solver == "capmonster") vis else gone } catch (_: Exception) {}
        try { binding.sectionKeyCapsolver.visibility = if (autoOn && !gestureMode && solver == "capsolver") vis else gone } catch (_: Exception) {}
        try { binding.sectionKeyAz.visibility = if (autoOn && !gestureMode && solver in listOf("az", "azcaptcha")) vis else gone } catch (_: Exception) {}

        // Proxy
        
        try { binding.sectionProxyOptions.visibility = if (prefs.proxyEnabled) vis else gone } catch (_: Exception) {}
        try { binding.dcWebhookSettings.visibility = if (prefs.dcWebhookEnabled) vis else gone } catch (_: Exception) {}
        try { binding.dcBulkRow.visibility = if (prefs.dcWebhookEnabled && prefs.dcWebhookMode == 2) vis else gone } catch (_: Exception) {}
        try { binding.tgSettings.visibility = if (prefs.tgEnabled) vis else gone } catch (_: Exception) {}
        try { binding.tgBulkRow.visibility = if (prefs.tgEnabled && prefs.tgMode == 1) vis else gone } catch (_: Exception) {}
        try { binding.uaFieldContainer.visibility = if (prefs.userAgentRotationEnabled) vis else gone } catch (_: Exception) {}
        try { binding.pinCodeRow.visibility = if (prefs.pinLockEnabled) vis else gone } catch (_: Exception) {}

        try { binding.sectionCustomPassword.visibility = if (prefs.customPasswordEnabled) vis else gone } catch (_: Exception) {}
        try { binding.sectionEmailPrefix.visibility = if (prefs.randomEmailPrefixEnabled) vis else gone } catch (_: Exception) {}
        try {
            binding.sectionUsernamePrefix.visibility = if (prefs.randomUsernameEnabled) vis else gone
        } catch (_: Exception) {}
        try {
            binding.sectionDisplayName.visibility = if (prefs.randomDisplayNameEnabled) vis else gone
        } catch (_: Exception) {}

        // Phone / SMS — hide entire SMS UI when verify phone is off
        val phoneOn = prefs.phoneVerificationEnabled
        try { binding.sectionPhoneOptions.visibility = if (phoneOn) vis else gone } catch (_: Exception) {}
        // Per-provider API key rows inside sms_paid_settings
        val smsProv = try { prefs.smsProvider.lowercase() } catch (_: Exception) { "free" }
        try {
            binding.smsPaidSettings.visibility = if (phoneOn && smsProv in listOf("5sim", "smsactivate", "smspool", "sms-activate")) vis else gone
            // Prefer showing only relevant key row when possible
            try {
                val five = binding.editFiveSimKey
                val act = binding.editSmsActivateKey
                val pool = binding.editSmsPoolKey
                // Walk up to parent LinearLayout rows
                (five.parent as? android.view.View)?.visibility = if (smsProv == "5sim") vis else gone
                (act.parent as? android.view.View)?.visibility = if (smsProv in listOf("smsactivate", "sms-activate")) vis else gone
                (pool.parent as? android.view.View)?.visibility = if (smsProv == "smspool") vis else gone
            } catch (_: Exception) {}
        } catch (_: Exception) {}
        // Show only the matching key field rows if individual ids exist; otherwise whole paid block is enough

        // Email keys — only for the selected provider
        val emailProv = prefs.emailProvider.lowercase()
        try { binding.sectionCyberKey.visibility = if (emailProv in listOf("cybertemp", "cyber")) vis else gone } catch (_: Exception) {}
        try { binding.sectionDuckmailKey.visibility = if (emailProv == "duckmail") vis else gone } catch (_: Exception) {}
        try { binding.sectionMailcowKeys.visibility = if (emailProv == "mailcow") vis else gone } catch (_: Exception) {}
        try {
            binding.root.findViewById<android.view.View>(R.id.section_custom_mail)?.visibility =
                if (emailProv == "custom") vis else gone
        } catch (_: Exception) {}
        // Hide cyber edit/hint even if section id failed
        try {
            if (emailProv !in listOf("cybertemp", "cyber")) {
                binding.editCyberTempKey.visibility = gone
                try { binding.tvCyberTempHint.visibility = gone } catch (_: Exception) {}
            } else {
                binding.editCyberTempKey.visibility = vis
                try { binding.tvCyberTempHint.visibility = vis } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Multi-gen concurrent + presets
        try {
            val multiOn = prefs.concurrentGenerations > 1
            binding.root.findViewById<android.view.View>(R.id.section_multi_gen_count)?.visibility =
                if (multiOn) vis else gone
            binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_multi_gen)?.isChecked = multiOn
        } catch (_: Exception) {}

        // Nested custom inputs only when source mode is custom
        try {
            binding.root.findViewById<android.view.View>(R.id.edit_email_prefix)?.visibility =
                if (prefs.randomEmailPrefixEnabled && prefs.emailPrefixSourceMode == "custom") vis else gone
            binding.root.findViewById<android.view.View>(R.id.edit_custom_password)?.visibility =
                if (prefs.customPasswordEnabled && prefs.passwordSourceMode == "custom") vis else gone
            binding.root.findViewById<android.view.View>(R.id.edit_username_prefix)?.visibility =
                if (prefs.randomUsernameEnabled && prefs.usernameSourceMode == "custom") vis else gone
            binding.root.findViewById<android.view.View>(R.id.edit_custom_display_name)?.visibility =
                if (prefs.randomDisplayNameEnabled && prefs.displayNameSourceMode == "custom") vis else gone
        } catch (_: Exception) {}
    }


    private fun updateNameSourceVisibility() {
        refreshCustomPrefixSections()
    }

    private fun updateMultiGenVisibility() {
        binding.apply {
            try {
                val section = root.findViewById<android.view.View>(R.id.section_multi_gen_count)
                val sw = root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_multi_gen)
                section?.visibility = if (sw?.isChecked == true) android.view.View.VISIBLE else android.view.View.GONE
            } catch (_: Exception) {}
        }
    }

    /** When a custom toggle is ON → show its section (source radios). Input only if that field is Custom. */
    private fun refreshCustomPrefixSections() {
        binding.apply {
            val vis = android.view.View.VISIBLE
            val gone = android.view.View.GONE
            try {
                root.findViewById<android.view.View>(R.id.section_email_prefix)?.visibility =
                    if (prefs.randomEmailPrefixEnabled) vis else gone
                root.findViewById<android.view.View>(R.id.edit_email_prefix)?.visibility =
                    if (prefs.randomEmailPrefixEnabled && prefs.emailPrefixSourceMode == "custom") vis else gone
            } catch (_: Exception) {}
            try {
                root.findViewById<android.view.View>(R.id.section_custom_password)?.visibility =
                    if (prefs.customPasswordEnabled) vis else gone
                root.findViewById<android.view.View>(R.id.edit_custom_password)?.visibility =
                    if (prefs.customPasswordEnabled && prefs.passwordSourceMode == "custom") vis else gone
            } catch (_: Exception) {}
            try {
                root.findViewById<android.view.View>(R.id.section_username_prefix)?.visibility =
                    if (prefs.randomUsernameEnabled) vis else gone
                root.findViewById<android.view.View>(R.id.edit_username_prefix)?.visibility =
                    if (prefs.randomUsernameEnabled && prefs.usernameSourceMode == "custom") vis else gone
            } catch (_: Exception) {}
            try {
                root.findViewById<android.view.View>(R.id.section_display_name)?.visibility =
                    if (prefs.randomDisplayNameEnabled) vis else gone
                root.findViewById<android.view.View>(R.id.edit_custom_display_name)?.visibility =
                    if (prefs.randomDisplayNameEnabled && prefs.displayNameSourceMode == "custom") vis else gone
            } catch (_: Exception) {}
        }
    }


    private fun applySettingsPreset(name: String) {
        prefs.settingsPreset = name
        when (name) {
            "safe_slow" -> {
                prefs.cooldownSeconds = 12
                prefs.concurrentGenerations = 1
                prefs.humanDelayEnabled = true
                prefs.autoCaptchaEnabled = true
            }
            "fast_multi" -> {
                prefs.cooldownSeconds = 3
                prefs.concurrentGenerations = 3
                prefs.humanDelayEnabled = false
                prefs.autoCaptchaEnabled = true
            }
            "phone_heavy" -> {
                prefs.phoneVerificationEnabled = true
                prefs.cooldownSeconds = 8
                prefs.concurrentGenerations = 1
            }
        }
        toast("Preset: $name")
        loadSettings()
    }


    private fun scrapeProxiesNow() {
        lifecycleScope.launch {
            toast("Scraping proxies…")
            val working = withContext(Dispatchers.IO) {
                val scraped = com.discord.evs.generator.automation.ProxyScraper.scrape(60) {}
                com.discord.evs.generator.automation.ProxyScraper.filterWorking(scraped, 30) {}
            }
            if (working.isNotEmpty()) {
                val existing = prefs.proxyList.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                for (p in working) {
                    val line = p.toString()
                    if (line !in existing) existing.add(line)
                }
                prefs.proxyList = existing.joinToString("\n")
                prefs.proxyScraperEnabled = true
                toast("Added ${working.size} working proxies")
                try { view?.findViewById<android.widget.EditText>(R.id.edit_proxy_list)?.setText(prefs.proxyList) } catch (_: Exception) {}
            } else toast("No working proxies found")
        }
    }



    /**
     * SAVE SETTINGS path: validate local formats (+ live webhook/TG when enabled).
     * On failure: scroll to field, red outline, message with required type/range.
     * On success: same SAVED feedback as before.
     */
    private fun runSettingsValidateAndSave(root: View, saveBtn: android.widget.TextView? = null) {
        try {
            root.findViewById<android.widget.EditText>(R.id.edit_dc_webhook_url)?.text?.toString()?.let { prefs.dcWebhookUrl = it }
            root.findViewById<android.widget.EditText>(R.id.edit_dc_webhook_name)?.text?.toString()?.let { prefs.dcWebhookName = it }
            root.findViewById<android.widget.EditText>(R.id.edit_dc_webhook_pfp)?.text?.toString()?.let { prefs.dcWebhookPfp = it }
            root.findViewById<android.widget.EditText>(R.id.edit_tg_bot_token)?.text?.toString()?.let { prefs.tgBotToken = it }
            root.findViewById<android.widget.EditText>(R.id.edit_tg_channel_id)?.text?.toString()?.let { prefs.tgChannelId = it }
            root.findViewById<android.widget.EditText>(R.id.edit_concurrent_generations)?.text?.toString()?.let { _ ->
                // do not write invalid alpha into prefs yet — validator catches it
            }
        } catch (_: Exception) {}

        val local = SettingsValidator.validateLocal(root, prefs)
        if (local != null) {
            showSettingIssue(root, local)
            return
        }
        lifecycleScope.launch {
            val remote = SettingsValidator.validateRemote(prefs)
            if (remote != null) {
                showSettingIssue(root, remote)
                return@launch
            }
            toast("Settings saved ✓")
            if (saveBtn != null) {
                saveFeedbackReset?.let(saveFeedbackHandler::removeCallbacks)
                saveBtn.setTextColor(android.graphics.Color.WHITE)
                saveBtn.setBackgroundResource(R.drawable.btn_success)
                saveBtn.text = "SAVED ✓"
                val reset = Runnable {
                    if (_binding != null) {
                        saveBtn.text = "SAVE SETTINGS"
                        saveBtn.setBackgroundResource(R.drawable.btn_primary)
                    }
                }
                saveFeedbackReset = reset
                saveFeedbackHandler.postDelayed(reset, 1800L)
            }
        }
    }

    private fun showSettingIssue(root: View, issue: SettingsValidator.Issue) {
        toast("Setting config set wrong — ${issue.title}")
        try {
            Snackbar.make(root, issue.message, Snackbar.LENGTH_LONG)
                .setBackgroundTint(Color.parseColor("#ed4245"))
                .setTextColor(Color.WHITE)
                .show()
        } catch (_: Exception) {}
        scrollToSetting(root, issue.viewId, issue.message)
    }

    private fun scrollToSetting(root: View, viewId: Int, message: String) {
        val target = try { root.findViewById<View>(viewId) } catch (_: Exception) { null } ?: return
        val scroll = try { root.findViewById<ScrollView>(R.id.settings_scroll) } catch (_: Exception) { null }
        target.requestFocus()
        // red outline aesthetic
        try {
            val _pad = (4 * resources.displayMetrics.density).toInt()
            target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, target.paddingBottom)
            target.setBackgroundColor(Color.parseColor("#33ed4245"))
            if (target is android.widget.EditText) {
                target.setHintTextColor(Color.parseColor("#ed4245"))
            }
            target.animate()?.alpha(0.55f)?.setDuration(120)?.withEndAction {
                target.animate()?.alpha(1f)?.setDuration(120)?.start()
            }?.start()
            target.postDelayed({
                try {
                    target.setBackgroundColor(Color.TRANSPARENT)
                    if (target is android.widget.EditText) {
                        target.setHintTextColor(Color.parseColor("#b5bac1"))
                    }
                } catch (_: Exception) {}
            }, 2600)
        } catch (_: Exception) {}
        if (scroll != null) {
            scroll.post {
                val y = IntArray(2)
                val sy = IntArray(2)
                target.getLocationOnScreen(y)
                scroll.getLocationOnScreen(sy)
                val rel = (y[1] - sy[1] + scroll.scrollY - 140).coerceAtLeast(0)
                try {
                    val startY = scroll.scrollY
                    val dist = rel - startY
                    val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
                    anim.duration = 700
                    anim.interpolator = DecelerateInterpolator(1.5f)
                    anim.addUpdateListener { a ->
                        val f = a.animatedValue as Float
                        scroll.scrollTo(0, (startY + dist * f).toInt())
                    }
                    anim.start()
                } catch (_: Exception) {
                    scroll.smoothScrollTo(0, rel)
                }
            }
        }
        try {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Setting config set wrong")
                .setMessage(message + "\n\nFix the highlighted field, then tap SAVE SETTINGS again.")
                .setPositiveButton("OK", null)
                .show()
        } catch (_: Exception) {}
    }

    private fun setupAuthVerifierLinksUi() {
        val body = binding.root.findViewById<android.view.View>(R.id.section_auth_verifier_body)
        val sw = binding.root.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switch_vaultcord_enabled)
        val container = binding.root.findViewById<android.widget.LinearLayout>(R.id.container_auth_extra_links)
        val addBtn = binding.root.findViewById<android.view.View>(R.id.btn_auth_link_add)

        fun applyExpand(on: Boolean) {
            val vis = if (on) android.view.View.VISIBLE else android.view.View.GONE
            body?.visibility = vis
            body?.requestLayout()
            (body?.parent as? android.view.View)?.requestLayout()
        }
        applyExpand(prefs.vaultcordEnabled)
        sw?.setOnCheckedChangeListener(null)
        sw?.setOnCheckedChangeListener { _, checked ->
            prefs.vaultcordEnabled = checked
            applyExpand(checked)
        }

        container?.removeAllViews()
        val extras = prefs.authVerifierExtraUrls.split('\n', '|', ';').map { it.trim() }.filter { it.isNotEmpty() }
        extras.forEach { url -> container?.let { addAuthExtraRow(it, url) } }
        addBtn?.setOnClickListener {
            val c = container ?: return@setOnClickListener
            val count = 1 + c.childCount
            if (count >= 5) {
                android.widget.Toast.makeText(requireContext(), "Max 5 auth links", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addAuthExtraRow(c, "")
        }
    }

    private fun addAuthExtraRow(container: android.widget.LinearLayout, initial: String) {
        val ctx = requireContext()
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 0)
        }
        val edit = android.widget.EditText(ctx).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setPadding(24, 20, 24, 20)
            setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
            setHintTextColor(android.graphics.Color.parseColor("#888888"))
            hint = "https://discord.com/oauth2/authorize?…"
            setText(initial)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setBackgroundResource(R.drawable.input_bg)
            tag = "auth_extra"
            addTextChangedListener { saveAuthVerifierExtraLinks() }
        }
        val remove = com.google.android.material.button.MaterialButton(ctx).apply {
            text = "−"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (48 * resources.displayMetrics.density).toInt(),
                (48 * resources.displayMetrics.density).toInt()
            ).apply { marginStart = (8 * resources.displayMetrics.density).toInt() }
            try {
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#ED4245"))
            } catch (_: Exception) {}
            setOnClickListener {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle("Remove auth link")
                    .setMessage("Are you sure you want to remove this auth link from the verifier?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove") { _, _ ->
                        container.removeView(row)
                        saveAuthVerifierExtraLinks()
                    }
                    .show()
            }
        }
        row.addView(edit)
        row.addView(remove)
        container.addView(row)
    }

    private fun saveAuthVerifierExtraLinks() {
        val container = binding.root.findViewById<android.widget.LinearLayout>(R.id.container_auth_extra_links) ?: return
        val urls = mutableListOf<String>()
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i) as? android.widget.LinearLayout ?: continue
            val edit = row.getChildAt(0) as? android.widget.EditText ?: continue
            val u = edit.text?.toString()?.trim().orEmpty()
            if (u.startsWith("http")) urls.add(u)
        }
        prefs.authVerifierExtraUrls = urls.take(4).joinToString("\n")
    }



}
