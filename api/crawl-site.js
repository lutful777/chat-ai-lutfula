const MAX_PAGES_HARD = 20;
const MAX_DEPTH_HARD = 3;
const MAX_CONTEXT = 50000;

function resolveInternalBase(req) {
  const forwarded = String(req.headers['x-forwarded-host'] || req.headers.host || '').toLowerCase();
  const safeHost = /^[a-z0-9.-]+(?::\d+)?$/.test(forwarded) &&
    (forwarded === 'chat-ai-lutfula.vercel.app' || forwarded.endsWith('.vercel.app'));
  if (safeHost) return `https://${forwarded}`;
  return process.env.RESEARCH_API_BASE_URL || 'https://chat-ai-lutfula.vercel.app';
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

function tokens(input) {
  return String(input || '').toLowerCase().split(/[^a-z0-9]+/).filter((x) => x.length > 2);
}

function scoreCandidate(url, label, query) {
  const haystack = `${url} ${label || ''}`.toLowerCase();
  let score = 0;
  for (const token of tokens(query)) if (haystack.includes(token)) score += 4;
  for (const keyword of ['models', 'model', 'docs', 'documentation', 'api', 'pricing', 'catalog', 'provider', 'reference', 'changelog']) {
    if (haystack.includes(keyword)) score += 3;
  }
  if (/\.(png|jpe?g|gif|svg|webp|ico|pdf|zip|mp4|mp3)(\?|$)/i.test(url)) score -= 20;
  if (/[?&](page|offset|cursor)=/i.test(url)) score -= 2;
  return score;
}

async function postJson(url, body, timeoutMs = 32000) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: controller.signal
    });
    const text = await response.text();
    let json;
    try { json = JSON.parse(text); } catch (_) { json = { raw: text }; }
    if (!response.ok) return null;
    return json;
  } catch (_) {
    return null;
  } finally {
    clearTimeout(timeout);
  }
}

function addCandidate(map, url, label, depth, origin, query) {
  const normalized = normalizedUrl(url);
  if (!normalized) return;
  const parsed = new URL(normalized);
  if (parsed.origin !== origin) return;
  const existing = map.get(normalized);
  const candidate = { url: normalized, label: label || '', depth, score: scoreCandidate(normalized, label, query) };
  if (!existing || candidate.score > existing.score || candidate.depth < existing.depth) map.set(normalized, candidate);
}

function buildContext(pages) {
  let context = '';
  for (let i = 0; i < pages.length; i += 1) {
    const page = pages[i];
    const headings = Array.isArray(page.headings) ? page.headings.map((x) => x.text || x).filter(Boolean).slice(0, 20) : [];
    const block = [
      `SOURCE ${i + 1}`,
      `Title: ${page.title || 'Untitled'}`,
      `URL: ${page.url}`,
      headings.length ? `Headings: ${headings.join(' | ')}` : '',
      'Content:',
      String(page.markdown || '').slice(0, 9000),
      ''
    ].filter(Boolean).join('\n');
    if (context.length + block.length > MAX_CONTEXT) break;
    context += `${block}\n`;
  }
  return context.trim();
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });

  const startUrl = normalizedUrl(req.body?.url);
  if (!startUrl) return res.status(400).json({ error: 'Missing or invalid URL' });

  const query = String(req.body?.query || '');
  const maxPages = Math.max(1, Math.min(MAX_PAGES_HARD, Number.parseInt(String(req.body?.maxPages || 8), 10) || 8));
  const maxDepth = Math.max(0, Math.min(MAX_DEPTH_HARD, Number.parseInt(String(req.body?.maxDepth || 2), 10) || 2));
  const origin = new URL(startUrl).origin;
  const internalBase = resolveInternalBase(req);
  const candidates = new Map();
  const visited = new Set();
  const pages = [];

  addCandidate(candidates, startUrl, 'start page', 0, origin, query);

  try {
    const sitemap = await postJson(`${internalBase}/api/read-sitemap`, { url: startUrl, query, maxUrls: 250 }, 18000);
    const sitemapUrls = sitemap?.data?.urls || sitemap?.urls || [];
    for (const url of sitemapUrls.slice(0, 80)) addCandidate(candidates, url, 'sitemap', 1, origin, query);

    while (pages.length < maxPages) {
      const next = Array.from(candidates.values())
        .filter((item) => !visited.has(item.url) && item.depth <= maxDepth)
        .sort((a, b) => b.score - a.score || a.depth - b.depth)[0];
      if (!next) break;
      visited.add(next.url);

      const result = await postJson(`${internalBase}/api/read-url`, { url: next.url }, 34000);
      const data = result?.data || result;
      if (!data?.markdown) continue;

      pages.push({
        url: data.url || next.url,
        title: data.title || next.label || next.url,
        markdown: String(data.markdown).slice(0, 14000),
        headings: Array.isArray(data.headings) ? data.headings : [],
        tables: Array.isArray(data.tables) ? data.tables.slice(0, 6) : [],
        structuredData: Array.isArray(data.structuredData) ? data.structuredData.slice(0, 6) : [],
        reader: data.reader || result?.reader || 'unknown',
        depth: next.depth
      });

      if (next.depth < maxDepth && Array.isArray(data.links)) {
        for (const link of data.links.slice(0, 100)) {
          if (link?.internal !== false) addCandidate(candidates, link.url, link.text, next.depth + 1, origin, query);
        }
      }
    }

    const context = buildContext(pages);
    return res.status(200).json({
      success: pages.length > 0,
      startUrl,
      origin,
      query,
      maxPages,
      maxDepth,
      pagesRead: pages.length,
      pages,
      sources: pages.map((page) => ({ title: page.title, url: page.url, reader: page.reader })),
      context,
      data: { pages, context }
    });
  } catch (error) {
    return res.status(500).json({
      error: 'Site crawl failed',
      message: error instanceof Error ? error.message : String(error),
      startUrl
    });
  }
}
