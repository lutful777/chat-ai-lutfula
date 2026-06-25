function getBody(req) {
  return req.body && typeof req.body === 'object' ? req.body : {};
}

function authHeaders(apiKey) {
  const headerName = process.env.MEMORY_API_AUTH_HEADER || 'Authorization';
  const headerValue = headerName.toLowerCase() === 'authorization' ? `Bearer ${apiKey}` : apiKey;
  return {
    'Content-Type': 'application/json',
    [headerName]: headerValue
  };
}

function normalizeAction(action) {
  const value = String(action || '').trim().toLowerCase();
  if (['enable', 'disable', 'save', 'list', 'clear', 'delete', 'debug', 'test'].includes(value)) return value;
  return 'test';
}

function defaultMessage(action) {
  switch (action) {
    case 'enable': return 'Memory berhasil diaktifkan.';
    case 'disable': return 'Memory berhasil dimatikan.';
    case 'save': return 'Memory berhasil disimpan.';
    case 'list': return 'Memory berhasil dibaca.';
    case 'clear': return 'Semua memory berhasil dihapus.';
    case 'delete': return 'Memory yang diminta berhasil dilupakan.';
    case 'debug': return 'Debug memory selesai.';
    default: return 'Memory API aktif.';
  }
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });

  const body = getBody(req);
  const action = normalizeAction(body.action);
  const content = String(body.content || '').trim();
  const command = String(body.command || '').trim();

  const apiKey = process.env.MEMORY_API_KEY || process.env.MEMORY_SERVICE_API_KEY || process.env.MEMORY_KEY || process.env.MEM0_API_KEY;
  const apiUrl = process.env.MEMORY_API_URL || process.env.MEMORY_SERVICE_URL || process.env.MEMORY_ENDPOINT;
  const userId = process.env.MEMORY_USER_ID || 'ai-chat-user';

  if (!apiKey) {
    return res.status(500).json({
      error: 'Memory API key not set',
      message: 'Set MEMORY_API_KEY or MEMORY_SERVICE_API_KEY in Vercel Environment Variables.'
    });
  }

  if (!apiUrl) {
    return res.status(500).json({
      error: 'Memory API URL not set',
      message: 'Memory key is configured, but MEMORY_API_URL / MEMORY_SERVICE_URL is missing in Vercel.',
      status: 'key_configured_url_missing'
    });
  }

  try {
    const response = await fetch(apiUrl, {
      method: 'POST',
      headers: authHeaders(apiKey),
      body: JSON.stringify({
        action,
        content,
        command,
        user_id: userId,
        source: 'ai-chat-android',
        timestamp: new Date().toISOString()
      })
    });

    const text = await response.text();
    let payload;
    try { payload = JSON.parse(text); } catch (_) { payload = { raw: text }; }

    if (!response.ok) {
      return res.status(response.status).json({
        error: 'Memory provider failed',
        status: response.status,
        details: payload
      });
    }

    const memories = Array.isArray(payload.memories) ? payload.memories
      : Array.isArray(payload.data) ? payload.data
      : Array.isArray(payload.results) ? payload.results
      : [];

    return res.status(200).json({
      status: 'ok',
      action,
      message: payload.message || defaultMessage(action),
      memories,
      provider_response: payload
    });
  } catch (error) {
    return res.status(500).json({
      error: 'Memory request failed',
      message: error instanceof Error ? error.message : String(error)
    });
  }
}
