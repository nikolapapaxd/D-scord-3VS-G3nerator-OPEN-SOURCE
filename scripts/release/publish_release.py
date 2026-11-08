#!/usr/bin/env python3
"""
Publish a Discord EVS Generator APK to a public GitHub Releases repo.

Setup:
  1. Create a PUBLIC GitHub repo (e.g. discord-evs-generator-releases)
  2. Create a Personal Access Token (classic) with `repo` or fine-grained:
     Contents: Read and write, Metadata: Read
  3. export GITHUB_TOKEN=ghp_...
  4. python publish_release.py --apk path/to/app-release.apk --version 5.0.0

This renames the APK to:
  Discord EVS Generator v{version}.apk
creates/updates a release tag v{version}, and uploads the asset.

The Android app checks:
  GET https://api.github.com/repos/{owner}/{repo}/releases/latest
No token is required in the app (public releases).
"""

# =============================================================================
# EDIT THESE — no need for environment variables
# =============================================================================
GITHUB_TOKEN_INLINE = ""          # paste ghp_... here
GITHUB_OWNER_INLINE = "yourname"          # e.g. YourName
GITHUB_REPO_INLINE = "discord-evs-generator-releases"
# =============================================================================

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path


def api(token: str, method: str, url: str, data: dict | None = None, raw: bytes | None = None, content_type: str | None = None):
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "User-Agent": "EVS-Release-Publisher",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    body = None
    if raw is not None:
        body = raw
        headers["Content-Type"] = content_type or "application/octet-stream"
    elif data is not None:
        body = json.dumps(data).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            raw_body = resp.read().decode()
            return resp.status, json.loads(raw_body) if raw_body else {}
    except urllib.error.HTTPError as e:
        err = e.read().decode(errors="replace")
        print("HTTP", e.code, err[:800], file=sys.stderr)
        raise


def main() -> int:
    ap = argparse.ArgumentParser(description="Publish EVS APK to GitHub Releases")
    ap.add_argument("--apk", required=True, help="Path to signed release APK")
    ap.add_argument("--version", required=True, help="Version e.g. 5.0.0 or v5.0.0")
    ap.add_argument("--owner", default=os.environ.get("GITHUB_OWNER") or GITHUB_OWNER_INLINE, help="GitHub user/org")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPO") or GITHUB_REPO_INLINE or "discord-evs-generator-releases")
    ap.add_argument("--token", default=os.environ.get("GITHUB_TOKEN") or GITHUB_TOKEN_INLINE)
    ap.add_argument("--notes", default="", help="Release notes markdown")
    ap.add_argument("--draft", action="store_true")
    args = ap.parse_args()

    token = args.token.strip()
    owner = args.owner.strip()
    repo = args.repo.strip()
    if not token:
        print("Set GITHUB_TOKEN or pass --token", file=sys.stderr)
        return 1
    if not owner:
        print("Set GITHUB_OWNER or pass --owner", file=sys.stderr)
        return 1

    version = args.version.strip().lstrip("vV")
    if not re.match(r"^\d+(\.\d+){0,3}$", version):
        print("Version should look like 5.0.0", file=sys.stderr)
        return 1
    tag = f"v{version}"
    apk_path = Path(args.apk)
    if not apk_path.is_file():
        print("APK not found:", apk_path, file=sys.stderr)
        return 1

    asset_name = f"Discord EVS Generator v{version}.apk"
    notes = args.notes or f"Discord EVS Generator {tag}"

    print(f"Creating release {tag} on {owner}/{repo}…")
    status, release = api(
        token,
        "POST",
        f"https://api.github.com/repos/{owner}/{repo}/releases",
        data={
            "tag_name": tag,
            "name": f"Discord EVS Generator {tag}",
            "body": notes,
            "draft": args.draft,
            "prerelease": False,
        },
    )
    # if exists, fetch by tag
    if status not in (200, 201):
        print("Unexpected status", status)
        return 1
    upload_url = release.get("upload_url", "").split("{")[0]
    release_id = release.get("id")
    print("Release id", release_id)

    # delete existing asset with same name if re-publishing
    assets = release.get("assets") or []
    for a in assets:
        if a.get("name") == asset_name:
            print("Deleting old asset", asset_name)
            api(token, "DELETE", f"https://api.github.com/repos/{owner}/{repo}/releases/assets/{a['id']}")

    print("Uploading", asset_name, "…")
    data = apk_path.read_bytes()
    url = f"{upload_url}?name={urllib.request.quote(asset_name)}"
    st, uploaded = api(token, "POST", url, raw=data, content_type="application/vnd.android.package-archive")
    print("Uploaded OK" if st in (200, 201) else st)
    print("browser_download_url:", uploaded.get("browser_download_url"))
    print("\nApp will see this via:")
    print(f"  https://api.github.com/repos/{owner}/{repo}/releases/latest")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
