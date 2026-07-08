import sharp from 'sharp';
import { extractImages, firecrawlRequest, normalizedUrl } from './search.js';

const MAX_GALLERY_IMAGES = 20;
const MAX_COLLAGE_IMAGES = 9;
const MAX_REMOTE_IMAGE_BYTES = 12 * 1024 * 1024;
const ARTICLE_CACHE_SECONDS = 3600;

function escapeHtml(input) {
  return String(input || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function galleryBaseUrl(req, articleUrl) {
  const forwardedHost = String(req.headers['x-forwarded-host'] || req.headers.host || '').split(',')[0].trim();
  const forwardedProto = String(req.headers['x-forwarded-proto'] || 'https').split(',')[0].trim();
  const protocol = forwardedProto === 'http' ? 'http' : 'https';
  return `${protocol}://${forwardedHost}/api/news-gallery?article=${encodeURIComponent(articleUrl)}`;
}

async function scrapeArticle(articleUrl, token) {
  const { response, json } = await firecrawlRequest(
    'https://api.firecrawl.dev/v1/scrape',
    token,
    {
      url: articleUrl,
      formats: ['html'],
      onlyMainContent: true
    },
    26000
  );

  if (!response.ok) return null;
  const data = json.data || json;
  const html = data.html || '';
  const metadata = data.metadata || {};
  return {
    html,
    metadata,
    images: extractImages(html, metadata, articleUrl, MAX_GALLERY_IMAGES)
  };
}

async function fetchRemoteImage(imageUrl) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 12000);
  try {
    const response = await fetch(imageUrl, {
      headers: {
        Accept: 'image/avif,image/webp,image/apng,image/jpeg,image/png,image/*,*/*;q=0.8',
        'User-Agent': 'Mozilla/5.0 (compatible; AiChatNewsGallery/1.0)'
      },
      redirect: 'follow',
      signal: controller.signal
    });
    if (!response.ok) return null;

    const contentType = String(response.headers.get('content-type') || '').toLowerCase();
    if (!contentType.startsWith('image/')) return null;
    const contentLength = Number(response.headers.get('content-length') || 0);
    if (contentLength > MAX_REMOTE_IMAGE_BYTES) return null;

    const buffer = Buffer.from(await response.arrayBuffer());
    if (!buffer.length || buffer.length > MAX_REMOTE_IMAGE_BYTES) return null;
    return { buffer, contentType };
  } catch (_) {
    return null;
  } finally {
    clearTimeout(timeout);
  }
}

function chooseGrid(count) {
  if (count <= 1) return { columns: 1, rows: 1 };
  if (count <= 4) return { columns: 2, rows: 2 };
  return { columns: 3, rows: 3 };
}

async function createCollage(images) {
  const selected = images.slice(0, MAX_COLLAGE_IMAGES);
  const fetched = await Promise.all(selected.map(image => fetchRemoteImage(image.url)));
  const usable = fetched.filter(Boolean);
  if (!usable.length) return null;

  const width = 1200;
  const height = 675;
  const { columns, rows } = chooseGrid(usable.length);
  const cellWidth = Math.floor(width / columns);
  const cellHeight = Math.floor(height / rows);
  const composites = [];

  for (let index = 0; index < usable.length; index += 1) {
    const column = index % columns;
    const row = Math.floor(index / columns);
    const tile = await sharp(usable[index].buffer)
      .rotate()
      .resize(cellWidth, cellHeight, {
        fit: 'cover',
        position: 'attention',
        withoutEnlargement: false
      })
      .jpeg({ quality: 82, progressive: true })
      .toBuffer();

    composites.push({
      input: tile,
      left: column * cellWidth,
      top: row * cellHeight
    });
  }

  if (images.length > MAX_COLLAGE_IMAGES) {
    const extra = images.length - MAX_COLLAGE_IMAGES;
    const labelWidth = 270;
    const labelHeight = 86;
    const svg = Buffer.from(`
      <svg width="${labelWidth}" height="${labelHeight}" xmlns="http://www.w3.org/2000/svg">
        <rect width="100%" height="100%" rx="18" fill="rgba(0,0,0,0.72)"/>
        <text x="50%" y="54%" dominant-baseline="middle" text-anchor="middle"
              font-family="sans-serif" font-size="34" font-weight="700" fill="white">+${extra} foto</text>
      </svg>
    `);
    composites.push({
      input: svg,
      left: width - labelWidth - 28,
      top: height - labelHeight - 24
    });
  }

  return sharp({
    create: {
      width,
      height,
      channels: 3,
      background: { r: 12, g: 12, b: 12 }
    }
  })
    .composite(composites)
    .jpeg({ quality: 84, progressive: true })
    .toBuffer();
}

function renderGalleryHtml(req, articleUrl, images) {
  const base = galleryBaseUrl(req, articleUrl);
  const cards = images.map((image, index) => {
    const proxyUrl = `${base}&mode=single&index=${index}`;
    const alt = escapeHtml(image.alt || `Foto berita ${index + 1}`);
    return `
      <figure class="photo-card">
        <img src="${escapeHtml(proxyUrl)}" alt="${alt}" loading="lazy" decoding="async" />
        <figcaption>Foto ${index + 1}</figcaption>
      </figure>`;
  }).join('');

  return `<!doctype html>
<html lang="id">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover" />
  <meta name="color-scheme" content="dark" />
  <title>Galeri Foto Berita</title>
  <style>
    :root { color-scheme: dark; }
    * { box-sizing: border-box; }
    body { margin: 0; background: #0d0d0f; color: #f4f4f5; font-family: system-ui,-apple-system,sans-serif; }
    header { position: sticky; top: 0; z-index: 3; padding: 16px 18px; background: rgba(13,13,15,.92); backdrop-filter: blur(14px); border-bottom: 1px solid #29292d; }
    h1 { margin: 0; font-size: 20px; }
    p { margin: 5px 0 0; color: #a8a8b0; font-size: 14px; }
    main { width: min(980px,100%); margin: 0 auto; padding: 14px; display: grid; gap: 14px; }
    .photo-card { margin: 0; overflow: hidden; border-radius: 14px; background: #18181b; border: 1px solid #2b2b30; }
    .photo-card img { display: block; width: 100%; height: auto; max-height: 82vh; object-fit: contain; background: #000; }
    figcaption { padding: 9px 12px; color: #b8b8bf; font-size: 13px; }
  </style>
</head>
<body>
  <header>
    <h1>Galeri Foto Berita</h1>
    <p>${images.length} foto relevan ditemukan dari artikel.</p>
  </header>
  <main>${cards}</main>
</body>
</html>`;
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('X-Content-Type-Options', 'nosniff');

  if (req.method !== 'GET') return res.status(405).send('Method not allowed');

  const articleUrl = normalizedUrl(typeof req.query.article === 'string' ? req.query.article : '');
  if (!articleUrl) return res.status(400).send('Artikel tidak valid.');

  const token = process.env.FIRECRAWL_API_KEY;
  if (!token) return res.status(500).send('Konfigurasi galeri belum tersedia.');

  const article = await scrapeArticle(articleUrl, token);
  const images = article?.images || [];
  if (!images.length) return res.status(404).send('Foto artikel tidak ditemukan.');

  res.setHeader('Cache-Control', `public, s-maxage=${ARTICLE_CACHE_SECONDS}, stale-while-revalidate=86400`);

  const mode = typeof req.query.mode === 'string' ? req.query.mode.toLowerCase() : '';
  if (mode === 'single') {
    const index = Math.max(0, Math.min(images.length - 1, Number(req.query.index) || 0));
    const remote = await fetchRemoteImage(images[index].url);
    if (!remote) return res.status(502).send('Foto gagal dimuat.');
    res.setHeader('Content-Type', remote.contentType);
    return res.status(200).send(remote.buffer);
  }

  const accept = String(req.headers.accept || '').toLowerCase();
  const wantsHtml = accept.includes('text/html') || mode === 'gallery';
  if (wantsHtml) {
    res.setHeader('Content-Type', 'text/html; charset=utf-8');
    res.setHeader(
      'Content-Security-Policy',
      "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"
    );
    return res.status(200).send(renderGalleryHtml(req, articleUrl, images));
  }

  try {
    const collage = await createCollage(images);
    if (collage) {
      res.setHeader('Content-Type', 'image/jpeg');
      res.setHeader('X-News-Image-Count', String(images.length));
      return res.status(200).send(collage);
    }
  } catch (_) {}

  const fallback = await fetchRemoteImage(images[0].url);
  if (!fallback) return res.status(502).send('Foto gagal dimuat.');
  res.setHeader('Content-Type', fallback.contentType);
  res.setHeader('X-News-Image-Count', String(images.length));
  return res.status(200).send(fallback.buffer);
}
