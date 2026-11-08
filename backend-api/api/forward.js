const {
  cors,
  requireAuth,
  readJson,
  readEnv,
  tgSendMessage,
  discordWebhook,
} = require("./_lib");

/**
 * Server-side replacement for NotificationHelper + secrets.dat
 * Secrets only here:
 *   TG_BOT_TOKEN, TG_CHANNEL_ID
 *   DC_WEBHOOK_URL, DC_WEBHOOK_NAME, DC_WEBHOOK_PFP
 *
 * Body can be:
 *   { text }                          plain forward
 *   { email, password, token, ... }   account notify (app forwardAccount)
 */

function phoneLine(phone, phoneStatus) {
  if (phoneStatus === "VERIFIED") return `${phone || ""} (Verified)`;
  if (phoneStatus === "ASKED" && phone) return `${phone} (Asked, not confirmed)`;
  if (phoneStatus === "ASKED") return "Asked (no number obtained)";
  return "Not Asked";
}

function formatAccountText(b) {
  const email = b.email || "";
  const password = b.password || "";
  const token = b.token || "";
  const status = b.status || "";
  const verified = !!b.verified;
  const emailVerified = !!b.emailVerified;
  const phone = b.phone || "";
  const phoneStatus = b.phoneStatus || "NOT_ASKED";
  const tags = b.tags || "";
  const notes = b.notes || "";
  const createdAt = b.createdAt || "";

  let t = "";
  t += "**EVS Generator — New Account**\n\n";
  t += `📧 **Email:** \`${email}\`\n`;
  t += `🔑 **Password:** \`${password}\`\n`;
  t += `📱 **Number:** ${phoneLine(phone, phoneStatus)}\n`;
  t += `✅ **Email Verified:** ${emailVerified ? "Yes" : "No"}\n`;
  t += `🪙 **Token Valid:** ${verified ? "Yes" : "No"}\n`;
  t += `📋 **Status:** ${status}\n`;
  t += `🪙 **Token:** \`${token}\`\n`;
  if (createdAt) t += `⏰ **Created:** ${createdAt}\n`;
  if (tags) t += `🏷 **Tags:** ${tags}\n`;
  if (notes) t += `📝 **Notes:** ${notes}\n`;
  t += `🔗 **Combo:** \`${email}:${password}:${token}\``;
  return t.slice(0, 3900);
}

function formatDiscordEmbed(b) {
  return {
    embeds: [
      {
        title: "EVS Generator — New Account",
        color: 0x5865f2,
        fields: [
          { name: "Email", value: `\`${b.email || "?"}\``, inline: false },
          { name: "Password", value: `\`${b.password || "?"}\``, inline: true },
          { name: "Status", value: String(b.status || "?"), inline: true },
          { name: "Token", value: `\`${String(b.token || "").slice(0, 80)}\``, inline: false },
        ],
        timestamp: new Date().toISOString(),
      },
    ],
    username: readEnv("DC_WEBHOOK_NAME", "EVS Generator"),
    avatar_url: readEnv("DC_WEBHOOK_PFP") || undefined,
  };
}

async function discordEmbed(url, embedPayload) {
  if (!url) return { ok: false, error: "webhook not configured" };
  try {
    const r = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(embedPayload),
    });
    return { ok: r.ok || r.status === 204, status: r.status };
  } catch (e) {
    return { ok: false, error: e.message };
  }
}

module.exports = async (req, res) => {
  cors(res);
  if (req.method === "OPTIONS") return res.status(204).end();
  if (!requireAuth(req, res, "/api/forward")) return;
  if (req.method !== "POST") {
    return res.status(405).json({ ok: false, error: "method not allowed" });
  }

  const body = await readJson(req);
  const reason = (req.headers["x-evs-reason"] || body.reason || "forward").toString();

  const isAccount = !!(body.email || body.password || body.token);
  let text = String(body.text || body.message || "").trim();
  if (!text && isAccount) text = formatAccountText(body);
  if (!text) {
    return res.status(400).json({ ok: false, error: "empty message" });
  }

  const bot = readEnv("TG_BOT_TOKEN");
  const chat = readEnv("TG_CHANNEL_ID");
  const hook = readEnv("DC_WEBHOOK_URL");
  const mode = String(body.mode || "embed").toLowerCase();

  const missing = [];
  if (!bot || !chat) missing.push("TG_BOT_TOKEN/TG_CHANNEL_ID");
  if (!hook) missing.push("DC_WEBHOOK_URL");

  const tg = await tgSendMessage(bot, chat, text);
  let dc;
  if (isAccount && mode !== "plain" && hook) {
    dc = await discordEmbed(hook, formatDiscordEmbed(body));
  } else {
    dc = await discordWebhook(
      hook,
      text.replace(/[*`]/g, ""),
      readEnv("DC_WEBHOOK_NAME", "EVS Generator"),
      readEnv("DC_WEBHOOK_PFP")
    );
  }

  const ok = !!(tg.ok || dc.ok);
  return res.status(200).json({
    ok,
    reason,
    telegram: tg,
    discord: dc,
    error: ok
      ? undefined
      : missing.length
        ? `not configured: ${missing.join(", ")} — set on Vercel (same values as old secrets.dat)`
        : "send failed",
  });
};
