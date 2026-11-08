package com.discord.evs.generator.features

/**
 * Master switches for experimental / not-yet-stable features.
 *
 * ALL of these must stay `false` for public APK builds unless noted.
 */
object FeatureFlags {

    /**
     * Vercel / generic Node backend for install count and account forwarding.
     * DISABLED — use GITHUB_INSTALL_COUNTER instead.
     */
    const val BACKEND_API_ENABLED: Boolean = false

    /**
     * Legacy Telegram channel message_id install counter.
     * DISABLED — use GITHUB_INSTALL_COUNTER instead.
     */
    const val TELEGRAM_INSTALL_COUNTER: Boolean = true

    /**
     * GitHub Pages + repository_dispatch install counter (active path).
     */
    const val GITHUB_INSTALL_COUNTER: Boolean = true

    /** Additional free temp-mail providers (no API key). */
    const val EXTRA_FREE_MAIL_PROVIDERS: Boolean = true

    /** Additional free public SMS / temp-number sources. */
    const val EXTRA_FREE_SMS_PROVIDERS: Boolean = true

    /** Aggressive timing / parallel fetch experiments. */
    const val SPEED_EXPERIMENTS: Boolean = true

    /** Require HMAC request signature in addition to API key (server must match). */
    const val BACKEND_HMAC_AUTH: Boolean = true

    /**
     * When true (and BACKEND_API_ENABLED), premium/gated actions must pass
     * BackendApi.checkEntitlement() before running. Public builds leave this false.
     */
    const val SERVER_ENTITLEMENT_GATE: Boolean = false

    /**
     * Local PC companion bridge (USB adb reverse / localhost API).
     * When true, code is compiled in; user still enables the server in Tools → PC Bridge.
     */
    const val PC_BRIDGE_ENABLED: Boolean = true
}
