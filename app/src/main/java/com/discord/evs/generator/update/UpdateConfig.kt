package com.discord.evs.generator.update

/**
 * Public GitHub repo used for in-app update checks (no token required).
 * Point these at your own releases repository before shipping.
 */
object UpdateConfig {
    const val GITHUB_OWNER = "YOUR_GITHUB_USERNAME"
    const val GITHUB_REPO = "YOUR_RELEASES_REPO"
    const val API_LATEST =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    const val API_RELEASES =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=30"
    const val USER_AGENT = "DiscordEVSGenerator-Updater"
}
