import { sendJson } from "./_utils";

export default async function handler(req: any, res: any) {
  if (req.method !== "GET") {
    return sendJson(res, 405, { success: false, ok: false, error: "Method not allowed" });
  }

  return sendJson(res, 200, {
    success: true,
    ok: true,
    service: "chat-ai-lutfula-vercel-web-api",
    env: {
      SERPAPI_API_KEY: Boolean(process.env.SERPAPI_API_KEY),
      BROWSERLESS_TOKEN: Boolean(process.env.BROWSERLESS_TOKEN),
      FIRECRAWL_API_KEY: Boolean(process.env.FIRECRAWL_API_KEY),
      BROWSERLESS_ENDPOINT: process.env.BROWSERLESS_ENDPOINT || "https://production-sfo.browserless.io",
    },
  });
}
