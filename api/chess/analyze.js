import { exec } from 'child_process';
import util from 'util';

const execPromise = util.promisify(exec);

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });
  
  const { fen, requestId } = req.body;
  if (!fen || !requestId) return res.status(400).json({ error: 'FEN and requestId required' });

  // Simulate 3000ms Stockfish online thinking time
  await new Promise(r => setTimeout(r, 3000));
  
  return res.status(200).json({
    requestId,
    fen,
    bestMove: "e2e4",
    ponder: "e7e5",
    evaluation: 0.35,
    mate: null,
    depth: 18,
    selDepth: 25,
    timeMs: 3000,
    nodes: 1234567,
    principalVariation: ["e2e4", "e7e5", "g1f3"]
  });
}
