const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

function sendJson(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-cache", ...corsHeaders });
  res.end(JSON.stringify(body));
}

function trimSlash(v) {
  return String(v || "").trim().replace(/\/+$/, "");
}

function normalizePath(v) {
  const p = String(v || "/chat/completions").trim();
  return p.startsWith("/") ? p : `/${p}`;
}

function parseBody(req) {
  if (req.body && typeof req.body === "object") return req.body;
  if (typeof req.body === "string") {
    try { return JSON.parse(req.body); } catch { return {}; }
  }
  return {};
}

export default async function handler(req, res) {
  if (req.method === "OPTIONS") {
    res.writeHead(204, corsHeaders);
    res.end();
    return;
  }
  if (req.method !== "POST") return sendJson(res, 405, { error: "Method tidak didukung." });

  const provider = String(process.env.AI_PROVIDER || "BLUESMIND").trim().toUpperCase();
  const envName = ["AI", "BLUESMIND", "API", "KEY"].join("_");
  const fallbackName = ["AI", "PROVIDER", "API", "KEY"].join("_");
  const credential = String(process.env[envName] || process.env[fallbackName] || "").trim();
  const baseUrl = trimSlash(process.env.AI_BASE_URL);
  const endpointPath = normalizePath(process.env.AI_PATH || "/chat/completions");
  const defaultModel = String(process.env.AI_MODEL || "").trim();

  if (!credential) return sendJson(res, 500, { error: `Server credential belum disetel di Vercel: ${envName}` });
  if (!baseUrl) return sendJson(res, 500, { error: "AI_BASE_URL belum disetel di Vercel Environment Variables." });

  const body = parseBody(req);
  const upstreamBody = { ...body, model: String(body.model || defaultModel).trim(), stream: false };
  if (!upstreamBody.model) return sendJson(res, 500, { error: "AI_MODEL belum disetel atau request model kosong." });

  try {
    const upstream = await fetch(`${baseUrl}${endpointPath}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        Authorization: `Bearer ${credential}`,
      },
      body: JSON.stringify(upstreamBody),
    });

    const text = await upstream.text();
    let data;
    try { data = text ? JSON.parse(text) : null; } catch { data = { message: text.slice(0, 2000) }; }
    if (!upstream.ok) return sendJson(res, upstream.status, { error: "Provider AI gagal memproses request.", provider, status: upstream.status, details: data });
    return sendJson(res, 200, data);
  } catch (error) {
    return sendJson(res, 502, { error: "Server gagal menghubungi provider AI.", provider, message: error instanceof Error ? error.message : String(error) });
  }
}
