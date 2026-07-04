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

## Think Deeply whole-site mode

Whole-site reading is deliberately separate from ordinary deep research. It activates only when both conditions are true:

1. the Android chat mode is `THINK_DEEPLY`;
2. the user supplies or references a URL and explicitly asks to read the whole website, all pages, or the entire public site.

Normal and Think modes keep their existing behavior. Think Deeply questions that do not explicitly request a whole-site scan also keep the normal deep-research flow.

When active, the Android client calls `/api/crawl-site` directly with `wholeSite=true`. The crawler:

- reads robots and expanded sitemap indexes;
- gathers up to 5,000 discoverable same-origin URLs;
- follows internal links and pagination up to depth 12;
- reads pages in small parallel batches;
- removes duplicate and tracking URLs;
- skips non-page assets;
- returns a page inventory, detailed excerpts, failures, and a coverage report.

The default whole-site request budget is 120 successfully read pages or about 105 seconds. The crawler reports `discovered`, `attempted`, `succeeded`, `failed`, `pending`, `duplicates`, `timedOut`, and `complete`. The assistant must not claim full coverage when failures, pending URLs, time limits, or discovery limits remain.

## Backend endpoints

- `/api/search`: Firecrawl search with longer snippets and optional full-page enrichment.
- `/api/read-url`: JavaScript-capable page reading with headings, links, tables, and structured data.
- `/api/read-sitemap`: discovers public sitemap URLs; whole-site mode expands the sitemap and URL limits.
- `/api/crawl-site`: bounded same-origin crawling; whole-site mode traverses all discoverable public page URLs within its declared budget.
- `/api/research`: handles the existing quick, standard, and deep research flows.

## Safety and limits

Website content is wrapped as `UNTRUSTED_WEB_DATA`. The model must treat it as evidence, not instructions. The backend rejects obvious private/local URLs, limits page counts and response sizes, avoids unsupported file types, and stops when the configured research budget is exhausted.

The crawler is intended for publicly accessible pages. It does not bypass login, paywalls, CAPTCHA, access controls, or private APIs. “Complete” means every public page URL discovered within the sitemap/link graph and configured limits was attempted; it is not a claim that hidden or inaccessible pages exist nowhere.

No SDK, Kotlin, Gradle, or Android plugin versions are changed by this feature.
