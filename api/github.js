const DEFAULT_REPOSITORY = 'lutful777/chat-ai-lutfula';
const ALLOWED_REPOSITORIES = Object.freeze({
  'lutful777/chat-ai-lutfula': {
    owner: 'lutful777',
    repo: 'chat-ai-lutfula',
    defaultRef: 'main'
  },
  'soprat123/bikin-foto': {
    owner: 'soprat123',
    repo: 'bikin-foto',
    defaultRef: 'main'
  },
  'soprat123/qris-dinamis-telegram': {
    owner: 'soprat123',
    repo: 'qris-dinamis-telegram',
    defaultRef: 'main'
  }
});
const GITHUB_API = 'https://api.github.com';
const MAX_FILE_CHARACTERS = 30000;
const MAX_TREE_ITEMS = 2000;
const MAX_ANALYSIS_FILES = 8;
const MAX_ANALYSIS_FILE_CHARACTERS = 6000;
const MAX_ANALYSIS_TOTAL_CHARACTERS = 30000;

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
  let text = String(query || '').trim();

  for (const fullName of Object.keys(ALLOWED_REPOSITORIES)) {
    text = text.replace(new RegExp(fullName.replace('/', '\\/'), 'ig'), ' ');
  }

  const labelled = text.match(
    /(?:file|berkas|folder|direktori)\s+[`"']?([A-Za-z0-9_.-]+(?:\/[A-Za-z0-9_.-]+)*\.?[A-Za-z0-9_-]*)[`"']?/i
  );
  if (labelled) return normalizePath(labelled[1]);

  const pathLike = text.match(
    /\b([A-Za-z0-9_.-]+(?:\/[A-Za-z0-9_.-]+)+(?:\.[A-Za-z0-9_-]+)?)\b/
  );
  return normalizePath(pathLike?.[1] || '');
}

function selectRepository(query) {
  const q = String(query || '').toLowerCase();
  const explicit = q.match(/\b([a-z0-9_.-]+\/[a-z0-9_.-]+)\b/)?.[1];

  if (explicit && ALLOWED_REPOSITORIES[explicit]) {
    return { fullName: explicit, ...ALLOWED_REPOSITORIES[explicit] };
  }

  for (const [fullName, repository] of Object.entries(ALLOWED_REPOSITORIES)) {
    if (q.includes(repository.repo.toLowerCase())) {
      return { fullName, ...repository };
    }
  }

  return {
    fullName: DEFAULT_REPOSITORY,
    ...ALLOWED_REPOSITORIES[DEFAULT_REPOSITORY]
  };
}

function selectReadEndpoint(query, requestedPath, ref, repository) {
  const q = String(query || '').toLowerCase();
  const filePath = normalizePath(requestedPath) || pathFromQuery(query);
  const owner = repository.owner;
  const repo = repository.repo;

  if (
    q.includes('username') ||
    q.includes('akun token') ||
    q.includes('siapa pemilik token') ||
    q.includes('cek koneksi') ||
    q.includes('cek apakah konek')
  ) {
    return { endpoint: '/user', kind: 'authenticated-user' };
  }

  if (filePath) {
    return {
      endpoint: `/repos/${owner}/${repo}/contents/${encodeContentPath(filePath)}?ref=${encodeURIComponent(ref)}`,
      kind: 'content',
      path: filePath
    };
  }

  if (
    q.includes('struktur repo') ||
    q.includes('seluruh file') ||
    q.includes('semua file') ||
    q.includes('daftar file') ||
    q.includes('tree repo')
  ) {
    return {
      endpoint: `/repos/${owner}/${repo}/git/trees/${encodeURIComponent(ref)}?recursive=1`,
      kind: 'tree'
    };
  }

  if (
    q.includes('baca repo') ||
    q.includes('periksa repo') ||
    q.includes('cek repo') ||
    q.includes('analisis repo') ||
    q.includes('cari error') ||
    q.includes('temukan error') ||
    q.includes('script yang error') ||
    q.includes('kode yang error') ||
    q.includes('perbaiki script') ||
    q.includes('periksa kode')
  ) {
    return { kind: 'automatic-analysis' };
  }

  if (q.includes('commit')) {
    return { endpoint: `/repos/${owner}/${repo}/commits`, kind: 'commits' };
  }
  if (q.includes('branch') || q.includes('cabang')) {
    return { endpoint: `/repos/${owner}/${repo}/branches`, kind: 'branches' };
  }
  if (q.includes('issue')) {
    return { endpoint: `/repos/${owner}/${repo}/issues`, kind: 'issues' };
  }
  if (q.includes('pull') || /\bpr\b/.test(q)) {
    return { endpoint: `/repos/${owner}/${repo}/pulls`, kind: 'pulls' };
  }
  if (
    q.includes('file') ||
    q.includes('berkas') ||
    q.includes('folder') ||
    q.includes('daftar') ||
    q.includes('struktur')
  ) {
    return {
      endpoint: `/repos/${owner}/${repo}/contents?ref=${encodeURIComponent(ref)}`,
      kind: 'contents'
    };
  }

  return { endpoint: `/repos/${owner}/${repo}`, kind: 'repository' };
}

function normalizeGitHubContent(data) {
  if (Array.isArray(data)) {
    return data.map(item => ({
      name: item.name,
      path: item.path,
      sha: item.sha,
      size: item.size,
      type: item.type,
      html_url: item.html_url
    }));
  }

  if (!data || data.type !== 'file') return data;
  if (data.encoding !== 'base64' || typeof data.content !== 'string') return data;

  let decodedContent = Buffer.from(
    data.content.replace(/\s/g, ''),
    'base64'
  ).toString('utf8');

  let truncated = false;
  if (decodedContent.length > MAX_FILE_CHARACTERS) {
    decodedContent =
      decodedContent.slice(0, MAX_FILE_CHARACTERS) +
      '\n\n[Isi file dipotong karena terlalu panjang]';
    truncated = true;
  }

  return {
    name: data.name,
    path: data.path,
    sha: data.sha,
    size: data.size,
    type: data.type,
    encoding: 'utf-8',
    truncated,
    content: decodedContent,
    html_url: data.html_url
  };
}

function normalizeTree(data) {
  if (!data || !Array.isArray(data.tree)) return data;

  const ignored = /(^|\/)(build|\.gradle|\.git|\.idea|node_modules)(\/|$)/;
  const binary = /\.(png|jpe?g|webp|gif|apk|jar|aar|zip|so|jks|keystore|ttf|ico)$/i;

  const tree = data.tree
    .filter(item => item?.path && !ignored.test(item.path))
    .filter(item => item.type !== 'blob' || !binary.test(item.path))
    .slice(0, MAX_TREE_ITEMS)
    .map(item => ({
      path: item.path,
      type: item.type,
      size: item.size ?? null,
      sha: item.sha
    }));

  return {
    sha: data.sha,
    truncated: Boolean(data.truncated) || data.tree.length > MAX_TREE_ITEMS,
    returned: tree.length,
    tree
  };
}

async function githubRead(endpoint, token) {
  const response = await fetch(GITHUB_API + endpoint, {
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
    const error = new Error(data?.message || 'GitHub API request failed');
    error.status = response.status;
    throw error;
  }

  return data;
}

function analysisCandidates(tree, query) {
  const ignored = /(^|\/)(build|\.gradle|\.git|\.idea|node_modules|dist|coverage)(\/|$)/;
  const textFile = /(^|\/)(readme[^/]*|dockerfile|makefile)$|\.(kt|kts|java|xml|gradle|properties|toml|js|mjs|cjs|ts|tsx|jsx|json|ya?ml|py|sh|md|html|css|sql|txt)$/i;
  const queryWords = String(query || '')
    .toLowerCase()
    .split(/[^a-z0-9_.-]+/)
    .filter(word => word.length > 2);

  return tree
    .filter(item => item?.type === 'blob' && item.path)
    .filter(item => !ignored.test(item.path))
    .filter(item => textFile.test(item.path))
    .filter(item => !item.size || item.size <= 100000)
    .map(item => {
      const path = item.path.toLowerCase();
      let score = 0;

      for (const word of queryWords) {
        if (path.includes(word)) score += 8;
      }

      if (/(^|\/)(readme|package\.json|settings\.gradle|settings\.gradle\.kts)$/i.test(item.path)) score += 10;
      if (/(build\.gradle|build\.gradle\.kts|androidmanifest\.xml|vercel\.json|wrangler\.toml)$/i.test(item.path)) score += 9;
      if (/(^|\/)(src|app|api|lib|server|functions)(\/|$)/i.test(item.path)) score += 4;
      if (/test|spec/i.test(item.path)) score += 2;

      return { ...item, score };
    })
    .sort((left, right) =>
      right.score - left.score ||
      (left.size || 0) - (right.size || 0) ||
      left.path.localeCompare(right.path)
    );
}

async function buildAutomaticAnalysis(repository, ref, query, token) {
  const owner = repository.owner;
  const repo = repository.repo;
  const treeData = await githubRead(
    `/repos/${owner}/${repo}/git/trees/${encodeURIComponent(ref)}?recursive=1`,
    token
  );

  const fullTree = Array.isArray(treeData.tree) ? treeData.tree : [];
  const candidates = analysisCandidates(fullTree, query);
  const selected = candidates.slice(0, MAX_ANALYSIS_FILES);

  const loaded = await Promise.all(selected.map(async item => {
    try {
      const data = await githubRead(
        `/repos/${owner}/${repo}/contents/${encodeContentPath(item.path)}?ref=${encodeURIComponent(ref)}`,
        token
      );

      if (!data || data.type !== 'file' || data.encoding !== 'base64' || !data.content) {
        return null;
      }

      const decoded = Buffer.from(
        data.content.replace(/\s/g, ''),
        'base64'
      ).toString('utf8');

      return {
        path: item.path,
        sha: item.sha,
        size: item.size ?? decoded.length,
        content: decoded.slice(0, MAX_ANALYSIS_FILE_CHARACTERS),
        truncated: decoded.length > MAX_ANALYSIS_FILE_CHARACTERS
      };
    } catch (error) {
      return {
        path: item.path,
        error: error instanceof Error ? error.message : String(error)
      };
    }
  }));

  const files = [];
  let totalCharacters = 0;
  for (const file of loaded.filter(Boolean)) {
    const length = file.content?.length || 0;
    if (totalCharacters + length > MAX_ANALYSIS_TOTAL_CHARACTERS) break;
    files.push(file);
    totalCharacters += length;
  }

  return {
    mode: 'automatic-multi-file-read',
    repository: repository.fullName,
    ref,
    readOnly: true,
    totalRepositoryEntries: fullTree.length,
    candidateTextFiles: candidates.length,
    selectedFiles: files.map(file => file.path),
    files,
    instructions:
      'Analisis file yang tersedia untuk menjawab pertanyaan. Sebutkan path file dan alasan konkret. Jangan mengklaim membaca file yang tidak tersedia.'
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
    const repository = selectRepository(query);
    const ref = requestedRef || repository.defaultRef;

    const selection = selectReadEndpoint(
      query,
      requestedPath,
      ref,
      repository
    );

    if (selection.kind === 'automatic-analysis') {
      try {
        const analysis = await buildAutomaticAnalysis(
          repository,
          ref,
          query,
          token
        );

        return res.status(200).json({
          repository: repository.fullName,
          allowedRepositories: Object.keys(ALLOWED_REPOSITORIES),
          readOnly: true,
          tokenEnvironment: 'GITHUB_TOKEN_Soprat123',
          kind: selection.kind,
          ref,
          data: analysis
        });
      } catch (error) {
        return res.status(error?.status || 500).json({
          error: 'Automatic GitHub analysis failed',
          source: 'github',
          status: error?.status || 500,
          message: error instanceof Error ? error.message : String(error)
        });
      }
    }

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

    if (selection.kind === 'content') {
      data = normalizeGitHubContent(data);
    } else if (selection.kind === 'tree') {
      data = normalizeTree(data);
    }

    return res.status(200).json({
      repository: repository.fullName,
      allowedRepositories: Object.keys(ALLOWED_REPOSITORIES),
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
