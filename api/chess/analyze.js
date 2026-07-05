const { spawn } = require("node:child_process");
const path = require("node:path");
const readline = require("node:readline");

const MOVETIME_MS = 3000;
const TIMEOUT_MS = 15000;

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

function engineScriptPath() {
  const packageDir = path.dirname(require.resolve("stockfish/package.json"));
  return path.join(packageDir, "bin", "stockfish-18-lite-single.js");
}

function analyzeFen(fen) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [engineScriptPath()], {
      stdio: ["pipe", "pipe", "pipe"]
    });
    const info = {
      evaluation: null,
      mate: null,
      depth: 0,
      selDepth: 0,
      nodes: 0,
      principalVariation: []
    };
    const startedAt = Date.now();
    let phase = "uci";
    let finished = false;
    let stderr = "";

    const finish = (error, result) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      stdout.close();
      try { child.stdin.write("quit\n"); } catch (_) {}
      setTimeout(() => {
        if (!child.killed) child.kill("SIGKILL");
      }, 250).unref();
      if (error) reject(error); else resolve(result);
    };

    const send = (command) => {
      if (!finished && child.stdin.writable) child.stdin.write(`${command}\n`);
    };

    const timer = setTimeout(() => {
      send("stop");
      finish(new Error(`Stockfish timeout${stderr ? `: ${stderr.slice(-300)}` : ""}`));
    }, TIMEOUT_MS);

    const stdout = readline.createInterface({ input: child.stdout });
    stdout.on("line", (rawLine) => {
      const line = rawLine.trim();
      if (!line) return;
      if (line.startsWith("info ")) parseInfo(line, info);

      if (phase === "uci" && line === "uciok") {
        send("setoption name Hash value 64");
        send("setoption name MultiPV value 1");
        send("setoption name Ponder value false");
        send("ucinewgame");
        send("isready");
        phase = "ready";
        return;
      }

      if (phase === "ready" && line === "readyok") {
        send(`position fen ${fen}`);
        send(`go movetime ${MOVETIME_MS}`);
        phase = "search";
        return;
      }

      if (phase === "search" && line.startsWith("bestmove ")) {
        const match = line.match(/^bestmove\s+(\S+)(?:\s+ponder\s+(\S+))?/);
        if (!match) {
          finish(new Error("Invalid bestmove response"));
          return;
        }
        finish(null, {
          bestMove: match[1],
          ponder: match[2] || null,
          ...info,
          timeMs: Date.now() - startedAt
        });
      }
    });

    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });

    child.once("error", (error) => finish(error));
    child.once("exit", (code, signal) => {
      if (!finished) {
        finish(new Error(`Stockfish exited before bestmove (code=${code}, signal=${signal})${stderr ? `: ${stderr.slice(-300)}` : ""}`));
      }
    });

    send("uci");
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
    const result = await analyzeFen(fen.trim());
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
