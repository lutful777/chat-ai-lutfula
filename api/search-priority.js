import { URL } from 'url';
import legacySearchHandler from './search.js';

const DAY_MS = 24 * 60 * 60 * 1000;
const ARTICLE_TIMEOUT_MS = 6000;
const SEARCH_LIMIT = 20;
const TARGET_ARTICLES = 5;

const PREFERRED_NEWS_SOURCES = [
  { name: 'Reuters', hosts: ['reuters.com'] },
  { name: 'Associated Press', hosts: ['apnews.com'] },
  { name: 'BBC', hosts: ['bbc.com', 'bbc.co.uk'] },
  { name: 'CNBC', hosts: ['cnbc.com', 'cnbcindonesia.com'] },
  { name: 'Kompas', hosts: ['kompas.com'] },
  { name: 'Tempo', hosts: ['tempo.co'] },
  { name: 'Antara', hosts: ['antaranews.com'] }
];

function cleanText(input) {
  return String(input || '')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/\s+/g, ' ')
    .trim();
}

function cleanNewsText(input) {
  return cleanText(String(input || '')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/https?:\/\/[^\s)\]}]+/gi, ' ')
    .replace(/www\.[^\s)\]}]+/gi, ' ')
    .replace(/[_*`~]+/g, ' '));
}

function shorten(input, max) {
  const text = cleanNewsText(input);
  if (text.length <= max) return text;
  const clipped = text.slice(0, max);
  const sentenceEnd = Math.max(
    clipped.lastIndexOf('. '),
    clipped.lastIndexOf('! '),
    clipped.lastIndexOf('? ')
  );
  if (sentenceEnd >= Math.floor(max * 0.55)) return clipped.slice(0, sentenceEnd + 1).trim();
  return clipped.replace(/\s+\S*$/, '').trim() + '...';
}

function normalizedUrl(input) {
  try {
    const url = new URL(String(input || '').trim());
    if (!['http:', 'https:'].includes(url.protocol)) return '';
    return url.toString();
  } catch (_) {
    return '';
  }
}

function preferredSource(urlStr) {
  try {
    const host = new URL(urlStr).hostname.toLowerCase().replace(/^www\./, '');
    for (const source of PREFERRED_NEWS_SOURCES) {
      if (source.hosts.some((domain) => host === domain || host.endsWith(`.${domain}`))) {
        return source;
      }
    }
  } catch (_) {}
  return null;
}

function sourceLabel(urlStr, metadata) {
  const preferred = preferredSource(urlStr);
  if (preferred) return preferred.name;
  const explicit = cleanText(
    metadata?.siteName || metadata?.publisher || metadata?.['og:site_name'] || ''
  );
  if (explicit) return explicit.slice(0, 100);
  try { return new URL(urlStr).hostname.replace(/^www\./i, ''); } catch (_) { return ''; }
}

function isBlockedSource(urlStr) {
  try {
    const url = new URL(urlStr);
    const host = url.hostname.toLowerCase();
    return [
      'youtube.com', 'youtu.be', 'tiktok.com', 'instagram.com',
      'facebook.com', 'twitter.com', 'x.com'
    ].some((domain) => host === domain || host.endsWith(`.${domain}`)) ||
      url.pathname.toLowerCase().includes('/login') ||
      url.pathname.toLowerCase().includes('/search');
  } catch (_) {
    return true;
  }
}

async function fetchWithTimeout(url, options = {}, timeoutMs = ARTICLE_TIMEOUT_MS) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

async function firecrawlSearch(query, token) {
  const endpoint = 'https://' + ['api', 'firecrawl', 'dev'].join('.') + '/v1/search';
  const response = await fetchWithTimeout(endpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`
    },
    body: JSON.stringify({ query, limit: SEARCH_LIMIT, tbs: 'sbd:1,qdr:d' })
  }, 8000);
  if (!response.ok) return [];
  const json = await response.json().catch(() => ({}));
  return Array.isArray(json.data) ? json.data : (Array.isArray(json.results) ? json.results : []);
}

async function firecrawlScrape(pageUrl, token) {
  const endpoint = 'https://' + ['api', 'firecrawl', 'dev'].join('.') + '/v1/scrape';
  const response = await fetchWithTimeout(endpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`
    },
    body: JSON.stringify({ url: pageUrl, formats: ['markdown', 'html'] })
  });
  if (!response.ok) return null;
  const json = await response.json().catch(() => ({}));
  const data = json.data || json;
  return {
    html: data.html || '',
    markdown: data.markdown || data.content || '',
    metadata: data.metadata || {}
  };
}

function extractImage(html, metadata) {
  const candidates = [
    metadata?.ogImage, metadata?.twitterImage, metadata?.image,
    metadata?.['og:image'], metadata?.['twitter:image']
  ];
  for (const candidate of candidates) {
    const value = Array.isArray(candidate) ? candidate[0] : candidate;
    const url = normalizedUrl(value);
    if (url && !/favicon|logo|avatar|icon|placeholder|\.svg(?:$|\?)/i.test(url)) return url;
  }
  const match = String(html || '').match(
    /<meta[^>]*(?:property|name)=["'](?:og:image|twitter:image)["'][^>]*content=["']([^"']+)["']/i
  ) || String(html || '').match(
    /<meta[^>]*content=["']([^"']+)["'][^>]*(?:property|name)=["'](?:og:image|twitter:image)["']/i
  );
  return normalizedUrl(match?.[1]) || null;
}

function extractPublished(html, metadata, row) {
  const candidates = [
    row?.publishedAt, row?.date, row?.metadata?.publishedAt, row?.metadata?.datePublished,
    metadata?.publishedAt, metadata?.datePublished, metadata?.date,
    metadata?.['article:published_time']
  ];
  for (const candidate of candidates) {
    if (candidate !== undefined && candidate !== null && String(candidate).trim()) return candidate;
  }
  const match = String(html || '').match(
    /<meta[^>]*(?:property|name|itemprop)=["'](?:article:published_time|datePublished|date|pubdate)["'][^>]*content=["']([^"']+)["']/i
  ) || String(html || '').match(/<time[^>]*datetime=["']([^"']+)["']/i);
  return match?.[1] || '';
}

function parsePublished(value, now = Date.now()) {
  const raw = cleanText(value).toLowerCase();
  if (!raw) return null;
  if (/baru saja|just now/.test(raw)) return now;
  const relative = raw.match(/(\d+)\s*(menit|minute|minutes|jam|hour|hours|hari|day|days)/i);
  if (relative) {
    const amount = Number(relative[1]);
    const unit = relative[2];
    if (/menit|minute/.test(unit)) return now - amount * 60 * 1000;
    if (/jam|hour/.test(unit)) return now - amount * 60 * 60 * 1000;
    if (/hari|day/.test(unit)) return now - amount * DAY_MS;
  }
  const parsed = Date.parse(raw);
  return Number.isFinite(parsed) ? parsed : null;
}

function completeDescription(html, markdown, metadata) {
  const parts = [];
  const meta = metadata?.description || metadata?.ogDescription || metadata?.['og:description'];
  if (meta) parts.push(cleanNewsText(meta));
  const paragraphs = [...String(html || '').matchAll(/<p\b[^>]*>([\s\S]*?)<\/p>/gi)];
  for (const paragraph of paragraphs) {
    const text = cleanNewsText(paragraph[1]);
    if (text.length < 55 || /cookie|privacy|login|subscribe|newsletter|iklan/i.test(text)) continue;
    parts.push(text);
    if (parts.join(' ').length >= 900 || parts.length >= 4) break;
  }
  if (parts.length === 0) parts.push(cleanNewsText(markdown));
  return shorten([...new Set(parts)].join(' '), 760);
}

function prioritizeRows(rows) {
  const buckets = new Map(PREFERRED_NEWS_SOURCES.map((source) => [source.name, []]));
  const others = [];
  for (const row of rows) {
    const url = normalizedUrl(row?.url || row?.sourceURL || row?.metadata?.sourceURL || '');
    const preferred = preferredSource(url);
    if (preferred) buckets.get(preferred.name).push(row);
    else others.push(row);
  }
  const ordered = [];
  let added = true;
  while (added) {
    added = false;
    for (const source of PREFERRED_NEWS_SOURCES) {
      const bucket = buckets.get(source.name);
      if (bucket?.length) {
        ordered.push(bucket.shift());
        added = true;
      }
    }
  }
  return ordered.concat(others);
}

async function translateText(input, maxLength) {
  const source = shorten(input, maxLength);
  if (!source) return '';
  try {
    const endpoint = new URL('https://translate.googleapis.com/translate_a/single');
    endpoint.searchParams.set('client', 'gtx');
    endpoint.searchParams.set('sl', 'auto');
    endpoint.searchParams.set('tl', 'id');
    endpoint.searchParams.set('dt', 't');
    endpoint.searchParams.set('q', source);
    const response = await fetchWithTimeout(endpoint, {
      headers: { Accept: 'application/json', 'User-Agent': 'Mozilla/5.0' }
    }, 6000);
    if (!response.ok) return source;
    const json = await response.json();
    const translated = Array.isArray(json?.[0])
      ? json[0].map((part) => Array.isArray(part) ? (part[0] || '') : '').join('')
      : '';
    return shorten(translated || source, maxLength);
  } catch (_) {
    return source;
  }
}

async function inspectArticle(row, token, now) {
  const pageUrl = normalizedUrl(row?.url || row?.sourceURL || row?.metadata?.sourceURL || '');
  if (!pageUrl || isBlockedSource(pageUrl)) return null;
  try {
    const page = await firecrawlScrape(pageUrl, token);
    if (!page) return null;
    const imageUrl = extractImage(page.html, page.metadata);
    if (!imageUrl) return null;
    const publishedTimestamp = parsePublished(extractPublished(page.html, page.metadata, row), now);
    if (!publishedTimestamp || now - publishedTimestamp < -15 * 60 * 1000 || now - publishedTimestamp > DAY_MS) return null;
    const title = shorten(row?.title || row?.metadata?.title || page.metadata?.title || '', 180);
    const description = completeDescription(page.html, page.markdown, page.metadata);
    if (!title || description.length < 80) return null;
    return {
      title,
      description,
      imageUrl,
      source: sourceLabel(pageUrl, page.metadata)
    };
  } catch (_) {
    return null;
  }
}

export default async function handler(req, res) {
  const mode = typeof req.query.mode === 'string' ? req.query.mode.trim().toLowerCase() : 'cari';
  if (mode !== 'berita' && mode !== 'news') return legacySearchHandler(req, res);

  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  res.setHeader('Cache-Control', 's-maxage=300, stale-while-revalidate=600');
  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'GET') return res.status(405).json({ error: 'Method not allowed' });

  const q = typeof req.query.q === 'string' ? req.query.q.trim() : '';
  if (!q) return res.status(400).json({ error: 'Missing query parameter q' });
  const token = process.env.FIRECRAWL_API_KEY;
  if (!token) return res.status(500).json({ error: 'FIRECRAWL_API_KEY not set' });

  try {
    const queries = [`${q} berita terbaru`, `${q} Reuters AP BBC CNBC Kompas Tempo Antara`];
    const batches = await Promise.all(queries.map((query) => firecrawlSearch(query, token).catch(() => [])));
    const seen = new Set();
    const rows = [];
    for (const row of batches.flat()) {
      const url = normalizedUrl(row?.url || row?.sourceURL || row?.metadata?.sourceURL || '');
      if (!url || seen.has(url)) continue;
      seen.add(url);
      rows.push(row);
    }

    const orderedRows = prioritizeRows(rows).slice(0, 16);
    const valid = [];
    const now = Date.now();
    for (let index = 0; index < orderedRows.length && valid.length < TARGET_ARTICLES; index += 4) {
      const batch = orderedRows.slice(index, index + 4);
      const inspected = await Promise.all(batch.map((row) => inspectArticle(row, token, now)));
      for (const article of inspected) {
        if (article) valid.push(article);
        if (valid.length >= TARGET_ARTICLES) break;
      }
    }

    const translated = await Promise.all(valid.slice(0, TARGET_ARTICLES).map(async (article, index) => ({
      title: await translateText(article.title, 180),
      description: await translateText(article.description, 760),
      url: `news-item-${Date.now()}-${index + 1}`,
      imageUrl: article.imageUrl,
      publishedAt: '',
      source: article.source,
      reader: 'firecrawl-priority',
      freshWithin24Hours: true,
      translatedTo: 'id'
    })));

    return res.status(200).json({
      query: q,
      mode: 'berita',
      limit: SEARCH_LIMIT,
      todayOnly: true,
      allowOlder: false,
      translatedTo: 'id',
      minimumRequested: TARGET_ARTICLES,
      returned: translated.length,
      completeMinimum: translated.length >= TARGET_ARTICLES,
      preferredSources: PREFERRED_NEWS_SOURCES.map((source) => source.name),
      data: translated
    });
  } catch (error) {
    return res.status(500).json({
      error: 'Realtime news search failed',
      message: error instanceof Error ? error.message : String(error)
    });
  }
}
