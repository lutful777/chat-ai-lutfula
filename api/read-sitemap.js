const MAX_SITEMAPS = 64;
const MAX_URLS = 5000;
const MAX_RESPONSE_BYTES = 2_000_000;

function isPrivateIpv4(host) {
  const parts = host.split('.').map(Number);
  if (parts.length !== 4 || parts.some((x) => !Number.isInteger(x) || x < 0 || x > 255)) return false;
  return parts[0] === 10 || parts[0] === 127 || parts[0] === 0 ||
    (parts[0] === 169 && parts[1] === 254) ||
    (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) ||
    (parts[0] === 192 && parts[1] === 168) ||
    (parts[0] === 100 && parts[1] >= 64 && parts[1] <= 127) ||
    parts[0] >= 224;
}

async function normalizePublicUrl(input) {
  const raw = String(input || '').trim();
  const u = new URL(/^https?:\/\//i.test(raw) ? raw : `https://${raw}`);
  if (!['http:', 'https:'].includes(u.protocol) || u.username || u.password) throw new Error('Invalid URL');
  const host = u.hostname.toLowerCase().replace(/\.$/, '');
  if (!host || host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') ||
      host === 'metadata.google.internal' || host === '169.254.169.254' || host === '100.100.100.200' ||
      isPrivateIpv4(host) || host === '::1' || host.startsWith('fc') || host.startsWith('fd') || host.startsWith('fe80:')) {
    throw new Error('Private or local addresses are not allowed');
  }
  u.hash = '';
  return u;
}

async function fetchText(url, timeoutMs = 12000) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      headers: { 'User-Agent': 'LutfulaResearchBot/1.0 (+public-site-research)' },
      redirect: 'follow',
      signal: controller.signal
    });
    if (!response.ok) return null;
    const contentLength = Number(response.headers.get('content-length') || 0);
    if (contentLength > MAX_RESPONSE_BYTES) return null;
    const text = await response.text();
    return text.slice(0, MAX_RESPONSE_BYTES);
  } catch (_) {
    return null;
  } finally {
    clearTimeout(timeout);
  }
}

function extractSitemapsFromRobots(text, origin) {
  const urls = [];
  for (const line of String(text || '').split(/\r?\n/)) {
    const match = line.match(/^\s*sitemap\s*:\s*(\S+)/i);
    if (!match) continue;
    try {
      const url = new URL(match[1], origin).toString();
      if (!urls.includes(url)) urls.push(url);
    } catch (_) {}
  }
  return urls;
}

function extractLocs(xml, limit = MAX_URLS) {
  const urls = [];
  const regex = /<loc\b[^>]*>([\s\S]*?)<\/loc>/gi;
  let match;
  while ((match = regex.exec(String(xml || ''))) && urls.length < limit) {
    const value = match[1]
      .replace(/&amp;/gi, '&')
      .replace(/&lt;/gi, '<')
      .replace(/&gt;/gi, '>')
      .trim();
    if (value) urls.push(value);
  }
  return urls;
}

function looksLikeSitemapIndex(xml) {
  return /<sitemapindex\b/i.test(String(xml || ''));
}

function scoreUrl(url, query) {
  const value = String(url || '').toLowerCase();
  const tokens = String(query || '').toLowerCase().split(/[^a-z0-9]+/).filter((x) => x.length > 2);
  let score = 0;
  for (const token of tokens) if (value.includes(token)) score += 3;
  for (const keyword of ['model', 'models', 'docs', 'documentation', 'api', 'pricing', 'catalog', 'provider', 'changelog', 'reference']) {
    if (value.includes(keyword)) score += 2;
  }
  return score;
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });

  let root;
  try {
    root = await normalizePublicUrl(req.body?.url || req.body?.domain);
  } catch (error) {
    return res.status(400).json({ error: 'Missing or invalid public URL', message: error.message });
  }

  const origin = root.origin;
  const query = String(req.body?.query || '');
  const wholeSite = req.body?.wholeSite === true;
  const requestedUrls = Number.parseInt(String(req.body?.maxUrls || (wholeSite ? MAX_URLS : 200)), 10) || 200;
  const requestedSitemaps = Number.parseInt(String(req.body?.maxSitemaps || (wholeSite ? MAX_SITEMAPS : 8)), 10) || 8;
  const maxUrls = Math.max(10, Math.min(MAX_URLS, requestedUrls));
  const maxSitemaps = Math.max(1, Math.min(MAX_SITEMAPS, requestedSitemaps));

  try {
    const robotsUrl = new URL('/robots.txt', origin).toString();
    const robotsText = await fetchText(robotsUrl);
    const candidates = extractSitemapsFromRobots(robotsText, origin);
    for (const path of ['/sitemap.xml', '/sitemap_index.xml', '/sitemap-index.xml']) {
      const candidate = new URL(path, origin).toString();
      if (!candidates.includes(candidate)) candidates.push(candidate);
    }

    const queue = [];
    const queuedSitemaps = new Set();
    for (const candidate of candidates) {
      if (queue.length >= maxSitemaps) break;
      if (!queuedSitemaps.has(candidate)) {
        queuedSitemaps.add(candidate);
        queue.push(candidate);
      }
    }

    const visitedSitemaps = new Set();
    const pageUrls = new Set();
    let sitemapLimitReached = false;
    let urlLimitReached = false;

    while (queue.length && visitedSitemaps.size < maxSitemaps && pageUrls.size < maxUrls) {
      const sitemapUrl = queue.shift();
      if (!sitemapUrl || visitedSitemaps.has(sitemapUrl)) continue;
      visitedSitemaps.add(sitemapUrl);
      const xml = await fetchText(sitemapUrl);
      if (!xml || !/<(?:urlset|sitemapindex)\b/i.test(xml)) continue;
      const locs = extractLocs(xml, maxUrls);

      if (looksLikeSitemapIndex(xml)) {
        for (const loc of locs) {
          try {
            const parsed = await normalizePublicUrl(loc);
            const normalized = parsed.toString();
            if (parsed.origin !== origin || visitedSitemaps.has(normalized) || queuedSitemaps.has(normalized)) continue;
            if (queuedSitemaps.size >= maxSitemaps) {
              sitemapLimitReached = true;
              break;
            }
            queuedSitemaps.add(normalized);
            queue.push(normalized);
          } catch (_) {}
        }
      } else {
        for (const loc of locs) {
          if (pageUrls.size >= maxUrls) {
            urlLimitReached = true;
            break;
          }
          try {
            const parsed = await normalizePublicUrl(loc);
            if (parsed.origin === origin) pageUrls.add(parsed.toString());
          } catch (_) {}
        }
      }
    }

    if (visitedSitemaps.size >= maxSitemaps && queue.length > 0) sitemapLimitReached = true;
    if (pageUrls.size >= maxUrls) urlLimitReached = true;

    const discovered = Array.from(pageUrls);
    const urls = wholeSite
      ? discovered.sort((a, b) => a.localeCompare(b))
      : discovered
          .map((url) => ({ url, score: scoreUrl(url, query) }))
          .sort((a, b) => b.score - a.score || a.url.localeCompare(b.url))
          .map((item) => item.url);

    const coverage = {
      wholeSite,
      sitemapsDiscovered: queuedSitemaps.size,
      sitemapsProcessed: visitedSitemaps.size,
      urlsFound: urls.length,
      sitemapLimitReached,
      urlLimitReached,
      complete: queue.length === 0 && !sitemapLimitReached && !urlLimitReached
    };

    return res.status(200).json({
      success: urls.length > 0,
      origin,
      robotsUrl,
      robotsFound: Boolean(robotsText),
      sitemaps: Array.from(visitedSitemaps),
      urls,
      coverage,
      data: { origin, sitemaps: Array.from(visitedSitemaps), urls, coverage }
    });
  } catch (error) {
    return res.status(500).json({
      error: 'Sitemap discovery failed',
      message: error instanceof Error ? error.message : String(error),
      origin
    });
  }
}
