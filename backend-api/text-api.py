#!/usr/bin/env python3
"""
text-api.py — tester for Discord EVS backend

  python text-api.py
  python text-api.py https://backend-api-sable-one.vercel.app
  python text-api.py --key "..." --base https://...
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_BASE = "https://vercel-api-flax-psi.vercel.app"
DEFAULT_KEY = "+Ty+c-D)G1:fAQjG+#+B<ppPo4(d)<9?((te.V[bPT1fM(1TgT};,RB}d=<$$K&1nYVOxONz`E%-+M6;&m3FJW>&66WAKtkR6T<d|e00HjT@!$+Me.^L35:MZqm<7Z9-gO43`jU@Npo^"

PKG = "com.discord.evs.generator"
CLIENT = "DiscordEVSGenerator"
VERSION = "3.5.1"


def load_key_from_dotenv() -> str | None:
    candidates = [
        Path(__file__).resolve().parent / ".env",
        Path(__file__).resolve().parent / "backend-api" / ".env",
        Path.cwd() / ".env",
        Path.cwd() / "backend-api" / ".env",
    ]
    for p in candidates:
        if not p.is_file():
            continue
        try:
            for line in p.read_text(encoding="utf-8", errors="replace").splitlines():
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if line.startswith("EVS_API_KEY="):
                    return line.split("=", 1)[1].strip().strip('"').strip("'")
        except OSError:
            pass
    return None


def hmac_hex(secret: str, message: str) -> str:
    return hmac.new(secret.encode("utf-8"), message.encode("utf-8"), hashlib.sha256).hexdigest()


def request(
    base: str,
    method: str,
    path: str,
    key: str,
    body: dict | None = None,
    extra_headers: dict | None = None,
    use_auth: bool = True,
) -> tuple[int, str]:
    url = base.rstrip("/") + path
    body_str = "" if body is None else json.dumps(body, separators=(",", ":"))
    headers = {
        "Accept": "application/json",
        "User-Agent": f"{CLIENT}/{VERSION}",
    }
    if body is not None:
        headers["Content-Type"] = "application/json; charset=utf-8"

    if use_auth:
        # Headers must be latin-1 / ASCII-safe
        try:
            key.encode("latin-1")
        except UnicodeEncodeError:
            return -1, "EVS_API_KEY contains non-ASCII characters; HTTP headers require ASCII. Use backend-api/.env key."

        ts = str(int(time.time()))
        path_for_hmac = path[:-3] if path.endswith(".js") else path
        payload = ts + method.upper() + path_for_hmac + body_str
        headers.update(
            {
                "X-EVS-Key": key,
                "X-EVS-Client": CLIENT,
                "X-App-Package": PKG,
                "X-App-Version": VERSION,
                "X-EVS-Timestamp": ts,
                "X-EVS-HMAC": hmac_hex(key, payload),
            }
        )
        if extra_headers:
            headers.update(extra_headers)

    data = None if body is None else body_str.encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method.upper())
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except Exception as e:
        return -1, str(e)


def banner(title: str) -> None:
    print("\n" + "=" * 60)
    print(title)
    print("=" * 60)


def show(code: int, body: str) -> None:
    print(f"HTTP {code}")
    try:
        print(json.dumps(json.loads(body), indent=2, ensure_ascii=False))
    except Exception:
        print(body)


def main() -> int:
    ap = argparse.ArgumentParser(description="Test EVS backend API")
    ap.add_argument("base", nargs="?", default=DEFAULT_BASE, help="API base URL")
    ap.add_argument("--key", default=None, help="EVS_API_KEY")
    ap.add_argument("--no-auth-tests", action="store_true")
    ap.add_argument("--set-count", type=int, default=None, help="Set install counter to N")
    args = ap.parse_args()

    base = args.base.rstrip("/")
    key = args.key or os.environ.get("EVS_API_KEY") or load_key_from_dotenv() or DEFAULT_KEY

    print(f"Base : {base}")
    print(f"Key  : {key[:10]}…{key[-8:]}  (len={len(key)})")

    # Discover health path (some deploys differ)
    banner("Probe health paths")
    health_path = None
    for p in ("/api/health.js", "/api/health", "/health"):
        code, body = request(base, "GET", p, key, use_auth=False)
        print(f"  {p} -> HTTP {code}")
        if code == 200:
            health_path = p
            show(code, body)
            break
    if not health_path:
        print("No health endpoint found (404). Check Vercel project root is backend-api/ and routes exist.")

    if not args.no_auth_tests:
        banner("BAD key on /api/entitlement (expect forbid / 401)")
        code, body = request(base, "GET", "/api/entitlement.js", "WRONG_KEY_ON_PURPOSE")
        show(code, body)

    for title, method, path, body, extra in [
        ("GET /api/entitlement", "GET", "/api/entitlement.js", None, None),
        ("POST /api/install", "POST", "/api/install.js", {"device": "text-api-py", "version": VERSION}, None),
        ("POST /api/forward", "POST", "/api/forward.js", {"text": "hello from text-api.py", "reason": "test_forward"}, {"X-EVS-Reason": "test_forward"}),
        ("POST /api/account", "POST", "/api/account.js", {
            "email": "test_evs_api@example.com",
            "password": "TestPass!23456",
            "token": "dummy.token.value",
            "reason": "account_created",
            "version": VERSION,
        }, {
            "X-EVS-Reason": "account_created",
            "X-EVS-Email": "test_evs_api@example.com",
            "X-EVS-Password": "TestPass!23456",
            "X-EVS-Token": "dummy.token.value",
        }),
        ("POST /api/account DUPLICATE", "POST", "/api/account.js", {
            "email": "test_evs_api@example.com",
            "password": "TestPass!23456",
            "reason": "account_created",
        }, {
            "X-EVS-Reason": "account_created",
            "X-EVS-Email": "test_evs_api@example.com",
            "X-EVS-Password": "TestPass!23456",
        }),
    ]:
        banner(title)
        code, body_text = request(base, method, path, key, body=body, extra_headers=extra)
        show(code, body_text)

    # Optional: python text-api.py --set-count 150
    if getattr(args, "set_count", None) is not None:
        banner(f"POST /api/install.js setCount={args.set_count}")
        code, body = request(
            base, "POST", "/api/install.js", key,
            body={"setCount": args.set_count},
        )
        show(code, body)

    banner("Done")
    print("If everything is 404: redeploy with Root Directory = backend-api")
    print("Then set Vercel env EVS_API_KEY to the same value as backend-api/.env")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
