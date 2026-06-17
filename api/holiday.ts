import { getRequestValue, sendJson } from "./_utils";

function normalizeHolidayName(item: any): string {
  return String(item?.name || item?.local_name || item?.holiday || "Holiday").trim();
}

function normalizeHolidayType(item: any): string {
  return String(item?.type || item?.types?.[0] || "HOLIDAY").trim();
}

export default async function handler(req: any, res: any) {
  if (!["GET", "POST"].includes(req.method)) {
    return sendJson(res, 405, { success: false, ok: false, error: "Method not allowed" });
  }

  try {
    const apiKey = process.env.API_NINJAS_API_KEY;
    if (!apiKey) {
      return sendJson(res, 500, {
        success: false,
        ok: false,
        source: "api-ninjas",
        result: "Backend realtime belum tersedia: API_NINJAS_API_KEY belum di-set di Vercel.",
        error: "API_NINJAS_API_KEY belum di-set di Vercel",
      });
    }

    const date = getRequestValue(req, "date");
    const country = (getRequestValue(req, "country") || "ID").toUpperCase();

    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      return sendJson(res, 400, {
        success: false,
        ok: false,
        source: "api-ninjas",
        result: "Format tanggal tidak valid. Gunakan YYYY-MM-DD.",
        error: "Invalid date format. Use YYYY-MM-DD.",
      });
    }

    const url = new URL("https://api.api-ninjas.com/v1/isholiday");
    url.searchParams.set("date", date);
    url.searchParams.set("country", country);

    const response = await fetch(url.toString(), {
      headers: {
        "X-Api-Key": apiKey,
      },
    });

    const text = await response.text();
    let json: any = null;
    try {
      json = JSON.parse(text);
    } catch {
      json = null;
    }

    if (!response.ok) {
      const message = json?.error || json?.message || text || `API Ninjas gagal HTTP ${response.status}`;
      return sendJson(res, response.status, {
        success: false,
        ok: false,
        source: "api-ninjas",
        date,
        country,
        result: `API holiday gagal: ${message}`,
        error: message,
      });
    }

    const holidays = Array.isArray(json) ? json : [];
    const names = holidays.map(normalizeHolidayName).filter(Boolean);
    const types = holidays.map(normalizeHolidayType).filter(Boolean);
    const isHoliday = holidays.length > 0;

    const result = isHoliday
      ? `Tanggal ${date} adalah hari libur/holiday di ${country}. Nama: ${names.join(", ") || "Holiday"}. Tipe: ${types.join(", ") || "HOLIDAY"}. Sumber: API Ninjas realtime API.`
      : `Tanggal ${date} bukan holiday menurut API Ninjas untuk negara ${country}. Sumber: API Ninjas realtime API.`;

    return sendJson(res, 200, {
      success: true,
      ok: true,
      source: "api-ninjas",
      date,
      country,
      isHoliday,
      holidays,
      result,
    });
  } catch (error: any) {
    return sendJson(res, 500, {
      success: false,
      ok: false,
      source: "api-ninjas",
      result: `Backend holiday gagal: ${error?.message || "Unknown error"}`,
      error: error?.message || "Holiday backend gagal",
    });
  }
}
