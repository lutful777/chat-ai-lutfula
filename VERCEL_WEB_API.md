# Vercel Web API

Endpoint ini dipakai oleh aplikasi Android untuk real-time search dan membaca website tanpa mengekspos API key ke APK.

## Environment Variables di Vercel

Tambahkan di Project Settings > Environment Variables:

```env
SERPAPI_API_KEY=isi_key_serpapi
BROWSERLESS_TOKEN=isi_token_browserless
FIRECRAWL_API_KEY=isi_key_firecrawl
BROWSERLESS_ENDPOINT=https://production-sfo.browserless.io
```

`FIRECRAWL_API_KEY` opsional untuk fallback, tetapi direkomendasikan. Kalau Firecrawl kosong/gagal, `/api/read-url` akan mencoba Browserless selama `BROWSERLESS_TOKEN` tersedia.

## Endpoint terpisah

### 1. Search Google via SerpApi

```bash
curl "https://YOUR-VERCEL-DOMAIN.vercel.app/api/search?q=harga%20btc%20hari%20ini"
```

Alias terpisah:

```bash
curl "https://YOUR-VERCEL-DOMAIN.vercel.app/api/search-serpapi?q=harga%20btc%20hari%20ini"
```

Response utama:

```json
{
  "success": true,
  "source": "serpapi",
  "data": [
    { "title": "...", "url": "...", "description": "..." }
  ]
}
```

### 2. Baca URL via Firecrawl saja

```bash
curl -X POST "https://YOUR-VERCEL-DOMAIN.vercel.app/api/read-firecrawl" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}'
```

### 3. Baca URL via Browserless saja

```bash
curl -X POST "https://YOUR-VERCEL-DOMAIN.vercel.app/api/read-browserless" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}'
```

### 4. Endpoint kompatibel untuk APK

```bash
curl -X POST "https://YOUR-VERCEL-DOMAIN.vercel.app/api/read-url" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}'
```

Alur `/api/read-url`:

1. Coba Firecrawl.
2. Kalau Firecrawl gagal/kosong, pakai Browserless.
3. Return `data.markdown`, `markdown`, dan `content` agar kompatibel dengan kode Android lama.

## Catatan keamanan

- Jangan pakai prefix `NEXT_PUBLIC_` untuk key rahasia.
- API key disimpan di Vercel server, bukan di APK.
- Endpoint memblokir URL `localhost`, `127.0.0.1`, IP private, dan protocol selain HTTP/HTTPS.
