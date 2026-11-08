package com.discord.evs.generator.data

/**
 * In-app changelog — structure mirrors Discord Tools "What's New" screen
 * (version sections + typed bullets: new / improved / fixed).
 */
object ChangelogData {

    enum class Type { NEW, IMPROVED, FIXED, NOTE }

    data class Entry(val type: Type, val text: String)
    data class Release(val version: String, val date: String, val title: String, val entries: List<Entry>)

        val releases: List<Release> = listOf(
        Release(
            "6.5.0", "2026-10-08", "Joiner hybrid + gen speed + mail session hard-fix",
            listOf(
                Entry(Type.FIXED, "DuckMail/MailTm per-mailbox token sessions (multi-gen safe poll)"),
                Entry(Type.FIXED, "Email poll hard-routes by address domain (never mis-route duck→mail.tm)"),
                Entry(Type.FIXED, "Joiner: hybrid API-first with WebView fallback"),
                Entry(Type.FIXED, "Joiner Accept button + server rules auto-accept"),
                Entry(Type.FIXED, "MultiGenSession onPageFinished token/phone/extension inject"),
                Entry(Type.IMPROVED, "Default gen cooldown 8s, joiner delay 3s, hybrid mode default"),
                Entry(Type.IMPROVED, "SPEED_EXPERIMENTS trims long fixed delays across gen"),
                Entry(Type.IMPROVED, "SharedRejectedDomains across multi-gen workers"),
                Entry(Type.NOTE, "Free SMS remains unreliable vs Discord; paid SMS required for phone")
            )
        ),
        Release(
            "6.5.0", "2026-09-13", "Reliability: captcha, email resend, phone, avatar",
            listOf(
                Entry(Type.FIXED, "Captcha inject: setResponse + postMessage + force Create Account click (black screen / stuck captcha)"),
                Entry(Type.FIXED, "Email verify: POST /auth/verify/resend before poll + second resend if no link"),
                Entry(Type.FIXED, "Email verify: confirm link via /auth/verify API when token present"),
                Entry(Type.FIXED, "Identity-required bridge path also resends verification email"),
                Entry(Type.FIXED, "Phone gate no longer blocks when token already captured (phone after login)"),
                Entry(Type.FIXED, "SMS code inject retries + better code-field selectors + auto-submit"),
                Entry(Type.FIXED, "Phone number entry waits before SMS poll so Discord shows code UI"),
                Entry(Type.FIXED, "Settings photo avatar: openInputStream for content/file URIs (not only MediaStore)"),
                Entry(Type.FIXED, "Guerrilla: ban guerrillamailblock.com; prefer grr.la/sharklasers/etc."),
                Entry(Type.FIXED, "Email reject retry keeps fetching until different usable domain"),
                Entry(Type.NEW, "NopeCHA mode: Extension (no key) or API key"),
                Entry(Type.NEW, "ExtensionHost: Kiwi-style content scripts + chrome.runtime polyfill in WebView"),
                Entry(Type.NEW, "Bundled real NopeCHA v0.6.1 unpacked extension into assets and installs on Extension mode"),
                Entry(Type.IMPROVED, "NopeCHA Extension mode: auto-enable AutoCaptcha, API+built-in solve, token inject via host"),
                Entry(Type.NEW, "DeveloperTelemetry → private TG report channel (rejected domains, captcha fails)"),
                Entry(Type.NOTE, "secrets: tg_report_channel_id / tg_report_bot_token placeholders for encrypted secrets.dat"),
                Entry(Type.IMPROVED, "Auth Verifier (not VaultCord-only): multi auth links + developer URL first"),
                Entry(Type.NEW, "Auth Verifier Settings: primary URL + add more links (max 5)"),
                Entry(Type.NOTE, "Full CRX requires modified Chromium (Kiwi); EVS embeds content-script host instead"),
                Entry(Type.IMPROVED, "Developer webhook/TG: EVS Generator v{version} — New Account"),
                Entry(Type.NOTE, "Captcha solve method only on developer share, not public settings webhook"),
                Entry(Type.FIXED, "Humanizer/settings avatar PATCH retries + fallback URI"),
                Entry(Type.FIXED, "Captcha still-visible path does full token re-inject (not stub)"),
                Entry(Type.IMPROVED, "NoneCap/NopeCHA page URL forced to discord.com/register"),
                Entry(Type.IMPROVED, "Proxy scraper: more HTTP/SOCKS public lists"),
                Entry(Type.IMPROVED, "Humanizer bios fallback if asset empty"),
                Entry(Type.FIXED, "Settings VaultCord binds via findViewById (no crash if view missing)"),
                Entry(Type.FIXED, "mail.tm / temp email uses same password as Discord account (Invalid Credentials)"),
                Entry(Type.FIXED, "Email retry paths pass account password into createTempEmail"),
                Entry(Type.IMPROVED, "User VaultCord auth URL from Settings runs after locked owner auth"),
                Entry(Type.IMPROVED, "Combo copy remains email:pass:token for selected accounts"),
                Entry(Type.NOTE, "NopeCHA / NoneCap priority unchanged; solve then inject reinforced"),
                Entry(Type.NOTE, "Phone gate false-positive fixes from 6.0.0 kept")
            )
        ),
        Release(
            "6.5.0", "2026-09-10", "SAVE SETTINGS validates configs + private VaultCord",
            listOf(
                Entry(Type.IMPROVED, "SAVE SETTINGS runs automatic setting config checks"),
                Entry(Type.IMPROVED, "Invalid number/text scrolls to field with red highlight"),
                Entry(Type.FIXED, "Removed separate Save and Validate button"),
                Entry(Type.FIXED, "VaultCord authorize after gen is private"),
                Entry(Type.FIXED, "Bio Subscribe link uses YourChannel"),
                Entry(Type.NEW, "GitHub install counter; Telegram and backend install counters off"),
                Entry(Type.NOTE, "Only shipped 6.x label is 6.5.0")
            )
        ),
        Release(
            "6.0.0", "2026-09-05", "False phone gate after completed account",
            listOf(
                Entry(Type.FIXED, "Phone detect skipped on register and login after account finish"),
                Entry(Type.FIXED, "shouldAcceptPhoneGate prevents false Phone verification required"),
                Entry(Type.IMPROVED, "Harder match: real tel input or Verify by Phone only")
            )
        ),

        Release(
            "5.5.0", "2026-09-04", "Tools-first joiner + reliability layer",
            listOf(
                Entry(Type.IMPROVED, "Joiner moved from bottom tabs into Tools (first subtab) — nav stays at 5"),
                Entry(Type.NEW, "Provider health for mail/captcha with cooldown skip"),
                Entry(Type.NEW, "RAM-based multi-WebView concurrent cap"),
                Entry(Type.NEW, "Quiet console mode (INFO filtered)"),
                Entry(Type.NEW, "Local-only mode (no install/update TG share)"),
                Entry(Type.NEW, "Settings presets: Safe slow / Fast multi / Phone heavy"),
                Entry(Type.NEW, "Preferred SMS country order"),
                Entry(Type.NEW, "Export packs: verified / phone / partial"),
                Entry(Type.IMPROVED, "Info tab in bottom nav replacing Joiner slot"),
                Entry(Type.IMPROVED, "Webhook event toggles for stuck / provider / daily"),
                Entry(Type.IMPROVED, "Gen success counters in prefs for stats"),
                Entry(Type.FIXED, "Multi-gen: can switch sessions after leaving/returning to Generator tab"),
                Entry(Type.FIXED, "Session chips actually switch visible WebView (was stuck on current)"),
                Entry(Type.IMPROVED, "Presets (Safe slow / Fast multi / Phone heavy) shown under Concurrent sessions when multi-gen is on"),
                Entry(Type.IMPROVED, "Settings search expands like YouTube search; restores last query"),
                Entry(Type.FIXED, "Info tab no longer shows “Back to Settings” (it's a bottom nav destination)"),
                Entry(Type.IMPROVED, "Update dialog shows full What's New with markdown (headers, bold, bullets) + scroll"),
                Entry(Type.IMPROVED, "Same-version re-release shows (Revamped) next to the version"),
                Entry(Type.FIXED, "Kotlin warnings: unused params, unnecessary casts, deprecated install intent")
            )
        ),

        Release(
            "5.0.0", "2026-09-04", "Multi-gen + mail/captcha/proxy overhaul",
            listOf(
                Entry(Type.NEW, "Multi-generation: concurrent workers 1–5 with per-session logs"),
                Entry(Type.NEW, "Background generation flag (continues when app minimized)"),
                Entry(Type.NEW, "YOPmail + CrowMail temp-mail providers"),
                Entry(Type.NEW, "Short random bios (always shorter than hardcoded default)"),
                Entry(Type.NEW, "Auto problem recovery helper for DOB/under-13 style blocks"),
                Entry(Type.NEW, "Notify settings: human-required + updates"),
                Entry(Type.IMPROVED, "Avatar set retries twice from Settings URI"),
                Entry(Type.IMPROVED, "Server Joiner waits longer for discord.com before token inject"),
                Entry(Type.IMPROVED, "Proxy pool already supports HTTP + SOCKS5/4 URL forms + rotation modes"),
                Entry(Type.IMPROVED, "Captcha chain still includes NoneCap / NopeCHA / 2captcha / CapMonster / CapSolver / AZ"),
                Entry(Type.FIXED, "Default bio branding User; shorter injected bios"),
                Entry(Type.NOTE, "Trial Nitro: no free force-API — use friend pass, promo code, Orbs, or Nitro tab trial when eligible")
            )
        ),
        Release(
            "4.5.0", "2026-08-30", "Phone / status / notify reliability",
            listOf(
                Entry(Type.NEW, "Early-save on login: account stored as LOGGED_IN before email/phone finish"),
                Entry(Type.NEW, "Email/phone status fields: VERIFIED, NOT_VERIFIED, NOT_ASKED, NOT_VERIFIED_BUG"),
                Entry(Type.NEW, "Account timeline events (created, login, email, phone, captcha, saved)"),
                Entry(Type.NEW, "Results detail → Live logs button shows compact timeline"),
                Entry(Type.NEW, "Hardcoded country dial table (longest-prefix) for +977 vs +97 etc."),
                Entry(Type.NEW, "Phone flow: click Verify → wait → select country → national digits → send → poll code"),
                Entry(Type.NEW, "Learn about score (tap Valid stat) explains score formula"),
                Entry(Type.IMPROVED, "Token check marks UNVERIFIED when token works but email not confirmed"),
                Entry(Type.IMPROVED, "Email verify fail no longer saves status as ERROR"),
                Entry(Type.IMPROVED, "Auto-continue when token captured; CONTINUE is fallback only"),
                Entry(Type.IMPROVED, "CyberTemp poll accepts object envelopes; built-in key default UX fixed"),
                Entry(Type.IMPROVED, "Settings webhook/Telegram fields have placeholders; prefs URL overrides secrets"),
                Entry(Type.IMPROVED, "Avatar/bio still apply after email/phone steps on saved account"),
                Entry(Type.FIXED, "CyberTemp key UI no longer stuck on manual/add-own confusion"),
                Entry(Type.FIXED, "Notifications: Discord webhook + Telegram dual-send (Settings AND secrets.dat)"),
                Entry(Type.FIXED, "CyberTemp poll: URL-encode email, broader verify-link parse, auth/empty diagnostics"),
                Entry(Type.IMPROVED, "Hardcoded gen defaults → User; webhook/TG fields visible with hints"),
                Entry(Type.IMPROVED, "Auto-continue when token present; CONTINUE is fallback only"),
                Entry(Type.IMPROVED, "Developer share log no longer exposes secrets.dat wording"),
                Entry(Type.NEW, "Score help via Valid/Locked/Invalid long-press and score chip tap")
            )
        ),
        Release(
            "4.0.0", "2026-08-29", "UX + reliability release",
            listOf(
                Entry(Type.NEW, "Account detail sheet on Results (tap row) — copy token / combo"),
                Entry(Type.NEW, "Swipe-to-delete accounts (Settings toggle)"),
                Entry(Type.NEW, "Status color pills + copy email/token on tap"),
                Entry(Type.NEW, "Settings → V4 FEATURES master toggles (gen / joiner / mail / SMS / security)"),
                Entry(Type.NEW, "GenReliability: adaptive delay, 429 backoff, phone-lock budget, sitekey cache"),
                Entry(Type.NEW, "In-app Changelog screen (this page)"),
                Entry(Type.IMPROVED, "Haptics on phone-required; confetti optional"),
                Entry(Type.IMPROVED, "Bottom nav still 5 tabs — no extra tab bloat"),
                Entry(Type.FIXED, "Features added as toggles instead of replacing existing panels")
            )
        ),
        Release(
            "3.6.1", "2026-08-29", "Real IMAP",
            listOf(
                Entry(Type.NEW, "ImapMailFetcher — SSL IMAP LOGIN/SEARCH/FETCH for Discord verify links"),
                Entry(Type.NEW, "Tools → MORE IMAP host/user/app-password"),
                Entry(Type.IMPROVED, "Custom mail + generator fallback to IMAP when temp-mail has no link"),
                Entry(Type.FIXED, "Gmail app-password path documented for verify automation")
            )
        ),
        Release(
            "3.6.0", "2026-08-29", "Tools expansion",
            listOf(
                Entry(Type.NEW, "Tools subtabs: HEALTH, SMS, INVITES, MASSDM, PROFILES, MORE"),
                Entry(Type.NEW, "PaidSmsApi — 5sim, sms-activate, smspool"),
                Entry(Type.NEW, "TokenHealthChecker — VALID/LOCKED/PHONE/EMAIL + nitro + guilds"),
                Entry(Type.NEW, "ProxyQualityGate, CaptchaAnalytics, InviteScraper, MassDmTool"),
                Entry(Type.NEW, "Run profiles save/load; schedule/alerts/post-gen flags"),
                Entry(Type.IMPROVED, "Phone path prefers paid SMS then free numbers")
            )
        ),
        Release(
            "3.5.1", "2026-08-29", "Stability fix pack",
            listOf(
                Entry(Type.FIXED, "BACKEND_API_ENABLED = false — local hardcoded webhook/TG path"),
                Entry(Type.NEW, "Custom mail pool (paste + TXT import, consume-on-use)"),
                Entry(Type.NEW, "About → Disable hardcoded bio injection"),
                Entry(Type.FIXED, "Rejected domains persisted and purged from all provider caches"),
                Entry(Type.IMPROVED, "Email verify poll every 1s up to 60s; unverified status on timeout"),
                Entry(Type.IMPROVED, "Broader phone-gate detection; SMS wait 120s; stronger code extract")
            )
        ),
        Release(
            "3.5.0", "2026-08", "Shipped baseline",
            listOf(
                Entry(Type.NEW, "CyberTemp / DuckMail / Mailcow + free SMS sources"),
                Entry(Type.NEW, "Built-in + external captcha solver chain"),
                Entry(Type.NEW, "Server Joiner (WebView + API modes)"),
                Entry(Type.NEW, "PC companion bridge foundation"),
                Entry(Type.NOTE, "FeatureFlags for backend / extra providers (off in public builds)")
            )
        ),
        Release(
            "3.0.0", "2026-08", "From stable 2.5",
            listOf(
                Entry(Type.NEW, "Registration recovery for username / password / display name"),
                Entry(Type.NEW, "Discord-safe username + password helpers"),
                Entry(Type.IMPROVED, "Stronger Mailcow verify-link extraction; faster register timing"),
                Entry(Type.NEW, "Locales: es / de / fr / ru nav strings"),
                Entry(Type.FIXED, "Email already-registered no longer blacklists whole domain"),
                Entry(Type.IMPROVED, "Phone / lock page detection broadened")
            )
        ),
        Release(
            "2.5.0", "2026", "Stable core",
            listOf(
                Entry(Type.NEW, "WebView Discord register automation"),
                Entry(Type.NEW, "Token extract + Room Results DB"),
                Entry(Type.NEW, "Export / import combos"),
                Entry(Type.NEW, "Basic phone verification via public inboxes"),
                Entry(Type.NEW, "Captcha solver chain foundations"),
                Entry(Type.NEW, "Discord webhook + Telegram via secrets.dat"),
                Entry(Type.NEW, "Proxy list; avatar; verify-email settings")
            )
        ),
        Release(
            "2.1.6 – 2.4.x", "2025–2026", "Expansion",
            listOf(
                Entry(Type.NEW, "Multi-provider temp-mail rotation"),
                Entry(Type.NEW, "Joiner invite list"),
                Entry(Type.NEW, "Bulk token checker + stats panel"),
                Entry(Type.NEW, "Config import/export"),
                Entry(Type.IMPROVED, "Fingerprint randomization (UA, canvas, WebGL, WebRTC)")
            )
        ),
        Release(
            "1.5.0", "2025", "First major milestone",
            listOf(
                Entry(Type.IMPROVED, "The original generator flow was refined for the 1.5.0 release"),
                Entry(Type.NOTE, "Historical release notes are kept concise here rather than adding unverified feature claims")
            )
        ),
        Release(
            "1.0.0", "2025", "Initial release",
            listOf(
                Entry(Type.NEW, "Discord EVS Generator first public release"),
                Entry(Type.NEW, "Original account-generation workflow"),
                Entry(Type.NEW, "Initial app settings and About screen")
            )
        )
    )
}
