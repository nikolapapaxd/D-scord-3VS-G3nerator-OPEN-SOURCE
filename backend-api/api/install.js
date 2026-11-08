const {
  cors,
  requireAuth,
  readJson,
  readEnv,
} = require("./_lib");

/**
 * Same behavior as the old in-app InstallCounter, secrets only on server:
 *   TG_INSTALL_BOT_TOKEN / TG_BOT_TOKEN
 *   TG_INSTALL_CHANNEL_ID / TG_CHANNEL_ID
 *
 * The counter is maintained by this endpoint. Telegram message IDs are not
 * install counts, and getUpdates is only a pending-update queue (not history).
 * POST records one install event and GET returns the stored value.
 */

function tokens() {
  return {
    bot: readEnv("TG_INSTALL_BOT_TOKEN") || readEnv("TG_BOT_TOKEN"),
    chat: readEnv("TG_INSTALL_CHANNEL_ID") || readEnv("TG_CHANNEL_ID"),
  };
}

// Set INSTALL_COUNT_FILE to a durable mounted path when running outside Vercel.
// Vercel's /tmp directory is instance-local and may reset between invocations.
const COUNT_FILE = process.env.INSTALL_COUNT_FILE || "/tmp/evs_install_count.json";

function memoryCount() {
  return Number(global.__evsInstallCount) || 0;
}

function setMemoryCount(n) {
  global.__evsInstallCount = Math.max(memoryCount(), Number(n) || 0);
}

function loadInstallCount() {
  try {
    const raw = require("fs").readFileSync(COUNT_FILE, "utf8");
    const j = JSON.parse(raw);
    const n = Number(j.count);
    if (Number.isFinite(n) && n >= 0) {
      setMemoryCount(n);
      return n;
    }
  } catch (_) {
  }
  return memoryCount();
}

function saveInstallCount(n) {
  setMemoryCount(n);
  try {
    require("fs").writeFileSync(
      COUNT_FILE,
      JSON.stringify({ count: n, updated: Date.now() }),
      "utf8"
    );
  } catch (_) {}
}


async function tgApi(bot, method, body) {
  const r = await fetch(`https://api.telegram.org/bot${bot}/${method}`, {
    method: body ? "POST" : "GET",
    headers: body ? { "Content-Type": "application/json" } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  const j = await r.json().catch(() => ({}));
  return { http: r.status, ...j };
}

async function postInstall(bot, chatId, device, version, event, versionCode, fromVersionCode) {
  const isUpdate = String(event || "").toLowerCase() === "update";
  const head = isUpdate ? "🔄 Update" : "🆕 Install";
  let fromBit = "";
  if (isUpdate && fromVersionCode) {
    fromBit = "\nFrom code: " + fromVersionCode + " → " + (versionCode || "?");
  } else if (versionCode) {
    fromBit = "\nCode: " + versionCode;
  }
  const text =
    head + " · device `" + (device || "?") + "`\nVer: " + (version || "?") + fromBit;
  const j = await tgApi(bot, "sendMessage", {
    chat_id: chatId,
    text,
    parse_mode: "Markdown",
    disable_web_page_preview: true,
  });
  if (!j.ok) {
    return { ok: false, count: 0, error: j.description || "sendMessage failed" };
  }
  return { ok: true };
}

module.exports = async (req, res) => {
  cors(res);
  if (req.method === "OPTIONS") return res.status(204).end();
  if (!requireAuth(req, res, "/api/install")) return;

  const { bot, chat } = tokens();
  if (!bot || !chat) {
    return res.status(200).json({
      ok: false,
      count: 0,
      error: "tg not configured — set TG_INSTALL_BOT_TOKEN and TG_INSTALL_CHANNEL_ID on Vercel",
    });
  }

  if (req.method === "GET") {
    const finalCount = loadInstallCount();
    return res.status(200).json({
      ok: true,
      count: finalCount,
      source: "stored",
    });
  }

  if (req.method !== "POST") {
    return res.status(405).json({ ok: false, error: "method not allowed" });
  }

  const body = await readJson(req);
  const device = String(body.device || "").slice(0, 64);
  const version = String(body.version || req.headers["x-app-version"] || "").slice(0, 32);
  const event = String(body.event || "install").slice(0, 16);
  const versionCode = body.versionCode != null ? Number(body.versionCode) : 0;
  const fromVersionCode = body.fromVersionCode != null ? Number(body.fromVersionCode) : 0;

  const posted = await postInstall(bot, chat, device, version, event, versionCode, fromVersionCode);
  const shouldCount = String(event || "").toLowerCase() !== "update";
  const previous = loadInstallCount();
  const after = posted.ok && shouldCount ? previous + 1 : previous;
  if (posted.ok && shouldCount) saveInstallCount(after);
  return res.status(200).json({
    ok: posted.ok,
    count: after,
    source: "stored",
    error: posted.ok ? undefined : posted.error,
  });
};
