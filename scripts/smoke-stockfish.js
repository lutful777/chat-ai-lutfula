const handler = require("../api/chess/analyze.js");

const req = {
  method: "POST",
  body: {
    fen: "7k/8/8/8/8/8/6K1/7R w - - 0 1",
    requestId: "smoke-test"
  }
};

let statusCode = 200;
let payload;
const res = {
  setHeader() {},
  status(code) {
    statusCode = code;
    return this;
  },
  json(value) {
    payload = value;
    return value;
  }
};

(async () => {
  await handler(req, res);
  console.log(JSON.stringify({ statusCode, payload }, null, 2));

  if (statusCode !== 200) throw new Error(`Expected HTTP 200, got ${statusCode}`);
  if (payload.movetimeMs !== 3000) throw new Error("movetimeMs must be 3000");
  if (!/^[a-h][1-8][a-h][1-8][qrbn]?$/.test(payload.bestMove || "")) {
    throw new Error(`Invalid bestMove: ${payload.bestMove}`);
  }
  if (payload.bestMove === "e2e4") throw new Error("Endpoint still returns the old hard-coded move");
  if (!Number.isFinite(payload.depth) || payload.depth < 1) throw new Error("Missing search depth");
  if (!Number.isFinite(payload.timeMs) || payload.timeMs < 2500) {
    throw new Error(`Stockfish stopped too early: ${payload.timeMs}ms`);
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
