#!/usr/bin/env python3
"""
Encrypt secrets/secrets.txt into app/src/main/res/raw/secrets.dat.

Uses the same format as the Android SecretsManager:
  AES-256-CBC + HMAC-SHA256 (Encrypt-then-MAC)
  PBKDF2-HMAC-SHA256, 100 000 rounds, dkLen=64 bytes
  File format: salt(16) || iv(16) || ciphertext || hmac(32)

Requires the cryptography package:
  pip install cryptography

If you prefer not to install Python packages, use the Node.js equivalent:
  node encrypt_secrets.js
"""

import hashlib
import hmac
import os
import sys

try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    from cryptography.hazmat.primitives import padding
    from cryptography.hazmat.backends import default_backend
except ImportError:
    print("ERROR: the 'cryptography' package is required.", file=sys.stderr)
    print("Install it with:  pip install cryptography", file=sys.stderr)
    print("Or use the Node.js script:  node encrypt_secrets.js", file=sys.stderr)
    sys.exit(1)

PASSPHRASE = "evs-opensource-secrets-v1"
ITERATIONS = 100_000

SECRETS_FILE = "secrets/secrets.txt"
OUT_FILE = "app/src/main/res/raw/secrets.dat"


def parse_secrets(text: str) -> dict:
    result = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        result[key.strip()] = value.strip()
    return result


def encrypt(plaintext: str, passphrase: str) -> bytes:
    salt = os.urandom(16)
    iv = os.urandom(16)
    derived = hashlib.pbkdf2_hmac(
        "sha256", passphrase.encode("utf-8"), salt, ITERATIONS, dklen=64
    )
    enc_key = derived[:32]
    mac_key = derived[32:]

    padder = padding.PKCS7(128).padder()
    padded = padder.update(plaintext.encode("utf-8")) + padder.finalize()

    cipher = Cipher(algorithms.AES(enc_key), modes.CBC(iv), backend=default_backend())
    encryptor = cipher.encryptor()
    ciphertext = encryptor.update(padded) + encryptor.finalize()

    blob = salt + iv + ciphertext
    mac = hmac.new(mac_key, blob, hashlib.sha256).digest()
    return blob + mac




def wrap_secv2(inner: bytes, passphrase: str, cert_sha256_hex: str) -> bytes:
    """Outer layer bound to APK signing cert SHA-256 (hex, lowercase).
    Format: SECV2\x01 || salt(16) || iv(16) || AES-CBC(inner) || HMAC-SHA256(32)
    Outer PBKDF2 password = passphrase + "|" + cert_sha256_hex
    """
    magic = b"SECV2" + bytes([1])
    salt = os.urandom(16)
    iv = os.urandom(16)
    outer_pass = (passphrase + "|" + cert_sha256_hex.strip().lower()).encode("utf-8")
    derived = hashlib.pbkdf2_hmac("sha256", outer_pass, salt, ITERATIONS, dklen=64)
    enc_key, mac_key = derived[:32], derived[32:]
    padder = padding.PKCS7(128).padder()
    padded = padder.update(inner) + padder.finalize()
    cipher = Cipher(algorithms.AES(enc_key), modes.CBC(iv), backend=default_backend())
    ct = cipher.encryptor().update(padded) + cipher.encryptor().finalize()
    body = magic + salt + iv + ct
    mac = hmac.new(mac_key, body, hashlib.sha256).digest()
    return body + mac


def main():
    if not os.path.exists(SECRETS_FILE):
        print(f"Create {SECRETS_FILE} first.")
        sys.exit(1)

    plaintext = open(SECRETS_FILE, "r", encoding="utf-8").read()
    secrets = parse_secrets(plaintext)

    required = ["dc_webhook_url", "tg_bot_token", "tg_channel_id"]
    placeholders = [k for k in required if secrets.get(k, "").endswith("REPLACE_ME")]
    if placeholders:
        print(
            f"WARNING: placeholders still present for {', '.join(placeholders)} — "
            f"replace them before release."
        )
    if not any(secrets.get(k, "").strip() for k in required):
        print("WARNING: all required secrets are empty; notifications will be disabled.")

    out = encrypt(plaintext, PASSPHRASE)
    cert = os.environ.get("EVS_SIGNING_CERT_SHA256", "").strip()
    if cert:
        out = wrap_secv2(out, PASSPHRASE, cert)
        print(f"SECV2 outer wrap applied (cert {cert[:16]}…)")
    else:
        print("NOTE: set EVS_SIGNING_CERT_SHA256=<sha256hex of release signing cert>")
        print("      to enable SECV2 outer wrap (secrets bound to your keystore).")
        print("      keytool -list -v -keystore app/release.keystore | findstr SHA256")

    os.makedirs(os.path.dirname(OUT_FILE), exist_ok=True)
    with open(OUT_FILE, "wb") as f:
        f.write(out)

    print(f"Encrypted {SECRETS_FILE} -> {OUT_FILE} ({len(out)} bytes)")
    print(f"PBKDF2 iterations: {ITERATIONS:,}")
    if any(v.endswith("REPLACE_ME") for v in secrets.values()):
        print("WARNING: REPLACE_ME placeholders still present — fill secrets.txt")
    else:
        print("secrets.txt values look filled (no REPLACE_ME placeholders).")


if __name__ == "__main__":
    main()
