#!/usr/bin/env node
/**
 * Encrypt secrets/secrets.txt into app/src/main/res/raw/secrets.dat.
 *
 * Uses the same format as the Android SecretsManager:
 *   AES-256-CBC + HMAC-SHA256 (Encrypt-then-MAC)
 *   PBKDF2-HMAC-SHA256, 100 000 rounds, dkLen=64 bytes
 *   File format: salt(16) || iv(16) || ciphertext || hmac(32)
 *
 * This is a Node.js replacement for encrypt_secrets.py that uses the built-in
 * crypto module so the output is compatible with standard AES/CBC/PKCS5Padding.
 *
 * Usage:  node encrypt_secrets.js
 */

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PASSPHRASE = "evs-opensource-secrets-v1"secrets", "secrets.txt");
const OUT_FILE = path.join(ROOT, "app", "src", "main", "res", "raw", "secrets.dat");

function parseSecrets(text) {
  const result = {};
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    if (!trimmed.includes("=")) continue;
    const [k, ...v] = trimmed.split("=");
    result[k.trim()] = v.join("=").trim();
  }
  return result;
}

function main() {
  if (!fs.existsSync(SECRETS_FILE)) {
    console.error(`Create ${SECRETS_FILE} first.`);
    process.exit(1);
  }

  const plaintext = fs.readFileSync(SECRETS_FILE, "utf8");
  const secrets = parseSecrets(plaintext);

  const required = ["dc_webhook_url", "tg_bot_token", "tg_channel_id"];
  const missing = required.filter(
    (k) => (secrets[k] || "").endsWith("REPLACE_ME")
  );
  if (missing.length) {
    console.warn(
      `WARNING: placeholders still present for ${missing.join(", ")} — replace them before release.`
    );
  }
  if (!required.some((k) => (secrets[k] || "").trim())) {
    console.warn("WARNING: all required secrets are empty; notifications will be disabled.");
  }

  const salt = crypto.randomBytes(16);
  const iv = crypto.randomBytes(16);
  const derived = crypto.pbkdf2Sync(
    PASSPHRASE,
    salt,
    ITERATIONS,
    64,
    "sha256"
  );
  const encKey = derived.slice(0, 32);
  const macKey = derived.slice(32);

  const cipher = crypto.createCipheriv("aes-256-cbc", encKey, iv);
  const ciphertext = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);

  const blob = Buffer.concat([salt, iv, ciphertext]);
  const mac = crypto.createHmac("sha256", macKey).update(blob).digest();
  const out = Buffer.concat([blob, mac]);

  fs.mkdirSync(path.dirname(OUT_FILE), { recursive: true });
  fs.writeFileSync(OUT_FILE, out);

  console.log(`Encrypted ${SECRETS_FILE} -> ${OUT_FILE}`);
  console.log(`PBKDF2 iterations: ${ITERATIONS.toLocaleString()}`);
  console.log("Replace placeholders with real values before shipping.");
}

main();
