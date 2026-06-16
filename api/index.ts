import { sendJson } from "./_utils";

export default async function handler(req: any, res: any) {
  return sendJson(res, 200, {
    success: true,
    ok: true,
    service: "chat-ai-lutfula-vercel-web-api",
    endpoints: {
      health: "/api/web-health",
      search: "/api/search?q=harga%20btc%20hari%20ini",
      serpapi: "/api/search-serpapi?q=harga%20btc%20hari%20ini",
      readUrl: "POST /api/read-url",
      firecrawl: "POST /api/read-firecrawl",
      browserless: "POST /api/read-browserless"
    }
  });
}
