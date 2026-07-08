const GENERAL_LIMIT = 5;
const NEWS_RESULT_LIMIT = 5;
const NEWS_SEARCH_LIMIT = 16;
const NEWS_SCRAPE_LIMIT = 10;
const MAX_DESCRIPTION = 700;

const BLOCKED_NEWS_HOSTS = [
  'youtube.com', 'youtu.be', 'tiktok.com', 'instagram.com', 'facebook.com',
  'fb.com', 'twitter.com', 'x.com', 'threads.net', 'pinterest.com'
];

const ERROR_PATTERNS = [
  /\b403\b/i, /\b404\b/i, /forbidden/i, /access denied/i,
  /you do not have access/i, /page not found/i, /verify you are human/i,
  /captcha/i, /sign in to continue/i, /enable javascript/i,
  /temporarily unavailable/i, /internal server error/i
];

const BAD_IMAGE_HINTS = [
  'favicon', 'logo', 'avatar', 'profile', 'sprite', 'badge', 'tracking',
  'pixel.gif', 'spacer', 'emoji', 'advert', '/ads/', 'doubleclick',
  'analytics', 'placeholder', 'loading.gif', 'blank.gif', '/icon/'
];

function decodeHtmlEntities(value) {
  return String(value || '')
    .replace(/&amp;/gi, '&')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&nbsp;/gi, ' ');
}

function cleanText(value) {
  return decodeHtmlEntities(value)
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

function shorten(value, max = MAX_DESCRIPTION) {
  const text = cleanText(value);
  if (text.length <= max) return text;
  return `${text.slice(0, max).replace(/\s+\S*$/, '')}...`;
}

function summarize(value) {
  const text = cleanText(value);
  if (!text) return '';
  const sentences = text.match(/[^.!?]+[.!?]+|[^.!?]+$/g) || [text];
  const selected = [];
  let total = 0;
  for (const sentence of sentences) {
    const part = sentence.trim();
    if (!part) continue;
    if (selected.length && total + part.length > MAX_DESCRIPTION) break;
    selected.push(part);
    total += part.length + 1;
    if (selected.length >= 4) break;
  }
  return shorten(selected.join(' '));
}

function normalizedUrl(value, baseUrl = '') {
  try {
    const raw = String(value || '').trim();
    if (!raw || raw.startsWith('data:') || raw.startsWith('blob:')) return '';
    const url = baseUrl ? new URL(raw, baseUrl) : new URL(raw);
    if (!['http:', 'https:'].includes(url.protocol)) return '';
    if (url.username || url.password) return '';
    const host = url.hostname.toLowerCase();
    if (!host || host === 'localhost' || host.endsWith('.local')) return '';
    if (/^(127\.|10\.|192\.168\.|169\.254\.)/.test(host)) return '';
    const private172 = host.match(/^172\.(\d{1,3})\./);
    if (private172 && Number(private172[1]) >= 16 && Number(private172[1]) <= 31) return '';
    url.hash = '';
    return url.toString();
  } catch (_) {
    return '';
  }
}

function hostnameOf(value) {
  try {
    return new URL(value).hostname.toLowerCase().replace(/^www\./, '');
  } catch (_) {
    return '';
  }
}

function isBlockedNewsUrl(value) {
  const host = hostnameOf(value);
  if (!host) return true;
  return BLOCKED_NEWS_HOSTS.some(blocked => host === blocked || host.endsWith(`.${blocked}`));
}

function isErrorPageText(value) {
  const text = cleanText(value);
  return !text || ERROR_PATTERNS.some(pattern => pattern.test(text));
}

function getAttributes(tag) {
  const attributes = {};
  const pattern = /([:\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/g;
  let match;
  while ((match = pattern.exec(tag)) !== null) {
    attributes[match[1].toLowerCase()] = decodeHtmlEntities(match[2] || match[3] || match[4] || '').trim();
  }
  return attributes;
}

function metaValues(html, names) {
  const wanted = new Set(names.map(name => name.toLowerCase()));
  const values = [];
  for (const tag of String(html || '').match(/<meta\b[^>]*>/gi) || []) {
    const attrs = getAttributes(tag);
    const key = String(attrs.property || attrs.name || '').toLowerCase();
    if (wanted.has(key) && attrs.content) values.push(attrs.content);
  }
  return values;
}

function firstString(...values) {
  for (const value of values) {
    if (typeof value === 'string' && value.trim()) return value.trim();
    if (Array.isArray(value)) {
      const first = value.find(item => typeof item === 'string' && item.trim());
      if (first) return first.trim();
    }
    if (value && typeof value === 'object') {
      const nested = value.url || value.src;
      if (typeof nested === 'string' && nested.trim()) return nested.trim();
    }
  }
  return '';
}

function isUsableImage(value, width = 0, height = 0) {
  const url = normalizedUrl(value);
  if (!url) return false;
  const lower = url.toLowerCase();
  if (BAD_IMAGE_HINTS.some(hint => lower.includes(hint))) return false;
  if (/\.(svg|ico)(\?|$)/i.test(lower)) return false;
  if (width && width < 480) return false;
  if (height && height < 240) return false;
  if (width && height) {
    const ratio = width / height;
    if (ratio < 0.45 || ratio > 3.5) return false;
  }
  return true;
}

function parseSrcset(value, pageUrl) {
  return String(value || '')
    .split(',')
    .map(part => {
      const [rawUrl, descriptor = ''] = part.trim().split(/\s+/);
      return {
        url: normalizedUrl(rawUrl, pageUrl),
        width: descriptor.endsWith('w') ? Number(descriptor.slice(0, -1)) || 0 : 0
      };
    })
    .filter(item => item.url)
    .sort((a, b) => b.width - a.width);
}

function extractImage(html, metadata = {}, pageUrl = '') {
  const candidates = [];
  const add = (rawUrl, width = 0, height = 0) => {
    const url = normalizedUrl(rawUrl, pageUrl);
    if (isUsableImage(url, Number(width) || 0, Number(height) || 0)) candidates.push(url);
  };

  [
    metadata.ogImage, metadata.ogImageUrl, metadata.twitterImage,
    metadata.twitterImageUrl, metadata.image
  ].forEach(value => {
    if (Array.isArray(value)) value.forEach(item => add(typeof item === 'string' ? item : item?.url, item?.width, item?.height));
    else if (value && typeof value === 'object') add(value.url || value.src, value.width, value.height);
    else add(value);
  });

  metaValues(html, ['og:image:secure_url', 'og:image', 'twitter:image:src', 'twitter:image'])
    .forEach(value => add(value));

  for (const tag of String(html || '').match(/<img\b[^>]*>/gi) || []) {
    const attrs = getAttributes(tag);
    const width = Number(String(attrs.width || '').replace(/[^0-9.]/g, '')) || 0;
    const height = Number(String(attrs.height || '').replace(/[^0-9.]/g, '')) || 0;
    const srcset = parseSrcset(attrs.srcset || attrs['data-srcset'], pageUrl);
    if (srcset.length) add(srcset[0].url, srcset[0].width || width, height);
    [attrs.src, attrs['data-src'], attrs['data-lazy-src'], attrs['data-original']]
      .forEach(value => add(value, width, height));
  }

  return [...new Set(candidates)][0] || null;
}

function extractPublishedAt(html, metadata = {}) {
  const raw = firstString(
    metadata.publishedAt,
    metadata.publishedTime,
    metadata.datePublished,
    metadata.articlePublishedTime,
    metadata.date,
    metaValues(html, ['article:published_time', 'datePublished', 'date', 'pubdate'])
  );
  if (!raw) return { iso: '', display: '' };
  const date = new Date(raw);
  if (Number.isNaN(date.getTime())) return { iso: '', display: shorten(raw, 80) };
  const display = new Intl.DateTimeFormat('id-ID', {
    timeZone: 'Asia/Jakarta',
    day: 'numeric', month: 'long', year: 'numeric',
    hour: '2-digit', minute: '2-digit'
  }).format(date).replace('.', ':') + ' WIB';
  return { iso: date.toISOString(), display };
}

function queryTokens(query) {
  const stop = new Set(['yang', 'dan', 'atau', 'apa', 'apakah', 'kenapa', 'mengapa', 'berita', 'terbaru', 'hari', 'ini']);
  return cleanText(query).toLowerCase().split(/[^\p{L}\p{N}]+/u)
    .filter(token => token.length > 2 && !stop.has(token));
}

function relevanceScore(query, title, description) {
  const titleLower = cleanText(title).toLowerCase();
  const descriptionLower = cleanText(description).toLowerCase();
  return queryTokens(query).reduce((score, token) => {
    return score + (titleLower.includes(token) ? 8 : 0) + (descriptionLower.includes(token) ? 2 : 0);
  }, 0);
}

function recencyScore(iso) {
  if (!iso) return 0;
  const timestamp = new Date(iso).getTime();
  if (Number.isNaN(timestamp)) return 0;
  const hours = Math.max(0, (Date.now() - timestamp) / 3600000);
  if (hours <= 24) return 20;
  if (hours <= 72) return 14;
  if (hours <= 168) return 8;
  if (hours <= 720) return 2;
  return -8;
}

async function firecrawlRequest(endpoint, token, body, timeoutMs = 25000) {
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

async function scrapeArticle(url, token) {
  try {
    const { response, json } = await firecrawlRequest(
      'https://api.firecrawl.dev/v1/scrape',
      token,
      { url, formats: ['markdown', 'html'], onlyMainContent: true },
      22000
    );
    if (!response.ok) return null;
    const data = json.data || json;
    return {
      metadata: data.metadata || {},
      html: data.html || '',
      markdown: data.markdown || data.content || ''
    };
  } catch (_) {
    return null;
  }
}

function selectDescription(row, page) {
  const metadata = page?.metadata || {};
  const candidates = [
    metadata.description,
    metadata.ogDescription,
    metadata.twitterDescription,
    row.description,
    row.snippet,
    row.content,
    page?.markdown
  ];
  for (const value of candidates) {
    const description = summarize(value);
    if (description.length >= 80 && !isErrorPageText(description)) return description;
  }
  return '';
}

async function buildNewsCandidate(row, token, query, index) {
  const pageUrl = normalizedUrl(row?.url || row?.sourceURL || row?.metadata?.sourceURL || '');
  if (!pageUrl || isBlockedNewsUrl(pageUrl)) return null;

  const page = await scrapeArticle(pageUrl, token);
  const metadata = page?.metadata || row?.metadata || {};
  const title = shorten(firstString(metadata.title, metadata.ogTitle, row?.title, row?.metadata?.title), 240);
  const description = selectDescription(row, page);
  const imageUrl = extractImage(page?.html || '', metadata, pageUrl);
  if (!title || !description || !imageUrl || isErrorPageText(`${title} ${description}`)) return null;

  const published = extractPublishedAt(page?.html || '', metadata);
  const source = cleanText(firstString(metadata.siteName, metadata.ogSiteName, metadata.source)) || hostnameOf(pageUrl);
  const score = relevanceScore(query, title, description) + recencyScore(published.iso) + Math.max(0, 12 - index);

  return {
    title,
    description,
    imageUrl,
    publishedAt: published.display,
    source,
    score
  };
}

function encodeNewsBatch(items) {
  const payload = items.map(({ title, description, imageUrl, publishedAt, source }) => ({
    title, description, imageUrl, publishedAt, source
  }));
  return `news-batch:${Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url')}`;
}

async function searchNews(query, token) {
  const searchQuery = [
    query, 'berita', '-site:youtube.com', '-site:youtu.be', '-site:tiktok.com',
    '-site:instagram.com', '-site:facebook.com', '-site:x.com', '-site:twitter.com'
  ].join(' ');

  const { response, json } = await firecrawlRequest(
    'https://api.firecrawl.dev/v1/search',
    token,
    { query: searchQuery, limit: NEWS_SEARCH_LIMIT, tbs: 'sbd:1,qdr:w' },
    28000
  );
  if (!response.ok) {
    const error = new Error('Search provider failed');
    error.status = response.status;
    error.details = json;
    throw error;
  }

  const rows = Array.isArray(json.data) ? json.data : (Array.isArray(json.results) ? json.results : []);
  const filtered = rows.filter(row => {
    const url = normalizedUrl(row?.url || row?.sourceURL || row?.metadata?.sourceURL || '');
    return url && !isBlockedNewsUrl(url);
  }).slice(0, NEWS_SCRAPE_LIMIT);

  const settled = await Promise.allSettled(
    filtered.map((row, index) => buildNewsCandidate(row, token, query, index))
  );

  const seen = new Set();
  const articles = settled
    .filter(result => result.status === 'fulfilled' && result.value)
    .map(result => result.value)
    .sort((a, b) => b.score - a.score)
    .filter(item => {
      const key = cleanText(item.title).toLowerCase();
      if (!key || seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .slice(0, NEWS_RESULT_LIMIT)
    .map(({ score, ...item }) => item);

  if (articles.length) {
    const batch = encodeNewsBatch(articles);
    return articles.map((item, index) => index === 0 ? { ...item, imageUrl: batch } : item);
  }
  return [];
}

async function searchGeneral(query, token) {
  const { response, json } = await firecrawlRequest(
    'https://api.firecrawl.dev/v1/search',
    token,
    { query, limit: GENERAL_LIMIT },
    25000
  );
  if (!response.ok) {
    const error = new Error('Search provider failed');
    error.status = response.status;
    error.details = json;
    throw error;
  }

  const rows = Array.isArray(json.data) ? json.data : (Array.isArray(json.results) ? json.results : []);
  return rows.slice(0, GENERAL_LIMIT).map(row => ({
    title: shorten(row?.title || row?.metadata?.title || 'Tanpa judul', 240),
    description: shorten(row?.description || row?.snippet || row?.content || '', 520),
    url: normalizedUrl(row?.url || row?.sourceURL || row?.metadata?.sourceURL || ''),
    reader: 'firecrawl-search'
  })).filter(item => item.url && item.title);
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
  relevanceScore,
  firecrawlRequest
};
