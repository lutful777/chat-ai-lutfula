function cleanText(input) {
  return String(input || '')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, ' ')
    .replace(/<svg[\s\S]*?<\/svg>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&amp;/gi, '&')
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/\s+/g, ' ')
    .trim();
}

function shortText(input, max = 10000) {
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

function looksLikeErrorPage(input) {
  const text = cleanText(input).toLowerCase();
  if (!text) return true;

  const beginning = text.slice(0, 2500);
  const errorPatterns = [
    /\b404\b.{0,120}\b(page\s*)?not\s+found\b/i,
    /\bpage\s+not\s+found\b/i,
    /\bthe\s+page\s+you\s+(are|were)\s+looking\s+for\b/i,
    /\bwe\s+could\s+not\s+find\s+what\s+you\s+were\s+looking\s+for\b/i,
    /\bthis\s+page\s+doesn'?t\s+exist\b/i,
    /\bthis\s+page\s+could\s+not\s+be\s+found\b/i,
    /\berror\s+404\b/i
  ];

  return errorPatterns.some((pattern) => pattern.test(beginning));
}

function usableResult(input) {
  const text = shortText(input, 10000);
  if (text.length < 80) return '';
  if (looksLikeErrorPage(text)) return '';
  return text;
}

async function readPageWithFirecrawl(pageUrl) {
  const token = process.env.FIRECRAWL_API_KEY;
  if (!token || !pageUrl) return null;

  const response = await fetch('https://api.firecrawl.dev/v1/scrape', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`
    },
    body: JSON.stringify({
      url: pageUrl,
      formats: ['markdown', 'html'],
      onlyMainContent: true,
      waitFor: 1500
    })
  });

  const text = await response.text();

  let json;
  try {
    json = JSON.parse(text);
  } catch (_) {
    json = { raw: text };
  }

  if (!response.ok) return null;

  const data = json.data || json;
  const rawContent =
    data.markdown ||
    data.content ||
    data.html ||
    json.markdown ||
    json.html ||
    '';
  const markdown = usableResult(rawContent);

  if (!markdown) return null;

  return {
    markdown,
    reader: 'firecrawl-scrape'
  };
}

async function readPageWithBrowserless(pageUrl) {
  const token = process.env.BROWSERLESS_TOKEN;
  if (!token || !pageUrl) return null;

  const base = process.env.BROWSERLESS_URL || 'https://chrome.browserless.io/content';
  const joiner = base.includes('?') ? '&' : '?';
  const endpoint = base + joiner + 'token=' + encodeURIComponent(token);

  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      url: pageUrl,
      gotoOptions: {
        waitUntil: 'networkidle2',
        timeout: 20000
      }
    })
  });

  if (!response.ok) return null;

  const html = await response.text();
  const markdown = usableResult(html);

  if (!markdown) return null;

  return {
    markdown,
    reader: 'browserless'
  };
}

async function readPageDirectly(pageUrl) {
  if (!pageUrl) return null;

  const response = await fetch(pageUrl, {
    method: 'GET',
    redirect: 'follow',
    headers: {
      'User-Agent':
        'Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36',
      Accept: 'text/html,application/xhtml+xml,application/json;q=0.9,text/plain;q=0.8,*/*;q=0.5'
    }
  });

  if (!response.ok) return null;

  const contentType = response.headers.get('content-type') || '';
  if (
    !contentType.includes('text/') &&
    !contentType.includes('application/json') &&
    !contentType.includes('application/xhtml+xml')
  ) {
    return null;
  }

  const body = await response.text();
  const markdown = usableResult(body);
  if (!markdown) return null;

  return {
    markdown,
    reader: 'direct-fetch'
  };
}

function sendResult(res, pageUrl, result) {
  return res.status(200).json({
    url: pageUrl,
    reader: result.reader,
    markdown: result.markdown,
    data: {
      markdown: result.markdown,
      url: pageUrl,
      reader: result.reader
    }
  });
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  res.setHeader('Cache-Control', 'no-store');

  if (req.method === 'OPTIONS') return res.status(204).end();

  if (req.method !== 'POST') {
    return res.status(405).json({
      error: 'Method not allowed'
    });
  }

  const pageUrl = normalizedUrl(req.body && req.body.url);

  if (!pageUrl) {
    return res.status(400).json({
      error: 'Missing or invalid url'
    });
  }

  const attempts = [];

  try {
    attempts.push('firecrawl');
    const firecrawlResult = await readPageWithFirecrawl(pageUrl);
    if (firecrawlResult) return sendResult(res, pageUrl, firecrawlResult);

    attempts.push('browserless');
    const browserlessResult = await readPageWithBrowserless(pageUrl);
    if (browserlessResult) return sendResult(res, pageUrl, browserlessResult);

    attempts.push('direct-fetch');
    const directResult = await readPageDirectly(pageUrl);
    if (directResult) return sendResult(res, pageUrl, directResult);

    return res.status(502).json({
      error: 'Read URL failed',
      message:
        'The URL readers returned no usable content or only an error/404 page.',
      url: pageUrl,
      attempts
    });
  } catch (error) {
    return res.status(500).json({
      error: 'Read URL request failed',
      message: error instanceof Error ? error.message : String(error),
      url: pageUrl,
      attempts
    });
  }
}
