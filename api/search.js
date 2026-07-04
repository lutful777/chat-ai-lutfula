const DEFAULT_LIMIT = 8;
const MAX_LIMIT = 20;
const MAX_SNIPPET = 1600;
const MAX_CONTENT = 7000;

function cleanText(input) {
  return String(input || '')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<\/(h1|h2|h3|p|li|tr|section|article|div)>/gi, '\n')
    .replace(/<br\s*\/?\s*>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function truncateAtWord(input, max) {
  const text = cleanText(input);
  if (text.length <= max) return text;
  return text.slice(0, max).replace(/\s+\S*$/, '') + '...';
}

function normalizedUrl(input) {
  try {
    const u = new URL(String(input || '').trim());
    if (!['http:', 'https:'].includes(u.protocol) || u.username || u.password) return '';
    const host = u.hostname.toLowerCase();
    if (!host || host === 'localhost' || host.endsWith('.local') || host.endsWith('.localhost')) return '';
    u.hash = '';
    return u.toString();
  } catch (_) {
    return '';
  }
}

async function browserlessRead(pageUrl) {
  const token = process.env.BROWSERLESS_TOKEN;
  if (!token || !pageUrl) return null;
  const base = process.env.BROWSERLESS_URL || 'https://chrome.browserless.io/content';
  const endpoint = base + (base.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(token);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 22000);
  try {
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: pageUrl, gotoOptions: { waitUntil: 'networkidle2', timeout: 18000 } }),
      signal: controller.signal
    });
    if (!response.ok) return null;
    const html = await response.text();
    const content = truncateAtWord(html, MAX_CONTENT);
    return content ? { content, reader: 'browserless' } : null;
  } finally {
    clearTimeout(timeout);
  }
}

async function firecrawlRead(pageUrl, token) {
  if (!token || !pageUrl) return null;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 22000);
  try {
    const response = await fetch('https://api.firecrawl.dev/v1/scrape', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ url: pageUrl, formats: ['markdown', 'html'], onlyMainContent: false }),
      signal: controller.signal
    });
    const text = await response.text();
    let json;
    try { json = JSON.parse(text); } catch (_) { json = { raw: text }; }
    if (!response.ok) return null;
    const data = json.data || json;
    const raw = data.markdown || data.content || data.html || json.markdown || json.html || '';
    const content = truncateAtWord(raw, MAX_CONTENT);
    return content ? { content, reader: 'firecrawl-scrape', title: data.metadata?.title || '' } : null;
  } finally {
    clearTimeout(timeout);
  }
}

async function enrichResult(row, token, includeContent) {
  const pageUrl = normalizedUrl(row.url || row.sourceURL || row.metadata?.sourceURL || '');
  let description = row.description || row.snippet || row.content || row.markdown || '';
  let title = row.title || row.metadata?.title || 'No Title';
  let content = '';
  let reader = 'firecrawl-search';

  if (pageUrl && (includeContent || cleanText(description).length < 220)) {
    try {
      const full = await browserlessRead(pageUrl) || await firecrawlRead(pageUrl, token);
      if (full) {
        content = full.content;
        reader = full.reader;
        if (full.title) title = full.title;
        if (cleanText(description).length < 220) description = content;
      }
    } catch (_) {}
  }

  return {
    title: truncateAtWord(title, 300) || 'No Title',
    description: truncateAtWord(description, MAX_SNIPPET),
    content: includeContent ? truncateAtWord(content || description, MAX_CONTENT) : undefined,
    url: pageUrl,
    reader
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
  const includeContent = ['1', 'true', 'yes'].includes(String(req.query.includeContent || '').toLowerCase());
  const requestedLimit = Number.parseInt(String(req.query.limit || DEFAULT_LIMIT), 10);
  const limit = Math.max(1, Math.min(MAX_LIMIT, Number.isFinite(requestedLimit) ? requestedLimit : DEFAULT_LIMIT));
  if (!q) return res.status(400).json({ error: 'Missing query parameter q' });

  const token = process.env.FIRECRAWL_API_KEY;
  if (!token) return res.status(500).json({ error: 'FIRECRAWL_API_KEY not set' });

  try {
    const isNews = mode === 'berita' || mode === 'news';
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 25000);
    let response;
    try {
      const body = { query: q, limit: isNews ? Math.max(limit, 12) : limit };
      if (isNews) body.tbs = 'sbd:1,qdr:d';
      response = await fetch('https://api.firecrawl.dev/v1/search', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify(body),
        signal: controller.signal
      });
    } finally {
      clearTimeout(timeout);
    }

    const rawText = await response.text();
    let json;
    try { json = JSON.parse(rawText); } catch (_) { json = { raw: rawText }; }
    if (!response.ok) {
      return res.status(response.status).json({ error: 'Search provider failed', status: response.status, details: json });
    }

    const rows = Array.isArray(json.data) ? json.data : (Array.isArray(json.results) ? json.results : []);
    const selected = rows.slice(0, limit);
    const data = [];

    for (let i = 0; i < selected.length; i += 1) {
      const shouldRead = includeContent && i < Math.min(5, selected.length);
      data.push(await enrichResult(selected[i] || {}, token, shouldRead));
    }

    return res.status(200).json({
      success: true,
      query: q,
      mode,
      limit,
      includeContent,
      data
    });
  } catch (error) {
    return res.status(500).json({
      error: 'Realtime search failed',
      message: error instanceof Error ? error.message : String(error)
    });
  }
}
