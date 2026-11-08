#!/usr/bin/env python3
"""
EVS PC Network Rotate Companion
--------------------------------
Phone Prefs:
  pcRotateHost  = this PC LAN IP (e.g. 192.168.1.10)
  pcRotatePort  = 9876
  pcRotateToken = optional shared secret
  adbIpSwitchEnabled / mullvadRotationEnabled = ON
  adbSerial = optional device serial

Endpoints:
  POST /v1/network/adb-rotate     {"serial":""}
  POST /v1/network/mullvad-rotate {"account":""}
  GET  /v1/health

Requires: adb on PATH, optional mullvad CLI.
"""
from __future__ import annotations

import json
import os
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = os.environ.get("EVS_ROTATE_HOST", "0.0.0.0")
PORT = int(os.environ.get("EVS_ROTATE_PORT", "9876"))
TOKEN = os.environ.get("EVS_ROTATE_TOKEN", "").strip()


def run(cmd: list[str], timeout: int = 30) -> tuple[int, str]:
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        out = (p.stdout or "") + (p.stderr or "")
        return p.returncode, out
    except Exception as e:
        return -1, str(e)


def adb_rotate(serial: str) -> dict:
    target = ["-s", serial] if serial else []
    steps = [
        ["adb", *target, "shell", "cmd", "connectivity", "airplane-mode", "enable"],
        ["adb", *target, "shell", "settings", "put", "global", "airplane_mode_on", "1"],
        ["adb", *target, "shell", "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE", "--ez", "state", "true"],
    ]
    logs = []
    for c in steps:
        code, out = run(c)
        logs.append(f"{' '.join(c)} -> {code} {out[:80]}")
    time.sleep(2.5)
    steps2 = [
        ["adb", *target, "shell", "cmd", "connectivity", "airplane-mode", "disable"],
        ["adb", *target, "shell", "settings", "put", "global", "airplane_mode_on", "0"],
        ["adb", *target, "shell", "am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE", "--ez", "state", "false"],
    ]
    for c in steps2:
        code, out = run(c)
        logs.append(f"{' '.join(c)} -> {code} {out[:80]}")
    # also try svc data bounce
    for c in (
        ["adb", *target, "shell", "su", "-c", "svc data disable"],
        ["adb", *target, "shell", "su", "-c", "svc data enable"],
    ):
        code, out = run(c, timeout=15)
        logs.append(f"{' '.join(c)} -> {code}")
    return {"ok": True, "logs": logs}


def mullvad_rotate(account: str) -> dict:
    logs = []
    if account:
        code, out = run(["mullvad", "account", "login", account])
        logs.append(f"login {code} {out[:80]}")
    code, out = run(["mullvad", "reconnect"])
    logs.append(f"reconnect {code} {out[:120]}")
    if code != 0:
        run(["mullvad", "disconnect"])
        time.sleep(1)
        code2, out2 = run(["mullvad", "connect"])
        logs.append(f"connect {code2} {out2[:120]}")
        return {"ok": code2 == 0, "logs": logs}
    return {"ok": code == 0, "logs": logs}


class H(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[rotate]", fmt % args)

    def _auth(self) -> bool:
        if not TOKEN:
            return True
        auth = self.headers.get("Authorization", "")
        return auth.replace("Bearer", "").strip() == TOKEN

    def _json(self, code: int, obj: dict):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path.startswith("/v1/health"):
            self._json(200, {"ok": True, "service": "evs-network-rotate"})
        else:
            self._json(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if not self._auth():
            self._json(401, {"ok": False, "error": "unauthorized"})
            return
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n).decode() if n else "{}"
        try:
            body = json.loads(raw) if raw else {}
        except Exception:
            body = {}
        if self.path.startswith("/v1/network/adb-rotate"):
            self._json(200, adb_rotate(str(body.get("serial") or "")))
        elif self.path.startswith("/v1/network/mullvad-rotate"):
            self._json(200, mullvad_rotate(str(body.get("account") or "")))
        else:
            self._json(404, {"ok": False, "error": "not found"})


if __name__ == "__main__":
    print(f"EVS network rotate companion on {HOST}:{PORT} token={'set' if TOKEN else 'none'}")
    ThreadingHTTPServer((HOST, PORT), H).serve_forever()
