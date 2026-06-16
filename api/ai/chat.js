const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
};

function sendJson(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-cache", ...corsHeaders });
  res.end(JSON.stringify(body));
}

function envValue(parts) {
  return String(process.env[parts.join("_")] || "").trim();
}

function trimSlash(value) {
  return String(value || "").trim().replace(/\/+$/, "");
}

function normalizePath(value) {
  const path = String(value || "/chat/completions").trim();
  return path.startsWith("/") ? path : `/${path}`;
}

function buildEndpoint(baseUrl, path) {
  const cleanBase = trimSlash(baseUrl);
  const cleanPath = normalizePath(path);
  if (cleanBase.endsWith("/chat/completions") || cleanBase.endsWith("/responses")) return cleanBase;
  return `${cleanBase}${cleanPath}`;
}

function parseBody(req) {
  if (req.body && typeof req.body === "object") return req.body;
  if (typeof req.body === "string") {
    try { return JSON.parse(req.body); } catch { return {}; }
  }
  return {};
}

function cleanUserText(text) {
  const raw = String(text || "");
  const marker = "PERTANYAAN USER:";
  if (raw.includes(marker)) return raw.slice(raw.lastIndexOf(marker) + marker.length).trim();
  return raw;
}

function cleanUserMessages(messages) {
  if (!Array.isArray(messages)) return messages;
  return messages.map((message) => {
    if (!message || message.role !== "user" || !Array.isArray(message.content)) return message;
    return {
      ...message,
      content: message.content.map((part) => {
        if (!part || part.type !== "text") return part;
        return { ...part, text: cleanUserText(part.text) };
      }),
    };
  });
}

function textFromMessage(message) {
  if (!message) return "";
  if (typeof message.content === "string") return cleanUserText(message.content);
  if (Array.isArray(message.content)) {
    return message.content
      .filter((part) => part?.type === "text")
      .map((part) => cleanUserText(part.text))
      .join(" ")
      .trim();
  }
  return "";
}

function lastUserText(messages) {
  if (!Array.isArray(messages)) return "";
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i]?.role === "user") return textFromMessage(messages[i]);
  }
  return "";
}

function isMapsQuery(text) {
  const lower = String(text || "").toLowerCase();
  return /\b(alamat|lokasi|maps|map|peta|koordinat|latitude|longitude|lat|lon|lng|dekat|terdekat|nearby|restoran|hotel|atm|spbu|pom bensin|rumah sakit|klinik|apotek|masjid|cafe|kafe|arah|rute|jarak)\b/.test(lower);
}

async function getJson(url) {
  const response = await fetch(url, { headers: { Accept: "application/json" } });
  const text = await response.text();
  try { return { ok: response.ok, status: response.status, data: text ? JSON.parse(text) : null }; }
  catch { return { ok: response.ok, status: response.status, data: { message: text.slice(0, 800) } }; }
}

function summarizeLocationIq(items) {
  if (!Array.isArray(items)) return [];
  return items.slice(0, 3).map((item) => `- ${item.display_name || "Lokasi"} | lat ${item.lat}, lon ${item.lon}`);
}

function summarizeMapbox(items) {
  if (!Array.isArray(items)) return [];
  return items.slice(0, 3).map((item) => {
    const coords = Array.isArray(item.center) ? item.center : item.geometry?.coordinates;
    const lon = Array.isArray(coords) ? coords[0] : "?";
    const lat = Array.isArray(coords) ? coords[1] : "?";
    return `- ${item.place_name || item.text || "Lokasi"} | lat ${lat}, lon ${lon}`;
  });
}

async function buildMapsContext(userText) {
  if (!isMapsQuery(userText)) return "";
  const locationToken = envValue(["LOCATIONIQ", "API", "KEY"]);
  const mapboxToken = envValue(["MAPBOX", "ACCESS", "TOKEN"]) || envValue(["MAPBOX", "API", "KEY"]);
  if (!locationToken && !mapboxToken) return "";

  const query = String(userText || "").trim();
  const lines = [];

  if (locationToken) {
    try {
      const url = new URL("https://us1.locationiq.com/v1/search");
      url.searchParams.set("key", locationToken);
      url.searchParams.set("q", query);
      url.searchParams.set("format", "json");
      url.searchParams.set("addressdetails", "1");
      url.searchParams.set("normalizeaddress", "1");
      url.searchParams.set("limit", "3");
      url.searchParams.set("countrycodes", "id");
      const response = await getJson(url.toString());
      if (response.ok) lines.push(...summarizeLocationIq(response.data));
    } catch {}
  }

  if (mapboxToken) {
    try {
      const url = new URL(`https://api.mapbox.com/geocoding/v5/mapbox.places/${encodeURIComponent(query)}.json`);
      url.searchParams.set("access_token", mapboxToken);
      url.searchParams.set("limit", "3");
      url.searchParams.set("language", "id");
      url.searchParams.set("country", "ID");
      const response = await getJson(url.toString());
      if (response.ok) lines.push(...summarizeMapbox(response.data?.features));
    } catch {}
  }

  if (lines.length === 0) return "";
  return `MAPS_TOOL_RESULT:\nPertanyaan lokasi user: ${query}\nHasil lokasi dari backend maps:\n${lines.join("\n")}\nInstruksi: jawab sesuai pertanyaan user memakai hasil lokasi ini. Jika hasil tidak cocok, bilang data lokasi belum pasti.`;
}

function chatMessage(text) {
  return {
    id: "server-message",
    object: "chat.completion",
    choices: [{ index: 0, message: { role: "assistant", content: text }, finish_reason: "stop" }],
  };
}

export default async function handler(req, res) {
  if (req.method === "OPTIONS") {
    res.writeHead(204, corsHeaders);
    res.end();
    return;
  }
  if (req.method !== "POST") return sendJson(res, 405, { error: "Method tidak didukung." });

  const provider = String(process.env.AI_PROVIDER || "BLUESMIND").trim().toUpperCase();
  const credential = envValue(["AI", "BLUESMIND", "API", "KEY"]) || envValue(["AI", "PROVIDER", "API", "KEY"]);
  const baseUrl = trimSlash(process.env.AI_BASE_URL);
  const endpointPath = normalizePath(process.env.AI_PATH || "/chat/completions");
  const defaultModel = String(process.env.AI_MODEL || "").trim();

  if (!credential) return sendJson(res, 200, chatMessage("Server AI key belum disetel di Vercel. Tambahkan AI_BLUESMIND_API_KEY di Environment Variables."));
  if (!baseUrl) return sendJson(res, 200, chatMessage("AI_BASE_URL belum disetel di Vercel Environment Variables."));

  const body = parseBody(req);
  const requestedModel = String(body.model || "").trim();
  const resolvedModel = requestedModel === "" || requestedModel === "server-default" ? defaultModel : requestedModel;
  if (!resolvedModel) return sendJson(res, 200, chatMessage("AI_MODEL belum disetel di Vercel Environment Variables."));

  const cleanedMessages = cleanUserMessages(body.messages);
  const mapsContext = await buildMapsContext(lastUserText(cleanedMessages));
  const messages = mapsContext ? [{ role: "system", content: [{ type: "text", text: mapsContext }] }, ...cleanedMessages] : cleanedMessages;

  try {
    const endpoint = buildEndpoint(baseUrl, endpointPath);
    const upstream = await fetch(endpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
        Authorization: `Bearer ${credential}`,
      },
      body: JSON.stringify({ ...body, model: resolvedModel, messages, stream: false }),
    });

    const text = await upstream.text();
    let data;
    try { data = text ? JSON.parse(text) : null; } catch { data = { message: text.slice(0, 2000) }; }
    if (!upstream.ok) {
      const detail = typeof data?.message === "string" ? data.message : JSON.stringify(data).slice(0, 1200);
      return sendJson(res, 200, chatMessage(`Provider AI gagal. Status ${upstream.status}. Cek AI_BASE_URL, AI_PATH, AI_MODEL, dan kredit provider. Detail: ${detail}`));
    }
    return sendJson(res, 200, data);
  } catch (error) {
    return sendJson(res, 200, chatMessage(`Server gagal menghubungi provider AI (${provider}). ${error instanceof Error ? error.message : String(error)}`));
  }
}
