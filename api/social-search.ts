import { getRequestValue, sendJson } from "./_utils";

type SocialResult = {
  position: number;
  title: string;
  url: string;
  link: string;
  description: string;
  snippet: string;
  displayedLink: string;
  platform: string;
};

const PLATFORM_ALIASES: Record<string, string> = {
  ig: "instagram",
  instagram: "instagram",
  tiktok: "tiktok",
  tt: "tiktok",
  x: "x",
  twitter: "x",
  facebook: "facebook",
  fb: "facebook",
  youtube: "youtube",
  yt: "youtube",
  reddit: "reddit",
  all: "all",
};

const ALL_PLATFORMS = ["instagram", "tiktok", "x", "facebook", "youtube", "reddit"];

function normalizePlatform(value: string) {
  const key = String(value || "").trim().toLowerCase();
  return PLATFORM_ALIASES[key] || "all";
}

function buildGoogleQuery(platform: string, query: string) {
  const q = query.trim() || "ai tools global";
  switch (platform) {
    case "instagram":
      return `site:instagram.com ${q}`;
    case "tiktok":
      return `site:tiktok.com ${q}`;
    case "x":
      return `(site:x.com OR site:twitter.com) ${q}`;
    case "facebook":
      return `site:facebook.com ${q}`;
    case "reddit":
      return `site:reddit.com ${q}`;
    default:
      return q;
  }
}

function normalizeGoogleResults(platform: string, json: any): SocialResult[] {
  const organic = Array.isArray(json?.organic_results) ? json.organic_results : [];
  return organic.slice(0, 8).map((item: any, index: number) => ({
    position: item?.position || index + 1,
    title: item?.title || "No title",
    url: item?.link || item?.redirect_link || "",
    link: item?.link || item?.redirect_link || "",
    description: item?.snippet || "",
    snippet: item?.snippet || "",
    displayedLink: item?.displayed_link || "",
    platform,
  })).filter((item: SocialResult) => item.url);
}

function normalizeYoutubeResults(json: any): SocialResult[] {
  const videoResults = Array.isArray(json?.video_results) ? json.video_results : [];
  const organic = Array.isArray(json?.organic_results) ? json.organic_results : [];
  const items = videoResults.length > 0 ? videoResults : organic;

  return items.slice(0, 8).map((item: any, index: number) => ({
    position: item?.position || index + 1,
    title: item?.title || item?.title_text || "No title",
    url: item?.link || item?.video_link || item?.url || "",
    link: item?.link || item?.video_link || item?.url || "",
    description: item?.description || item?.snippet || "",
    snippet: item?.description || item?.snippet || "",
    displayedLink: item?.channel?.name || item?.displayed_link || "YouTube",
    platform: "youtube",
  })).filter((item: SocialResult) => item.url);
}

async function serpApiRequest(params: Record<string, string>) {
  const apiKey = process.env.SERPAPI_API_KEY;
  if (!apiKey) throw new Error("SERPAPI_API_KEY belum di-set di Vercel");

  const url = new URL("https://serpapi.com/search.json");
  Object.entries(params).forEach(([key, value]) => url.searchParams.set(key, value));
  url.searchParams.set("api_key", apiKey);

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

  return json;
}

async function searchPlatform(platform: string, query: string) {
  if (platform === "youtube") {
    const json = await serpApiRequest({
      engine: "youtube",
      search_query: query.trim() || "ai tools global",
      hl: "en",
      gl: "us",
    });

    return {
      platform,
      engine: "youtube",
      query: query.trim() || "ai tools global",
      searchQuery: query.trim() || "ai tools global",
      data: normalizeYoutubeResults(json),
    };
  }

  const searchQuery = buildGoogleQuery(platform, query);
  const json = await serpApiRequest({
    engine: "google",
    q: searchQuery,
    num: "8",
    hl: "en",
    gl: "us",
  });

  return {
    platform,
    engine: "google",
    query: query.trim() || "ai tools global",
    searchQuery,
    data: normalizeGoogleResults(platform, json),
  };
}

export default async function handler(req: any, res: any) {
  if (!["GET", "POST"].includes(req.method)) {
    return sendJson(res, 405, { success: false, ok: false, error: "Method not allowed" });
  }

  try {
    const query = getRequestValue(req, "q") || getRequestValue(req, "query") || "ai tools global";
    const platform = normalizePlatform(getRequestValue(req, "platform"));

    if (platform === "all") {
      const settled = await Promise.allSettled(
        ALL_PLATFORMS.map((p) => searchPlatform(p, query))
      );

      const platforms: Record<string, any> = {};
      const combined: SocialResult[] = [];
      const errors: Record<string, string> = {};

      settled.forEach((item, index) => {
        const p = ALL_PLATFORMS[index];
        if (item.status === "fulfilled") {
          platforms[p] = item.value;
          combined.push(...item.value.data);
        } else {
          errors[p] = item.reason?.message || "gagal";
        }
      });

      return sendJson(res, 200, {
        success: true,
        ok: true,
        source: "serpapi-social-search",
        platform: "all",
        query,
        data: combined,
        results: combined,
        platforms,
        errors,
      });
    }

    const result = await searchPlatform(platform, query);
    return sendJson(res, 200, {
      success: true,
      ok: true,
      source: "serpapi-social-search",
      platform,
      engine: result.engine,
      query: result.query,
      searchQuery: result.searchQuery,
      data: result.data,
      results: result.data,
    });
  } catch (error: any) {
    return sendJson(res, 500, {
      success: false,
      ok: false,
      source: "serpapi-social-search",
      error: error?.message || "Social search gagal",
    });
  }
}
