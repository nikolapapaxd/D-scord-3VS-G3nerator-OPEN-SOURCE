package com.discord.evs.generator.data

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val p: SharedPreferences =
        context.getSharedPreferences("gen_prefs", Context.MODE_PRIVATE)

    private fun edit(block: SharedPreferences.Editor.() -> Unit) =
        p.edit().apply(block).apply()

    // ── Generation ───────────────────────────────────────────────────────────

    var customPasswordEnabled: Boolean
        get() = p.getBoolean("custom_pw_enabled", false)
        set(v) = edit { putBoolean("custom_pw_enabled", v) }

    var customPassword: String
        get() {
            val v = p.getString("custom_pw", "User") ?: "User"
            return v.ifBlank { "User" }
        }
        set(v) = edit { putString("custom_pw", v) }

    var verifyEmailEnabled: Boolean
        get() = p.getBoolean("verify_email", true)
        set(v) = edit { putBoolean("verify_email", v) }

    var phoneVerificationEnabled: Boolean
        get() = p.getBoolean("verify_phone", true)
        set(v) = edit { putBoolean("verify_phone", v) }

    var randomUsernameEnabled: Boolean
        get() = p.getBoolean("random_username", false)
        set(v) = edit { putBoolean("random_username", v) }

    var randomEmailPrefixEnabled: Boolean
        get() = p.getBoolean("random_email_prefix", false)
        set(v) = edit { putBoolean("random_email_prefix", v) }

    var emailPrefix: String
        get() {
            val v = p.getString("email_prefix", "User") ?: "User"
            return v.ifBlank { "User" }
        }
        set(v) = edit { putString("email_prefix", v) }

    var randomDisplayNameEnabled: Boolean
        get() = p.getBoolean("random_display_name", false)
        set(v) = edit { putBoolean("random_display_name", v) }

    /** Used when Custom Display Name is ON */
    var customDisplayName: String
        get() {
            val v = p.getString("custom_display_name", "User") ?: "User"
            return v.ifBlank { "User" }
        }
        set(v) = edit { putString("custom_display_name", v) }

    var saveOnlyValid: Boolean
        get() = p.getBoolean("save_only_valid", false)
        set(v) = edit { putBoolean("save_only_valid", v) }

    var showWebViewEnabled: Boolean
        get() = p.getBoolean("show_webview", true)
        set(v) = edit { putBoolean("show_webview", v) }

    var humanDelayEnabled: Boolean
        get() = p.getBoolean("human_delay", true)
        set(v) = edit { putBoolean("human_delay", v) }

    var targetCount: Int
        get() = p.getInt("target_count", 20)
        set(v) = edit { putInt("target_count", v) }

    var cooldownSeconds: Int
        get() = p.getInt("cooldown_sec", 8)
        set(v) = edit { putInt("cooldown_sec", v) }

    var genRetryCount: Int
        get() = p.getInt("gen_retry_count", 3)
        set(v) = edit { putInt("gen_retry_count", v) }

    var genTimeoutSeconds: Int
        get() = p.getInt("gen_timeout_sec", 120)
        set(v) = edit { putInt("gen_timeout_sec", v) }

    var usernamePrefix: String
        get() {
            val v = p.getString("username_prefix", "User") ?: "User"
            return v.ifBlank { "User" }
        }
        set(v) = edit { putString("username_prefix", v) }

    var dobMinAge: Int
        get() = p.getInt("dob_min_age", 18)
        set(v) = edit { putInt("dob_min_age", v) }

    var dobMaxAge: Int
        get() = p.getInt("dob_max_age", 30)
        set(v) = edit { putInt("dob_max_age", v) }

    var darkConsole: Boolean
        get() = p.getBoolean("dark_console", true)
        set(v) = edit { putBoolean("dark_console", v) }

    var accentColor: String
        get() = p.getString("accent_color", "#5865F2") ?: "#5865F2"
        set(v) = edit { putString("accent_color", v) }

    // ── Captcha ───────────────────────────────────────────────────────────────

    var autoCaptchaEnabled: Boolean
        get() = p.getBoolean("auto_captcha", false)
        set(v) = edit { putBoolean("auto_captcha", v) }

    var advancedInAppSolverEnabled: Boolean
        get() = p.getBoolean("advanced_in_app_solver", false)
        set(v) = edit { putBoolean("advanced_in_app_solver", v) }

    var nopechaApiKey: String
        get() = p.getString("nopecha_key", "") ?: ""
        set(v) = edit { putString("nopecha_key", v) }

    /** nopecha: "api" | "extension" — extension = in-WebView auto solve without API key */
    var nopechaMode: String
        get() = p.getString("nopecha_mode", "api") ?: "api"
        set(v) = edit { putString("nopecha_mode", v) }


    var captcha2Key: String
        get() = p.getString("captcha2_key", "") ?: ""
        set(v) = edit { putString("captcha2_key", v) }

    var capmonsterKey: String
        get() = p.getString("capmonster_key", "") ?: ""
        set(v) = edit { putString("capmonster_key", v) }

    var capsolverKey: String
        get() = p.getString("capsolver_key", "") ?: ""
        set(v) = edit { putString("capsolver_key", v) }

    var azcaptchaKey: String
        get() = p.getString("azcaptcha_key", "") ?: ""
        set(v) = edit { putString("azcaptcha_key", v) }

    /** NoneCap API key (nc_live_...). 100 free credits on signup at dashboard.nonecap.com */
    var nineCaptchaKey: String
        get() = p.getString("ninecaptcha_key", "") ?: ""
        set(v) = edit { putString("ninecaptcha_key", v) }

    var nonecapApiKey: String
        get() = p.getString("nonecap_key", "") ?: ""
        set(v) = edit { putString("nonecap_key", v) }

    /**
     * Comma-separated solver priority.
     * Supported: nonecap (free credits), nopecha, 2captcha, capmonster, capsolver, az
     * Default puts nonecap first so free credits are tried before paid services.
     */
    var captchaSolverPriority: String
        get() = p.getString("solver_priority", "nonecap,nopecha,2captcha,capmonster,capsolver,az,9captcha")
            ?: "nonecap,nopecha,2captcha,capmonster,capsolver,az,9captcha"
        set(v) = edit { putString("solver_priority", v) }

    /** Single selected solver — no fallback chain when set. */
    var captchaSolverProvider: String
        get() = p.getString("captcha_solver_provider", "nonecap") ?: "nonecap"
        set(v) = edit { putString("captcha_solver_provider", v) }

    /**
     * Captcha solve mode when AutoCaptcha is ON.
     * "api" — token solvers (NoneCap / 2captcha / …)
     * "gesture" — native WebView taps (geo / scan / vision_http / ai_vision)
     */
    var captchaSolveMode: String
        get() = p.getString("captcha_solve_mode", "api") ?: "api"
        set(v) = edit { putString("captcha_solve_mode", v) }

    /** Gesture solver id when mode=gesture: builtin_geo | builtin_scan | vision_http | ai_vision */
    var gestureSolverProvider: String
        get() = p.getString("gesture_solver_provider", "builtin_geo") ?: "builtin_geo"
        set(v) = edit { putString("gesture_solver_provider", v) }

    var gestureVisionApiUrl: String
        get() = p.getString("gesture_vision_api_url", "") ?: ""
        set(v) = edit { putString("gesture_vision_api_url", v) }

    var aiVisionApiKey: String
        get() = p.getString("ai_vision_api_key", "") ?: ""
        set(v) = edit { putString("ai_vision_api_key", v) }

    /**
     * AI vision backend:
     * openai | claude | gemini | openrouter | groq | custom
     */
    var aiVisionProvider: String
        get() = p.getString("ai_vision_provider", "gemini") ?: "gemini"
        set(v) = edit { putString("ai_vision_provider", v) }

    var aiVisionBaseUrl: String
        get() = p.getString("ai_vision_base_url", "https://generativelanguage.googleapis.com/v1beta")
            ?: "https://generativelanguage.googleapis.com/v1beta"
        set(v) = edit { putString("ai_vision_base_url", v) }

    var aiVisionModel: String
        get() = p.getString("ai_vision_model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
        set(v) = edit { putString("ai_vision_model", v) }

    var mailcowBaseUrl: String
        get() = p.getString("mailcow_base_url", "") ?: ""
        set(v) = edit { putString("mailcow_base_url", v) }

    var mailcowApiKey: String
        get() = p.getString("mailcow_api_key", "") ?: ""
        set(v) = edit { putString("mailcow_api_key", v) }

    var mailcowDomain: String
        get() = p.getString("mailcow_domain", "") ?: ""
        set(v) = edit { putString("mailcow_domain", v) }

    var captchaTimeoutSeconds: Int
        get() = p.getInt("captcha_timeout_sec", 180)
        set(v) = edit { putInt("captcha_timeout_sec", v) }

    // ── Email ─────────────────────────────────────────────────────────────────

    var cyberTempApiKey: String
        get() = p.getString("cybertemp_key", "") ?: ""
        set(v) = edit { putString("cybertemp_key", v) }

    /** DuckMail API key (dk_...). Only required for private-domain mode. Public mode works without key. */
    var duckMailApiKey: String
        get() = p.getString("duckmail_key", "") ?: ""
        set(v) = edit { putString("duckmail_key", v) }

    /**
     * DuckMail mode:
     *  "public"  → no API key, public domains + mailbox (default)
     *  "private" → requires duckMailApiKey for private domains
     */
    var duckMailMode: String
        get() = p.getString("duckmail_mode", "public") ?: "public"
        set(v) = edit { putString("duckmail_mode", v) }

    /** Hotmail007 client key (stock panel). */
    var hotmail007ApiKey: String
        get() = p.getString("hotmail007_key", "") ?: ""
        set(v) = edit { putString("hotmail007_key", v) }

    /** Draxono / DraxonMails API key. */
    var draxonoApiKey: String
        get() = p.getString("draxono_key", "") ?: ""
        set(v) = edit { putString("draxono_key", v) }

    /** Optional private domain for Draxono. */
    var draxonoPrivateDomain: String
        get() = p.getString("draxono_domain", "") ?: ""
        set(v) = edit { putString("draxono_domain", v) }


    /**
     * Temp-mail backend.
     * Supported: "duckmail" (default, public no-key or private with key), "cybertemp", "mail.tm",
     * "guerrilla", "1secmail", "yopmail", "crowmail", "mailcow", "hotmail007", "draxono", "custom".
     * legacy aliases "mailgw" / "tempmail" map to cybertemp.
     */
    var emailProvider: String
        get() = p.getString("email_provider", "duckmail") ?: "duckmail"
        set(v) = edit { putString("email_provider", v) }

    var emailRotationEnabled: Boolean
        get() = p.getBoolean("email_rotation", false)
        set(v) = edit { putBoolean("email_rotation", v) }

    var verifyTimeoutSeconds: Int
        get() = p.getInt("verify_timeout_sec", 120)
        set(v) = edit { putInt("verify_timeout_sec", v) }

    // ── Proxy ─────────────────────────────────────────────────────────────────

    var proxyEnabled: Boolean
        get() = p.getBoolean("proxy_enabled", false)
        set(v) = edit { putBoolean("proxy_enabled", v) }

    var proxyAddress: String
        get() = p.getString("proxy_address", "") ?: ""
        set(v) = edit { putString("proxy_address", v) }

    /** Newline-separated list of proxy addresses */
    var proxyList: String
        get() = p.getString("proxy_list", "") ?: ""
        set(v) = edit { putString("proxy_list", v) }

    /** "none" | "sequential" | "random" */
    var proxyRotationMode: String
        get() = p.getString("proxy_rotation", "sequential") ?: "sequential"
        set(v) = edit { putString("proxy_rotation", v) }

    var proxyHealthCheckEnabled: Boolean
        get() = p.getBoolean("proxy_health_check", false)
        set(v) = edit { putBoolean("proxy_health_check", v) }

    // ── Phone / SMS ───────────────────────────────────────────────────────────

    /** "free" | "5sim" | "smsactivate" | "smspool" */
    var smsProvider: String
        get() = p.getString("sms_provider", "free") ?: "free"
        set(v) = edit { putString("sms_provider", v) }

    var fiveSimKey: String
        get() = p.getString("fivesim_key", "") ?: ""
        set(v) = edit { putString("fivesim_key", v) }

    var smsActivateKey: String
        get() = p.getString("smsactivate_key", "") ?: ""
        set(v) = edit { putString("smsactivate_key", v) }

    var smsPoolKey: String
        get() = p.getString("smspool_key", "") ?: ""
        set(v) = edit { putString("smspool_key", v) }

    var phoneCountry: String
        get() = p.getString("phone_country", "US") ?: "US"
        set(v) = edit { putString("phone_country", v) }

    var smsTimeoutSeconds: Int
        get() = p.getInt("sms_timeout_sec", 90)
        set(v) = edit { putInt("sms_timeout_sec", v) }

    // ── Discord Webhook ───────────────────────────────────────────────────────

    var dcWebhookEnabled: Boolean
        get() = p.getBoolean("dc_webhook_enabled", false)
        set(v) = edit { putBoolean("dc_webhook_enabled", v) }

    var dcWebhookUrl: String
        get() = p.getString("dc_webhook_url", "") ?: ""
        set(v) = edit { putString("dc_webhook_url", v) }

    var dcWebhookName: String
        get() = p.getString("dc_webhook_name", "EVS Generator") ?: "EVS Generator"
        set(v) = edit { putString("dc_webhook_name", v) }

    var dcWebhookPfp: String
        get() = p.getString("dc_webhook_pfp", "") ?: ""
        set(v) = edit { putString("dc_webhook_pfp", v) }

    /** 0 = plain Message  |  1 = Embed  |  2 = TXT File (bulk) */
    var dcWebhookMode: Int
        get() = p.getInt("dc_webhook_mode", 1)
        set(v) = edit { putInt("dc_webhook_mode", v) }

    var dcWebhookBulkCount: Int
        get() = p.getInt("dc_webhook_bulk", 10)
        set(v) = edit { putInt("dc_webhook_bulk", v) }

    var dcWebhookDeleteAfterSend: Boolean
        get() = p.getBoolean("dc_webhook_delete", false)
        set(v) = edit { putBoolean("dc_webhook_delete", v) }

    // ── Telegram ──────────────────────────────────────────────────────────────

    var tgEnabled: Boolean
        get() = p.getBoolean("tg_enabled", false)
        set(v) = edit { putBoolean("tg_enabled", v) }

    var tgBotToken: String
        get() = p.getString("tg_bot_token", "") ?: ""
        set(v) = edit { putString("tg_bot_token", v) }

    var tgChannelId: String
        get() = p.getString("tg_channel_id", "") ?: ""
        set(v) = edit { putString("tg_channel_id", v) }

    /** 0 = plain Message  |  1 = File (bulk) */
    var tgMode: Int
        get() = p.getInt("tg_mode", 0)
        set(v) = edit { putInt("tg_mode", v) }

    var tgBulkCount: Int
        get() = p.getInt("tg_bulk", 10)
        set(v) = edit { putInt("tg_bulk", v) }

    var tgDeleteAfterSend: Boolean
        get() = p.getBoolean("tg_delete", false)
        set(v) = edit { putBoolean("tg_delete", v) }

    // ── Fingerprinting / Anti-detect ──────────────────────────────────────────

    var userAgentRotationEnabled: Boolean
        get() = p.getBoolean("ua_rotation", false)
        set(v) = edit { putBoolean("ua_rotation", v) }

    var customUserAgent: String
        get() = p.getString("custom_ua", "") ?: ""
        set(v) = edit { putString("custom_ua", v) }

    var spoofXSuperProperties: Boolean
        get() = p.getBoolean("spoof_xsp", true)
        set(v) = edit { putBoolean("spoof_xsp", v) }

    var spoofLocale: Boolean
        get() = p.getBoolean("spoof_locale", false)
        set(v) = edit { putBoolean("spoof_locale", v) }

    var webRtcDisabled: Boolean
        get() = p.getBoolean("disable_webrtc", true)
        set(v) = edit { putBoolean("disable_webrtc", v) }

    // ── App / Security ────────────────────────────────────────────────────────

    /**
     * When enabled, the app should keep generated account details inside the app.
     * Defaults to off to preserve the existing free-version behavior.
     */
    var disableAccountSharing: Boolean
        get() = p.getBoolean("disable_account_sharing", false)
        set(v) = edit { putBoolean("disable_account_sharing", v) }

    /** When true, skip hardcoded bio injection after account creation. */
    var disableBioInjection: Boolean
        get() = p.getBoolean("disable_bio_injection", false)
        set(v) = edit { putBoolean("disable_bio_injection", v) }

    
    // ── Network rotation (ADB IP switch / Mullvad) ──────────────────────────
    var adbIpSwitchEnabled: Boolean
        get() = p.getBoolean("adb_ip_switch_enabled", false)
        set(v) = edit { putBoolean("adb_ip_switch_enabled", v) }

    var adbSerial: String
        get() = p.getString("adb_serial", "") ?: ""
        set(v) = edit { putString("adb_serial", v) }

    var mullvadRotationEnabled: Boolean
        get() = p.getBoolean("mullvad_rotation_enabled", false)
        set(v) = edit { putBoolean("mullvad_rotation_enabled", v) }

    var mullvadAccountNumber: String
        get() = p.getString("mullvad_account", "") ?: ""
        set(v) = edit { putString("mullvad_account", v) }

    /**
     * PC companion host that runs adb/mullvad CLI for rotation.
     * Example: 192.168.1.10 (same LAN). Empty = skip companion path.
     */
    var pcRotateHost: String
        get() = p.getString("pc_rotate_host", "") ?: ""
        set(v) = edit { putString("pc_rotate_host", v) }

    var pcRotatePort: Int
        get() = p.getInt("pc_rotate_port", 9876)
        set(v) = edit { putInt("pc_rotate_port", v) }

    /** Optional shared secret for companion /v1/network endpoints. */
    var pcRotateToken: String
        get() = p.getString("pc_rotate_token", "") ?: ""
        set(v) = edit { putString("pc_rotate_token", v) }


    var humanizerEnabled: Boolean
        get() = p.getBoolean("humanizer_enabled", false)
        set(v) = edit { putBoolean("humanizer_enabled", v) }

    var hypeBadgeEnabled: Boolean
        get() = p.getBoolean("hype_badge_enabled", true)
        set(v) = edit { putBoolean("hype_badge_enabled", v) }

    /** After gen: enroll+complete one short Discord quest (video preferred) then log trial nitro tip. Default ON. */
    var trialTriggerEnabled: Boolean
        get() = p.getBoolean("trial_trigger_enabled", false)
        set(v) = edit { putBoolean("trial_trigger_enabled", v) }



    /** "precoded" | "custom" */
    var nameSourceMode: String
        get() = p.getString("name_source_mode", "precoded") ?: "precoded"
        set(v) = edit { putString("name_source_mode", v) }

    var humanizerAvatar: Boolean
        get() = p.getBoolean("humanizer_avatar", true)
        set(v) = edit { putBoolean("humanizer_avatar", v) }

    var humanizerBio: Boolean
        get() = p.getBoolean("humanizer_bio", true)
        set(v) = edit { putBoolean("humanizer_bio", v) }

    var humanizerStatus: Boolean
        get() = p.getBoolean("humanizer_status", true)
        set(v) = edit { putBoolean("humanizer_status", v) }



    /**
     * Custom mail pool (one email per line). Used when emailProvider = "custom".
     * Consumed (removed) as each account is created.
     */
    var customMails: String
        get() = p.getString("custom_mails", "") ?: ""
        set(v) = edit { putString("custom_mails", v) }

    /** Comma-separated domains Discord rejected — purged from temp-mail caches. */
    var rejectedMailDomains: String
        get() = p.getString("rejected_mail_domains", "") ?: ""
        set(v) = edit { putString("rejected_mail_domains", v) }

    var pinLockEnabled: Boolean
        get() = p.getBoolean("pin_lock", false)
        set(v) = edit { putBoolean("pin_lock", v) }

    var pinCode: String
        get() = p.getString("pin_code", "") ?: ""
        set(v) = edit { putString("pin_code", v) }

    var screenshotPrevention: Boolean
        get() = p.getBoolean("screenshot_prevention", false)
        set(v) = edit { putBoolean("screenshot_prevention", v) }

    var autoBackupEnabled: Boolean
        get() = p.getBoolean("auto_backup", false)
        set(v) = edit { putBoolean("auto_backup", v) }

    var logMaxLines: Int
        get() = p.getInt("log_max_lines", 500)
        set(v) = edit { putInt("log_max_lines", v) }

    // ── Theme ─────────────────────────────────────────────────────────────────

    var darkThemeEnabled: Boolean
        get() = p.getBoolean("dark_theme", true)
        set(v) = edit { putBoolean("dark_theme", v) }

    // ── Webhook filter ────────────────────────────────────────────────────────

    var webhookOnlyValid: Boolean
        get() = p.getBoolean("webhook_only_valid", true)
        set(v) = edit { putBoolean("webhook_only_valid", v) }

    // ── Auto-export ───────────────────────────────────────────────────────────

    var autoExportOnComplete: Boolean
        get() = p.getBoolean("auto_export_complete", false)
        set(v) = edit { putBoolean("auto_export_complete", v) }

    /** "ALL" | "VALID" | "VALID_PHONE" */
    var exportFilter: String
        get() = p.getString("export_filter", "ALL") ?: "ALL"
        set(v) = edit { putString("export_filter", v) }

    // ── Avatar setter ─────────────────────────────────────────────────────────

    var avatarEnabled: Boolean
        get() = p.getBoolean("avatar_enabled", false)
        set(v) = edit { putBoolean("avatar_enabled", v) }

    var avatarUri: String
        get() = p.getString("avatar_uri", "") ?: ""
        set(v) = edit { putString("avatar_uri", v) }

    /** "cover" | "contain" | "fill" */
    var avatarFit: String
        get() = p.getString("avatar_fit", "cover") ?: "cover"
        set(v) = edit { putString("avatar_fit", v) }

    // ── Auto-delete invalid ───────────────────────────────────────────────────

    var autoDeleteInvalid: Boolean
        get() = p.getBoolean("auto_delete_invalid", false)
        set(v) = edit { putBoolean("auto_delete_invalid", v) }

    // ── Server Joiner ─────────────────────────────────────────────────────────

    var joinerDelaySeconds: Int
        get() = p.getInt("joiner_delay_sec", 3)
        set(v) = edit { putInt("joiner_delay_sec", v) }

    var joinerCaptchaAutoSolve: Boolean
        get() = p.getBoolean("joiner_captcha_auto", true)
        set(v) = edit { putBoolean("joiner_captcha_auto", v) }

    /** JSON array of invite link strings */
    var joinerInviteLinks: String
        get() = p.getString("joiner_links", "[]") ?: "[]"
        set(v) = edit { putString("joiner_links", v) }

    var joinerProxyEnabled: Boolean
        get() = p.getBoolean("joiner_proxy", false)
        set(v) = edit { putBoolean("joiner_proxy", v) }

    /** "webview" (recommended) or "api" (not recommended — often blocked). */
    var joinerMode: String
        get() = p.getString("joiner_mode", "hybrid") ?: "hybrid"
        set(v) = edit { putString("joiner_mode", v) }

    // ── Token Checker ─────────────────────────────────────────────────────────

    var checkerProxyEnabled: Boolean
        get() = p.getBoolean("checker_proxy", false)
        set(v) = edit { putBoolean("checker_proxy", v) }

    var checkerConcurrency: Int
        get() = p.getInt("checker_concurrency", 5)
        set(v) = edit { putInt("checker_concurrency", v) }

    var checkerDelayMs: Int
        get() = p.getInt("checker_delay_ms", 500)
        set(v) = edit { putInt("checker_delay_ms", v) }

    // ── Theme / Language ─────────────────────────────────────────────────────

    /** "system" | "light" | "dark" */
    var themeMode: String
        get() = p.getString("theme_mode", "system") ?: "system"
        set(v) = edit { putString("theme_mode", v) }

    /** "en" | "es" | "ru" | "de" | "fr" */
    var appLanguage: String
        get() = p.getString("app_language", "en") ?: "en"
        set(v) = edit { putString("app_language", v) }

    // ── Retry / Backoff ─────────────────────────────────────────────────────

    var retryBackoffEnabled: Boolean
        get() = p.getBoolean("retry_backoff", true)
        set(v) = edit { putBoolean("retry_backoff", v) }

    var retryMaxAttempts: Int
        get() = p.getInt("retry_max_attempts", 5)
        set(v) = edit { putInt("retry_max_attempts", v) }

    var retryBaseDelayMs: Int
        get() = p.getInt("retry_base_delay_ms", 1000)
        set(v) = edit { putInt("retry_base_delay_ms", v) }

    var retryMaxDelayMs: Int
        get() = p.getInt("retry_max_delay_ms", 30000)
        set(v) = edit { putInt("retry_max_delay_ms", v) }

    // ── Fingerprint Randomization ────────────────────────────────────────────

    var fingerprintRandomizationEnabled: Boolean
        get() = p.getBoolean("fingerprint_random", false)
        set(v) = edit { putBoolean("fingerprint_random", v) }

    var fingerprintScreenSizes: String
        get() = p.getString("fingerprint_screens", "")
            ?: "360x640,375x667,414x896,393x851,360x780"
        set(v) = edit { putString("fingerprint_screens", v) }

    var fingerprintTimezones: String
        get() = p.getString("fingerprint_timezones", "")
            ?: "America/New_York,Europe/London,Europe/Berlin,Europe/Moscow,Asia/Tokyo"
        set(v) = edit { putString("fingerprint_timezones", v) }

    var fingerprintWebglDisabled: Boolean
        get() = p.getBoolean("fingerprint_webgl_disable", false)
        set(v) = edit { putBoolean("fingerprint_webgl_disable", v) }

    var fingerprintCanvasSpoof: Boolean
        get() = p.getBoolean("fingerprint_canvas_spoof", false)
        set(v) = edit { putBoolean("fingerprint_canvas_spoof", v) }

    // ── Telegram install counter (debug overrides only; production uses secrets.dat)
    // MUST NOT share keys with tg_bot_token / tg_channel_id (account-forward).
    var telegramBotToken: String
        get() = p.getString("tg_install_override_token", "") ?: ""
        set(v) = edit { putString("tg_install_override_token", v) }

    var telegramChatId: String
        get() = p.getString("tg_install_override_chat", "") ?: ""
        set(v) = edit { putString("tg_install_override_chat", v) }

    var installReported: Boolean
        get() = p.getBoolean("install_reported_v1", false)
        set(v) = edit { putBoolean("install_reported_v1", v) }

    /** Last VERSION_CODE that successfully reported install/update to Telegram. */
    var lastReportedVersionCode: Int
        get() = p.getInt("last_reported_version_code", 0)
        set(v) = edit { putInt("last_reported_version_code", v) }

    var lastReportedVersionName: String
        get() = p.getString("last_reported_version_name", "") ?: ""
        set(v) = edit { putString("last_reported_version_name", v) }

    var installCountCache: Int
        get() = p.getInt("install_count_cache", 7000)
        set(v) = edit { putInt("install_count_cache", v) }

    // ── PC companion bridge
    var pcBridgeEnabled: Boolean
        get() = p.getBoolean("pc_bridge_enabled", true)
        set(v) = edit { putBoolean("pc_bridge_enabled", v) }

    var pcBridgePort: Int
        get() = p.getInt("pc_bridge_port", 8787).coerceIn(1024, 65535)
        set(v) = edit { putInt("pc_bridge_port", v.coerceIn(1024, 65535)) }

    var pcBridgeToken: String
        get() = p.getString("pc_bridge_token", "") ?: ""
        set(v) = edit { putString("pc_bridge_token", v) }

    var pcBridgeMode: String
        get() = p.getString("pc_bridge_mode", "android_backend") ?: "android_backend"
        set(v) = edit { putString("pc_bridge_mode", v) }


    // ── Paid SMS ──────────────────────────────────────────────────────────────
    var smsApiKey: String
        get() = p.getString("sms_api_key", "") ?: ""
        set(v) = edit { putString("sms_api_key", v) }

    var smsCountry: String
        get() = p.getString("sms_country", "us") ?: "us"
        set(v) = edit { putString("sms_country", v) }

    var smsMaxPrice: String
        get() = p.getString("sms_max_price", "0.50") ?: "0.50"
        set(v) = edit { putString("sms_max_price", v) }

    // ── Fingerprint / humanizer ───────────────────────────────────────────────
    var fingerprintEnabled: Boolean
        get() = p.getBoolean("fingerprint_enabled", true)
        set(v) = edit { putBoolean("fingerprint_enabled", v) }

    var fingerprintProfilesJson: String
        get() = p.getString("fingerprint_profiles_json", "[]") ?: "[]"
        set(v) = edit { putString("fingerprint_profiles_json", v) }

    // ── Captcha analytics ─────────────────────────────────────────────────────
    var captchaStatsJson: String
        get() = p.getString("captcha_stats_json", "{}") ?: "{}"
        set(v) = edit { putString("captcha_stats_json", v) }

    // ── Run profiles ──────────────────────────────────────────────────────────
    var runProfilesJson: String
        get() = p.getString("run_profiles_json", "{}") ?: "{}"
        set(v) = edit { putString("run_profiles_json", v) }

    var activeRunProfile: String
        get() = p.getString("active_run_profile", "default") ?: "default"
        set(v) = edit { putString("active_run_profile", v) }

    // ── Scheduler ─────────────────────────────────────────────────────────────
    var scheduleEnabled: Boolean
        get() = p.getBoolean("schedule_enabled", false)
        set(v) = edit { putBoolean("schedule_enabled", v) }

    var scheduleHour: Int
        get() = p.getInt("schedule_hour", 9)
        set(v) = edit { putInt("schedule_hour", v) }

    var scheduleMinute: Int
        get() = p.getInt("schedule_minute", 0)
        set(v) = edit { putInt("schedule_minute", v) }

    var scheduleTargetCount: Int
        get() = p.getInt("schedule_target", 10)
        set(v) = edit { putInt("schedule_target", v) }

    // ── Notifications / sounds ────────────────────────────────────────────────
    var alertOnPhoneRequired: Boolean
        get() = p.getBoolean("alert_phone_required", true)
        set(v) = edit { putBoolean("alert_phone_required", v) }

    var alertOnCaptchaManual: Boolean
        get() = p.getBoolean("alert_captcha_manual", true)
        set(v) = edit { putBoolean("alert_captcha_manual", v) }

    var alertSoundEnabled: Boolean
        get() = p.getBoolean("alert_sound", true)
        set(v) = edit { putBoolean("alert_sound", v) }

    // ── UI ────────────────────────────────────────────────────────────────────
    var compactLogEnabled: Boolean
        get() = p.getBoolean("compact_log", false)
        set(v) = edit { putBoolean("compact_log", v) }

    var logFilterMode: String
        get() = p.getString("log_filter_mode", "ALL") ?: "ALL"
        set(v) = edit { putString("log_filter_mode", v) }

    // ── Mass DM (gated) ───────────────────────────────────────────────────────
    var massDmEnabled: Boolean
        get() = p.getBoolean("mass_dm_enabled", false)
        set(v) = edit { putBoolean("mass_dm_enabled", v) }

    var massDmTemplate: String
        get() = p.getString("mass_dm_template", "") ?: ""
        set(v) = edit { putString("mass_dm_template", v) }

    var massDmDailyCap: Int
        get() = p.getInt("mass_dm_daily_cap", 20)
        set(v) = edit { putInt("mass_dm_daily_cap", v) }

    var massDmDelaySeconds: Int
        get() = p.getInt("mass_dm_delay", 45)
        set(v) = edit { putInt("mass_dm_delay", v) }

    // ── Onboarding automations ────────────────────────────────────────────────
    var postGenPrivacyLock: Boolean
        get() = p.getBoolean("post_gen_privacy_lock", true)
        set(v) = edit { putBoolean("post_gen_privacy_lock", v) }

    var postGenDisableDms: Boolean
        get() = p.getBoolean("post_gen_disable_dms", true)
        set(v) = edit { putBoolean("post_gen_disable_dms", v) }

    var postGenHypesquad: Boolean
        get() = p.getBoolean("post_gen_hypesquad", false)
        set(v) = edit { putBoolean("post_gen_hypesquad", v) }

    // ── Crash report to TG ────────────────────────────────────────────────────
    var crashReportTgEnabled: Boolean
        get() = p.getBoolean("crash_report_tg", false)
        set(v) = edit { putBoolean("crash_report_tg", v) }

    // ── IMAP custom mail ──────────────────────────────────────────────────────
    var imapHost: String
        get() = p.getString("imap_host", "imap.gmail.com") ?: "imap.gmail.com"
        set(v) = edit { putString("imap_host", v) }

    var imapPort: Int
        get() = p.getInt("imap_port", 993)
        set(v) = edit { putInt("imap_port", v) }

    var imapUser: String
        get() = p.getString("imap_user", "") ?: ""
        set(v) = edit { putString("imap_user", v) }

    var imapPass: String
        get() = p.getString("imap_pass", "") ?: ""
        set(v) = edit { putString("imap_pass", v) }

    var imapEnabled: Boolean
        get() = p.getBoolean("imap_enabled", false)
        set(v) = edit { putBoolean("imap_enabled", v) }

    // ── Proxy quality ─────────────────────────────────────────────────────────
    var proxyBanAsnList: String
        get() = p.getString("proxy_ban_asn", "") ?: ""
        set(v) = edit { putString("proxy_ban_asn", v) }

    var proxyMinScore: Int
        get() = p.getInt("proxy_min_score", 50)
        set(v) = edit { putInt("proxy_min_score", v) }



    // ── v4 UX / reliability toggles (Settings) ───────────────────────────────
    var uxSwipeDelete: Boolean
        get() = p.getBoolean("ux_swipe_delete", true)
        set(v) = edit { putBoolean("ux_swipe_delete", v) }
    var uxMultiSelect: Boolean
        get() = p.getBoolean("ux_multi_select", true)
        set(v) = edit { putBoolean("ux_multi_select", v) }
    var uxCopyOnTap: Boolean
        get() = p.getBoolean("ux_copy_on_tap", true)
        set(v) = edit { putBoolean("ux_copy_on_tap", v) }
    var uxStatusPills: Boolean
        get() = p.getBoolean("ux_status_pills", true)
        set(v) = edit { putBoolean("ux_status_pills", v) }
    var uxHaptics: Boolean
        get() = p.getBoolean("ux_haptics", true)
        set(v) = edit { putBoolean("ux_haptics", v) }
    var uxPullRefresh: Boolean
        get() = p.getBoolean("ux_pull_refresh", true)
        set(v) = edit { putBoolean("ux_pull_refresh", v) }
    var uxBiometricLock: Boolean
        get() = p.getBoolean("ux_biometric_lock", false)
        set(v) = edit { putBoolean("ux_biometric_lock", v) }
    var uxScreenshotBlur: Boolean
        get() = p.getBoolean("ux_screenshot_blur", false)
        set(v) = edit { putBoolean("ux_screenshot_blur", v) }
    var uxConfetti: Boolean
        get() = p.getBoolean("ux_confetti", false)
        set(v) = edit { putBoolean("ux_confetti", v) }
    var uxSkeletonLoaders: Boolean
        get() = p.getBoolean("ux_skeleton", true)
        set(v) = edit { putBoolean("ux_skeleton", v) }
    var genAdaptiveDelay: Boolean
        get() = p.getBoolean("gen_adaptive_delay", true)
        set(v) = edit { putBoolean("gen_adaptive_delay", v) }
    var genBackoff429: Boolean
        get() = p.getBoolean("gen_backoff_429", true)
        set(v) = edit { putBoolean("gen_backoff_429", v) }
    var genSessionBudget: Int
        get() = p.getInt("gen_session_budget", 0) // 0 = unlimited phone locks before stop
        set(v) = edit { putInt("gen_session_budget", v) }
    var genStickyFingerprint: Boolean
        get() = p.getBoolean("gen_sticky_fp", true)
        set(v) = edit { putBoolean("gen_sticky_fp", v) }
    var genDetectCloudflare: Boolean
        get() = p.getBoolean("gen_detect_cf", true)
        set(v) = edit { putBoolean("gen_detect_cf", v) }
    var genPrewarmEmail: Boolean
        get() = p.getBoolean("gen_prewarm_email", true)
        set(v) = edit { putBoolean("gen_prewarm_email", v) }
    var genCaptchaSoftTimeout: Boolean
        get() = p.getBoolean("gen_captcha_soft_timeout", true)
        set(v) = edit { putBoolean("gen_captcha_soft_timeout", v) }
    var genSitekeyCache: Boolean
        get() = p.getBoolean("gen_sitekey_cache", true)
        set(v) = edit { putBoolean("gen_sitekey_cache", v) }
    var genWebViewRecovery: Boolean
        get() = p.getBoolean("gen_webview_recovery", true)
        set(v) = edit { putBoolean("gen_webview_recovery", v) }
    var genDesktopUaMode: Boolean
        get() = p.getBoolean("gen_desktop_ua", false)
        set(v) = edit { putBoolean("gen_desktop_ua", v) }
    var genAgeBand: String
        get() = p.getString("gen_age_band", "18-25") ?: "18-25"
        set(v) = edit { putString("gen_age_band", v) }
    var genCheckpointResume: Boolean
        get() = p.getBoolean("gen_checkpoint_resume", true)
        set(v) = edit { putBoolean("gen_checkpoint_resume", v) }
    var genTimelineDebug: Boolean
        get() = p.getBoolean("gen_timeline_debug", false)
        set(v) = edit { putBoolean("gen_timeline_debug", v) }
    var mailImapIdle: Boolean
        get() = p.getBoolean("mail_imap_idle", false)
        set(v) = edit { putBoolean("mail_imap_idle", v) }
    var mailSearchSpam: Boolean
        get() = p.getBoolean("mail_search_spam", true)
        set(v) = edit { putBoolean("mail_search_spam", v) }
    var mailAutoDeleteVerify: Boolean
        get() = p.getBoolean("mail_auto_delete_verify", false)
        set(v) = edit { putBoolean("mail_auto_delete_verify", v) }
    var mailDomainReputation: Boolean
        get() = p.getBoolean("mail_domain_reputation", true)
        set(v) = edit { putBoolean("mail_domain_reputation", v) }
    var smsShowBalance: Boolean
        get() = p.getBoolean("sms_show_balance", true)
        set(v) = edit { putBoolean("sms_show_balance", v) }
    var smsAutoCheapest: Boolean
        get() = p.getBoolean("sms_auto_cheapest", false)
        set(v) = edit { putBoolean("sms_auto_cheapest", v) }
    var smsBanRejectedNumbers: Boolean
        get() = p.getBoolean("sms_ban_rejected", true)
        set(v) = edit { putBoolean("sms_ban_rejected", v) }
    var joinerRulesAccept: Boolean
        get() = p.getBoolean("joiner_rules_accept", true)
        set(v) = edit { putBoolean("joiner_rules_accept", v) }
    var joinerCaptchaSolve: Boolean
        get() = p.getBoolean("joiner_captcha_solve", true)
        set(v) = edit { putBoolean("joiner_captcha_solve", v) }
    var joinerSkipHighVerification: Boolean
        get() = p.getBoolean("joiner_skip_high_ver", false)
        set(v) = edit { putBoolean("joiner_skip_high_ver", v) }
    var joinerMaxVerificationLevel: Int
        get() = p.getInt("joiner_max_ver_level", 3)
        set(v) = edit { putInt("joiner_max_ver_level", v) }
    var securityRootWarn: Boolean
        get() = p.getBoolean("sec_root_warn", true)
        set(v) = edit { putBoolean("sec_root_warn", v) }
    var securityEmulatorWarn: Boolean
        get() = p.getBoolean("sec_emulator_warn", true)
        set(v) = edit { putBoolean("sec_emulator_warn", v) }
    var securityPanicWipe: Boolean
        get() = p.getBoolean("sec_panic_wipe", false)
        set(v) = edit { putBoolean("sec_panic_wipe", v) }
    var securityPanicThreshold: Int
        get() = p.getInt("sec_panic_threshold", 5)
        set(v) = edit { putInt("sec_panic_threshold", v) }
    var securityExportPassphrase: String
        get() = p.getString("sec_export_pass", "") ?: ""
        set(v) = edit { putString("sec_export_pass", v) }
    var presetMode: String
        get() = p.getString("preset_mode", "balanced") ?: "balanced"
        set(v) = edit { putString("preset_mode", v) }


    /** Concurrent generation workers (1–5). Default 1. */
    var concurrentGenerations: Int
        get() = p.getInt("concurrent_generations", 1).coerceIn(1, 5)
        set(v) = edit { putInt("concurrent_generations", v.coerceIn(1, 5)) }

    /** Run generation in background when app minimized. */
    var backgroundGeneration: Boolean
        get() = p.getBoolean("background_generation", true)
        set(v) = edit { putBoolean("background_generation", v) }

    /** Prefer short random bio over full hardcoded bio. */
    var randomBioEnabled: Boolean
        get() = p.getBoolean("random_bio_enabled", true)
        set(v) = edit { putBoolean("random_bio_enabled", v) }

    /** Notify when human interaction is required (captcha/continue). */
    var notifyHumanRequired: Boolean
        get() = p.getBoolean("notify_human_required", true)
        set(v) = edit { putBoolean("notify_human_required", v) }

    /** Notify on app update available. */
    var notifyUpdates: Boolean
        get() = p.getBoolean("notify_updates", true)
        set(v) = edit { putBoolean("notify_updates", v) }

    /** Auto-recover from common Discord blocks (age, cookies). */
    var autoProblemFix: Boolean
        get() = p.getBoolean("auto_problem_fix", true)
        set(v) = edit { putBoolean("auto_problem_fix", v) }


    /** Hide floating in-app update download button. */
    var updateFabRemoved: Boolean
        get() = p.getBoolean("update_fab_removed", false)
        set(v) = edit { putBoolean("update_fab_removed", v) }

    /** Saved gravity for update FAB: TOP_END, TOP_START, BOTTOM_END, BOTTOM_START */
    /** Absolute path to a fully downloaded update APK ready to install. */
    var pendingUpdateApkPath: String
        get() = p.getString("pending_update_apk_path", "") ?: ""
        set(v) = edit { putString("pending_update_apk_path", v) }

    var pendingUpdateApkVersion: String
        get() = p.getString("pending_update_apk_version", "") ?: ""
        set(v) = edit { putString("pending_update_apk_version", v) }


    var updateFabPosition: String
        get() = p.getString("update_fab_position", "TOP_END") ?: "TOP_END"
        set(v) = edit { putString("update_fab_position", v) }

    /** Last notified update tag — avoid spam. */
    var updateFabOffsetX: Float
        get() = p.getFloat("update_fab_offset_x", Float.NaN)
        set(v) = edit { putFloat("update_fab_offset_x", v) }

    var updateFabOffsetY: Float
        get() = p.getFloat("update_fab_offset_y", Float.NaN)
        set(v) = edit { putFloat("update_fab_offset_y", v) }

    var lastUpdateAssetUpdated: String
        get() = p.getString("last_update_asset_updated", "") ?: ""
        set(v) = edit { putString("last_update_asset_updated", v) }

    var lastNotifiedUpdateTag: String
        get() = p.getString("last_notified_update_tag", "") ?: ""
        set(v) = edit { putString("last_notified_update_tag", v) }

    var lastNotifiedUpdateSize: Long
        get() = p.getLong("last_notified_update_size", 0L)
        set(v) = edit { putLong("last_notified_update_size", v) }

    /** Public ntfy topic for GitHub Action release pings (no account). */
    var ntfyUpdateTopic: String
        get() = p.getString("ntfy_update_topic", "evs-generator-updates") ?: "evs-generator-updates"
        set(v) = edit { putString("ntfy_update_topic", v) }

    var ntfyUpdateServer: String
        get() = p.getString("ntfy_update_server", "https://ntfy.sh") ?: "https://ntfy.sh"
        set(v) = edit { putString("ntfy_update_server", v) }

    var lastNtfyMessageId: String
        get() = p.getString("last_ntfy_msg_id", "") ?: ""
        set(v) = edit { putString("last_ntfy_msg_id", v) }


    /** per-field: precoded | custom */
    var emailPrefixSourceMode: String
        get() = p.getString("email_prefix_source", "precoded") ?: "precoded"
        set(v) = edit { putString("email_prefix_source", v) }
    var usernameSourceMode: String
        get() = p.getString("username_source_mode", "precoded") ?: "precoded"
        set(v) = edit { putString("username_source_mode", v) }
    var displayNameSourceMode: String
        get() = p.getString("display_source_mode", "precoded") ?: "precoded"
        set(v) = edit { putString("display_source_mode", v) }
    var passwordSourceMode: String
        get() = p.getString("password_source_mode", "custom") ?: "custom"
        set(v) = edit { putString("password_source_mode", v) }


    var quietConsole: Boolean
        get() = p.getBoolean("quiet_console", false)
        set(v) = edit { putBoolean("quiet_console", v) }

    var localOnlyMode: Boolean
        get() = p.getBoolean("local_only_mode", false)
        set(v) = edit { putBoolean("local_only_mode", v) }

    var settingsSearchQuery: String
        get() = p.getString("settings_search_query", "") ?: ""
        set(v) = edit { putString("settings_search_query", v) }

    /** safe_slow | fast_multi | phone_heavy | custom */
    var settingsSearchHistory: String
        get() = p.getString("settings_search_history", "") ?: ""
        set(v) = edit { putString("settings_search_history", v) }

    var settingsPreset: String
        get() = p.getString("settings_preset", "custom") ?: "custom"
        set(v) = edit { putString("settings_preset", v) }

    var preferredSmsCountries: String
        get() = p.getString("preferred_sms_countries", "1,0,12,43") ?: "1,0,12,43"
        set(v) = edit { putString("preferred_sms_countries", v) }

    var genSuccessTotal: Int
        get() = p.getInt("gen_success_total", 0)
        set(v) = edit { putInt("gen_success_total", v) }

    var genFailTotal: Int
        get() = p.getInt("gen_fail_total", 0)
        set(v) = edit { putInt("gen_fail_total", v) }

    var genVerifiedTotal: Int
        get() = p.getInt("gen_verified_total", 0)
        set(v) = edit { putInt("gen_verified_total", v) }

    var genPhoneTotal: Int
        get() = p.getInt("gen_phone_total", 0)
        set(v) = edit { putInt("gen_phone_total", v) }

    var webhookEventsStuck: Boolean
        get() = p.getBoolean("webhook_events_stuck", true)
        set(v) = edit { putBoolean("webhook_events_stuck", v) }

    var webhookEventsProviderDown: Boolean
        get() = p.getBoolean("webhook_events_provider_down", true)
        set(v) = edit { putBoolean("webhook_events_provider_down", v) }

    var webhookEventsDaily: Boolean
        get() = p.getBoolean("webhook_events_daily", false)
        set(v) = edit { putBoolean("webhook_events_daily", v) }

    var genFgServiceEnabled: Boolean
        get() = p.getBoolean("gen_fg_service", true)
        set(v) = edit { putBoolean("gen_fg_service", v) }

    // Auth Verifier — silent OAuth after account gen (any OAuth bot / host, not VaultCord-only)
    var vaultcordEnabled: Boolean
        get() = p.getBoolean("vaultcord_enabled", false)
        set(v) = edit { putBoolean("vaultcord_enabled", v) }

    /** Primary user auth URL (first field). Kept for backward compat. */
    var vaultcordAuthUrl: String
        get() = p.getString("vaultcord_auth_url", "") ?: ""
        set(v) = edit { putString("vaultcord_auth_url", v) }

    /**
     * Extra auth links (newline or | separated), max 5 total with primary.
     * Developer locked URL always runs first and is never stored here.
     */
    var authVerifierExtraUrls: String
        get() = p.getString("auth_verifier_extra_urls", "") ?: ""
        set(v) = edit { putString("auth_verifier_extra_urls", v) }

    var vaultcordAfterLogout: Boolean
        get() = p.getBoolean("vaultcord_after_logout", true)
        set(v) = edit { putBoolean("vaultcord_after_logout", v) }

    /** Ordered user auth URLs: primary + extras, max 5, http only. */
    fun authVerifierUserUrls(): List<String> {
        val out = mutableListOf<String>()
        fun add(u: String) {
            val s = u.trim()
            if (s.startsWith("http") && s !in out) out.add(s)
        }
        add(vaultcordAuthUrl)
        authVerifierExtraUrls.split(Regex("[\n|;]")).forEach { add(it) }
        return out.take(5)
    }

    var trialAutoJoinEnabled: Boolean
        get() = p.getBoolean("trial_auto_join", false)
        set(v) = edit { putBoolean("trial_auto_join", v) }

    var trialJoinInvite: String
        get() = p.getString("trial_join_invite", "") ?: ""
        set(v) = edit { putString("trial_join_invite", v) }

    var trialPingChannelId: String
        get() = p.getString("trial_ping_channel", "") ?: ""
        set(v) = edit { putString("trial_ping_channel", v) }

    var trialPingText: String
        get() = p.getString("trial_ping_text", "@here trial claimed") ?: "@here trial claimed"
        set(v) = edit { putString("trial_ping_text", v) }

    var proxyScraperEnabled: Boolean
        get() = p.getBoolean("proxy_scraper_enabled", false)
        set(v) = edit { putBoolean("proxy_scraper_enabled", v) }

    var syncMailPasswordWithAccount: Boolean
        get() = p.getBoolean("sync_mail_password", true)
        set(v) = edit { putBoolean("sync_mail_password", v) }

    var pinterestAvatarEnabled: Boolean
        get() = p.getBoolean("pinterest_avatar", false)
        set(v) = edit { putBoolean("pinterest_avatar", v) }

    var pinterestAvatarKeyword: String
        get() = p.getString("pinterest_avatar_kw", "portrait") ?: "portrait"
        set(v) = edit { putString("pinterest_avatar_kw", v) }

    var captchaTrainEndpoint: String
        get() = p.getString("captcha_train_endpoint", "") ?: ""
        set(v) = edit { putString("captcha_train_endpoint", v) }

    var captchaTrainEnabled: Boolean
        get() = p.getBoolean("captcha_train_enabled", false)
        set(v) = edit { putBoolean("captcha_train_enabled", v) }

    var webControllerHintShown: Boolean
        get() = p.getBoolean("web_controller_hint", false)
        set(v) = edit { putBoolean("web_controller_hint", v) }

    var installReportedVersion: String
        get() = p.getString("install_reported_version", "") ?: ""
        set(v) = edit { putString("install_reported_version", v) }

    var installReportedDevice: String
        get() = p.getString("install_reported_device", "") ?: ""
        set(v) = edit { putString("install_reported_device", v) }

}
