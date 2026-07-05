const stockfishFactory = require("stockfish");

const MOVETIME_MS = 3000;
const TIMEOUT_MS = 12000;

function validFen(fen) {
  if (typeof fen !== "string" || fen.length > 180) return false;
  const fields = fen.trim().split(/\s+/);
  if (fields.length !== 6 || !/^[wb]$/.test(fields[1])) return false;
  const ranks = fields[0].split("/");
  return ranks.length === 8 && ranks.every((rank) => {
    if (!/^[prnbqkPRNBQK1-8]+$/.test(rank)) return false;
    let count = 0;
    for (const c of rank) count += /[1-8]/.test(c) ? Number(c) : 1;
    return count === 8;
  });
}

function updateInfo(line, info) {
  const depth = line.match(/\bdepth\s+(\d+)/);
  const selDepth = line.match(/\bseldepth\s+(\d+)/);
  const nodes = line.match(/\bnodes\s+(\d+)/);
  const cp = line.match(/\bscore\s+cp\s+(-?\d+)/);
  const mate = line.match(/\bscore\s+mate\s+(-?\d+)/);
  const pv = line.match(/\bpv\s+(.+)$/);

  if (depth) info.depth = Number(depth[1]);
  if (selDepth) info.selDepth = Number(selDepth[1]);
  if (nodes) info.nodes = Number(nodes[1]);
  if (cp) {
    info.evaluation = Number(cp[1]) / 100;
    info.mate = null;
  }
  if (mate) {
    info.mate = Number(mate[1]);
    info.evaluation = null;
  }
  if (pv) info.principalVariation = pv[1].trim().split(/\s+/).slice(0, 8);
}

async function analyze(fen) {
  const engine = await stockfishFactory("lite-single");
  const info = {
    evaluation: null,
    mate: null,
    depth: 0,
    selDepth: 0,
    nodes: 0,
    principalVariation: []
  };
  const startedAt = Date.now();

  try {
    return await new Promise((resolve, reject) => {
      let phase = "uci";
      let finished = false;

      const complete = (error, result) => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        if (error) reject(error); else resolve(result);
      };

      const timer = setTimeout(() => {
        engine.sendCommand("stop");
        complete(new Error("Stockfish timeout"));
      }, TIMEOUT_MS);

      engine.listener = (rawLine) => {
        const line = String(rawLine).trim();
        if (line.startsWith("info ")) updateInfo(line, info);

        if (phase === "uci" && line === "uciok") {
          engine.sendCommand("setoption name Hash value 64");
          engine.sendCommand("setoption name MultiPV value 1");
          engine.sendCommand("setoption name Ponder value false");
          engine.sendCommand("ucinewgame");
          engine.sendCommand("isready");
          phase = "ready";
        } else if (phase === "ready" && line === "readyok") {
          engine.sendCommand(`position fen ${fen}`);
          engine.sendCommand(`go movetime ${MOVETIME_MS}`);
          phase = "search";
        } else if (phase === "search" && line.startsWith("bestmove ")) {
          const match = line.match(/^bestmove\s+(\S+)(?:\s+ponder\s+(\S+))?/);
          if (!match) return complete(new Error("Invalid bestmove response"));
          complete(null, {
            bestMove: match[1],
            ponder: match[2] || null,
            ...info,
            timeMs: Date.now() - startedAt
          });
        }
      };

      engine.sendCommand("uci");
    });
  } finally {
    try { engine.sendCommand("quit"); } catch (_) {}
  }
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
    const result = await analyze(fen.trim());
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
