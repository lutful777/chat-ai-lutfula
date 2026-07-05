import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const Stockfish = require('stockfish.wasm');
const wasmBinary = readFileSync(require.resolve('stockfish.wasm/stockfish.wasm'));

const DEFAULT_MOVE_TIME_MS = 1500;
const MAX_MOVE_TIME_MS = 5000;
const MAX_DEPTH = 24;

let enginePromise = null;
let analysisQueue = Promise.resolve();

function setHeaders(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  res.setHeader('Cache-Control', 'no-store');
}

function parseBody(body) {
  if (!body) return {};
  if (typeof body === 'string') {
    try {
      return JSON.parse(body);
    } catch {
      return {};
    }
  }
  return body;
}

function isValidFen(fen) {
  if (typeof fen !== 'string' || fen.length > 200) return false;

  const parts = fen.trim().split(/\s+/);
  if (parts.length !== 6) return false;

  const [placement, side, castling, enPassant, halfmove, fullmove] = parts;
  const ranks = placement.split('/');
  if (ranks.length !== 8) return false;

  for (const rank of ranks) {
    let squares = 0;
    for (const character of rank) {
      if (/[1-8]/.test(character)) squares += Number(character);
      else if (/[prnbqkPRNBQK]/.test(character)) squares += 1;
      else return false;
    }
    if (squares !== 8) return false;
  }

  if (!/^[wb]$/.test(side)) return false;
  if (!/^(?:-|K?Q?k?q?)$/.test(castling)) return false;
  if (!/^(?:-|[a-h][36])$/.test(enPassant)) return false;
  if (!/^\d+$/.test(halfmove) || !/^[1-9]\d*$/.test(fullmove)) return false;
  return true;
}

function waitForLine(engine, command, predicate, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      engine.removeMessageListener(listener);
      reject(new Error(`Stockfish timeout while waiting for ${command}`));
    }, timeoutMs);

    const listener = (rawLine) => {
      const line = String(rawLine).trim();
      if (!predicate(line)) return;

      clearTimeout(timeout);
      engine.removeMessageListener(listener);
      resolve(line);
    };

    engine.addMessageListener(listener);
    engine.postMessage(command);
  });
}

async function createEngine() {
  const engine = await Stockfish({ wasmBinary });
  await waitForLine(engine, 'uci', (line) => line === 'uciok');
  engine.postMessage('setoption name Threads value 1');
  engine.postMessage('setoption name Hash value 16');
  engine.postMessage('setoption name MultiPV value 1');
  engine.postMessage('setoption name Ponder value false');
  await waitForLine(engine, 'isready', (line) => line === 'readyok');
  return engine;
}

async function getEngine() {
  if (!enginePromise) {
    enginePromise = createEngine().catch((error) => {
      enginePromise = null;
      throw error;
    });
  }
  return enginePromise;
}

function parseInfo(line, previous) {
  const depth = /\bdepth\s+(\d+)/.exec(line);
  const selDepth = /\bseldepth\s+(\d+)/.exec(line);
  const time = /\btime\s+(\d+)/.exec(line);
  const nodes = /\bnodes\s+(\d+)/.exec(line);
  const cp = /\bscore\s+cp\s+(-?\d+)/.exec(line);
  const mate = /\bscore\s+mate\s+(-?\d+)/.exec(line);
  const pv = /\bpv\s+(.+)$/.exec(line);

  return {
    depth: depth ? Number(depth[1]) : previous.depth,
    selDepth: selDepth ? Number(selDepth[1]) : previous.selDepth,
    timeMs: time ? Number(time[1]) : previous.timeMs,
    nodes: nodes ? Number(nodes[1]) : previous.nodes,
    evaluation: cp ? Number(cp[1]) / 100 : previous.evaluation,
    mate: mate ? Number(mate[1]) : previous.mate,
    principalVariation: pv ? pv[1].trim().split(/\s+/) : previous.principalVariation,
  };
}

async function runAnalysis(fen, moveTimeMs, depth) {
  const engine = await getEngine();
  await waitForLine(engine, 'isready', (line) => line === 'readyok');

  return new Promise((resolve, reject) => {
    let info = {
      depth: 0,
      selDepth: 0,
      timeMs: 0,
      nodes: 0,
      evaluation: 0,
      mate: null,
      principalVariation: [],
    };
    let stoppedForTimeout = false;
    let settled = false;

    const cleanup = () => {
      clearTimeout(softTimeout);
      clearTimeout(hardTimeout);
      engine.removeMessageListener(listener);
    };

    const finish = (callback) => {
      if (settled) return;
      settled = true;
      cleanup();
      callback();
    };

    const listener = (rawLine) => {
      const line = String(rawLine).trim();
      if (line.startsWith('info ')) {
        info = parseInfo(line, info);
        return;
      }
      if (!line.startsWith('bestmove ')) return;

      const match = /^bestmove\s+(\S+)(?:\s+ponder\s+(\S+))?/.exec(line);
      const bestMove = match?.[1] || '';
      const ponder = match?.[2] || null;

      if (stoppedForTimeout) {
        finish(() => reject(new Error('Stockfish analysis timed out')));
      } else if (!/^[a-h][1-8][a-h][1-8][qrbn]?$/.test(bestMove)) {
        finish(() => reject(new Error('Stockfish did not return a legal UCI move')));
      } else {
        finish(() => resolve({ bestMove, ponder, ...info }));
      }
    };

    const softTimeout = setTimeout(() => {
      stoppedForTimeout = true;
      engine.postMessage('stop');
    }, Math.max(moveTimeMs + 5000, 8000));

    const hardTimeout = setTimeout(() => {
      try {
        engine.terminate();
      } catch {
        // The worker may already have exited.
      }
      enginePromise = null;
      finish(() => reject(new Error('Stockfish engine became unresponsive')));
    }, Math.max(moveTimeMs + 7000, 10000));

    engine.addMessageListener(listener);
    engine.postMessage(`position fen ${fen}`);
    if (depth) engine.postMessage(`go depth ${depth}`);
    else engine.postMessage(`go movetime ${moveTimeMs}`);
  });
}

function enqueueAnalysis(task) {
  const current = analysisQueue.then(task, task);
  analysisQueue = current.catch(() => undefined);
  return current;
}

export default async function handler(req, res) {
  setHeaders(res);

  if (req.method === 'OPTIONS') return res.status(204).end();

  if (req.method === 'GET') {
    return res.status(200).json({
      status: 'ok',
      service: 'Stockfish chess analysis',
      engine: 'Stockfish SF_classical WebAssembly',
      endpoint: '/api/chess/analyze',
    });
  }

  if (req.method !== 'POST') {
    res.setHeader('Allow', 'GET, POST, OPTIONS');
    return res.status(405).json({ error: 'Method not allowed' });
  }

  const body = parseBody(req.body);
  const fen = typeof body.fen === 'string' ? body.fen.trim() : '';
  const requestId = typeof body.requestId === 'string' && body.requestId.trim()
    ? body.requestId.trim().slice(0, 128)
    : null;

  if (!isValidFen(fen)) {
    return res.status(400).json({ requestId, error: 'Invalid FEN' });
  }

  const requestedMoveTime = Number(body.movetimeMs ?? body.moveTimeMs ?? DEFAULT_MOVE_TIME_MS);
  const moveTimeMs = Number.isFinite(requestedMoveTime)
    ? Math.min(MAX_MOVE_TIME_MS, Math.max(100, Math.round(requestedMoveTime)))
    : DEFAULT_MOVE_TIME_MS;

  const requestedDepth = Number(body.depth);
  const depth = Number.isInteger(requestedDepth) && requestedDepth > 0
    ? Math.min(MAX_DEPTH, requestedDepth)
    : null;

  try {
    const result = await enqueueAnalysis(() => runAnalysis(fen, moveTimeMs, depth));
    return res.status(200).json({
      requestId,
      fen,
      ...result,
      engine: 'Stockfish SF_classical WebAssembly',
    });
  } catch (error) {
    console.error('Stockfish analysis failed:', error);
    return res.status(500).json({
      requestId,
      fen,
      error: 'Stockfish analysis failed',
      details: error instanceof Error ? error.message : String(error),
    });
  }
}
