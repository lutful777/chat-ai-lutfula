import { URL } from 'url';

const DAY_MS = 24 * 60 * 60 * 1000;

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
  const withoutLinks = String(input || '')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/https?:\/\/[^\s)\]}]+/gi, ' ')
    .replace(/www\.[^\s)\]}]+/gi, ' ')
    .replace(/\b(?:[a-z0-9-]+\.)+(?:com|id|net|org|co|io|ai|news)\b(?:\/[^\s]*)?/gi, ' ')
    .replace(/(?:strip_icc|format|resize|quality|width|height)\([^)]*\)/gi, ' ')
    .replace(/\b(?:source|sumber)\s*:\s*[^|•\n]+/gi, ' ')
    .replace(/\b(?:kly-media-production|medias?|uploads?)\/[\w./%-]+/gi, ' ')
    .replace(/[_*`~]+/g, ' ')
    .replace(/\s*[-|•]\s*/g, ' - ');

  return cleanText(withoutLinks)
    .replace(/(?:\s+-\s+){2,}/g, ' - ')
    .replace(/^[-–—|•\s]+|[-–—|•\s]+$/g, '')
    .trim();
}

function shortText(input, max = 420) {
  const text = cleanText(input);
  if (text.length <= max) return text;
  return text.slice(0, max).replace(/\s+\S*$/, '') + '...';
}

function shortNewsText(input, max = 360) {
  const text = cleanNewsText(input);
  if (!text) return '';
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
    const u = new URL(String(input || '').trim());
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return '';
    return u.toString();
  } catch (_) {
    return '';
  }
}

function isBadSource(urlStr) {
  try {
    const u = new URL(urlStr);
    const host = u.hostname.toLowerCase();
    if (
      host.includes('youtube.com') || host.includes('youtu.be') || host.includes('tiktok.com') ||
      host.includes('instagram.com') || host.includes('facebook.com') || host.includes('twitter.com') ||
      host.includes('x.com')
    ) return true;
    if (u.pathname.toLowerCase().includes('/login') || u.pathname.toLowerCase().includes('/search')) return true;
    return false;
  } catch (_) {
    return true;
  }
}

function isPrivateUrl(urlStr) {
  try {
    const u = new URL(urlStr);
    const host = u.hostname;
    if (host === 'localhost' || host === '127.0.0.1') return true;
    if (host.startsWith('10.') || host.startsWith('192.168.')) return true;
    if (host.match(/^172\.(1[6-9]|2[0-9]|3[0-1])\./)) return true;
    return false;
  } catch (_) {
    return true;
  }
}

function hasErrorKeywords(html) {
  if (!html) return false;
  const text = cleanText(html).toLowerCase();
  const errors = [
    '403 forbidden', '404 not found', 'access denied', 'you do not have access', 'captcha',
    'verify you are human', 'sign in to continue', 'page not found', 'internal server error'
  ];
  return errors.some((error) => text.includes(error));
}

function isBadImage(urlStr) {
  const value = String(urlStr || '').toLowerCase();
  return value.includes('favicon') || value.includes('logo') || value.includes('avatar') ||
    value.includes('icon') || value.endsWith('.svg') || value.includes('pixel') ||
    value.includes('ads') || value.includes('placeholder') || value.includes('blank') ||
    value.includes('1x1');
}

function extractImage(html, metadata) {
  const metadataCandidates = [
    metadata?.ogImage,
    metadata?.twitterImage,
    metadata?.image,
    metadata?.['og:image'],
    metadata?.['twitter:image']
  ];

  for (const candidate of metadataCandidates) {
    const url = normalizedUrl(Array.isArray(candidate) ? candidate[0] : candidate);
    if (url && !isBadImage(url)) return url;
  }

  if (!html) return null;
  const patterns = [
    /<meta[^>]*property=["']og:image["'][^>]*content=["']([^"']+)["'][^>]*>/i,
    /<meta[^>]*content=["']([^"']+)["'][^>]*property=["']og:image["'][^>]*>/i,
    /<meta[^>]*name=["']twitter:image["'][^>]*content=["']([^"']+)["'][^>]*>/i,
    /<meta[^>]*content=["']([^"']+)["'][^>]*name=["']twitter:image["'][^>]*>/i,
    /<img[^>]*class=["'][^"']*(?:hero|main|featured)[^"']*["'][^>]*src=["']([^"']+)["'][^>]*>/i,
    /<img[^>]*src=["']([^"']+)["'][^>]*class=["'][^"']*(?:hero|main|featured)[^"']*["'][^>]*>/i
  ];

  for (const pattern of patterns) {
    const match = html.match(pattern);
    if (!match?.[1]) continue;
    const url = normalizedUrl(match[1]);
    if (url && !isBadImage(url)) return url;
  }
  return null;
}

function extractMetaDescription(html, metadata) {
  const metadataCandidates = [
    metadata?.description,
    metadata?.ogDescription,
    metadata?.twitterDescription,
    metadata?.['og:description'],
    metadata?.['twitter:description']
  ];

  for (const candidate of metadataCandidates) {
    const text = shortNewsText(candidate);
    if (text.length >= 40) return text;
  }

  if (!html) return '';
  const metaPatterns = [
    /<meta[^>]*(?:name|property)=["'](?:description|og:description|twitter:description)["'][^>]*content=["']([^"']+)["'][^>]*>/i,
    /<meta[^>]*content=["']([^"']+)["'][^>]*(?:name|property)=["'](?:description|og:description|twitter:description)["'][^>]*>/i
  ];
  for (const pattern of metaPatterns) {
    const match = html.match(pattern);
    const text = shortNewsText(match?.[1]);
    if (text.length >= 40) return text;
  }

  const paragraphs = [...html.matchAll(/<p\b[^>]*>([\s\S]*?)<\/p>/gi)];
  for (const paragraph of paragraphs) {
    const text = shortNewsText(paragraph[1]);
    if (text.length >= 70 && !/cookie|privacy|login|subscribe|newsletter/i.test(text)) return text;
  }
  return '';
}

function extractPublishedValue(html, metadata, row) {
  const candidates = [
    row?.publishedAt,
    row?.date,
    row?.metadata?.publishedAt,
    row?.metadata?.datePublished,
    row?.metadata?.date,
    row?.metadata?.publishedTime,
    metadata?.publishedAt,
    metadata?.datePublished,
    metadata?.date,
    metadata?.publishedTime,
    metadata?.['article:published_time']
  ];

  for (const candidate of candidates) {
    if (candidate !== undefined && candidate !== null && String(candidate).trim()) return candidate;
  }

  if (!html) return '';
  const patterns = [
    /<meta[^>]*(?:property|name|itemprop)=["'](?:article:published_time|datePublished|date|pubdate|publish-date)["'][^>]*content=["']([^"']+)["'][^>]*>/i,
    /<meta[^>]*content=["']([^"']+)["'][^>]*(?:property|name|itemprop)=["'](?:article:published_time|datePublished|date|pubdate|publish-date)["'][^>]*>/i,
    /<time[^>]*datetime=["']([^"']+)["'][^>]*>/i,
    /["']datePublished["']\s*:\s*["']([^"']+)["']/i
  ];
  for (const pattern of patterns) {
    const match = html.match(pattern);
    if (match?.[1]) return match[1];
  }
  return '';
}

function parsePublishedTimestamp(value, now = Date.now()) {
  if (value === undefined || value === null || value === '') return null;

  if (typeof value === 'number' && Number.isFinite(value)) {
    return value < 10_000_000_000 ? value * 1000 : value;
  }

  const raw = cleanText(value);
  const lower = raw.toLowerCase();
  if (!lower) return null;
  if (/baru saja|just now/.test(lower)) return now;

  const relative = lower.match(/(\d+)\s*(menit|minute|minutes|jam|hour|hours|hari|day|days)\s*(?:yang\s+)?(?:lalu|ago)?/i);
  if (relative) {
    const amount = Number(relative[1]);
    const unit = relative[2];
    if (/menit|minute/.test(unit)) return now - amount * 60 * 1000;
    if (/jam|hour/.test(unit)) return now - amount * 60 * 60 * 1000;
    if (/hari|day/.test(unit)) return now - amount * DAY_MS;
  }

  if (/\bkemarin\b|\byesterday\b/.test(lower)) return now - DAY_MS;

  const numericDate = lower.match(/\b(\d{1,2})[\/-](\d{1,2})[\/-](\d{4})(?:[,\s]+(\d{1,2}):(\d{2}))?/);
  if (numericDate) {
    const day = Number(numericDate[1]);
    const month = Number(numericDate[2]);
    const year = Number(numericDate[3]);
    const hour = Number(numericDate[4] || 12);
    const minute = Number(numericDate[5] || 0);
    const timestamp = Date.UTC(year, month - 1, day, hour - 7, minute);
    if (Number.isFinite(timestamp)) return timestamp;
  }

  const parsed = Date.parse(raw);
  return Number.isFinite(parsed) ? parsed : null;
}

function isWithinLast24Hours(timestamp, now = Date.now()) {
  if (!Number.isFinite(timestamp)) return false;
  const age = now - timestamp;
  return age >= -15 * 60 * 1000 && age <= DAY_MS;
}

function allowsHistoricalNews(query) {
  const text = String(query || '').toLowerCase();
  return /\b(?:19|20)\d{2}\b/.test(text) ||
    /\b\d{1,2}[\/-]\d{1,2}[\/-](?:19|20)\d{2}\b/.test(text) ||
    /\b(?:kemarin|minggu lalu|bulan lalu|tahun lalu|masa lalu|sejarah|historis|arsip|dulu|terdahulu)\b/.test(text) ||
    /\b(?:pada|tanggal|tahun|bulan)\s+(?:januari|februari|maret|april|mei|juni|juli|agustus|september|oktober|november|desember|\d{1,4})\b/.test(text);
}

function hiddenNewsId(index) {
  return `news-item-${Date.now()}-${index + 1}`;
}

function looksIndonesian(input) {
  const words = cleanText(input).toLowerCase().split(/[^\p{L}]+/u).filter(Boolean);
  if (words.length === 0) return false;
  const common = new Set([
    'yang', 'dan', 'di', 'ke', 'dari', 'untuk', 'dengan', 'pada', 'ini', 'itu', 'adalah',
    'akan', 'telah', 'tidak', 'dalam', 'sebagai', 'karena', 'setelah', 'saat', 'oleh', 'juga',
    'hingga', 'terhadap', 'antara', 'menurut', 'baru', 'hari', 'berita', 'harga', 'pasar'
  ]);
  const score = words.reduce((total, word) => total + (common.has(word) ? 1 : 0), 0);
  return score >= Math.min(2, Math.max(1, Math.floor(words.length / 8)));
}

async function translateTextToIndonesian(input, maxLength) {
  const source = shortNewsText(input, maxLength);
  if (!source) return { text: '', ok: false, detectedLanguage: '' };

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  try {
    const endpoint = new URL('https://translate.googleapis.com/translate_a/single');
    endpoint.searchParams.set('client', 'gtx');
    endpoint.searchParams.set('sl', 'auto');
    endpoint.searchParams.set('tl', 'id');
    endpoint.searchParams.set('dt', 't');
    endpoint.searchParams.set('q', source);

    const response = await fetch(endpoint, {
      signal: controller.signal,
      headers: {
        'Accept': 'application/json',
        'User-Agent': 'Mozilla/5.0'
      }
    });
    if (!response.ok) throw new Error(`Translation HTTP ${response.status}`);

    const data = await response.json();
    const translated = Array.isArray(data?.[0])
      ? data[0].map((part) => Array.isArray(part) ? (part[0] || '') : '').join('')
      : '';
    const detectedLanguage = typeof data?.[2] === 'string' ? data[2].toLowerCase() : '';
    const cleaned = shortNewsText(translated, maxLength);

    if (!cleaned) throw new Error('Translation returned empty text');
    return { text: cleaned, ok: true, detectedLanguage };
  } catch (_) {
    return {
      text: source,
      ok: looksIndonesian(source),
      detectedLanguage: looksIndonesian(source) ? 'id' : ''
    };
  } finally {
    clearTimeout(timeout);
  }
}

async function translateNewsItemToIndonesian(item) {
  const [titleResult, descriptionResult] = await Promise.all([
    translateTextToIndonesian(item.title, 180),
    translateTextToIndonesian(item.description, 360)
  ]);

  if (!titleResult.ok || !descriptionResult.ok) return null;
  if (!titleResult.text || descriptionResult.text.length < 35) return null;

  return {
    ...item,
    title: titleResult.text,
    description: descriptionResult.text,
    translatedTo: 'id'
  };
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
      gotoOptions: { waitUntil: 'networkidle2', timeout: 15000 }
    })
  });
  if (!response.ok) return null;
  const html = await response.text();
  if (hasErrorKeywords(html)) return null;
  const text = shortText(html, 1200);
  if (!text) return null;
  return { title: pageUrl, description: text, url: pageUrl, reader: 'browserless', html };
}

async function readPageWithFirecrawl(pageUrl, token) {
  if (!token || !pageUrl) return null;
  const url = 'https://' + ['api', 'firecrawl', 'dev'].join('.') + '/v1/scrape';
  const headers = { 'Content-Type': 'application/json' };
  headers[['Authori', 'zation'].join('')] = ['Bearer', token].join(' ');
  const response = await fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify({ url: pageUrl, formats: ['markdown', 'html'] })
  });
  const responseText = await response.text();
  let json;
  try { json = JSON.parse(responseText); } catch (_) { json = { raw: responseText }; }
  if (!response.ok) return null;
  const data = json.data || json;
  const metadata = data.metadata || {};
  const description = data.markdown || data.content || data.html || json.markdown || json.html || '';
  if (hasErrorKeywords(data.html || description)) return null;
  const text = shortText(description, 1200);
  if (!text) return null;
  return {
    title: metadata.title || pageUrl,
    description: text,
    url: pageUrl,
    reader: 'firecrawl-scrape',
    metadata,
    html: data.html || ''
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
  if (targetUrl && isPrivateUrl(targetUrl)) {
    return res.status(403).json({ error: 'Access to private URLs is not allowed' });
  }

  try {
    if (targetUrl) {
      const browserlessResult = await readPageWithBrowserless(targetUrl);
      if (browserlessResult) {
        return res.status(200).json({ query: q || targetUrl, mode: 'website', url: targetUrl, data: [browserlessResult] });
      }
      const firecrawlPage = await readPageWithFirecrawl(targetUrl, token);
      if (firecrawlPage) {
        return res.status(200).json({ query: q || targetUrl, mode: 'website', url: targetUrl, data: [firecrawlPage] });
      }
    }

    const isBeritaMode = mode === 'berita' || mode === 'news';
    const allowOlder = isBeritaMode && (
      req.query.allowOlder === '1' ||
      req.query.allowOlder === 'true' ||
      allowsHistoricalNews(q)
    );
    const searchLimit = isBeritaMode ? 30 : 5;
    const searchUrl = 'https://' + ['api', 'firecrawl', 'dev'].join('.') + '/v1/search';
    const headers = { 'Content-Type': 'application/json' };
    headers[['Authori', 'zation'].join('')] = ['Bearer', token].join(' ');
    const searchBody = { query: targetUrl || q, limit: searchLimit };
    if (isBeritaMode && !allowOlder) searchBody.tbs = 'sbd:1,qdr:d';

    const searchResponse = await fetch(searchUrl, {
      method: 'POST',
      headers,
      body: JSON.stringify(searchBody)
    });
    const responseText = await searchResponse.text();
    let json;
    try { json = JSON.parse(responseText); } catch (_) { json = { raw: responseText }; }
    if (!searchResponse.ok) {
      return res.status(searchResponse.status).json({
        error: 'Search provider failed',
        status: searchResponse.status,
        details: json
      });
    }

    const rows = Array.isArray(json.data) ? json.data : (Array.isArray(json.results) ? json.results : []);
    const validArticles = [];
    const seenUrls = new Set();
    const now = Date.now();

    for (let index = 0; index < rows.length; index++) {
      const row = rows[index] || {};
      const pageUrl = normalizedUrl(row.url || row.sourceURL || row.metadata?.sourceURL || '');
      if (!pageUrl || seenUrls.has(pageUrl) || isPrivateUrl(pageUrl)) continue;
      if (isBeritaMode && isBadSource(pageUrl)) continue;

      let description = row.description || row.snippet || row.content || row.markdown || '';
      let reader = 'firecrawl-search';
      let imageUrl = null;
      let publishedValue = extractPublishedValue('', null, row);
      let firecrawlPage = null;

      if (isBeritaMode && validArticles.length < 5) {
        try {
          firecrawlPage = await readPageWithFirecrawl(pageUrl, token);
          if (!firecrawlPage) continue;
          imageUrl = extractImage(firecrawlPage.html, firecrawlPage.metadata);
          const metaDescription = extractMetaDescription(firecrawlPage.html, firecrawlPage.metadata);
          if (metaDescription) description = metaDescription;
          publishedValue = extractPublishedValue(firecrawlPage.html, firecrawlPage.metadata, row);
        } catch (_) {
          continue;
        }
      }

      if (!isBeritaMode && validArticles.length < 3 && cleanText(description).length < 120) {
        try {
          const browserlessPage = await readPageWithBrowserless(pageUrl);
          if (browserlessPage && browserlessPage.description.length > cleanText(description).length) {
            description = browserlessPage.description;
            reader = browserlessPage.reader;
          }
        } catch (_) {}
      }

      if (isBeritaMode) {
        if (!imageUrl) continue;
        const publishedTimestamp = parsePublishedTimestamp(publishedValue, now);
        if (!allowOlder && !isWithinLast24Hours(publishedTimestamp, now)) continue;

        const title = shortNewsText(row.title || row.metadata?.title || firecrawlPage?.metadata?.title || '', 180);
        const summary = shortNewsText(description, 360);
        if (!title || summary.length < 35) continue;

        seenUrls.add(pageUrl);
        validArticles.push({
          title,
          description: summary,
          url: hiddenNewsId(validArticles.length),
          imageUrl,
          publishedAt: '',
          source: '',
          reader,
          freshWithin24Hours: allowOlder ? null : true
        });
      } else {
        seenUrls.add(pageUrl);
        validArticles.push({
          title: cleanText(row.title || row.metadata?.title || 'No Title'),
          description: shortText(description, 420),
          url: pageUrl,
          imageUrl: null,
          publishedAt: '',
          source: '',
          reader
        });
      }

      if (validArticles.length >= 5) break;
    }

    const outputArticles = isBeritaMode
      ? (await Promise.all(validArticles.map(translateNewsItemToIndonesian))).filter(Boolean)
      : validArticles;

    return res.status(200).json({
      query: targetUrl || q,
      mode,
      limit: searchLimit,
      todayOnly: isBeritaMode && !allowOlder,
      allowOlder,
      translatedTo: isBeritaMode ? 'id' : null,
      data: outputArticles
    });
  } catch (error) {
    return res.status(500).json({
      error: 'Realtime search failed',
      message: error instanceof Error ? error.message : String(error)
    });
  }
}

export {
  cleanNewsText,
  extractImage,
  extractPublishedValue,
  parsePublishedTimestamp,
  isWithinLast24Hours,
  allowsHistoricalNews,
  translateTextToIndonesian,
  translateNewsItemToIndonesian
};
