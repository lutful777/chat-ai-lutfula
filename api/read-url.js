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
        timeout: 15000
      }
    })
  });

  if (!response.ok) return null;

  const html = await response.text();
  const markdown = shortText(html, 10000);
  if (!markdown) return null;

  return {
    markdown,
    reader: 'browserless'
  };
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
      formats: ['markdown', 'html']
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
  const markdown = data.markdown || data.content || data.html || json.markdown || json.html || '';
  const cleaned = shortText(markdown, 10000);

  if (!cleaned) return null;

  return {
    markdown: cleaned,
    reader: 'firecrawl-scrape'
  };
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

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

  try {
    const browserlessResult = await readPageWithBrowserless(pageUrl);

    if (browserlessResult) {
      return res.status(200).json({
        url: pageUrl,
        reader: browserlessResult.reader,
        markdown: browserlessResult.markdown,
        data: {
          markdown: browserlessResult.markdown,
          url: pageUrl,
          reader: browserlessResult.reader
        }
      });
    }

    const firecrawlResult = await readPageWithFirecrawl(pageUrl);

    if (firecrawlResult) {
      return res.status(200).json({
        url: pageUrl,
        reader: firecrawlResult.reader,
        markdown: firecrawlResult.markdown,
        data: {
          markdown: firecrawlResult.markdown,
          url: pageUrl,
          reader: firecrawlResult.reader
        }
      });
    }

    return res.status(502).json({
      error: 'Read URL failed',
      message: 'Browserless and Firecrawl could not read this URL.',
      url: pageUrl
    });
  } catch (error) {
    return res.status(500).json({
      error: 'Read URL request failed',
      message: error instanceof Error ? error.message : String(error),
      url: pageUrl
    });
  }
}
