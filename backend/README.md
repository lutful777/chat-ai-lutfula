# Chat AI Backend

Backend ini untuk menyimpan API key di server, bukan di APK.

## Deploy ke Vercel

1. Import repo ini ke Vercel.
2. Pada pengaturan project Vercel, set **Root Directory** ke:

```text
backend
```

3. Tambahkan Environment Variables di Vercel:

```text
FIRECRAWL_API_KEY
API_NINJAS_API_KEY
```

4. Deploy ulang project.

## Endpoint

```text
GET  /api/health
GET  /api/search?q=btc
POST /api/search
POST /api/read-url
GET  /api/read-url?url=https://example.com
GET  /api/holiday?date=2026-06-15&country=ID
GET  /api/is-working-day?date=2026-06-15&country=ID
```

## Contoh dari APK Kotlin

```text
GET https://YOUR-VERCEL-DOMAIN.vercel.app/api/holiday?date=2026-06-15&country=ID
GET https://YOUR-VERCEL-DOMAIN.vercel.app/api/search?q=btc
```

APK hanya perlu menyimpan Backend Base URL. Jangan simpan Firecrawl/API Ninjas key di APK.
