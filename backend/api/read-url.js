import { getEnv, handleOptions, json, readJsonBody, safeError } from './_utils.js';

export default async function handler(req, res) {
  if (handleOptions(req, res)) return;

  try {
    const body = req.method === 'POST' ? await readJsonBody(req) : {};
    const url = String(req.query?.url || body.url || '').trim();

    if (!url || !/^https?:\/\//i.test(url)) {
      return json(res, 400, { ok: false, error: 'Missing valid URL.' });
    }

    const apiKey = getEnv('FIRECRAWL_API_KEY');
    if (!apiKey) return json(res, 500, { ok: false, error: 'FIRECRAWL_API_KEY is not configured on backend.' });

    const upstream = await fetch('https://api.firecrawl.dev/v1/scrape', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ url, formats: ['markdown'] })
    });

    const text = await upstream.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = { raw: text.slice(0, 1000) }; }

    if (!upstream.ok) {
      return json(res, upstream.status, { ok: false, error: 'Firecrawl scrape failed.', status: upstream.status, details: data });
    }

    const page = data?.data || data || {};
    const markdown = String(page.markdown || '');

    json(res, 200, {
      ok: true,
      url,
      title: page.metadata?.title || page.title || '',
      markdown: markdown.length > 12000 ? `${markdown.slice(0, 12000)}\n[Content truncated]` : markdown,
      metadata: page.metadata || {},
      raw: data
    });
  } catch (error) {
    json(res, 500, { ok: false, error: safeError(error) });
  }
}
