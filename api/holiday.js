function isValidDate(value) {
  return /^\d{4}-\d{2}-\d{2}$/.test(String(value || ''));
}

function safeCountry(value) {
  const country = String(value || 'ID').trim().toUpperCase();
  return /^[A-Z]{2}$/.test(country) ? country : 'ID';
}

function formatResult(date, country, holiday, isWeekend) {
  if (holiday) {
    return `${date} adalah hari libur/tanggal merah di ${country}: ${holiday.localName || holiday.name}.`;
  }

  if (isWeekend) {
    return `${date} bukan hari libur nasional yang terdeteksi, tetapi jatuh pada akhir pekan.`;
  }

  return `${date} adalah hari kerja normal. Tidak terdeteksi sebagai hari libur nasional di ${country}.`;
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'GET') return res.status(405).json({ error: 'Method not allowed' });

  const date = String(req.query.date || '').trim();
  const country = safeCountry(req.query.country);

  if (!isValidDate(date)) {
    return res.status(400).json({
      error: 'Invalid date',
      result: 'Format tanggal tidak valid. Gunakan YYYY-MM-DD.'
    });
  }

  const year = date.slice(0, 4);
  const targetDate = new Date(`${date}T00:00:00Z`);
  const day = targetDate.getUTCDay();
  const isWeekend = day === 0 || day === 6;

  try {
    const url = `https://date.nager.at/api/v3/PublicHolidays/${year}/${country}`;
    const response = await fetch(url, { headers: { accept: 'application/json' } });
    const text = await response.text();

    let holidays = [];
    try {
      holidays = JSON.parse(text);
    } catch (_) {
      holidays = [];
    }

    if (!response.ok || !Array.isArray(holidays)) {
      return res.status(502).json({
        error: 'Holiday provider failed',
        result: 'Backend realtime belum tersedia atau gagal mengambil data.',
        status: response.status
      });
    }

    const holiday = holidays.find((item) => item && item.date === date);
    const result = formatResult(date, country, holiday, isWeekend);

    return res.status(200).json({
      date,
      country,
      is_holiday: Boolean(holiday),
      is_weekend: isWeekend,
      holiday: holiday || null,
      result
    });
  } catch (error) {
    return res.status(500).json({
      error: 'Holiday request failed',
      message: error instanceof Error ? error.message : String(error),
      result: 'Backend realtime belum tersedia atau gagal mengambil data.'
    });
  }
}
