const { runStockfish, MOVETIME_MS } = require("./stockfish-runner");

function validFen(fen) {
  if (typeof fen !== "string" || fen.length > 180) return false;
  const fields = fen.trim().split(/\s+/);
  if (fields.length !== 6 || !/^[wb]$/.test(fields[1])) return false;
  const ranks = fields[0].split("/");
  return ranks.length === 8 && ranks.every((rank) => {
    if (!/^[prnbqkPRNBQK1-8]+$/.test(rank)) return false;
    let count = 0;
    for (const char of rank) count += /[1-8]/.test(char) ? Number(char) : 1;
    return count === 8;
  });
}

module.exports = async function handler(req, res) {
  res.setHeader("Cache-Control", "no-store");

  if (req.method !== "POST") {
    return res.status(405).json({ error: "Method not allowed" });
  }

  const { fen, requestId } = req.body || {};
  if (!validFen(fen) || typeof requestId !== "string" || requestId.length > 100) {
    return res.status(400).json({ error: "Valid FEN and requestId are required" });
  }

  try {
    const result = await runStockfish(fen.trim());
    return res.status(200).json({
      requestId,
      fen: fen.trim(),
      movetimeMs: MOVETIME_MS,
      ...result
    });
  } catch (error) {
    return res.status(503).json({
      error: "Stockfish analysis failed",
      message: error instanceof Error ? error.message : "Unknown error"
    });
  }
};
