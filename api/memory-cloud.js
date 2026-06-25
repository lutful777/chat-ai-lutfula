function getBody(req) {
  return req.body && typeof req.body === 'object' ? req.body : {};
}

function normalizeAction(action) {
  const value = String(action || '').trim().toLowerCase();
  if (['test', 'save', 'list', 'clear', 'delete', 'debug'].includes(value)) return value;
  return 'test';
}

function makeHeaders(key) {
  const headerName = process.env.MEMORY_CLOUD_AUTH_HEADER || 'Authorization';
  const headerValue = headerName.toLowerCase() === 'authorization' ? `Bearer ${key}` : key;
  return {
    'Content-Type': 'application/json',
    [headerName]: headerValue
  };
}

function fallbackMessage(action) {
  switch (action) {
    case 'test': return 'Memory cloud API berhasil dites.';
    case 'debug': return 'Debug memory cloud selesai.';
    case 'save': return 'Memory berhasil dikirim ke cloud.';
    case 'list': return 'Memory cloud berhasil dibaca.';
    case 'delete': return 'Memory cloud berhasil diproses untuk delete.';
    case 'clear': return 'Memory cloud berhasil diproses untuk clear.';
    default: return 'Memory cloud selesai diproses.';
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

  const apiKey = process.env.MEMORY_CLOUD_API_KEY || process.env.MEMORY_CLOUD_KEY || process.env.APPWRITE_KEY;
  const apiUrl = process.env.MEMORY_CLOUD_API_URL || process.env.MEMORY_CLOUD_ENDPOINT || process.env.APPWRITE_ENDPOINT;
  const projectId = process.env.MEMORY_CLOUD_PROJECT_ID || process.env.APPWRITE_PROJECT_ID || '';

  if (!apiKey) {
    return res.status(500).json({
      error: 'Memory cloud API key not set',
      message: 'Set MEMORY_CLOUD_API_KEY in Vercel Environment Variables.'
    });
  }

  if (!apiUrl) {
    return res.status(500).json({
      error: 'Memory cloud API URL not set',
      message: 'Memory cloud key is configured, but MEMORY_CLOUD_API_URL / MEMORY_CLOUD_ENDPOINT is missing in Vercel.',
      status: 'key_configured_url_missing'
    });
  }

  try {
    const response = await fetch(apiUrl, {
      method: 'POST',
      headers: makeHeaders(apiKey),
      body: JSON.stringify({
        action,
        content,
        command,
        project_id: projectId,
        source: 'ai-chat-android',
        timestamp: new Date().toISOString()
      })
    });

    const text = await response.text();
    let payload;
    try { payload = JSON.parse(text); } catch (_) { payload = { raw: text }; }

    if (!response.ok) {
      return res.status(response.status).json({
        error: 'Memory cloud provider failed',
        status: response.status,
        details: payload
      });
    }

    const memories = Array.isArray(payload.memories) ? payload.memories
      : Array.isArray(payload.data) ? payload.data
      : Array.isArray(payload.documents) ? payload.documents
      : Array.isArray(payload.results) ? payload.results
      : [];

    return res.status(200).json({
      status: 'ok',
      action,
      message: payload.message || fallbackMessage(action),
      memories,
      provider_response: payload
    });
  } catch (error) {
    return res.status(500).json({
      error: 'Memory cloud request failed',
      message: error instanceof Error ? error.message : String(error)
    });
  }
}
