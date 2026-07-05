const createStockfish = require("stockfish");

const MOVETIME_MS = 3000;
const TIMEOUT_MS = 60000;

function parseInfo(line, info) {
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

async function runStockfish(fen) {
  const engine = await createStockfish("lite-single");
  const startedAt = Date.now();
  const info = {
    evaluation: null,
    mate: null,
    depth: 0,
    selDepth: 0,
    nodes: 0,
    principalVariation: []
  };

  return new Promise((resolve, reject) => {
    let phase = "uci";
    let done = false;

    const closeEngine = () => {
      try {
        if (typeof engine.terminate === "function") engine.terminate();
      } catch (_) {}
    };

    const finish = (error, value) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      closeEngine();
      if (error) reject(error); else resolve(value);
    };

    const timer = setTimeout(() => {
      try { engine.sendCommand("stop"); } catch (_) {}
      finish(new Error("Stockfish analysis timeout"));
    }, TIMEOUT_MS);

    engine.listener = (rawLine) => {
      const line = String(rawLine).trim();
      if (!line) return;
      if (line.startsWith("info ")) parseInfo(line, info);

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
        if (!match) return finish(new Error("Invalid bestmove response"));
        finish(null, {
          bestMove: match[1],
          ponder: match[2] || null,
          ...info,
          timeMs: Date.now() - startedAt,
          movetimeMs: MOVETIME_MS
        });
      }
    };

    try {
      engine.sendCommand("uci");
    } catch (error) {
      finish(error);
    }
  });
}

module.exports = { runStockfish, MOVETIME_MS };
