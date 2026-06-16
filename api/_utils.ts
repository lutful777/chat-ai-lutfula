const MAX_CONTENT_CHARS = 25000;

export function sendJson(res: any, status: number, payload: any) {
  res.status(status).setHeader("Content-Type", "application/json; charset=utf-8");
  res.end(JSON.stringify(payload));
}

export function getRequestValue(req: any, key: string): string {
  const fromQuery = req?.query?.[key];
  if (Array.isArray(fromQuery)) return String(fromQuery[0] || "").trim();
  if (typeof fromQuery === "string") return fromQuery.trim();
  const fromBody = req?.body?.[key];
  if (typeof fromBody === "string") return fromBody.trim();
  return "";
}

export function normalizeUrl(input: string): string {
  const value = String(input || "").trim();
  if (!value) return "";
  return value;
}

export function isSafeHttpUrl(input: string): boolean {
  try {
    const url = new URL(input);
    if (!["http:", "https:"].includes(url.protocol)) return false;

    const host = url.hostname.toLowerCase();
    if (!host || host === "localhost" || host.endsWith(".localhost")) return false;
    if (host === "0.0.0.0" || host === "127.0.0.1" || host === "::1") return false;
    if (host.startsWith("10.") || host.startsWith("192.168.")) return false;
    if (/^172\.(1[6-9]|2\d|3[0-1])\./.test(host)) return false;
    if (host.startsWith("169.254.")) return false;

    return true;
  } catch {
    return false;
  }
}

export function cleanHtmlToText(html: string): string {
  return String(html || "")
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, " ")
    .replace(/<svg[\s\S]*?<\/svg>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, '"')
    .replace(/&#39;/gi, "'")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, MAX_CONTENT_CHARS);
}

export function normalizeMarkdown(input: string): string {
  return String(input || "")
    .replace(/\u0000/g, "")
    .replace(/\n{4,}/g, "\n\n\n")
    .trim()
    .slice(0, MAX_CONTENT_CHARS);
}

export async function scrapeWithFirecrawl(url: string) {
  const apiKey = process.env.FIRECRAWL_API_KEY;
  if (!apiKey) throw new Error("FIRECRAWL_API_KEY belum di-set di Vercel");

  const response = await fetch("https://api.firecrawl.dev/v2/scrape", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      url,
      formats: ["markdown"],
      onlyMainContent: true,
    }),
  });

  const text = await response.text();
  let json: any = {};
  try {
    json = JSON.parse(text);
  } catch {
    json = {};
  }

  if (!response.ok) {
    throw new Error(json?.error || json?.message || `Firecrawl gagal HTTP ${response.status}: ${text.slice(0, 300)}`);
  }

  const markdown = normalizeMarkdown(
    json?.data?.markdown ||
    json?.data?.content ||
    json?.markdown ||
    json?.content ||
    ""
  );

  if (!markdown || markdown.length < 80) {
    throw new Error("Firecrawl berhasil tetapi konten kosong/terlalu pendek");
  }

  return {
    source: "firecrawl",
    markdown,
    title: json?.data?.metadata?.title || json?.metadata?.title || "",
    description: json?.data?.metadata?.description || json?.metadata?.description || "",
  };
}

export async function scrapeWithBrowserless(url: string) {
  const token = process.env.BROWSERLESS_TOKEN;
  if (!token) throw new Error("BROWSERLESS_TOKEN belum di-set di Vercel");

  const baseEndpoint = (process.env.BROWSERLESS_ENDPOINT || "https://production-sfo.browserless.io").replace(/\/$/, "");
  const response = await fetch(`${baseEndpoint}/content?token=${encodeURIComponent(token)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      url,
      gotoOptions: {
        waitUntil: "networkidle2",
        timeout: 45000,
      },
    }),
  });

  const html = await response.text();
  if (!response.ok) {
    throw new Error(`Browserless gagal HTTP ${response.status}: ${html.slice(0, 300)}`);
  }

  const markdown = cleanHtmlToText(html);
  if (!markdown || markdown.length < 40) {
    throw new Error("Browserless berhasil tetapi konten kosong/terlalu pendek");
  }

  return {
    source: "browserless",
    markdown,
    title: "",
    description: "",
  };
}

export async function searchWithSerpApi(query: string) {
  const apiKey = process.env.SERPAPI_API_KEY;
  if (!apiKey) throw new Error("SERPAPI_API_KEY belum di-set di Vercel");

  const url = new URL("https://serpapi.com/search.json");
  url.searchParams.set("engine", "google");
  url.searchParams.set("q", query);
  url.searchParams.set("api_key", apiKey);
  url.searchParams.set("num", "5");
  url.searchParams.set("hl", "id");
  url.searchParams.set("gl", "id");

  const response = await fetch(url.toString());
  const text = await response.text();
  let json: any = {};
  try {
    json = JSON.parse(text);
  } catch {
    json = {};
  }

  if (!response.ok || json?.error) {
    throw new Error(json?.error || `SerpApi gagal HTTP ${response.status}: ${text.slice(0, 300)}`);
  }

  const organic = Array.isArray(json?.organic_results) ? json.organic_results : [];
  const data = organic.slice(0, 5).map((item: any, index: number) => ({
    position: item?.position || index + 1,
    title: item?.title || "No title",
    url: item?.link || item?.redirect_link || "",
    link: item?.link || item?.redirect_link || "",
    description: item?.snippet || item?.rich_snippet?.top?.detected_extensions?.description || "",
    snippet: item?.snippet || "",
    displayedLink: item?.displayed_link || "",
  })).filter((item: any) => item.url);

  return { source: "serpapi", query, data };
}
