# Semantic browser router and deep web research

The app asks the configured text model whether a question needs current web data before invoking the browser backend.

## Routing order

1. Detect an explicit or previously referenced URL.
2. Respect explicit `#browser`, `#cari`, and `#berita` commands.
3. Ask the configured text model for a JSON browser decision.
4. Fall back to deterministic rules if the router call fails or returns invalid JSON.

The router never answers the user. It returns:

- whether browsing is required;
- `search` or `news` mode;
- a concise research query;
- research depth: `quick`, `standard`, or `deep`.

## Research depths

- `quick`: one search and a small number of page reads.
- `standard`: multiple searches, full-page reads, and limited same-domain crawling.
- `deep`: query expansion, sitemap discovery, same-domain crawling, full-page reads, and cross-source verification.

Requests that ask for a complete list, all available models/products, full website contents, or deep verification should use `deep`.

## Backend endpoints

- `/api/search`: Firecrawl search with longer snippets and optional full-page enrichment.
- `/api/read-url`: JavaScript-capable page reading with headings, links, tables, and structured data.
- `/api/read-sitemap`: discovers public sitemap URLs and ranks pages relevant to a query.
- `/api/crawl-site`: bounded, same-origin crawl with depth and page limits.
- `/api/research`: orchestrates direct reading, crawling, query expansion, search, additional page reads, deduplication, and source ranking.

## Safety and limits

Website content is wrapped as `UNTRUSTED_WEB_DATA`. The model must treat it as evidence, not instructions. The backend rejects obvious private/local URLs, limits page counts and response sizes, avoids unsupported file types, and stops when the configured research budget is exhausted.

The crawler is intended for publicly accessible pages. It does not bypass login, paywalls, CAPTCHA, access controls, or private APIs.

No SDK, Kotlin, Gradle, or Android plugin versions are changed by this feature.
