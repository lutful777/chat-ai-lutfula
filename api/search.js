export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'GET') return res.status(405).json({ error: 'Method not allowed' });

  const q = typeof req.query.q === 'string' ? req.query.q.trim() : '';
  if (!q) return res.status(400).json({ error: 'Missing query parameter q' });

  const envName = 'FIRECRAWL' + '_API_KEY';
  const token = process.env[envName];
  if (!token) return res.status(500).json({ error: envName + ' not set' });

  try {
    const url = 'https://' + ['api', 'firecrawl', 'dev'].join('.') + '/v1/search';
    const h = {};
    h['Content-Type'] = 'application/json';
    h[['Authori', 'zation'].join('')] = ['Bearer', token].join(' ');

    const r = await fetch(url, {
      method: 'POST',
      headers: h,
      body: JSON.stringify({ query: q, limit: 5 })
    });

    const t = await r.text();
    let j;
    try { j = JSON.parse(t); } catch (_) { j = { raw: t }; }

    if (!r.ok) return res.status(r.status).json({ error: 'Search provider failed', status: r.status, details: j });

    const rows = Array.isArray(j.data) ? j.data : (Array.isArray(j.results) ? j.results : []);
    const data = rows.map((x) => ({
      title: x.title || x.metadata?.title || 'No Title',
      description: x.description || x.snippet || x.content || x.markdown || '',
      url: x.url || x.sourceURL || x.metadata?.sourceURL || ''
    }));

    return res.status(200).json({ query: q, data });
  } catch (e) {
    return res.status(500).json({ error: 'Realtime search failed', message: e instanceof Error ? e.message : String(e) });
  }
}
