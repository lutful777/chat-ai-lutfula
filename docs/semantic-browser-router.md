# Semantic browser router

The app now asks the configured text model whether a question needs current web data before invoking Browserless/Firecrawl.

Routing order:

1. Reuse an explicit or previously referenced URL.
2. Respect explicit `#browser`, `#cari`, and `#berita` commands.
3. Ask the configured text model for a JSON browser decision.
4. Fall back to the existing deterministic rules if the router call fails or returns invalid JSON.

The router never answers the user. It only returns whether browsing is needed, the search mode, and a concise query. Existing dedicated price and holiday contexts are exposed to the router so it can avoid unnecessary browsing when those sources already answer the request.

No dependency, SDK, Kotlin, Gradle, or Android plugin versions are changed.
