const PRIMARY_BASE_URL = 'https://open-api.bingx.com';
const FALLBACK_BASE_URL = 'https://open-api.bingx.pro';
const ANNOUNCEMENT_PATH = '/openApi/content/v1/announcement';
const MAX_ITEMS = 5;

function cleanText(input) {
  return String(input || '')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
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

function shortText(input, max = 700) {
  const text = cleanText(input);
  if (text.length <= max) return text;
  return text.slice(0, max).replace(/\s+\S*$/, '').trim() + '...';
}

function looksIndonesian(input) {
  const words = cleanText(input).toLowerCase().split(/[^\p{L}]+/u).filter(Boolean);
  if (words.length === 0) return false;

  const commonWords = new Set([
    'yang', 'dan', 'di', 'ke', 'dari', 'untuk', 'dengan', 'pada', 'ini', 'itu',
    'akan', 'telah', 'tidak', 'dalam', 'sebagai', 'karena', 'setelah', 'saat',
    'oleh', 'juga', 'hingga', 'pengumuman', 'perdagangan', 'listing', 'pengguna'
  ]);
  const score = words.reduce((total, word) => total + (commonWords.has(word) ? 1 : 0), 0);
  return score >= Math.min(2, Math.max(1, Math.floor(words.length / 8)));
}

async function translateTextToIndonesian(input, maxLength) {
  const source = shortText(input, maxLength);
  if (!source || looksIndonesian(source)) return source;

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 10000);

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
        Accept: 'application/json',
        'User-Agent': 'Mozilla/5.0'
      }
    });

    if (!response.ok) throw new Error(`Translation HTTP ${response.status}`);

    const json = await response.json();
    const translated = Array.isArray(json?.[0])
      ? json[0].map((part) => Array.isArray(part) ? (part[0] || '') : '').join('')
      : '';

    return shortText(translated, maxLength) || source;
  } catch (_) {
    return source;
  } finally {
    clearTimeout(timeout);
  }
}

function contentTypeFromQuery(query) {
  const value = String(query || '').trim().toLowerCase();

  if (/\b(delisting|delist|hapus koin|penghapusan)\b/.test(value)) return 'Delisting';
  if (/\b(futures|future|perpetual)\b/.test(value) && /\b(listing|list)\b/.test(value)) return 'FuturesListing';
  if (/\b(innovation|zona inovasi)\b/.test(value) && /\b(listing|list)\b/.test(value)) return 'InnovationListing';
  if (/\b(listing|list|koin baru|token baru|spot)\b/.test(value)) return 'SpotListing';
  if (/\b(funding|funding rate)\b/.test(value)) return 'FundingRate';
  if (/\b(asset maintenance|maintenance aset|deposit|withdrawal|penarikan)\b/.test(value)) return 'AssetMaintenance';
  if (/\b(maintenance|pemeliharaan|gangguan sistem|system)\b/.test(value)) return 'SystemMaintenance';
  if (/\b(product|produk|update produk)\b/.test(value)) return 'ProductUpdates';
  if (/\b(promo|promosi|bonus)\b/.test(value)) return 'LatestPromotions';
  return 'LatestAnnouncements';
}

async function fetchJsonWithTimeout(url, timeoutMs = 10000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(url, {
      signal: controller.signal,
      headers: {
        Accept: 'application/json',
        'User-Agent': 'chat-ai-lutfula/1.0'
      }
    });

    const body = await response.text();
    let json;
    try {
      json = JSON.parse(body);
    } catch (_) {
      throw new Error(`BingX returned invalid JSON (HTTP ${response.status})`);
    }

    if (!response.ok) {
      const error = new Error(`BingX HTTP ${response.status}`);
      error.isBusinessResponse = true;
      throw error;
    }

    if (Number(json?.code) !== 0) {
      const error = new Error(json?.msg || `BingX API error ${json?.code}`);
      error.isBusinessResponse = true;
      throw error;
    }

    return json;
  } finally {
    clearTimeout(timer);
  }
}

async function fetchAnnouncements(contentType) {
  const params = new URLSearchParams({
    contentType,
    language: 'en-us',
    page: '1'
  });
  const pathAndQuery = `${ANNOUNCEMENT_PATH}?${params.toString()}`;

  try {
    return await fetchJsonWithTimeout(`${PRIMARY_BASE_URL}${pathAndQuery}`);
  } catch (error) {
    if (error?.isBusinessResponse) throw error;
    return fetchJsonWithTimeout(`${FALLBACK_BASE_URL}${pathAndQuery}`);
  }
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'GET') return res.status(405).json({ error: 'Method not allowed' });

  const query = typeof req.query.q === 'string' ? req.query.q.trim() : '';
  const requestedType = typeof req.query.contentType === 'string' ? req.query.contentType.trim() : '';
  const contentType = requestedType || contentTypeFromQuery(query);

  try {
    const json = await fetchAnnouncements(contentType);
    const announcements = Array.isArray(json?.data?.list) ? json.data.list : [];
    const seenLinks = new Set();

    const rawItems = announcements
      .map((item) => {
        const title = cleanText(item?.title);
        const url = String(item?.link || '').trim();
        if (!title || !/^https?:\/\//i.test(url) || seenLinks.has(url)) return null;
        seenLinks.add(url);

        return {
          title,
          description: shortText(item?.content, 700) || `Pengumuman resmi BingX kategori ${cleanText(item?.type || contentType)}.`,
          url,
          publishedAt: cleanText(item?.time),
          source: 'BingX',
          type: cleanText(item?.type || contentType),
          reader: 'bingx-announcement-api'
        };
      })
      .filter(Boolean)
      .slice(0, MAX_ITEMS);

    const data = await Promise.all(
      rawItems.map(async (item) => {
        const [title, description] = await Promise.all([
          translateTextToIndonesian(item.title, 180),
          translateTextToIndonesian(item.description, 700)
        ]);

        return {
          ...item,
          title,
          description,
          imageUrl: null,
          translatedTo: 'id'
        };
      })
    );

    return res.status(200).json({
      query: query || 'Pengumuman terbaru BingX',
      mode: 'bingx',
      contentType,
      translatedTo: 'id',
      includeImages: false,
      returned: data.length,
      data
    });
  } catch (error) {
    return res.status(502).json({
      error: 'BingX announcement API failed',
      message: error instanceof Error ? error.message : String(error),
      translatedTo: 'id',
      includeImages: false,
      data: []
    });
  }
}

export {
  cleanText,
  shortText,
  looksIndonesian,
  translateTextToIndonesian,
  contentTypeFromQuery,
  fetchAnnouncements
};
