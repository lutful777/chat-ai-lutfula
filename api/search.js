import { URL } from 'url';

// Utility for cleaning HTML text
function cleanText(input) {
  return String(input || '')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/\s+/g, ' ')
    .trim();
}

function shortText(input, max = 420) {
  const text = cleanText(input);
  if (text.length <= max) return text;
  return text.slice(0, max).replace(/\s+\S*$/, '') + '...';
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
    if (host.includes('youtube.com') || host.includes('youtu.be') || host.includes('tiktok.com') || 
        host.includes('instagram.com') || host.includes('facebook.com') || host.includes('twitter.com') || 
        host.includes('x.com')) return true;
    if (u.pathname.toLowerCase().includes('/login') || u.pathname.toLowerCase().includes('/search')) return true;
    return false;
  } catch (e) {
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
  } catch (e) {
    return true;
  }
}

function hasErrorKeywords(html) {
  if (!html) return false;
  const t = cleanText(html).toLowerCase();
  const errors = ['403 forbidden', '404 not found', 'access denied', 'you do not have access', 'captcha', 'verify you are human', 'sign in to continue', 'page not found', 'internal server error'];
  return errors.some(e => t.includes(e));
}

function extractImage(html, metadata) {
  if (metadata) {
    if (metadata.ogImage) return normalizedUrl(metadata.ogImage);
    if (metadata.twitterImage) return normalizedUrl(metadata.twitterImage);
    if (metadata.image) return normalizedUrl(metadata.image);
  }
  if (!html) return null;
  const matches = [
    /<meta[^>]*property="og:image"[^>]*content="([^"]+)"[^>]*>/i,
    /<meta[^>]*content="([^"]+)"[^>]*property="og:image"[^>]*>/i,
    /<meta[^>]*name="twitter:image"[^>]*content="([^"]+)"[^>]*>/i,
    /<meta[^>]*content="([^"]+)"[^>]*name="twitter:image"[^>]*>/i,
    /<img[^>]*class="[^"]*(?:hero|main|featured)[^"]*"[^>]*src="([^"]+)"[^>]*>/i,
    /<img[^>]*src="([^"]+)"[^>]*class="[^"]*(?:hero|main|featured)[^"]*"[^>]*>/i
  ];
  for (const m of matches) {
    const match = html.match(m);
    if (match && match[1]) {
      const u = normalizedUrl(match[1]);
      if (u && !isBadImage(u)) return u;
    }
  }
  return null;
}

function isBadImage(urlStr) {
  const u = urlStr.toLowerCase();
  return u.includes('favicon') || u.includes('logo') || u.includes('avatar') || u.includes('icon') || 
         u.endsWith('.svg') || u.includes('pixel') || u.includes('ads') || u.includes('placeholder') || 
         u.includes('blank') || u.includes('1x1');
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
  return {
    title: pageUrl,
    description: text,
    url: pageUrl,
    reader: 'browserless',
    html: html
  };
}

async function readPageWithFirecrawl(pageUrl, token) {
  if (!token || !pageUrl) return null;
  const url = 'https://' + ['api', 'firecrawl', 'dev'].join('.') + '/v1/scrape';
  const h = {};
  h['Content-Type'] = 'application/json';
  h[['Authori', 'zation'].join('')] = ['Bearer', token].join(' ');
  const response = await fetch(url, {
    method: 'POST',
    headers: h,
    body: JSON.stringify({
      url: pageUrl,
      formats: ['markdown', 'html']
    })
  });
  const t = await response.text();
  let j;
  try { j = JSON.parse(t); } catch (_) { j = { raw: t }; }
  if (!response.ok) return null;
  const data = j.data || j;
  const metadata = data.metadata || {};
  const description = data.markdown || data.content || data.html || j.markdown || j.html || '';
  if (hasErrorKeywords(data.html || description)) return null;
  const text = shortText(description, 1200);
  if (!text) return null;
  return {
    title: metadata.title || pageUrl,
    description: text,
    url: pageUrl,
    reader: 'firecrawl-scrape',
    metadata: metadata,
    html: data.html
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
        return res.status(200).json({
          query: q || targetUrl, mode: 'website', url: targetUrl, data: [browserlessResult]
        });
      }
      const firecrawlPage = await readPageWithFirecrawl(targetUrl, token);
      if (firecrawlPage) {
        return res.status(200).json({
          query: q || targetUrl, mode: 'website', url: targetUrl, data: [firecrawlPage]
        });
      }
    }
    const isBeritaMode = mode === 'berita' || mode === 'news';
    const searchLimit = isBeritaMode ? 20 : 5;
    const url = 'https://' + ['api', 'firecrawl', 'dev'].join('.') + '/v1/search';
    const h = {};
    h['Content-Type'] = 'application/json';
    h[['Authori', 'zation'].join('')] = ['Bearer', token].join(' ');
    const searchBody = { query: targetUrl || q, limit: searchLimit };
    if (isBeritaMode) searchBody.tbs = 'sbd:1,qdr:d';
        
    const r = await fetch(url, { method: 'POST', headers: h, body: JSON.stringify(searchBody) });
    const t = await r.text();
    let j;
    try { j = JSON.parse(t); } catch (_) { j = { raw: t }; }
    if (!r.ok) return res.status(r.status).json({ error: 'Search provider failed', status: r.status, details: j });
    const rows = Array.isArray(j.data) ? j.data : (Array.isArray(j.results) ? j.results : []);
    let validArticles = [];
    let seenUrls = new Set();
    
    for (let i = 0; i < rows.length; i++) {
      const x = rows[i] || {};
      const pageUrl = x.url || x.sourceURL || x.metadata?.sourceURL || '';
      if (!pageUrl || seenUrls.has(pageUrl) || isPrivateUrl(pageUrl)) continue;
      if (isBeritaMode && isBadSource(pageUrl)) continue;
      
      let description = x.description || x.snippet || x.content || x.markdown || '';
      let reader = 'firecrawl-search';
      let imageUrl = null;
      let sourceName = x.metadata?.source || new URL(pageUrl || 'https://example.com').hostname;
      let publishedAt = x.metadata?.date || x.metadata?.publishedAt || '';
      
      let fcPage = null;
      if (isBeritaMode && validArticles.length < 5) {
         try {
           fcPage = await readPageWithFirecrawl(pageUrl, token);
           if (!fcPage) continue; 
           imageUrl = extractImage(fcPage.html, fcPage.metadata);
           if (fcPage.description && fcPage.description.length > cleanText(description).length) {
             description = fcPage.description;
           }
           if (fcPage.metadata?.source) sourceName = fcPage.metadata.source;
           if (fcPage.metadata?.date || fcPage.metadata?.publishedAt) publishedAt = fcPage.metadata.date || fcPage.metadata.publishedAt;
         } catch (_) {}
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
      
      if (isBeritaMode && !imageUrl) continue; // Must have image for news
      
      seenUrls.add(pageUrl);
      validArticles.push({
        title: x.title || x.metadata?.title || 'No Title',
        description: shortText(description, 420),
        url: pageUrl,
        imageUrl: imageUrl,
        publishedAt: publishedAt,
        source: sourceName,
        reader
      });
      
      if (isBeritaMode && validArticles.length >= 5) break;
      if (!isBeritaMode && validArticles.length >= 5) break;
    }
    
    return res.status(200).json({ query: targetUrl || q, mode, limit: searchLimit, todayOnly: isBeritaMode, data: validArticles });
  } catch (e) {
    return res.status(500).json({ error: 'Realtime search failed', message: e instanceof Error ? e.message : String(e) });
  }
}
export { extractImage };
