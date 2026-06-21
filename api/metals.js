function setCors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
}

function pickPrice(payload) {
  const candidates = [];

  if (payload && typeof payload.price === 'number') candidates.push(payload.price);
  if (payload && typeof payload.rate === 'number') candidates.push(payload.rate);

  const metals = payload && typeof payload === 'object' ? payload.metals : null;
  if (metals) {
    if (typeof metals.gold === 'number') candidates.push(metals.gold);
    if (typeof metals.XAU === 'number') candidates.push(metals.XAU);
    if (typeof metals.xau === 'number') candidates.push(metals.xau);
  }

  const rates = payload && typeof payload === 'object' ? payload.rates : null;
  if (rates) {
    if (typeof rates.gold === 'number') candidates.push(rates.gold);
    if (typeof rates.XAU === 'number') candidates.push(rates.XAU);
    if (typeof rates.xau === 'number') candidates.push(rates.xau);
    if (rates.metals && typeof rates.metals === 'object') {
      if (typeof rates.metals.gold === 'number') candidates.push(rates.metals.gold);
      if (typeof rates.metals.XAU === 'number') candidates.push(rates.metals.XAU);
      if (typeof rates.metals.xau === 'number') candidates.push(rates.metals.xau);
    }
  }

  return candidates.find((value) => Number.isFinite(value) && value > 0) || 0;
}

export default async function handler(req, res) {
  setCors(res);

  if (req.method === 'OPTIONS') {
    return res.status(204).end();
  }

  if (req.method !== 'GET') {
    return res.status(405).json({ success: false, error: 'Method not allowed' });
  }

  const apiKey = process.env.METALS_API_KEY || process.env.METALS_DEV_API_KEY;
  if (!apiKey || apiKey === 'YOUR_METALS_API_KEY' || apiKey === 'YOUR_METALS_DEV_API_KEY' || apiKey === 'VERCEL_BACKEND') {
    return res.status(500).json({
      success: false,
      error: 'METALS_API_KEY is not configured in Vercel Environment Variables'
    });
  }

  const symbol = String(req.query.symbol || 'XAU').toUpperCase();
  const currency = String(req.query.currency || 'USD').toUpperCase();
  const unit = String(req.query.unit || 'toz').toLowerCase();

  if (symbol !== 'XAU' && symbol !== 'GOLD') {
    return res.status(400).json({ success: false, error: 'Only XAU/GOLD is supported by this endpoint' });
  }

  const upstreamUrl = new URL('https://api.metals.dev/v1/latest');
  upstreamUrl.searchParams.set('api_key', apiKey);
  upstreamUrl.searchParams.set('currency', currency);
  upstreamUrl.searchParams.set('unit', unit);

  try {
    const upstream = await fetch(upstreamUrl.toString(), {
      method: 'GET',
      headers: { Accept: 'application/json' }
    });

    const text = await upstream.text();
    let payload;
    try {
      payload = JSON.parse(text);
    } catch (error) {
      payload = { raw: text };
    }

    if (!upstream.ok) {
      return res.status(upstream.status).json({
        success: false,
        error: `Metals upstream error ${upstream.status}`,
        upstream: payload
      });
    }

    const price = pickPrice(payload);
    if (!price) {
      return res.status(502).json({
        success: false,
        error: 'Gold/XAU price was not found in Metals API response',
        upstream: payload
      });
    }

    return res.status(200).json({
      success: true,
      ok: true,
      symbol: 'XAU',
      currency,
      unit,
      price,
      priceFormatted: Number(price).toFixed(2),
      source: 'metals.dev via Vercel backend',
      updatedAt: new Date().toISOString()
    });
  } catch (error) {
    return res.status(500).json({
      success: false,
      error: error && error.message ? error.message : 'Failed to fetch Metals API'
    });
  }
}
