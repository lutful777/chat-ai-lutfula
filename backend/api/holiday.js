import { getEnv, handleOptions, isValidDate, json, safeError, todayJakartaDate } from './_utils.js';

async function fetchJson(url, headers) {
  const response = await fetch(url, { headers });
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { raw: text.slice(0, 1000) }; }
  return { response, data };
}

export default async function handler(req, res) {
  if (handleOptions(req, res)) return;

  try {
    const date = String(req.query?.date || todayJakartaDate()).trim();
    const country = String(req.query?.country || 'ID').trim().toUpperCase();

    if (!isValidDate(date)) return json(res, 400, { ok: false, error: 'Invalid date. Use YYYY-MM-DD.' });

    const apiKey = getEnv('API_NINJAS_API_KEY');
    if (!apiKey) return json(res, 500, { ok: false, error: 'API_NINJAS_API_KEY is not configured on backend.' });

    const headers = { 'X-Api-Key': apiKey };
    const year = date.slice(0, 4);

    const workingUrl = `https://api.api-ninjas.com/v1/isworkingday?country=${encodeURIComponent(country)}&date=${encodeURIComponent(date)}`;
    const holidayUrl = `https://api.api-ninjas.com/v1/publicholidays?country=${encodeURIComponent(country)}&year=${encodeURIComponent(year)}`;

    const [workingResult, holidaysResult] = await Promise.all([
      fetchJson(workingUrl, headers),
      fetchJson(holidayUrl, headers)
    ]);

    if (!workingResult.response.ok) {
      return json(res, workingResult.response.status, { ok: false, error: 'API Ninjas isworkingday failed.', status: workingResult.response.status, details: workingResult.data });
    }

    const holidays = Array.isArray(holidaysResult.data) ? holidaysResult.data : [];
    const holiday = holidays.find((item) => item?.date === date) || null;
    const isWorkingDay = Boolean(workingResult.data?.is_working_day);

    json(res, 200, {
      ok: true,
      result: holiday?.name
        ? `Tanggal merah nasional: Yes. Libur: ${holiday.name}. Status isWorkingDay: ${isWorkingDay}.`
        : (!isWorkingDay ? `Tanggal merah nasional: Yes (Weekend/Non-working day). Status isWorkingDay: ${isWorkingDay}.` : 'Tanggal merah nasional: No (Working Day).'),
      date,
      country,
      isWorkingDay,
      isHoliday: Boolean(holiday) || !isWorkingDay,
      holidayName: holiday?.name || '',
      holiday,
      holidays,
      rawWorkingDay: workingResult.data
    });
  } catch (error) {
    json(res, 500, { ok: false, error: safeError(error) });
  }
}
