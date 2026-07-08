const DEFAULT_SEARCH_LIMIT = 5;
const NEWS_SEARCH_LIMIT = 18;
const NEWS_SCRAPE_LIMIT = 10;
const MAX_DESCRIPTION = 680;
const MAX_ARTICLE_IMAGES = 20;

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

const BAD_IMAGE_HINTS = [
  'favicon', 'logo', 'avatar', 'profile', 'icon-', '/icon/', 'sprite',
  'badge', 'tracking', 'pixel.gif', 'spacer', 'emoji', 'advert', '/ads/',
  'doubleclick', 'analytics', 'placeholder', 'loading.gif', 'blank.gif'
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

function isPrivateHostname(hostname) {
  const host = String(hostname || '').toLowerCase().replace(/^\[|\]$/g, '');
  if (!host || host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local')) return true;
  if (host === '::1' || host.startsWith('fe80:') || host.startsWith('fc') || host.startsWith('fd')) return true;
  if (/^127\./.test(host) || /^10\./.test(host) || /^192\.168\./.test(host) || /^169\.254\./.test(host)) return true;
  const private172 = host.match(/^172\.(\d{1,3})\./);
  if (private172) {
    const second = Number(private172[1]);
    if (second >= 16 && second <= 31) return true;
  }
  return false;
}

function normalizedUrl(input, baseUrl = '') {
  try {
    const raw = String(input || '').trim();
    if (!raw || raw.startsWith('data:') || raw.startsWith('blob:')) return '';
    const url = baseUrl ? new URL(raw, baseUrl) : new URL(raw);
    if (!['http:', 'https:'].includes(url.protocol)) return '';
    if (url.username || url.password || isPrivateHostname(url.hostname)) return '';
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

function getAttributes(tag) {
  const attributes = {};
  const regex = /([:\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))/g;
  let match;
  while ((match = regex.exec(tag)) !== null) {
    attributes[match[1].toLowerCase()] = decodeHtmlEntities(match[2] || match[3] || match[4] || '').trim();
  }
  return attributes;
}

function metaContents(html, keys) {
  if (!html) return [];
  const wanted = new Set(keys.map(key => key.toLowerCase()));
  const values = [];
  const tags = html.match(/<meta\b[^>]*>/gi) || [];
  for (const tag of tags) {
    const attrs = getAttributes(tag);
    const key = String(attrs.property || attrs.name || '').toLowerCase();
    if (wanted.has(key) && attrs.content) values.push(attrs.content);
  }
  return values;
}

function metaContent(html, keys) {
  return metaContents(html, keys)[0] || '';
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

function parseSrcset(value, pageUrl) {
  if (!value) return [];
  return String(value)
    .split(',')
    .map(part => {
      const pieces = part.trim().split(/\s+/);
      const url = normalizedUrl(pieces[0], pageUrl);
      const descriptor = pieces[1] || '';
      const width = descriptor.endsWith('w') ? Number(descriptor.slice(0, -1)) || 0 : 0;
      const density = descriptor.endsWith('x') ? Number(descriptor.slice(0, -1)) || 0 : 0;
      return { url, width, density };
    })
    .filter(item => item.url)
    .sort((a, b) => (b.width || b.density * 1000) - (a.width || a.density * 1000));
}

function canonicalImageKey(input) {
  try {
    const url = new URL(input);
    const removable = ['w', 'width', 'h', 'height', 'q', 'quality', 'fit', 'crop', 'format', 'fm', 'auto', 'dpr'];
    for (const key of removable) url.searchParams.delete(key);
    url.hash = '';
    return `${url.hostname.toLowerCase()}${url.pathname}`.replace(/\/+$/, '').toLowerCase();
  } catch (_) {
    return input.toLowerCase();
  }
}

function isUsableImageUrl(imageUrl, width = 0, height = 0) {
  if (!imageUrl) return false;
  const lower = imageUrl.toLowerCase();
  if (BAD_IMAGE_HINTS.some(hint => lower.includes(hint))) return false;
  if (/\.(svg|ico)(\?|$)/i.test(lower)) return false;
  if (width > 0 && width < 300) return false;
  if (height > 0 && height < 180) return false;
  if (width > 0 && height > 0) {
    const ratio = width / height;
    if (ratio < 0.35 || ratio > 4.5) return false;
  }
  return true;
}

function collectJsonLdImages(value, output, depth = 0) {
  if (depth > 8 || value == null) return;
  if (Array.isArray(value)) {
    value.forEach(item => collectJsonLdImages(item, output, depth + 1));
    return;
  }
  if (typeof value !== 'object') return;

  for (const [key, child] of Object.entries(value)) {
    const lowerKey = key.toLowerCase();
    if (['image', 'images', 'thumbnailurl', 'contenturl'].includes(lowerKey)) {
      if (typeof child === 'string') output.push({ rawUrl: child, priority: 80 });
      else collectJsonLdImages(child, output, depth + 1);
    } else if (lowerKey === 'url' && String(value['@type'] || '').toLowerCase().includes('image')) {
      if (typeof child === 'string') output.push({ rawUrl: child, priority: 75 });
    } else {
      collectJsonLdImages(child, output, depth + 1);
    }
  }
}

function extractImages(html, metadata = {}, pageUrl = '', limit = MAX_ARTICLE_IMAGES) {
  const candidates = [];
  const add = (rawUrl, priority = 0, width = 0, height = 0, alt = '') => {
    if (typeof rawUrl !== 'string' || !rawUrl.trim()) return;
    const url = normalizedUrl(rawUrl, pageUrl);
    if (!isUsableImageUrl(url, Number(width) || 0, Number(height) || 0)) return;
    candidates.push({ url, priority, width: Number(width) || 0, height: Number(height) || 0, alt: cleanText(alt) });
  };

  const metadataValues = [
    metadata?.ogImage, metadata?.ogImageUrl, metadata?.twitterImage,
    metadata?.twitterImageUrl, metadata?.image, metadata?.images
  ];
  for (const value of metadataValues) {
    if (Array.isArray(value)) value.forEach(item => add(typeof item === 'string' ? item : item?.url, 100));
    else if (typeof value === 'object' && value) add(value.url || value.src, 100, value.width, value.height);
    else add(value, 100);
  }

  metaContents(html, ['og:image:secure_url', 'og:image', 'twitter:image:src', 'twitter:image'])
    .forEach(value => add(value, 95));

  const imgTags = html?.match(/<img\b[^>]*>/gi) || [];
  imgTags.forEach((tag, index) => {
    const attrs = getAttributes(tag);
    const width = Number(String(attrs.width || '').replace(/[^0-9.]/g, '')) || 0;
    const height = Number(String(attrs.height || '').replace(/[^0-9.]/g, '')) || 0;
    const alt = attrs.alt || attrs.title || '';
    const srcsets = [attrs.srcset, attrs['data-srcset']].flatMap(value => parseSrcset(value, pageUrl));
    if (srcsets.length) add(srcsets[0].url, 70 - Math.min(index, 30), width || srcsets[0].width, height, alt);
    [attrs.src, attrs['data-src'], attrs['data-lazy-src'], attrs['data-original'], attrs['data-url']]
      .forEach(value => add(value, 65 - Math.min(index, 30), width, height, alt));
  });

  const sourceTags = html?.match(/<source\b[^>]*>/gi) || [];
  sourceTags.forEach((tag, index) => {
    const attrs = getAttributes(tag);
    const best = parseSrcset(attrs.srcset || attrs['data-srcset'], pageUrl)[0];
    if (best) add(best.url, 55 - Math.min(index, 20), best.width, 0, '');
  });

  const jsonLdRegex = /<script\b[^>]*type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi;
  let jsonMatch;
  while ((jsonMatch = jsonLdRegex.exec(html || '')) !== null) {
    try {
      const found = [];
      collectJsonLdImages(JSON.parse(jsonMatch[1]), found);
      found.forEach(item => add(item.rawUrl, item.priority));
    } catch (_) {}
  }

  const seen = new Set();
  return candidates
    .map((item, index) => ({
      ...item,
      index,
      score: item.priority + Math.min(item.width, 2400) / 200 + Math.min(item.height, 1600) / 250
    }))
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .filter(item => {
      const key = canonicalImageKey(item.url);
      if (!key || seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .slice(0, limit)
    .map(({ url, width, height, alt }) => ({ url, width, height, alt }));
}

function extractImage(html, metadata, pageUrl = '') {
  return extractImages(html, metadata, pageUrl, 1)[0]?.url || null;
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
    timeZone: 'Asia/Jakarta', day: 'numeric', month: 'long', year: 'numeric',
    hour: '2-digit', minute: '2-digit'
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
    metadata.description, metadata.ogDescription, metadata.twitterDescription,
    metaContent(html, ['og:description', 'twitter:description', 'description'])
  );
  return { metadata, html, markdown, metadataDescription };
}

function selectDescription(row, page) {
  const candidates = [page?.metadataDescription, row?.description, row?.snippet, row?.content, page?.markdown];
  for (const candidate of candidates) {
    if (isUsableArticleText(candidate)) return summarizeDescription(candidate);
  }
  return '';
}

function buildGalleryUrl(req, pageUrl) {
  const forwardedHost = String(req.headers['x-forwarded-host'] || req.headers.host || '').split(',')[0].trim();
  if (!forwardedHost) return '';
  const forwardedProto = String(req.headers['x-forwarded-proto'] || 'https').split(',')[0].trim();
  const protocol = forwardedProto === 'http' ? 'http' : 'https';
  return `${protocol}://${forwardedHost}/api/news-gallery?article=${encodeURIComponent(pageUrl)}`;
}

async function buildNewsCandidate(row, token, query, index, req) {
  const pageUrl = normalizedUrl(row?.url || row?.sourceURL || row?.metadata?.sourceURL || '');
  if (!pageUrl || isBlockedNewsUrl(pageUrl)) return null;

  let page = null;
  try { page = await readPageWithFirecrawl(pageUrl, token); } catch (_) { page = null; }

  const metadata = page?.metadata || row?.metadata || {};
  const title = shortText(firstString(metadata.title, metadata.ogTitle, row?.title, row?.metadata?.title), 240);
  const description = selectDescription(row, page);
  if (!title || !description || isErrorPageText(title + ' ' + description)) return null;

  const images = extractImages(page?.html || '', metadata, pageUrl, MAX_ARTICLE_IMAGES);
  const published = extractPublishedAt(page?.html || '', metadata);
  const source = sourceNameFor(pageUrl, metadata);

  let score = relevanceScore(query, title, description);
  score += recencyScore(published.iso);
  score += images.length ? 14 + Math.min(images.length, 6) : 0;
  score += page ? 10 : 0;
  score += Math.max(0, 10 - index);
  if (description.length >= 180) score += 5;

  return {
    title,
    description,
    imageUrl: images.length ? buildGalleryUrl(req, pageUrl) : null,
    imageCount: images.length,
    publishedAt: published.display,
    source,
    reader: page ? 'firecrawl-scrape' : 'firecrawl-search',
    score
  };
}

async function searchNews(query, token, req) {
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
    const candidate = await buildNewsCandidate(filteredRows[index], token, query, index, req);
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
    const data = isNews ? await searchNews(query, token, req) : await searchGeneral(query, token);
    return res.status(200).json({ success: true, query, mode: isNews ? 'berita' : 'cari', data });
  } catch (error) {
    return res.status(error.status || 500).json({
      error: error.message || 'Realtime search failed', details: error.details || undefined
    });
  }
}

export {
  cleanText,
  normalizedUrl,
  isPrivateHostname,
  isBlockedNewsUrl,
  isErrorPageText,
  extractImage,
  extractImages,
  relevanceScore,
  firecrawlRequest
};
