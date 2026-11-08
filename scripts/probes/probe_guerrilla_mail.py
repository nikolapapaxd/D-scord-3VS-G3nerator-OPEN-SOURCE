#!/usr/bin/env python3
"""Probe Guerrilla Mail public API until a usable (non-block) domain appears.

Current finding (2026-09): every endpoint returns @guerrillamailblock.com only.
Discord rejects that domain. EVS refuses it and rotates providers + reports TG.

Usage:
  python3 scripts/probes/probe_guerrilla_mail.py
"""
from __future__ import annotations

import json
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = "https://api.guerrillamail.com/ajax.php"
BANNED = {"guerrillamailblock.com", "guerrillamailblock.org"}
PREFERRED = [
    "grr.la", "sharklasers.com", "guerrillamail.com", "guerrillamail.net",
    "guerrillamail.org", "guerrillamail.biz", "pokemail.net", "spam4.me",
]


def call(params: dict) -> dict | str:
    q = urllib.parse.urlencode(params)
    url = f"{BASE}?{q}"
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "Mozilla/5.0", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=25) as resp:
            raw = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code}"
    except Exception as e:
        return f"ERR {e}"
    if not raw.strip():
        return ""
    try:
        return json.loads(raw)
    except Exception:
        return raw[:300]


def main() -> int:
    print("=== Guerrilla Mail API probe ===")
    sess = call({"f": "get_email_address", "ip": "127.0.0.1", "agent": "Mozilla_foo"})
    print("get_email_address:", sess)
    if not isinstance(sess, dict):
        print("FAIL: no session")
        return 1
    sid = sess.get("sid_token", "")
    email = sess.get("email_addr", "")
    domain = email.split("@")[-1].lower() if "@" in email else ""
    print(f"session domain: {domain}  banned={domain in BANNED}")

    ok = []
    for d in PREFERRED:
        r = call({
            "f": "set_email_user",
            "email_user": "evsprobe123",
            "sid_token": sid,
            "lang": "en",
            "domain": d,
        })
        if isinstance(r, dict):
            e = r.get("email_addr", "")
            got = e.split("@")[-1].lower() if "@" in e else ""
            print(f"  set domain={d} → {e} (got={got})")
            if got and got not in BANNED:
                ok.append(got)
        else:
            print(f"  set domain={d} → {r}")

    scramble = call({"f": "scramble_email_address", "sid_token": sid})
    print("scramble:", scramble)

    print()
    if ok:
        print("USABLE DOMAINS:", sorted(set(ok)))
        return 0
    print("RESULT: no usable domains — API forces guerrillamailblock.com")
    print("EVS must refuse Guerrilla and rotate / report TEMPMAIL_NO_DOMAINS")
    return 2


if __name__ == "__main__":
    sys.exit(main())
