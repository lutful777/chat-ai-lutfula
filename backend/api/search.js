import { getEnv, handleOptions, json, readJsonBody, safeError } from './_utils.js';

export default async function handler(req, res) {
  if (handleOptions(req, res)) return;

  try {
    const body = req.method === 'POST' ? await readJsonBody(req) : {};
    const query = String(req.query?.q || body.query || '').trim();
    const limit = Math.min(Math.max(Number(req.query?.limit || body.limit || 5), 1), 10);

    if (!query) return json(res, 400, { ok: false, error: 'Missing query.' });

    const apiKey = getEnv('FIRECRAWL_API_KEY');
    if (!apiKey) return json(res, 500, { ok: false, error: 'FIRECRAWL_API_KEY is not configured on backend.' });

    const upstream = await fetch('https://api.firecrawl.dev/v1/search', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ query, limit })
    });

    const text = await upstream.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = { raw: text.slice(0, 1000) }; }

    if (!upstream.ok) {
      return json(res, upstream.status, { ok: false, error: 'Firecrawl search failed.', status: upstream.status, details: data });
    }

    const results = Array.isArray(data?.data) ? data.data : [];
    json(res, 200, {
      ok: true,
      query,
      results: results.slice(0, limit).map((item) => ({
        title: item.title || '',
        description: item.description || item.markdown?.slice(0, 500) || '',
        url: item.url || ''
      })),
      raw: data
    });
  } catch (error) {
    json(res, 500, { ok: false, error: safeError(error) });
  }
}
