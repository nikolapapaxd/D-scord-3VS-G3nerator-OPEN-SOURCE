const { cors } = require("./_lib");

module.exports = async (req, res) => {
  cors(res);
  if (req.method === "OPTIONS") return res.status(204).end();
  return res.status(200).json({
    ok: true,
    service: "evs-backend",
    version: "3.5.7",
    ts: Date.now(),
    note: "secrets server-side only; mirrors local install/forward/account",
  });
};
