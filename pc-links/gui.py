#!/usr/bin/env python3
"""
EVS PC Companion — dark GUI for hybrid Android bridge + PC generation.
Run:  python gui.py
"""
from __future__ import annotations

import subprocess
import threading
import tkinter as tk
from tkinter import ttk, messagebox, scrolledtext

from bridge_client import BridgeClient
from pc_generator import PcGenerator


class EvSGui(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("EVS PC Companion")
        self.geometry("980x680")
        self.minsize(860, 600)
        self.configure(bg="#0f1115")
        self._worker: PcGenerator | None = None
        self._thread: threading.Thread | None = None
        self._build_style()
        self._build()

    def _build_style(self):
        style = ttk.Style(self)
        try:
            style.theme_use("clam")
        except Exception:
            pass
        bg, panel, accent, text, muted = "#0f1115", "#1a1d24", "#5865F2", "#e8eaed", "#9aa0a6"
        style.configure(".", background=bg, foreground=text, fieldbackground=panel)
        style.configure("TFrame", background=bg)
        style.configure("Card.TFrame", background=panel)
        style.configure("TLabel", background=bg, foreground=text, font=("Segoe UI", 10))
        style.configure("Title.TLabel", background=bg, foreground=text, font=("Segoe UI", 16, "bold"))
        style.configure("Sub.TLabel", background=bg, foreground=muted, font=("Segoe UI", 9))
        style.configure("Card.TLabel", background=panel, foreground=text)
        style.configure("TButton", font=("Segoe UI", 10), padding=8)
        style.configure("Accent.TButton", background=accent, foreground="#fff")
        style.configure("TEntry", fieldbackground=panel, foreground=text)
        style.configure("TCombobox", fieldbackground=panel, foreground=text)
        style.configure("TCheckbutton", background=panel, foreground=text)
        style.configure("TLabelframe", background=panel, foreground=text)
        style.configure("TLabelframe.Label", background=panel, foreground=text, font=("Segoe UI", 10, "bold"))

    def _build(self):
        pad = {"padx": 12, "pady": 8}
        header = ttk.Frame(self)
        header.pack(fill="x", **pad)
        ttk.Label(header, text="EVS PC Companion", style="Title.TLabel").pack(anchor="w")
        ttk.Label(
            header,
            text="Android app = API backend · PC = Chrome generation · USB adb reverse",
            style="Sub.TLabel",
        ).pack(anchor="w")

        body = ttk.Frame(self)
        body.pack(fill="both", expand=True, **pad)

        left = ttk.Frame(body)
        left.pack(side="left", fill="y", padx=(0, 10))

        conn = ttk.LabelFrame(left, text="Connection", padding=12)
        conn.pack(fill="x", pady=(0, 10))
        ttk.Label(conn, text="Bridge URL", style="Card.TLabel").grid(row=0, column=0, sticky="w")
        self.url_var = tk.StringVar(value="http://127.0.0.1:8787")
        ttk.Entry(conn, textvariable=self.url_var, width=32).grid(row=0, column=1, sticky="ew", pady=4)
        ttk.Label(conn, text="Token", style="Card.TLabel").grid(row=1, column=0, sticky="w")
        self.token_var = tk.StringVar()
        ttk.Entry(conn, textvariable=self.token_var, width=32, show="•").grid(row=1, column=1, sticky="ew", pady=4)
        btns = ttk.Frame(conn)
        btns.grid(row=2, column=0, columnspan=2, sticky="ew", pady=(8, 0))
        ttk.Button(btns, text="Test connection", command=self.on_test).pack(side="left", padx=(0, 6))
        ttk.Button(btns, text="adb reverse", command=self.on_adb).pack(side="left")

        mode = ttk.LabelFrame(left, text="Mode", padding=12)
        mode.pack(fill="x", pady=(0, 10))
        self.mode_var = tk.StringVar(value="android_backend")
        for val, label in [
            ("android_backend", "Android backend (email/API from phone)"),
            ("pc_generation", "PC generation (ChromeDriver on PC)"),
            ("hybrid", "Hybrid (phone email + PC Chrome)"),
        ]:
            ttk.Radiobutton(mode, text=label, value=val, variable=self.mode_var).pack(anchor="w", pady=2)

        gen = ttk.LabelFrame(left, text="Generation", padding=12)
        gen.pack(fill="x", pady=(0, 10))
        ttk.Label(gen, text="Accounts", style="Card.TLabel").grid(row=0, column=0, sticky="w")
        self.count_var = tk.StringVar(value="5")
        ttk.Entry(gen, textvariable=self.count_var, width=8).grid(row=0, column=1, sticky="w", pady=4)
        ttk.Label(gen, text="Email provider", style="Card.TLabel").grid(row=1, column=0, sticky="w")
        self.provider_var = tk.StringVar(value="mail.tm")
        ttk.Combobox(
            gen,
            textvariable=self.provider_var,
            values=["mail.tm", "guerrilla", "1secmail", "tempmail.lol", "from-app"],
            width=18,
            state="readonly",
        ).grid(row=1, column=1, sticky="w", pady=4)
        ttk.Label(gen, text="Captcha", style="Card.TLabel").grid(row=2, column=0, sticky="w")
        self.captcha_var = tk.StringVar(value="manual")
        ttk.Combobox(
            gen,
            textvariable=self.captcha_var,
            values=["manual", "extension", "api"],
            width=18,
            state="readonly",
        ).grid(row=2, column=1, sticky="w", pady=4)
        ttk.Label(gen, text="Solver / extension", style="Card.TLabel").grid(row=3, column=0, sticky="w")
        self.solver_var = tk.StringVar(value="nopecha")
        ttk.Combobox(
            gen,
            textvariable=self.solver_var,
            values=["none", "nopecha", "2captcha", "capmonster", "capsolver"],
            width=18,
            state="readonly",
        ).grid(row=3, column=1, sticky="w", pady=4)
        self.headless_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(gen, text="Headless Chrome", variable=self.headless_var).grid(
            row=4, column=0, columnspan=2, sticky="w", pady=6
        )

        actions = ttk.Frame(left)
        actions.pack(fill="x", pady=10)
        ttk.Button(actions, text="▶  Start", command=self.on_start).pack(side="left", padx=(0, 8))
        ttk.Button(actions, text="■  Stop", command=self.on_stop).pack(side="left", padx=(0, 8))
        ttk.Button(actions, text="Pull accounts", command=self.on_pull).pack(side="left")

        right = ttk.Frame(body)
        right.pack(side="left", fill="both", expand=True)
        ttk.Label(right, text="Live log").pack(anchor="w")
        self.log = scrolledtext.ScrolledText(
            right,
            bg="#12151c",
            fg="#d7dadc",
            insertbackground="#fff",
            font=("Consolas", 10),
            relief="flat",
            borderwidth=0,
        )
        self.log.pack(fill="both", expand=True, pady=(6, 0))
        self._log("Ready. 1) Enable PC Bridge in the Android app  2) adb reverse  3) paste token  4) Test connection")

    def _log(self, msg: str):
        self.log.insert("end", msg + "\n")
        self.log.see("end")

    def _client(self) -> BridgeClient:
        return BridgeClient(self.url_var.get().strip(), self.token_var.get().strip())

    def on_test(self):
        try:
            c = self._client()
            st = c.status()
            self._log(f"Connected: {st}")
            cfg = c.get_config()
            self._log(f"Config from app: provider={cfg.get('emailProvider')} mode={cfg.get('mode')}")
            messagebox.showinfo("EVS", "Bridge OK")
        except Exception as e:
            self._log(f"Connection failed: {e}")
            messagebox.showerror("EVS", str(e))

    def on_adb(self):
        port = "8787"
        try:
            url = self.url_var.get()
            if ":" in url.rsplit("/", 1)[-1]:
                port = url.rstrip("/").rsplit(":", 1)[-1]
            cmd = ["adb", "reverse", f"tcp:{port}", f"tcp:{port}"]
            self._log("Running: " + " ".join(cmd))
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
            self._log((r.stdout or r.stderr or "adb ok").strip())
        except Exception as e:
            self._log(f"adb error: {e}")

    def on_start(self):
        if self._thread and self._thread.is_alive():
            messagebox.showwarning("EVS", "Already running")
            return
        try:
            count = max(1, int(self.count_var.get()))
        except ValueError:
            messagebox.showerror("EVS", "Invalid account count")
            return
        client = self._client()
        provider = self.provider_var.get()
        if provider == "from-app":
            provider = ""
        gen = PcGenerator(
            client,
            mode=self.mode_var.get(),
            captcha_mode=self.captcha_var.get(),
            solver=self.solver_var.get(),
            headless=self.headless_var.get(),
            log=lambda m: self.after(0, self._log, m),
        )
        self._worker = gen

        def run():
            try:
                # Push mode to phone
                try:
                    client.set_config(mode=self.mode_var.get())
                except Exception as e:
                    self.after(0, self._log, f"Config push skipped: {e}")
                gen.run(count, provider=provider)
            except Exception as e:
                self.after(0, self._log, f"Worker error: {e}")

        self._thread = threading.Thread(target=run, daemon=True)
        self._thread.start()

    def on_stop(self):
        if self._worker:
            self._worker.stop()
            self._log("Stop requested")

    def on_pull(self):
        try:
            data = self._client().list_accounts()
            accounts = data.get("accounts") or []
            self._log(f"Accounts on phone: {len(accounts)}")
            for a in accounts[-15:]:
                self._log(f"  {a.get('email')} | {a.get('status')}")
        except Exception as e:
            self._log(f"Pull failed: {e}")


if __name__ == "__main__":
    EvSGui().mainloop()
