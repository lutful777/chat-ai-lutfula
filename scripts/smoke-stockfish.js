const createStockfish = require("stockfish");

(async () => {
  const engine = await createStockfish("lite-single");
  if (!engine || typeof engine.sendCommand !== "function") {
    throw new Error("Stockfish engine did not initialize correctly");
  }
  if (typeof engine.terminate === "function") engine.terminate();
  console.log("Stockfish initialization passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
