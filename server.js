import { createServer } from 'node:http';
import { URL } from 'node:url';
import chessHandler from './api/chess/analyze.js';

const PORT = Number.parseInt(process.env.PORT || '10000', 10);
const HOST = '0.0.0.0';
const MAX_BODY_BYTES = 64 * 1024;

function sendJson(response, statusCode, payload) {
  response.statusCode = statusCode;
  response.setHeader('Content-Type', 'application/json; charset=utf-8');
  response.setHeader('Cache-Control', 'no-store');
  response.end(JSON.stringify(payload));
}

function createRenderResponse(response) {
  return {
    setHeader(name, value) {
      response.setHeader(name, value);
      return this;
    },
    status(statusCode) {
      response.statusCode = statusCode;
      return this;
    },
    json(payload) {
      if (!response.hasHeader('Content-Type')) {
        response.setHeader('Content-Type', 'application/json; charset=utf-8');
      }
      response.end(JSON.stringify(payload));
      return this;
    },
    end(body) {
      response.end(body);
      return this;
    },
  };
}

async function readJsonBody(request) {
  const chunks = [];
  let totalBytes = 0;

  for await (const chunk of request) {
    totalBytes += chunk.length;
    if (totalBytes > MAX_BODY_BYTES) {
      const error = new Error('Request body is too large');
      error.statusCode = 413;
      throw error;
    }
    chunks.push(chunk);
  }

  if (chunks.length === 0) return {};

  const rawBody = Buffer.concat(chunks).toString('utf8');
  try {
    return JSON.parse(rawBody);
  } catch {
    const error = new Error('Request body must be valid JSON');
    error.statusCode = 400;
    throw error;
  }
}

const server = createServer(async (request, response) => {
  const requestUrl = new URL(request.url || '/', `http://${request.headers.host || 'localhost'}`);
  const pathname = requestUrl.pathname.replace(/\/+$/, '') || '/';

  try {
    if (pathname === '/' && request.method === 'GET') {
      return sendJson(response, 200, {
        status: 'ok',
        service: 'Chat AI Lutfula Stockfish API',
        provider: 'Render',
        endpoint: '/api/chess/analyze',
        health: '/healthz',
      });
    }

    if (pathname === '/healthz' && request.method === 'GET') {
      return sendJson(response, 200, {
        status: 'ok',
        uptimeSeconds: Math.round(process.uptime()),
      });
    }

    if (pathname === '/api/chess/analyze') {
      const body = ['POST', 'PUT', 'PATCH'].includes(request.method || '')
        ? await readJsonBody(request)
        : {};

      const renderRequest = {
        method: request.method,
        headers: request.headers,
        body,
        query: Object.fromEntries(requestUrl.searchParams.entries()),
      };

      return await chessHandler(renderRequest, createRenderResponse(response));
    }

    return sendJson(response, 404, {
      error: 'Not found',
      endpoint: '/api/chess/analyze',
    });
  } catch (error) {
    console.error('Render server request failed:', error);
    if (!response.headersSent) {
      return sendJson(response, error.statusCode || 500, {
        error: error.statusCode ? error.message : 'Internal server error',
      });
    }
    response.end();
  }
});

server.requestTimeout = 30_000;
server.headersTimeout = 35_000;
server.keepAliveTimeout = 5_000;

server.listen(PORT, HOST, () => {
  console.log(`Stockfish API listening on http://${HOST}:${PORT}`);
});

function shutdown(signal) {
  console.log(`${signal} received, shutting down`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 25_000).unref();
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
