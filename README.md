# Discord EVS Generator

Android app for automated Discord account registration flows: temp mail, captcha handling, optional phone verification, token capture, and optional webhook / Telegram forwarding.

This is the public source tree. No private keys, webhook URLs, bot tokens, or release keystores are included.

---

## What it does

- Registers Discord accounts through an in-app WebView
- Creates temporary email addresses (DuckMail, mail.tm, CyberTemp, and others)
- Polls the inbox for the Discord verification link and confirms it
- Optional phone step with free public SMS sources or paid providers (5sim / sms-activate / smspool)
- Captures the session token when Discord lands on `channels/@me`
- Saves results locally (email / password / token / status / phone / timeline)
- Optional Discord webhook + Telegram notifications when an account is stored
- In-app update check against a GitHub Releases repo you configure
- Optional install counter (Telegram channel markers and/or GitHub Pages dispatch)

---

## Requirements

| Tool | Version |
|------|---------|
| JDK | 17+ |
| Android SDK | API 34 (compile / target), build-tools 34.x |
| Android Studio | Hedgehog / Iguana or newer, **or** command-line SDK only |
| Python 3 | for `scripts/secrets/encrypt_secrets.py` (`pip install cryptography`) |
| Node.js | optional alternative: `scripts/secrets/encrypt_secrets.js` |

Confirm Java:

```bash
java -version
# should show 17+
```

---

## Build a downloadable APK (full path)

### 1. Get the source

```bash
git clone <your-fork-url>
cd Discord-EVS-Generator   # or whatever you named the folder
```

Or unzip the opensource archive and open that folder.

### 2. Open in Android Studio (optional but easiest)

- **File → Open** → select the repo root (the folder that contains `settings.gradle.kts` and `gradlew`)
- Let Gradle sync finish
- Install any SDK / build-tools prompts it shows (API 34)

### 3. (Recommended) Create your own signing keystore

Without this, release builds sign with the Android **debug** keystore (fine for testing, not ideal for public distribution).

```bash
keytool -genkeypair -v \
  -keystore app/release.keystore \
  -alias evs \
  -keyalg RSA -keysize 2048 -validity 10000
```

Set the password for Gradle:

```bash
# Windows PowerShell
$env:EVS_KEYSTORE_PASSWORD = "the-password-you-chose"

# Linux / macOS
export EVS_KEYSTORE_PASSWORD=the-password-you-chose
```

You can also put it in `gradle.properties` (do **not** commit this file if it has secrets):

```properties
EVS_KEYSTORE_PASSWORD=the-password-you-chose
```

### 4. Secrets (webhooks / Telegram) — optional but needed for notifications

```bash
cp secrets/secrets.txt.example secrets/secrets.txt
```

Edit `secrets/secrets.txt` and fill only what you need:

```text
dc_webhook_url=https://discord.com/api/webhooks/...
dc_webhook_name=EVS Generator
dc_webhook_pfp=

tg_bot_token=123456:ABC...
tg_channel_id=-100...

tg_install_bot_token=
tg_install_channel_id=

github_dispatch_token=

tg_report_channel_id=
tg_report_bot_token=
```

Encrypt into the app resource:

```bash
python3 scripts/secrets/encrypt_secrets.py
# or
node scripts/secrets/encrypt_secrets.js
```

This writes `app/src/main/res/raw/secrets.dat`.  
If you skip this step, the bundled blank `secrets.dat` loads with empty values — generation still works; webhooks/TG/install counter simply stay off until you set values in **Settings** or re-encrypt.

### 5. Point updates at *your* releases repo

Edit:

```text
app/src/main/java/com/discord/evs/generator/update/UpdateConfig.kt
```

```kotlin
const val GITHUB_OWNER = "your-github-username"
const val GITHUB_REPO  = "your-releases-repo"
```

Create that public repo, publish a Release with an `.apk` asset, and the in-app “Check for updates” path will see it. Leave the placeholders if you do not care about in-app updates.

### 6. Package id / version (optional branding)

In `app/build.gradle.kts`:

```kotlin
applicationId = "com.discord.evs.generator"  // change if you want a unique id
versionCode   = 65
versionName   = "6.5.0"
```

Changing `applicationId` means it installs **beside** other builds instead of overwriting them.

### 7. Assemble release

```bash
# Windows
gradlew.bat :app:assembleRelease

# Linux / macOS
./gradlew :app:assembleRelease
```

Output APK:

```text
app/build/outputs/apk/release/app-release.apk
```

Install on a device:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or copy the APK to the phone and open it (allow install from unknown sources).

### 8. Debug build (faster, debug-signed)

```bash
gradlew.bat :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

---

## How to modify the app

### Feature flags (on/off compile-time switches)

File:

```text
app/src/main/java/com/discord/evs/generator/features/FeatureFlags.kt
```

| Flag | Default | Meaning |
|------|---------|---------|
| `BACKEND_API_ENABLED` | `false` | Use remote backend for forward/install (off = local webhook/TG only) |
| `TELEGRAM_INSTALL_COUNTER` | `true` | Report installs/updates to a Telegram channel |
| `GITHUB_INSTALL_COUNTER` | `true` | Report via GitHub `repository_dispatch` + Pages JSON |
| `EXTRA_FREE_MAIL_PROVIDERS` | `true` | Extra free temp-mail backends |
| `EXTRA_FREE_SMS_PROVIDERS` | `true` | Extra free public SMS number scrapers |
| `SPEED_EXPERIMENTS` | `true` | Shorter delays in generation |
| `BACKEND_HMAC_AUTH` | `true` | HMAC on backend calls (only if backend enabled) |
| `SERVER_ENTITLEMENT_GATE` | `false` | Gate features behind backend entitlement |
| `PC_BRIDGE_ENABLED` | `true` | Compile PC companion bridge code |

Change a flag, rebuild. No secrets required for flags.

### Placeholders you should replace before a public release

| Location | Placeholder | What to put |
|----------|-------------|-------------|
| `UpdateConfig.kt` | `YOUR_GITHUB_USERNAME` / `YOUR_RELEASES_REPO` | Your releases repo |
| `secrets/secrets.txt` | empty keys | Your webhook / bot tokens |
| `app/release.keystore` | missing | Generate with `keytool` (see above) |
| `EVS_KEYSTORE_PASSWORD` | env / gradle prop | Password for that keystore |
| Default display name / email prefix | `"User"` / `"evsuser"` | Your brand strings in Prefs defaults / `AccountGenerator` / `TokenUtil` |
| Info tab links | `discord.gg/your-invite`, `@yourhandle` | Your community links in `fragment_info.xml` / `InfoFragment.kt` |
| Bio strings | empty or generic | `DiscordApi.kt` / humanizer settings |

### Notifications (Discord + Telegram)

Two layers:

1. **Encrypted secrets** (`secrets.dat`) — always available once encrypted.
2. **In-app Settings → Notifications** — user can set webhook URL / TG token without rebuilding.

Webhook modes (Settings): plain message, **embed** (default recommended — avoids Discord token scrubbers on plain content), or TXT file bulk.

Telegram: plain text posts (Markdown is avoided so tokens with underscores do not break the API call).

### Generation / mail / phone behavior

Main logic lives in:

```text
app/src/main/java/com/discord/evs/generator/automation/AccountGenerator.kt
```

Useful hooks:

- Email provider selection and fallbacks
- Phone gate serialization (email finishes before phone number fetch)
- CONTINUE button path (`onContinuePressed`) for stuck captcha / verify steps
- Early-save of accounts and timeline events

Mail providers and SMS helpers are under the same `automation/` package (`PhoneVerificationApi.kt`, mail clients, etc.).

### UI

- `ui/GeneratorFragment.kt` — main generate screen + WebView
- `ui/SettingsFragment.kt` — all user-facing toggles
- `ui/AccountsFragment` / results list — saved accounts
- Layouts under `app/src/main/res/layout/`

Change strings, colors, or visibility there, then rebuild.

### Install counter

- Telegram path: needs `tg_install_bot_token` + `tg_install_channel_id` in secrets.
- GitHub path: host the `github-counter/` folder as GitHub Pages, enable the workflow, put a limited PAT in `github_dispatch_token`.

Flags in `FeatureFlags.kt` turn each path on/off.

### In-app updates

- Checker: `update/UpdateChecker.kt` + `UpdateConfig.kt`
- FAB / multi-version browser: `update/UpdateManager.kt`
- Sample ntfy notify workflow: `.github/workflows/notify-release.yml` (copy into your **releases** repo, not necessarily this source repo)

### Optional backend

`backend-api/` is a small Node/Vercel-style forwarder. It is **off** by default (`BACKEND_API_ENABLED = false`). Only enable if you deploy it and understand the auth headers.

### PC companion

`pc-companion/` and `PC_BRIDGE_ENABLED` — local bridge for desktop tooling. Not required for a normal phone APK.

---

## Secrets encryption details

- Algorithm: AES-256-CBC + HMAC-SHA256 (encrypt-then-MAC)
- KDF: PBKDF2-HMAC-SHA256, 100 000 iterations
- Runtime passphrase is XOR-obfuscated in `SecretsManager.kt` and must match `PASSPHRASE` in `scripts/secrets/encrypt_secrets.py` (and the JS script)
- Opensource default passphrase: `evs-opensource-secrets-v1`  
  Change **both** the script passphrase and the `SecretsManager` arrays together if you rotate it.

After every edit to `secrets/secrets.txt`:

```bash
python3 scripts/secrets/encrypt_secrets.py
```

Then rebuild.

---

## Project layout

```text
app/                  Android application module
secrets/              secrets.txt (+ example) — never commit real values
scripts/secrets/      encrypt_secrets.py / encrypt_secrets.js
github-counter/       optional static install counter site + workflow
backend-api/          optional Node/Vercel forwarder (disabled by default)
pc-companion/         optional desktop companion helpers
pc-links/             optional PC link tools
captcha-train-host/   optional captcha training host stubs
native/               optional native helpers
tools/                packaging helpers (e.g. DPT) — not required for assembleRelease
.github/workflows/    sample notify-release workflow
```

---

## Common build failures

| Problem | Fix |
|---------|-----|
| `Could not decrypt secrets.dat` at runtime | Re-run encrypt script; ensure passphrase matches `SecretsManager` |
| SDK / build-tools not found | Install Android SDK 34 + build-tools 34 in SDK Manager |
| Java version errors | Use JDK 17 (not 8, not 21-only if AGP complains — 17 is the safe target) |
| Signing failure | Set `EVS_KEYSTORE_PASSWORD` or let it fall back to debug keystore |
| Duplicate class / dependency | `./gradlew clean :app:assembleRelease` |
| Windows `gradlew.bat` not found | Run from repo root; or `git update-index` if line endings broke the script |

---

## Privacy / safety

- Free public SMS inboxes are shared and are usually rejected by Discord. Prefer paid SMS for phone verification.
- Do not commit `secrets/secrets.txt` with real tokens, `app/release.keystore`, or `local.properties`.
- The app can store Discord tokens on device. Treat exports as credentials.

---

## License

Add a `LICENSE` file before publishing. None is bundled by default.

---

## Disclaimer

This project interacts with Discord’s registration and verification flows in ways that may violate Discord’s Terms of Service. You are responsible for how you use it. The maintainers provide source code only.
