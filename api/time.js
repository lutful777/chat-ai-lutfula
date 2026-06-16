const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function sendJson(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-cache", ...corsHeaders });
  res.end(JSON.stringify(body));
}

export default async function handler(req, res) {
  if (req.method === "OPTIONS") {
    res.writeHead(204, corsHeaders);
    res.end();
    return;
  }
  if (req.method !== "GET") return sendJson(res, 405, { error: "Method tidak didukung." });

  const envName = ["API", "NINJAS", "API", "KEY"].join("_");
  const token = (process.env[envName] || "").trim();
  if (!token) return sendJson(res, 500, { error: `${envName} belum disetel di Vercel Environment Variables.` });

  const city = String(req.query.city || "Jakarta").trim().slice(0, 120) || "Jakarta";
  const timezone = String(req.query.timezone || "").trim().slice(0, 120);
  const lat = String(req.query.lat || "").trim().slice(0, 32);
  const lon = String(req.query.lon || req.query.lng || "").trim().slice(0, 32);

  const target = new URL("https://api.api-ninjas.com/v1/worldtime");
  if (timezone) target.searchParams.set("timezone", timezone);
  else if (lat && lon) {
    target.searchParams.set("lat", lat);
    target.searchParams.set("lon", lon);
  } else target.searchParams.set("city", city);

  try {
    const upstream = await fetch(target.toString(), { headers: { Accept: "application/json", "X-Api-Key": token } });
    const text = await upstream.text();
    let data;
    try { data = text ? JSON.parse(text) : null; } catch { data = { message: text.slice(0, 1000) }; }
    if (!upstream.ok) return sendJson(res, upstream.status, { error: "Gagal mengambil jam/tanggal dari provider.", provider: "api-ninjas", status: upstream.status, details: data });
    return sendJson(res, 200, { status: "success", provider: "api-ninjas", city: timezone || lat ? undefined : city, timezone: timezone || undefined, lat: lat || undefined, lon: lon || undefined, generatedAt: new Date().toISOString(), data });
  } catch (error) {
    return sendJson(res, 502, { error: "Gagal menghubungi provider waktu.", message: error instanceof Error ? error.message : String(error) });
  }
}
