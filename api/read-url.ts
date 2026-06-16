import { getRequestValue, isSafeHttpUrl, normalizeUrl, scrapeWithBrowserless, scrapeWithFirecrawl, sendJson } from "./_utils";

export default async function handler(req: any, res: any) {
  if (req.method !== "POST") {
    return sendJson(res, 405, { success: false, ok: false, error: "Method not allowed. Gunakan POST." });
  }

  const url = normalizeUrl(getRequestValue(req, "url"));
  if (!isSafeHttpUrl(url)) {
    return sendJson(res, 400, { success: false, ok: false, error: "URL tidak valid atau tidak aman" });
  }

  const errors: string[] = [];

  if (process.env.FIRECRAWL_API_KEY) {
    try {
      const result = await scrapeWithFirecrawl(url);
      return sendJson(res, 200, {
        success: true,
        ok: true,
        source: result.source,
        url,
        markdown: result.markdown,
        content: result.markdown,
        data: {
          markdown: result.markdown,
          metadata: {
            title: result.title,
            description: result.description,
            source: result.source,
          },
        },
      });
    } catch (error: any) {
      errors.push(`Firecrawl: ${error?.message || "gagal"}`);
    }
  } else {
    errors.push("Firecrawl: FIRECRAWL_API_KEY belum di-set");
  }

  if (process.env.BROWSERLESS_TOKEN) {
    try {
      const result = await scrapeWithBrowserless(url);
      return sendJson(res, 200, {
        success: true,
        ok: true,
        source: result.source,
        url,
        warning: errors.join(" | "),
        markdown: result.markdown,
        content: result.markdown,
        data: {
          markdown: result.markdown,
          metadata: {
            title: result.title,
            description: result.description,
            source: result.source,
            warning: errors.join(" | "),
          },
        },
      });
    } catch (error: any) {
      errors.push(`Browserless: ${error?.message || "gagal"}`);
    }
  } else {
    errors.push("Browserless: BROWSERLESS_TOKEN belum di-set");
  }

  return sendJson(res, 500, {
    success: false,
    ok: false,
    source: "none",
    url,
    error: errors.join(" | ") || "Gagal membaca website",
  });
}
