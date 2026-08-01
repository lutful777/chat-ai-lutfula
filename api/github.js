const OWNER = 'lutful777';
const REPO = 'chat-ai-lutfula';
const DEFAULT_REF = 'main';
const GITHUB_API = 'https://api.github.com';

function encodeContentPath(path) {
  return String(path || '')
    .split('/')
    .filter(Boolean)
    .map(encodeURIComponent)
    .join('/');
}

function normalizePath(path) {
  const value = String(path || '').trim().replace(/^\/+/, '');
  if (!value || value.includes('..') || value.includes('\\')) return '';
  return value;
}

function pathFromQuery(query) {
  const text = String(query || '').trim();

  const labelled = text.match(
    /(?:file|berkas)\s+[`"']?([A-Za-z0-9_.-]+(?:\/[A-Za-z0-9_.-]+)*\.[A-Za-z0-9_-]+)[`"']?/i
  );
  if (labelled) return normalizePath(labelled[1]);

  const pathLike = text.match(
    /\b([A-Za-z0-9_.-]+(?:\/[A-Za-z0-9_.-]+)+\.[A-Za-z0-9_-]+)\b/
  );
  return normalizePath(pathLike?.[1] || '');
}

function selectReadEndpoint(query, requestedPath, ref) {
  const q = String(query || '').toLowerCase();
  const filePath = normalizePath(requestedPath) || pathFromQuery(query);

  if (filePath) {
    return {
      endpoint: `/repos/${OWNER}/${REPO}/contents/${encodeContentPath(filePath)}?ref=${encodeURIComponent(ref)}`,
      kind: 'file',
      path: filePath
    };
  }

  if (q.includes('commit')) {
    return { endpoint: `/repos/${OWNER}/${REPO}/commits`, kind: 'commits' };
  }
  if (q.includes('branch') || q.includes('cabang')) {
    return { endpoint: `/repos/${OWNER}/${REPO}/branches`, kind: 'branches' };
  }
  if (q.includes('issue')) {
    return { endpoint: `/repos/${OWNER}/${REPO}/issues`, kind: 'issues' };
  }
  if (q.includes('pull') || /\bpr\b/.test(q)) {
    return { endpoint: `/repos/${OWNER}/${REPO}/pulls`, kind: 'pulls' };
  }
  if (
    q.includes('file') ||
    q.includes('berkas') ||
    q.includes('folder') ||
    q.includes('daftar') ||
    q.includes('struktur')
  ) {
    return {
      endpoint: `/repos/${OWNER}/${REPO}/contents?ref=${encodeURIComponent(ref)}`,
      kind: 'contents'
    };
  }

  return { endpoint: `/repos/${OWNER}/${REPO}`, kind: 'repository' };
}

function decodeGitHubFile(data) {
  if (!data || Array.isArray(data) || data.type !== 'file') return data;
  if (data.encoding !== 'base64' || typeof data.content !== 'string') return data;

  const decodedContent = Buffer.from(
    data.content.replace(/\s/g, ''),
    'base64'
  ).toString('utf8');

  return {
    name: data.name,
    path: data.path,
    sha: data.sha,
    size: data.size,
    type: data.type,
    encoding: 'utf-8',
    content: decodedContent,
    html_url: data.html_url
  };
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS');
  res.setHeader(
    'Access-Control-Allow-Headers',
    'Accept, Content-Type, X-GitHub-Proxy-Secret'
  );
  res.setHeader('Cache-Control', 'no-store');

  if (req.method === 'OPTIONS') {
    return res.status(204).end();
  }

  if (req.method !== 'GET') {
    return res.status(405).json({
      error: 'Method Not Allowed. This endpoint is read-only.'
    });
  }

  try {
    const providedSecret = req.headers['x-github-proxy-secret'];
    const expectedSecret = process.env.APP_GITHUB_PROXY_SECRET;

    if (!expectedSecret) {
      return res.status(500).json({
        error: 'Server misconfiguration: APP_GITHUB_PROXY_SECRET not set'
      });
    }

    if (providedSecret !== expectedSecret) {
      return res.status(401).json({
        error: 'Unauthorized: Invalid proxy secret'
      });
    }

    const token = process.env.GITHUB_TOKEN_Soprat123;
    if (!token) {
      return res.status(500).json({
        error: 'Server misconfiguration: GITHUB_TOKEN_Soprat123 not set'
      });
    }

    const query = typeof req.query.q === 'string' ? req.query.q : '';
    const requestedPath =
      typeof req.query.path === 'string' ? req.query.path : '';
    const requestedRef =
      typeof req.query.ref === 'string' ? req.query.ref.trim() : '';
    const ref = requestedRef || DEFAULT_REF;

    const selection = selectReadEndpoint(query, requestedPath, ref);
    const targetUrl = GITHUB_API + selection.endpoint;

    const response = await fetch(targetUrl, {
      method: 'GET',
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': 'Chat-AI-Lutfula-App'
      }
    });

    const responseText = await response.text();
    let data;
    try {
      data = JSON.parse(responseText);
    } catch (_) {
      data = { message: responseText };
    }

    if (!response.ok) {
      return res.status(response.status).json({
        error: 'GitHub upstream request failed',
        source: 'github',
        tokenEnvironment: 'GITHUB_TOKEN_Soprat123',
        status: response.status,
        message: data?.message || 'Unknown GitHub API error'
      });
    }

    if (selection.kind === 'file') {
      data = decodeGitHubFile(data);
    }

    return res.status(200).json({
      repository: `${OWNER}/${REPO}`,
      readOnly: true,
      tokenEnvironment: 'GITHUB_TOKEN_Soprat123',
      kind: selection.kind,
      path: selection.path || null,
      ref,
      data
    });
  } catch (error) {
    console.error('GitHub read-only proxy error:', error);
    return res.status(500).json({
      error: 'Failed to read GitHub',
      details: error instanceof Error ? error.message : String(error)
    });
  }
}
