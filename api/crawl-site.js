const MAX_PAGES_HARD = 250;
const MAX_DEPTH_HARD = 12;
const MAX_DISCOVERED = 5000;
const MAX_CONTEXT = 100000;
const WHOLE_SITE_BATCH_SIZE = 4;

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
    for (const key of Array.from(u.searchParams.keys())) {
      if (/^(utm_|fbclid$|gclid$|ref$|source$)/i.test(key)) u.searchParams.delete(key);
    }
    return u.toString();
  } catch (_) {
    return '';
  }
}

function shouldSkipUrl(url) {
  return /\.(?:png|jpe?g|gif|svg|webp|ico|pdf|zip|rar|7z|mp4|webm|mp3|wav|woff2?|ttf|eot|css|js)(?:\?|$)/i.test(url);
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
  if (/[?&](page|offset|cursor)=/i.test(url) || /\/page\/\d+/i.test(url)) score += 2;
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
    if (!response.ok) return { ok: false, status: response.status, json };
    return { ok: true, status: response.status, json };
  } catch (error) {
    return { ok: false, status: 0, error: error instanceof Error ? error.message : String(error) };
  } finally {
    clearTimeout(timeout);
  }
}

function addCandidate(map, url, label, depth, origin, query, stats) {
  const normalized = normalizedUrl(url);
  if (!normalized || shouldSkipUrl(normalized)) return false;
  const parsed = new URL(normalized);
  if (parsed.origin !== origin) return false;
  if (map.has(normalized)) {
    stats.duplicates += 1;
    const existing = map.get(normalized);
    if (depth < existing.depth) existing.depth = depth;
    if (label && !existing.label) existing.label = label;
    existing.score = Math.max(existing.score, scoreCandidate(normalized, label, query));
    return false;
  }
  if (map.size >= MAX_DISCOVERED) {
    stats.discoveryLimitReached = true;
    return false;
  }
  map.set(normalized, {
    url: normalized,
    label: label || '',
    depth,
    score: scoreCandidate(normalized, label, query)
  });
  return true;
}

function selectNextCandidates(candidates, visited, maxDepth, count, wholeSite) {
  return Array.from(candidates.values())
    .filter((item) => !visited.has(item.url) && item.depth <= maxDepth)
    .sort((a, b) => {
      if (wholeSite) return a.depth - b.depth || a.url.localeCompare(b.url);
      return b.score - a.score || a.depth - b.depth || a.url.localeCompare(b.url);
    })
    .slice(0, count);
}

function buildContext(pages, query, coverage, wholeSite) {
  const queryTokens = tokens(query);
  const rankedPages = [...pages].sort((a, b) => {
    const aText = `${a.title} ${a.url} ${a.headings.map((x) => x.text || x).join(' ')} ${a.markdown}`.toLowerCase();
    const bText = `${b.title} ${b.url} ${b.headings.map((x) => x.text || x).join(' ')} ${b.markdown}`.toLowerCase();
    const aScore = queryTokens.reduce((score, token) => score + (aText.includes(token) ? 1 : 0), 0);
    const bScore = queryTokens.reduce((score, token) => score + (bText.includes(token) ? 1 : 0), 0);
    return bScore - aScore || a.depth - b.depth;
  });

  let context = [
    wholeSite ? 'WHOLE_SITE_PUBLIC_SCAN' : 'SITE_CRAWL',
    `Origin: ${coverage.origin}`,
    `URLs discovered: ${coverage.discovered}`,
    `URLs attempted: ${coverage.attempted}`,
    `Pages succeeded: ${coverage.succeeded}`,
    `Pages failed: ${coverage.failed}`,
    `URLs pending: ${coverage.pending}`,
    `Duplicates removed: ${coverage.duplicates}`,
    `Timed out: ${coverage.timedOut}`,
    `Crawl complete: ${coverage.complete}`,
    '',
    'PAGE INVENTORY'
  ].join('\n');

  for (const page of pages) {
    const headingText = page.headings.map((x) => x.text || x).filter(Boolean).slice(0, 6).join(' | ');
    const line = `- ${page.title || 'Untitled'} | ${page.url}${headingText ? ` | ${headingText}` : ''}\n`;
    if (context.length + line.length > MAX_CONTEXT * 0.45) break;
    context += line;
  }

  context += '\nDETAILED EXCERPTS\n';
  for (let i = 0; i < rankedPages.length; i += 1) {
    const page = rankedPages[i];
    const block = [
      `SOURCE ${i + 1}`,
      `Title: ${page.title || 'Untitled'}`,
      `URL: ${page.url}`,
      page.headings.length ? `Headings: ${page.headings.map((x) => x.text || x).filter(Boolean).slice(0, 20).join(' | ')}` : '',
      'Content:',
      String(page.markdown || '').slice(0, wholeSite ? 3500 : 9000),
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
  const wholeSite = req.body?.wholeSite === true;
  const requestedPages = Number.parseInt(String(req.body?.maxPages || (wholeSite ? 120 : 8)), 10) || 8;
  const requestedDepth = Number.parseInt(String(req.body?.maxDepth || (wholeSite ? 12 : 2)), 10) || 2;
  const requestedBudget = Number.parseInt(String(req.body?.timeBudgetMs || (wholeSite ? 105000 : 55000)), 10) || 55000;
  const maxPages = Math.max(1, Math.min(MAX_PAGES_HARD, requestedPages));
  const maxDepth = Math.max(0, Math.min(MAX_DEPTH_HARD, requestedDepth));
  const timeBudgetMs = Math.max(15000, Math.min(150000, requestedBudget));
  const origin = new URL(startUrl).origin;
  const internalBase = resolveInternalBase(req);
  const candidates = new Map();
  const visited = new Set();
  const pages = [];
  const failures = [];
  const stats = { duplicates: 0, discoveryLimitReached: false };
  const startedAt = Date.now();

  addCandidate(candidates, startUrl, 'start page', 0, origin, query, stats);

  try {
    const sitemapResponse = await postJson(`${internalBase}/api/read-sitemap`, {
      url: startUrl,
      query,
      wholeSite,
      maxUrls: wholeSite ? MAX_DISCOVERED : 250,
      maxSitemaps: wholeSite ? 64 : 8
    }, 22000);
    const sitemap = sitemapResponse?.ok ? sitemapResponse.json : null;
    const sitemapUrls = sitemap?.data?.urls || sitemap?.urls || [];
    for (const url of sitemapUrls) addCandidate(candidates, url, 'sitemap', 1, origin, query, stats);

    while (pages.length < maxPages && Date.now() - startedAt < timeBudgetMs) {
      const batchSize = wholeSite ? WHOLE_SITE_BATCH_SIZE : 1;
      const batch = selectNextCandidates(candidates, visited, maxDepth, batchSize, wholeSite);
      if (!batch.length) break;
      for (const item of batch) visited.add(item.url);

      const results = await Promise.all(batch.map(async (item) => {
        const result = await postJson(`${internalBase}/api/read-url`, { url: item.url }, 36000);
        return { item, result };
      }));

      for (const { item, result } of results) {
        if (!result?.ok) {
          failures.push({ url: item.url, status: result?.status || 0, error: result?.error || 'read failed' });
          continue;
        }
        const data = result.json?.data || result.json;
        if (!data?.markdown) {
          failures.push({ url: item.url, status: result.status, error: 'empty content' });
          continue;
        }

        const headings = Array.isArray(data.headings) ? data.headings : [];
        pages.push({
          url: data.url || item.url,
          title: data.title || item.label || item.url,
          markdown: String(data.markdown).slice(0, wholeSite ? 5000 : 14000),
          headings,
          tables: Array.isArray(data.tables) ? data.tables.slice(0, 6) : [],
          structuredData: Array.isArray(data.structuredData) ? data.structuredData.slice(0, 6) : [],
          reader: data.reader || result.json?.reader || 'unknown',
          depth: item.depth
        });

        if (item.depth < maxDepth && Array.isArray(data.links)) {
          for (const link of data.links) {
            if (link?.internal !== false) addCandidate(candidates, link.url, link.text, item.depth + 1, origin, query, stats);
          }
        }
      }
    }

    const pending = Array.from(candidates.values()).filter((item) => !visited.has(item.url) && item.depth <= maxDepth).length;
    const timedOut = Date.now() - startedAt >= timeBudgetMs;
    const complete = pending === 0 && !timedOut && !stats.discoveryLimitReached && pages.length < maxPages;
    const coverage = {
      origin,
      wholeSite,
      discovered: candidates.size,
      attempted: visited.size,
      succeeded: pages.length,
      failed: failures.length,
      pending,
      duplicates: stats.duplicates,
      discoveryLimitReached: stats.discoveryLimitReached,
      pageLimitReached: pages.length >= maxPages && pending > 0,
      timedOut,
      complete
    };

    const context = buildContext(pages, query, coverage, wholeSite);
    return res.status(200).json({
      success: pages.length > 0,
      startUrl,
      origin,
      query,
      wholeSite,
      maxPages,
      maxDepth,
      pagesRead: pages.length,
      pages,
      failures: failures.slice(0, 100),
      coverage,
      sources: pages.map((page) => ({ title: page.title, url: page.url, reader: page.reader })),
      context,
      data: { pages, context, coverage, failures: failures.slice(0, 100) }
    });
  } catch (error) {
    return res.status(500).json({
      error: 'Site crawl failed',
      message: error instanceof Error ? error.message : String(error),
      startUrl
    });
  }
}
