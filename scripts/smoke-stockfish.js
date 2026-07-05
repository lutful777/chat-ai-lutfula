const createStockfish = require("stockfish");

(async () => {
  const engine = await createStockfish("lite-single");

  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("UCI handshake timeout")), 30000);

    engine.listener = (rawLine) => {
      const line = String(rawLine).trim();
      if (line === "uciok") {
        clearTimeout(timer);
        resolve();
      }
    };

    engine.sendCommand("uci");
  });

  if (typeof engine.terminate === "function") engine.terminate();
  console.log("Stockfish UCI handshake passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
