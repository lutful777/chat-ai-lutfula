const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function sendJson(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-cache", ...corsHeaders });
  res.end(JSON.stringify(body));
}

function limitValue(value) {
  const parsed = Number.parseInt(String(value || "5"), 10);
  return Number.isNaN(parsed) ? 5 : Math.min(Math.max(parsed, 1), 10);
}

async function fetchJson(url) {
  const response = await fetch(url, { headers: { Accept: "application/json" } });
  const text = await response.text();
  try {
    return { ok: response.ok, status: response.status, data: text ? JSON.parse(text) : null };
  } catch {
    return { ok: response.ok, status: response.status, data: { message: text.slice(0, 1000) } };
  }
}

function envValue(parts) {
  return String(process.env[parts.join("_")] || "").trim();
}

function locationResult(item) {
  return {
    provider: "locationiq",
    name: item.display_name || "",
    lat: Number(item.lat),
    lon: Number(item.lon),
    type: item.type || item.class || "place",
    raw: item,
  };
}

function mapboxResult(feature) {
  const coords = Array.isArray(feature.center) ? feature.center : feature.geometry?.coordinates;
  return {
    provider: "mapbox",
    name: feature.place_name || feature.text || "",
    lat: Array.isArray(coords) ? Number(coords[1]) : null,
    lon: Array.isArray(coords) ? Number(coords[0]) : null,
    type: Array.isArray(feature.place_type) ? feature.place_type.join(",") : "place",
    raw: feature,
  };
}

export default async function handler(req, res) {
  if (req.method === "OPTIONS") {
    res.writeHead(204, corsHeaders);
    res.end();
    return;
  }
  if (req.method !== "GET") return sendJson(res, 405, { success: false, error: "Method tidak didukung." });

  const query = String(req.query?.q || req.query?.query || "").trim();
  const limit = limitValue(req.query?.limit);
  const country = String(req.query?.country || req.query?.countrycodes || "id").trim().toLowerCase();

  if (!query) return sendJson(res, 400, { success: false, error: "Parameter q wajib diisi." });

  const locationToken = envValue(["LOCATIONIQ", "API", "KEY"]);
  const mapboxToken = envValue(["MAPBOX", "ACCESS", "TOKEN"]) || envValue(["MAPBOX", "API", "KEY"]);
  if (!locationToken && !mapboxToken) return sendJson(res, 500, { success: false, error: "Credential maps belum disetel di Vercel." });

  const results = [];
  const errors = [];

  if (locationToken) {
    try {
      const url = new URL("https://us1.locationiq.com/v1/search");
      url.searchParams.set("key", locationToken);
      url.searchParams.set("q", query);
      url.searchParams.set("format", "json");
      url.searchParams.set("addressdetails", "1");
      url.searchParams.set("normalizeaddress", "1");
      url.searchParams.set("limit", String(limit));
      if (country) url.searchParams.set("countrycodes", country);
      const response = await fetchJson(url.toString());
      if (response.ok && Array.isArray(response.data)) results.push(...response.data.map(locationResult));
      else errors.push({ provider: "locationiq", status: response.status, details: response.data });
    } catch (error) {
      errors.push({ provider: "locationiq", message: error instanceof Error ? error.message : String(error) });
    }
  }

  if (mapboxToken) {
    try {
      const url = new URL(`https://api.mapbox.com/geocoding/v5/mapbox.places/${encodeURIComponent(query)}.json`);
      url.searchParams.set("access_token", mapboxToken);
      url.searchParams.set("limit", String(limit));
      url.searchParams.set("language", "id");
      if (country) url.searchParams.set("country", country.toUpperCase());
      const response = await fetchJson(url.toString());
      if (response.ok && Array.isArray(response.data?.features)) results.push(...response.data.features.map(mapboxResult));
      else errors.push({ provider: "mapbox", status: response.status, details: response.data });
    } catch (error) {
      errors.push({ provider: "mapbox", message: error instanceof Error ? error.message : String(error) });
    }
  }

  return sendJson(res, 200, { success: results.length > 0, query, count: results.length, results, errors });
}
