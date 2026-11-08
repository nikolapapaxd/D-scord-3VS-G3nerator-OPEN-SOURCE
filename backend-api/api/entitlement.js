const { cors, requireAuth, readEnv } = require("./_lib");

module.exports = async (req, res) => {
  cors(res);
  if (req.method === "OPTIONS") return res.status(204).end();
  if (!requireAuth(req, res, "/api/entitlement")) return;
  if (req.method !== "GET" && req.method !== "POST") {
    return res.status(405).json({ ok: false, error: "method not allowed" });
  }
  // Simple gate — extend with license DB as needed
  const premium = readEnv("EVS_PREMIUM_DEFAULT", "1") === "1";
  return res.status(200).json({ ok: true, premium, service: "evs-entitlement" });
};
