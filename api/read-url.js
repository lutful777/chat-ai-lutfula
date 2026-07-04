const MAX_TEXT = 30000;
const MAX_HTML = 1500000;
const MAX_LINKS = 120;

function cleanInline(input) {
  return String(input || '')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/\s+/g, ' ')
    .trim();
}

function htmlToText(input) {
  return String(input || '')
    .replace(/<script(?![^>]*type=["']application\/(?:ld\+json|json)["'])[^>]*>[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<\/(h1|h2|h3|h4|p|li|tr|section|article|div)>/gi, '\n')
    .replace(/<br\s*\/?\s*>/gi, '\n')
    .replace(/<li[^>]*>/gi, '- ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/[ \t]+/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function truncateAtWord(input, max = MAX_TEXT) {
  const text = String(input || '').trim();
  if (text.length <= max) return text;
  return text.slice(0, max).replace(/\s+\S*$/, '') + '\n[Content truncated]';
}

function extractTitle(html, fallback = '') {
  const match = String(html || '').match(/<title[^>]*>([\s\S]*?)<\/title>/i);
  return cleanInline(match?.[1] || fallback);
}

function extractHeadings(html) {
  const headings = [];
  const regex = /<(h[1-4])[^>]*>([\s\S]*?)<\/\1>/gi;
  let match;
  while ((match = regex.exec(String(html || ''))) && headings.length < 80) {
    const text = cleanInline(match[2].replace(/<[^>]+>/g, ' '));
    if (text && !headings.some((x) => x.text === text)) {
      headings.push({ level: Number(match[1].slice(1)), text });
    }
  }
  return headings;
}

function extractLinks(html, baseUrl) {
  const links = [];
  const seen = new Set();
  const regex = /<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;
  let match;
  while ((match = regex.exec(String(html || ''))) && links.length < MAX_LINKS) {
    try {
      const href = match[1].trim();
      if (!href || href.startsWith('#') || href.startsWith('javascript:') || href.startsWith('mailto:') || href.startsWith('tel:')) continue;
      const url = new URL(href, baseUrl);
      if (!['http:', 'https:'].includes(url.protocol)) continue;
      url.hash = '';
      const normalized = url.toString();
      if (seen.has(normalized)) continue;
      seen.add(normalized);
      links.push({
        url: normalized,
        text: cleanInline(match[2].replace(/<[^>]+>/g, ' ')).slice(0, 240),
        internal: url.origin === new URL(baseUrl).origin
      });
    } catch (_) {}
  }
  return links;
}

function extractTables(html) {
  const tables = [];
  const regex = /<table\b[^>]*>([\s\S]*?)<\/table>/gi;
  let match;
  while ((match = regex.exec(String(html || ''))) && tables.length < 12) {
    const rows = [];
    const rowRegex = /<tr\b[^>]*>([\s\S]*?)<\/tr>/gi;
    let rowMatch;
    while ((rowMatch = rowRegex.exec(match[1])) && rows.length < 40) {
      const cells = [];
      const cellRegex = /<(?:th|td)\b[^>]*>([\s\S]*?)<\/(?:th|td)>/gi;
      let cellMatch;
      while ((cellMatch = cellRegex.exec(rowMatch[1])) && cells.length < 20) {
        cells.push(cleanInline(cellMatch[1].replace(/<[^>]+>/g, ' ')).slice(0, 500));
      }
      if (cells.some(Boolean)) rows.push(cells);
    }
    if (rows.length) tables.push(rows);
  }
  return tables;
}

function extractStructuredData(html) {
  const items = [];
  const regex = /<script\b[^>]*type=["']application\/(?:ld\+json|json)["'][^>]*>([\s\S]*?)<\/script>/gi;
  let match;
  while ((match = regex.exec(String(html || ''))) && items.length < 20) {
    const raw = match[1].trim();
    if (!raw || raw.length > 100000) continue;
    try {
      items.push(JSON.parse(raw));
    } catch (_) {
      items.push({ raw: raw.slice(0, 8000) });
    }
  }

  const nextData = String(html || '').match(/<script\b[^>]*id=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)<\/script>/i);
  if (nextData?.[1] && nextData[1].length <= 250000) {
    try {
      items.push({ __NEXT_DATA__: JSON.parse(nextData[1]) });
    } catch (_) {}
  }
  return items;
}

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
  const u = new URL(String(input || '').trim());
  if (!['http:', 'https:'].includes(u.protocol) || u.username || u.password) throw new Error('Invalid URL');
  const host = u.hostname.toLowerCase().replace(/\.$/, '');
  if (!host || host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') ||
      host === 'metadata.google.internal' || host === '169.254.169.254' || host === '100.100.100.200' ||
      isPrivateIpv4(host) || host === '::1' || host.startsWith('fc') || host.startsWith('fd') || host.startsWith('fe80:')) {
    throw new Error('Private or local addresses are not allowed');
  }
  u.hash = '';
  return u.toString();
}

async function readPageWithBrowserless(pageUrl) {
  const token = process.env.BROWSERLESS_TOKEN;
  if (!token) return null;
  const base = process.env.BROWSERLESS_URL || 'https://chrome.browserless.io/content';
  const endpoint = base + (base.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(token);
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 25000);
  try {
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        url: pageUrl,
        gotoOptions: { waitUntil: 'networkidle2', timeout: 20000 }
      }),
      signal: controller.signal
    });
    if (!response.ok) return null;
    const html = (await response.text()).slice(0, MAX_HTML);
    if (!html.trim()) return null;
    return { html, markdown: htmlToText(html), reader: 'browserless' };
  } finally {
    clearTimeout(timeout);
  }
}

async function readPageWithFirecrawl(pageUrl) {
  const token = process.env.FIRECRAWL_API_KEY;
  if (!token) return null;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 25000);
  try {
    const response = await fetch('https://api.firecrawl.dev/v1/scrape', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ url: pageUrl, formats: ['markdown', 'html'], onlyMainContent: false }),
      signal: controller.signal
    });
    const text = await response.text();
    let json;
    try { json = JSON.parse(text); } catch (_) { json = { raw: text }; }
    if (!response.ok) return null;
    const data = json.data || json;
    const html = String(data.html || json.html || '').slice(0, MAX_HTML);
    const markdown = String(data.markdown || data.content || json.markdown || '').trim();
    if (!html && !markdown) return null;
    return {
      html,
      markdown: markdown || htmlToText(html),
      reader: 'firecrawl-scrape',
      metadata: data.metadata || {}
    };
  } finally {
    clearTimeout(timeout);
  }
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });

  let pageUrl;
  try {
    pageUrl = await normalizePublicUrl(req.body?.url);
  } catch (error) {
    return res.status(400).json({ error: 'Missing or invalid public URL', message: error.message });
  }

  try {
    const prefer = String(req.body?.prefer || 'browserless').toLowerCase();
    let result = null;
    if (prefer === 'firecrawl') result = await readPageWithFirecrawl(pageUrl) || await readPageWithBrowserless(pageUrl);
    else result = await readPageWithBrowserless(pageUrl) || await readPageWithFirecrawl(pageUrl);

    if (!result) {
      return res.status(502).json({ error: 'Read URL failed', message: 'Browserless and Firecrawl could not read this URL.', url: pageUrl });
    }

    const html = result.html || '';
    const markdown = truncateAtWord(result.markdown || htmlToText(html));
    const title = cleanInline(result.metadata?.title || extractTitle(html, pageUrl));
    const headings = extractHeadings(html);
    const links = extractLinks(html, pageUrl);
    const tables = extractTables(html);
    const structuredData = extractStructuredData(html);

    return res.status(200).json({
      success: true,
      url: pageUrl,
      reader: result.reader,
      title,
      markdown,
      data: {
        url: pageUrl,
        reader: result.reader,
        title,
        markdown,
        headings,
        links,
        tables,
        structuredData,
        truncated: String(result.markdown || '').length > MAX_TEXT
      }
    });
  } catch (error) {
    return res.status(500).json({
      error: 'Read URL request failed',
      message: error instanceof Error ? error.message : String(error),
      url: pageUrl
    });
  }
}
