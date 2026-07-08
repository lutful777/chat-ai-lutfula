const DEFAULT_SEARCH_LIMIT = 5;
const NEWS_SEARCH_LIMIT = 18;
const NEWS_SCRAPE_LIMIT = 10;
const MAX_DESCRIPTION = 680;

const BLOCKED_NEWS_HOSTS = [
  'youtube.com', 'youtu.be', 'tiktok.com', 'instagram.com', 'facebook.com',
  'fb.com', 'twitter.com', 'x.com', 'threads.net', 'pinterest.com'
];

const ERROR_TEXT_PATTERNS = [
  /\b403\b/i, /\b404\b/i, /forbidden/i, /access denied/i,
  /you do not have access/i, /page not found/i, /not available/i,
  /sign in to continue/i, /verify you are human/i, /enable javascript/i,
  /captcha/i, /robot check/i, /temporarily unavailable/i,
  /internal server error/i
];

function decodeHtmlEntities(input) {
  return String(input || '')
    .replace(/&amp;/gi, '&')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&nbsp;/gi, ' ');
}

function cleanText(input) {
  return decodeHtmlEntities(input)
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/https?:\/\/\S+/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/[`*_>#|]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function shortText(input, max = MAX_DESCRIPTION) {
  const text = cleanText(input);
  if (text.length <= max) return text;
  return text.slice(0, max).replace(/\s+\S*$/, '') + '...';
}

function summarizeDescription(input) {
  const text = cleanText(input);
  if (!text) return '';
  const sentences = text.match(/[^.!?]+[.!?]+|[^.!?]+$/g) || [text];
  const selected = [];
  let length = 0;
  for (const sentence of sentences) {
    const value = sentence.trim();
    if (!value) continue;
    if (length + value.length > MAX_DESCRIPTION && selected.length > 0) break;
    selected.push(value);
    length += value.length + 1;
    if (selected.length >= 4) break;
  }
  return shortText(selected.join(' '), MAX_DESCRIPTION);
}

function normalizedUrl(input, baseUrl = '') {
  try {
    const raw = String(input || '').trim();
    if (!raw) return '';
    const url = baseUrl ? new URL(raw, baseUrl) : new URL(raw);
    if (!['http:', 'https:'].includes(url.protocol)) return '';
    if (url.username || url.password) return '';
    const host = url.hostname.toLowerCase();
    if (!host || host === 'localhost' || host.endsWith('.local') || host.endsWith('.localhost')) return '';
    url.hash = '';
    return url.toString();
  } catch (_) {
    return '';
  }
}

function hostnameOf(input) {
  try {
    return new URL(input).hostname.toLowerCase().replace(/^www\./, '');
  } catch (_) {
    return '';
  }
}

function isBlockedNewsUrl(input) {
  const host = hostnameOf(input);
  if (!host) return true;
  return BLOCKED_NEWS_HOSTS.some(blocked => host === blocked || host.endsWith('.' + blocked));
}

function isErrorPageText(input) {
  const text = cleanText(input);
  if (!text) return true;
  return ERROR_TEXT_PATTERNS.some(pattern => pattern.test(text));
}

function isUsableArticleText(input) {
  const text = cleanText(input);
  if (text.length < 80 || isErrorPageText(text)) return false;
  const navigationNoise = ['skip navigation', 'search with your voice', 'cookie settings', 'all rights reserved'];
  return navigationNoise.filter(value => text.toLowerCase().includes(value)).length < 2;
}

function metaContent(html, keys) {
  if (!html) return '';
  for (const key of keys) {
    const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const patterns = [
      new RegExp(`<meta[^>]+(?:property|name)=["']${escaped}["'][^>]+content=["']([^"']+)["'][^>]*>`, 'i'),
      new RegExp(`<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']${escaped}["'][^>]*>`, 'i')
    ];
    for (const pattern of patterns) {
      const match = html.match(pattern);
      if (match?.[1]) return decodeHtmlEntities(match[1]).trim();
    }
  }
  return '';
}

function firstString(...values) {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) return value.trim();
    if (Array.isArray(value)) {
      const first = value.find(item => typeof item === 'string' && item.trim());
      if (first) return first.trim();
    }
  }
  return '';
}

function numericMeta(metadata, html, names) {
  for (const name of names) {
    const direct = Number(metadata?.[name]);
    if (Number.isFinite(direct) && direct > 0) return direct;
  }
  const value = Number(metaContent(html, names));
  return Number.isFinite(value) && value > 0 ? value : 0;
}

function extractImage(html, metadata, pageUrl = '') {
  const candidate = firstString(
    metadata?.ogImage,
    metadata?.ogImageUrl,
    metadata?.twitterImage,
    metadata?.twitterImageUrl,
    metadata?.image,
    metadata?.images,
    metaContent(html, ['og:image:secure_url', 'og:image', 'twitter:image:src', 'twitter:image'])
  );
  const imageUrl = normalizedUrl(candidate, pageUrl);
  if (!imageUrl) return null;

  const lower = imageUrl.toLowerCase();
  const badHints = ['favicon', 'logo', 'avatar', 'profile', 'icon-', '/icon/', 'sprite', 'badge', 'tracking', 'pixel.gif'];
  if (badHints.some(hint => lower.includes(hint))) return null;
  if (/\.(svg)(\?|$)/i.test(lower)) return null;

  const width = numericMeta(metadata, html, ['ogImageWidth', 'imageWidth', 'og:image:width']);
  const height = numericMeta(metadata, html, ['ogImageHeight', 'imageHeight', 'og:image:height']);
  if (width && height) {
    if (width < 480 || height < 240) return null;
    const ratio = width / height;
    if (ratio < 1.1 || ratio > 2.5) return null;
  }
  return imageUrl;
}

function extractPublishedAt(html, metadata) {
  const raw = firstString(
    metadata?.publishedAt,
    metadata?.publishedTime,
    metadata?.datePublished,
    metadata?.articlePublishedTime,
    metadata?.date,
    metaContent(html, ['article:published_time', 'datePublished', 'date', 'pubdate'])
  );
  if (!raw) return { iso: '', display: '' };
  const date = new Date(raw);
  if (Number.isNaN(date.getTime())) return { iso: '', display: shortText(raw, 80) };
  const display = new Intl.DateTimeFormat('id-ID', {
    timeZone: 'Asia/Jakarta',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date).replace('.', ':') + ' WIB';
  return { iso: date.toISOString(), display };
}

function sourceNameFor(pageUrl, metadata) {
  const source = cleanText(firstString(metadata?.source, metadata?.siteName, metadata?.ogSiteName));
  return source || hostnameOf(pageUrl);
}

function queryTokens(query) {
  const stop = new Set(['yang', 'dan', 'atau', 'apa', 'apakah', 'kenapa', 'mengapa', 'tentang', 'berita', 'terbaru', 'hari', 'ini']);
  return cleanText(query).toLowerCase().split(/[^\p{L}\p{N}]+/u)
    .filter(token => token.length > 2 && !stop.has(token));
}

function relevanceScore(query, title, description) {
  const tokens = queryTokens(query);
  const titleLower = cleanText(title).toLowerCase();
  const descriptionLower = cleanText(description).toLowerCase();
  let score = 0;
  for (const token of tokens) {
    if (titleLower.includes(token)) score += 7;
    if (descriptionLower.includes(token)) score += 2;
  }
  return score;
}

function recencyScore(publishedAtIso) {
  if (!publishedAtIso) return 0;
  const timestamp = new Date(publishedAtIso).getTime();
  if (Number.isNaN(timestamp)) return 0;
  const ageHours = Math.max(0, (Date.now() - timestamp) / 3600000);
  if (ageHours <= 24) return 18;
  if (ageHours <= 72) return 12;
  if (ageHours <= 168) return 7;
  if (ageHours <= 720) return 2;
  return -6;
}

async function firecrawlRequest(endpoint, token, body, timeoutMs = 22000) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(body),
      signal: controller.signal
    });
    const raw = await response.text();
    let json;
    try { json = JSON.parse(raw); } catch (_) { json = { raw }; }
    return { response, json };
  } finally {
    clearTimeout(timeout);
  }
}

async function readPageWithFirecrawl(pageUrl, token) {
  if (!token || !pageUrl) return null;
  const { response, json } = await firecrawlRequest(
    'https://api.firecrawl.dev/v1/scrape', token,
    { url: pageUrl, formats: ['markdown', 'html'], onlyMainContent: true }
  );
  if (!response.ok) return null;

  const data = json.data || json;
  const metadata = data.metadata || {};
  const html = data.html || '';
  const markdown = data.markdown || data.content || json.markdown || '';
  const metadataDescription = firstString(
    metadata.description,
    metadata.ogDescription,
    metadata.twitterDescription,
    metaContent(html, ['og:description', 'twitter:description', 'description'])
  );
  return { metadata, html, markdown, metadataDescription };
}

function selectDescription(row, page) {
  const candidates = [
    page?.metadataDescription,
    row?.description,
    row?.snippet,
    row?.content,
    page?.markdown
  ];
  for (const candidate of candidates) {
    if (isUsableArticleText(candidate)) return summarizeDescription(candidate);
  }
  return '';
}

async function buildNewsCandidate(row, token, query, index) {
  const pageUrl = normalizedUrl(row?.url || row?.sourceURL || row?.metadata?.sourceURL || '');
  if (!pageUrl || isBlockedNewsUrl(pageUrl)) return null;

  let page = null;
  try { page = await readPageWithFirecrawl(pageUrl, token); } catch (_) { page = null; }

  const metadata = page?.metadata || row?.metadata || {};
  const title = shortText(firstString(metadata.title, metadata.ogTitle, row?.title, row?.metadata?.title), 240);
  const description = selectDescription(row, page);
  if (!title || !description || isErrorPageText(title + ' ' + description)) return null;

  const imageUrl = extractImage(page?.html || '', metadata, pageUrl);
  const published = extractPublishedAt(page?.html || '', metadata);
  const source = sourceNameFor(pageUrl, metadata);

  let score = relevanceScore(query, title, description);
  score += recencyScore(published.iso);
  score += imageUrl ? 14 : 0;
  score += page ? 10 : 0;
  score += Math.max(0, 10 - index);
  if (description.length >= 180) score += 5;

  return {
    title,
    description,
    imageUrl,
    publishedAt: published.display,
    source,
    reader: page ? 'firecrawl-scrape' : 'firecrawl-search',
    score
  };
}

async function searchNews(query, token) {
  const exclusionQuery = [
    query, 'berita', '-site:youtube.com', '-site:youtu.be', '-site:tiktok.com',
    '-site:instagram.com', '-site:facebook.com', '-site:x.com', '-site:twitter.com'
  ].join(' ');

  const { response, json } = await firecrawlRequest(
    'https://api.firecrawl.dev/v1/search', token,
    { query: exclusionQuery, limit: NEWS_SEARCH_LIMIT, tbs: 'sbd:1,qdr:w' }, 26000
  );
  if (!response.ok) {
    const error = new Error('Search provider failed');
    error.status = response.status;
    error.details = json;
    throw error;
  }

  const rows = Array.isArray(json.data) ? json.data : (Array.isArray(json.results) ? json.results : []);
  const filteredRows = rows.filter(row => {
    const url = normalizedUrl(row?.url || row?.sourceURL || row?.metadata?.sourceURL || '');
    return url && !isBlockedNewsUrl(url);
  });

  const candidates = [];
  for (let index = 0; index < Math.min(filteredRows.length, NEWS_SCRAPE_LIMIT); index += 1) {
    const candidate = await buildNewsCandidate(filteredRows[index], token, query, index);
    if (candidate) candidates.push(candidate);
  }
  candidates.sort((a, b) => b.score - a.score);
  return candidates.slice(0, 5).map(({ score, ...item }) => item);
}

async function searchGeneral(query, token, limit = DEFAULT_SEARCH_LIMIT) {
  const { response, json } = await firecrawlRequest(
    'https://api.firecrawl.dev/v1/search', token, { query, limit }, 25000
  );
  if (!response.ok) {
    const error = new Error('Search provider failed');
    error.status = response.status;
    error.details = json;
    throw error;
  }

  const rows = Array.isArray(json.data) ? json.data : (Array.isArray(json.results) ? json.results : []);
  return rows.slice(0, limit).map(row => {
    const pageUrl = normalizedUrl(row?.url || row?.sourceURL || row?.metadata?.sourceURL || '');
    return {
      title: shortText(row?.title || row?.metadata?.title || 'No Title', 240),
      description: shortText(row?.description || row?.snippet || row?.content || '', 520),
      url: pageUrl,
      reader: 'firecrawl-search'
    };
  }).filter(item => item.url && item.title);
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'GET') return res.status(405).json({ error: 'Method not allowed' });

  const query = typeof req.query.q === 'string' ? req.query.q.trim() : '';
  const mode = typeof req.query.mode === 'string' ? req.query.mode.trim().toLowerCase() : 'cari';
  if (!query) return res.status(400).json({ error: 'Missing query parameter q' });

  const token = process.env.FIRECRAWL_API_KEY;
  if (!token) return res.status(500).json({ error: 'FIRECRAWL_API_KEY not set' });

  try {
    const isNews = mode === 'berita' || mode === 'news';
    const data = isNews ? await searchNews(query, token) : await searchGeneral(query, token);
    return res.status(200).json({ success: true, query, mode: isNews ? 'berita' : 'cari', data });
  } catch (error) {
    return res.status(error.status || 500).json({
      error: error.message || 'Realtime search failed',
      details: error.details || undefined
    });
  }
}

export {
  cleanText,
  normalizedUrl,
  isBlockedNewsUrl,
  isErrorPageText,
  extractImage,
  relevanceScore
};
