"""HTTP client for the Android EVS PC bridge (localhost after adb reverse)."""
from __future__ import annotations

import requests
from typing import Any, Optional


class BridgeClient:
    def __init__(self, base_url: str = "http://127.0.0.1:8787", token: str = ""):
        self.base_url = base_url.rstrip("/")
        self.token = token.strip()
        self.session = requests.Session()
        self.session.headers.update({"Content-Type": "application/json"})
        if self.token:
            self.session.headers["Authorization"] = f"Bearer {self.token}"

    def set_token(self, token: str) -> None:
        self.token = token.strip()
        self.session.headers["Authorization"] = f"Bearer {self.token}"

    def _url(self, path: str) -> str:
        return f"{self.base_url}{path}"

    def status(self) -> dict[str, Any]:
        r = self.session.get(self._url("/v1/status"), timeout=5)
        r.raise_for_status()
        return r.json()

    def get_config(self) -> dict[str, Any]:
        r = self.session.get(self._url("/v1/config"), timeout=8)
        r.raise_for_status()
        return r.json()

    def set_config(self, **kwargs: Any) -> dict[str, Any]:
        r = self.session.post(self._url("/v1/config"), json=kwargs, timeout=8)
        r.raise_for_status()
        return r.json()

    def create_email(self, provider: str = "", local_part: str = "") -> dict[str, Any]:
        payload = {}
        if provider:
            payload["provider"] = provider
        if local_part:
            payload["localPart"] = local_part
        r = self.session.post(self._url("/v1/email/create"), json=payload, timeout=30)
        r.raise_for_status()
        return r.json()

    def save_account(
        self,
        email: str,
        password: str,
        token: str = "",
        status: str = "VALID",
        **extra: Any,
    ) -> dict[str, Any]:
        payload = {
            "email": email,
            "password": password,
            "token": token,
            "status": status,
            **extra,
        }
        r = self.session.post(self._url("/v1/account/save"), json=payload, timeout=15)
        r.raise_for_status()
        return r.json()

    def list_accounts(self) -> dict[str, Any]:
        r = self.session.get(self._url("/v1/accounts"), timeout=15)
        r.raise_for_status()
        return r.json()
