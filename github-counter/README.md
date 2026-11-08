# EVS GitHub Install Counter

Host this folder as a **GitHub Pages** site (or any static host that serves the `data/` JSON).

## Layout

```
github-counter/
  index.html              # public totals
  users.html              # recent install/update events
  data/installs.json      # totals
  data/users_or_info.json # event log
  .github/workflows/increment.yml  # authenticated bump
```

## Setup (once)

1. Create a **public** repo, e.g. `installation-counter`.
2. Copy everything in this folder to the repo root (or `/docs`).
3. Enable **GitHub Pages** → Deploy from branch `main` / `/ (root)`.
4. Site URL example: `https://USERNAME.github.io/installation-counter/`
5. Create a **fine-grained PAT** or classic PAT with `repo` scope on that repo only.
   - App uses **repository_dispatch** (no PAT inside APK for writes — use a short-lived edge proxy **or** embed a dispatch token that can only trigger this workflow).
6. In the Android app `FeatureFlags` / `InstallCounter` config:
   - `GITHUB_COUNTER_OWNER` = your username
   - `GITHUB_COUNTER_REPO` = `installation-counter`
   - `GITHUB_DISPATCH_TOKEN` = PAT with `actions:write` (or repo) on that repo only

## How the app reports

`POST https://api.github.com/repos/{owner}/{repo}/dispatches`

```json
{
  "event_type": "evs_install",
  "client_payload": {
    "type": "install",
    "device": "sha256prefix",
    "version": "6.5.0"
  }
}
```

Workflow bumps JSON and commits. Pages refreshes from `data/installs.json`.

## Read totals (no auth)

`GET https://USERNAME.github.io/installation-counter/data/installs.json`
