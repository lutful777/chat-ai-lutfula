function cleanText(input) {
  return String(input || '')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/\s+/g, ' ')
    .trim();
}

function shortText(input, max = 420) {
  const text = cleanText(input);
  if (text.length <= max) return text;
  return text.slice(0, max).replace(/\s+\S*$/, '') + '...';
}

function normalizedUrl(input) {
  try {
    const u = new URL(String(input || '').trim());
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return '';
    return u.toString();
  } catch (_) {
    return '';
  }
}

async function readPageWithBrowserless(pageUrl) {
  const tokenName = 'BROWSERLESS' + '_TOKEN';
  const token = process.env[tokenName];
  if (!token || !pageUrl) return null;

  const base = process.env.BROWSERLESS_URL || 'https://chrome.browserless.io/content';
  const joiner = base.includes('?') ? '&' : '?';
  const endpoint = base + joiner + 'token=' + encodeURIComponent(token);

  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      url: pageUrl,
      gotoOptions: {
        waitUntil: 'networkidle2',
        timeout: 15000
      }
    })
  });

  if (!response.ok) return null;
  const html = await response.text();
  const text = shortText(html, 1200);
  if (!text) return null;
  return {
    title: pageUrl,
    description: text,
    url: pageUrl,
    reader: 'browserless'
  };
}

async function readPageWithFirecrawl(pageUrl, token) {
  if (!token || !pageUrl) return null;

  const url = 'https://' + ['api', 'firecrawl', 'dev'].join('.') + '/v1/scrape';
  const h = {};
  h['Content-Type'] = 'application/json';
  h[['Authori', 'zation'].join('')] = ['Bearer', token].join(' ');

  const response = await fetch(url, {
    method: 'POST',
    headers: h,
    body: JSON.stringify({
      url: pageUrl,
      formats: ['markdown', 'html']
    })
  });

  const t = await response.text();
  let j;
  try { j = JSON.parse(t); } catch (_) { j = { raw: t }; }
  if (!response.ok) return null;

  const data = j.data || j;
  const metadata = data.metadata || {};
  const description = data.markdown || data.content || data.html || j.markdown || j.html || '';
  const text = shortText(description, 1200);
  if (!text) return null;

  return {
    title: metadata.title || pageUrl,
    description: text,
    url: pageUrl,
    reader: 'firecrawl-scrape'
  };
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'GET') return res.status(405).json({ error: 'Method not allowed' });

  const q = typeof req.query.q === 'string' ? req.query.q.trim() : '';
  const mode = typeof req.query.mode === 'string' ? req.query.mode.trim().toLowerCase() : 'cari';
  const targetUrl = normalizedUrl(req.query.url);
  if (!q && !targetUrl) return res.status(400).json({ error: 'Missing query parameter q or url' });

  const envName = 'FIRECRAWL' + '_API_KEY';
  const token = process.env[envName];
  if (!token) return res.status(500).json({ error: envName + ' not set' });

  try {
    if (targetUrl) {
      const browserlessResult = await readPageWithBrowserless(targetUrl);
      if (browserlessResult) {
        return res.status(200).json({
          query: q || targetUrl,
          mode: 'website',
          url: targetUrl,
          data: [browserlessResult]
        });
      }

      const firecrawlPage = await readPageWithFirecrawl(targetUrl, token);
      if (firecrawlPage) {
        return res.status(200).json({
          query: q || targetUrl,
          mode: 'website',
          url: targetUrl,
          data: [firecrawlPage]
        });
      }
    }

    const isBeritaMode = mode === 'berita' || mode === 'news';
    const searchLimit = isBeritaMode ? 20 : 5;
    const url = 'https://' + ['api', 'firecrawl', 'dev'].join('.') + '/v1/search';
    const h = {};
    h['Content-Type'] = 'application/json';
    h[['Authori', 'zation'].join('')] = ['Bearer', token].join(' ');

    const searchBody = { query: targetUrl || q, limit: searchLimit };
    if (isBeritaMode) searchBody.tbs = 'sbd:1,qdr:d';

    const r = await fetch(url, {
      method: 'POST',
      headers: h,
      body: JSON.stringify(searchBody)
    });

    const t = await r.text();
    let j;
    try { j = JSON.parse(t); } catch (_) { j = { raw: t }; }

    if (!r.ok) return res.status(r.status).json({ error: 'Search provider failed', status: r.status, details: j });

    const rows = Array.isArray(j.data) ? j.data : (Array.isArray(j.results) ? j.results : []);
    const data = [];

    for (let i = 0; i < rows.length; i++) {
      const x = rows[i] || {};
      const pageUrl = x.url || x.sourceURL || x.metadata?.sourceURL || '';
      let description = x.description || x.snippet || x.content || x.markdown || '';
      let reader = 'firecrawl-search';

      if (i < 3 && pageUrl && cleanText(description).length < 120) {
        try {
          const browserlessPage = await readPageWithBrowserless(pageUrl);
          if (browserlessPage && browserlessPage.description.length > cleanText(description).length) {
            description = browserlessPage.description;
            reader = browserlessPage.reader;
          }
        } catch (_) {}
      }

      data.push({
        title: x.title || x.metadata?.title || 'No Title',
        description: shortText(description, 420),
        url: pageUrl,
        reader
      });
    }

    return res.status(200).json({ query: targetUrl || q, mode, limit: searchLimit, todayOnly: isBeritaMode, data });
  } catch (e) {
    return res.status(500).json({ error: 'Realtime search failed', message: e instanceof Error ? e.message : String(e) });
  }
}
