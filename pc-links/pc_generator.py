"""
PC generation worker (ChromeDriver optional).

Modes:
  android_backend — email from phone bridge; browser work on PC
  pc_generation   — full ChromeDriver register loop on PC
  hybrid          — phone email + PC Chrome
"""
from __future__ import annotations

import random
import string
import time
from typing import Callable, Optional

from bridge_client import BridgeClient

LogFn = Callable[[str], None]


def _rand_user(n: int = 10) -> str:
    chars = string.ascii_lowercase + string.digits
    return "".join(random.choice(chars) for _ in range(n))


def _rand_pass(n: int = 14) -> str:
    upper = random.choice(string.ascii_uppercase)
    lower = random.choice(string.ascii_lowercase)
    digit = random.choice(string.digits)
    rest = "".join(random.choice(string.ascii_letters + string.digits + "!@#") for _ in range(max(0, n - 3)))
    return "".join(random.sample(list(upper + lower + digit + rest), n))


class PcGenerator:
    def __init__(
        self,
        client: BridgeClient,
        mode: str = "android_backend",
        captcha_mode: str = "manual",
        solver: str = "none",
        headless: bool = False,
        log: Optional[LogFn] = None,
    ):
        self.client = client
        self.mode = mode
        self.captcha_mode = captcha_mode
        self.solver = solver
        self.headless = headless
        self.log = log or (lambda m: print(m))
        self._stop = False

    def stop(self) -> None:
        self._stop = True

    def run(self, count: int, provider: str = "") -> None:
        self._stop = False
        self.log(f"Starting batch: count={count} mode={self.mode} captcha={self.captcha_mode}")
        for i in range(count):
            if self._stop:
                self.log("Stopped by user")
                break
            self.log(f"—— Account {i + 1}/{count} ——")
            try:
                self._one(provider)
            except Exception as e:
                self.log(f"Error: {e}")
            time.sleep(1.2)
        self.log("Batch finished")

    def _one(self, provider: str) -> None:
        # 1) Email from Android bridge (android_backend / hybrid) or local placeholder
        if self.mode in ("android_backend", "hybrid", "pc_generation"):
            try:
                mail = self.client.create_email(provider=provider)
                if not mail.get("ok"):
                    raise RuntimeError(mail.get("error") or "email create failed")
                email = mail["email"]
                self.log(f"Email from app: {email}")
            except Exception as e:
                self.log(f"Bridge email failed ({e}) — using local placeholder")
                email = f"{_rand_user()}@example.invalid"
        else:
            email = f"{_rand_user()}@example.invalid"

        password = _rand_pass()
        username = _rand_user(12)

        if self.mode == "android_backend":
            # PC only orchestrates; user can still open browser manually later
            self.log("Mode android_backend: requesting services from phone only")
            self.log(f"Would register: {email} / {username}")
            # Save skeleton so flow is testable end-to-end
            self.client.save_account(email, password, token="", status="PENDING", notes="pc-android_backend")
            self.log("Saved PENDING account to phone")
            return

        # PC browser generation
        driver = self._open_chrome()
        if driver is None:
            self.log("ChromeDriver not available — install selenium + chromedriver")
            self.client.save_account(email, password, status="PENDING", notes="pc-no-driver")
            return
        try:
            self.log("Opening discord.com/register …")
            driver.get("https://discord.com/register")
            time.sleep(2.5)
            self.log("Fill form fields (best-effort selectors)")
            self._try_fill(driver, email, username, password)
            if self.captcha_mode == "manual":
                self.log("Captcha mode MANUAL — solve in the Chrome window, waiting 90s…")
                time.sleep(90)
            elif self.captcha_mode == "extension":
                self.log(f"Captcha extension mode ({self.solver}) — waiting for extension 60s…")
                time.sleep(60)
            else:
                self.log("Captcha API mode — wire your solver key in a future step")
                time.sleep(15)
            token = self._extract_token(driver)
            status = "VALID" if token else "PENDING"
            self.client.save_account(email, password, token=token or "", status=status, notes="pc-generation")
            self.log(f"Saved to phone ({status})")
        finally:
            try:
                driver.quit()
            except Exception:
                pass

    def _open_chrome(self):
        try:
            from selenium import webdriver
            from selenium.webdriver.chrome.options import Options
        except ImportError:
            return None
        opts = Options()
        opts.add_argument("--disable-blink-features=AutomationControlled")
        opts.add_argument("--user-data-dir=./evs-chrome-profile")
        if self.headless:
            opts.add_argument("--headless=new")
        # Extension load (user places unpacked extension under ./extensions/nopecha)
        import os
        ext = os.path.abspath("./extensions/nopecha")
        if self.captcha_mode == "extension" and os.path.isdir(ext):
            opts.add_argument(f"--load-extension={ext}")
            self.log(f"Loading extension from {ext}")
        try:
            return webdriver.Chrome(options=opts)
        except Exception as e:
            self.log(f"Chrome start failed: {e}")
            return None

    def _try_fill(self, driver, email: str, username: str, password: str) -> None:
        from selenium.webdriver.common.by import By
        mapping = [
            ('input[name="email"]', email),
            ('input[name="username"]', username),
            ('input[name="password"]', password),
        ]
        for sel, val in mapping:
            try:
                el = driver.find_element(By.CSS_SELECTOR, sel)
                el.clear()
                el.send_keys(val)
            except Exception:
                pass

    def _extract_token(self, driver) -> str:
        try:
            token = driver.execute_script(
                "try { var t = localStorage.getItem('token'); return t ? t.replace(/\"/g,'') : ''; } catch(e) { return ''; }"
            )
            return token or ""
        except Exception:
            return ""
