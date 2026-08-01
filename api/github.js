import crypto from 'crypto';

const DEFAULT_OWNER = 'lutful777';
const DEFAULT_REPO = 'chat-ai-lutfula';
const MAX_QUERY_LENGTH = 1000;
const MAX_FILE_CHARACTERS = 14000;
const GITHUB_API = 'https://api.github.com';

function secureEqual(left, right) {
  const a = Buffer.from(String(left || ''));
  const b = Buffer.from(String(right || ''));
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function selectedAccount(input) {
  return String(input || '').trim().toLowerCase() === 'lutful' ? 'lutful' : 'soprat123';
}

function tokenFor(account) {
  if (account === 'lutful') return process.env.GITHUB_TOKEN_LUTFUL || '';
  return process.env.GITHUB_TOKEN_Soprat123 || process.env.GITHUB_TOKEN_SOPRAT123 || '';
}

function parseTarget(query) {
  const githubUrl = query.match(
    /https?:\/\/github\.com\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)(?:\/(blob|tree)\/([^/\s?#]+)\/([^\s?#]+))?(?:\/(issues|pull)\/(\d+))?/i
  );
  if (githubUrl) {
    return {
      owner: githubUrl[1],
      repo: githubUrl[2].replace(/\.git$/i, ''),
      ref: githubUrl[4] || '',
      path: githubUrl[5] ? decodeURIComponent(githubUrl[5]) : '',
      itemType: githubUrl[6] || '',
      itemNumber: githubUrl[7] ? Number(githubUrl[7]) : null
    };
  }
  const repoRef = query.match(/\b([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)\b/);
  return {
    owner: repoRef?.[1] || DEFAULT_OWNER,
    repo: (repoRef?.[2] || DEFAULT_REPO).replace(/\.git$/i, ''),
    ref: '',
    path: '',
    itemType: '',
    itemNumber: null
  };
}

async function githubFetch(path, token) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10000);
  try {
    const response = await fetch(GITHUB_API + path, {
      headers: {
        Accept: 'application/vnd.github+json',
        Authorization: `Bearer ${token}`,
        'User-Agent': 'chat-ai-lutfula',
        'X-GitHub-Api-Version': '2022-11-28'
      },
      signal: controller.signal
    });
    const text = await response.text();
    let data;
    try { data = JSON.parse(text); } catch (_) { data = { message: text }; }
    if (!response.ok) {
      const error = new Error(data?.message || `GitHub API HTTP ${response.status}`);
      error.status = response.status;
      throw error;
    }
    return data;
  } finally {
    clearTimeout(timer);
  }
}

function encodeContentPath(path) {
  return String(path || '').split('/').filter(Boolean).map(encodeURIComponent).join('/');
}

function decodeFile(data) {
  if (!data || Array.isArray(data) || data.type !== 'file') return '';
  if (data.encoding !== 'base64' || !data.content) return '';
  const decoded = Buffer.from(data.content.replace(/\s/g, ''), 'base64').toString('utf8');
  if (decoded.includes('\u0000')) return '[File biner tidak ditampilkan]';
  return decoded.length > MAX_FILE_CHARACTERS
    ? decoded.slice(0, MAX_FILE_CHARACTERS) + '\n[Isi file dipotong]'
    : decoded;
}

function compactRepo(repo) {
  return {
    fullName: repo.full_name,
    private: repo.private,
    defaultBranch: repo.default_branch,
    description: repo.description,
    language: repo.language,
    updatedAt: repo.updated_at,
    openIssues: repo.open_issues_count,
    url: repo.html_url
  };
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-GitHub-Proxy-Secret');
  res.setHeader('Cache-Control', 'no-store');

  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });

  const expectedSecret = process.env.APP_GITHUB_PROXY_SECRET || '';
  if (!expectedSecret) {
    return res.status(503).json({ error: 'APP_GITHUB_PROXY_SECRET belum dikonfigurasi di Vercel.' });
  }
  if (!secureEqual(req.headers['x-github-proxy-secret'], expectedSecret)) {
    return res.status(401).json({ error: 'Akses endpoint GitHub ditolak.' });
  }

  const query = String(req.body?.query || '').trim().slice(0, MAX_QUERY_LENGTH);
  if (!query) return res.status(400).json({ error: 'Pertanyaan GitHub kosong.' });

  const account = selectedAccount(req.body?.account);
  const token = tokenFor(account);
  if (!token) {
    const key = account === 'lutful' ? 'GITHUB_TOKEN_LUTFUL' : 'GITHUB_TOKEN_Soprat123';
    return res.status(503).json({ error: `${key} belum dikonfigurasi di Vercel.` });
  }

  const target = parseTarget(query);
  const basePath = `/repos/${encodeURIComponent(target.owner)}/${encodeURIComponent(target.repo)}`;

  try {
    const repoData = await githubFetch(basePath, token);
    const result = {
      account,
      authenticatedWith: account === 'lutful' ? 'GITHUB_TOKEN_LUTFUL' : 'GITHUB_TOKEN_Soprat123',
      repository: compactRepo(repoData)
    };

    if (target.itemType && target.itemNumber) {
      const apiType = target.itemType === 'pull' ? 'pulls' : 'issues';
      const item = await githubFetch(`${basePath}/${apiType}/${target.itemNumber}`, token);
      result.item = {
        type: target.itemType,
        number: item.number,
        title: item.title,
        state: item.state,
        body: String(item.body || '').slice(0, 8000),
        user: item.user?.login,
        createdAt: item.created_at,
        updatedAt: item.updated_at,
        url: item.html_url
      };
    } else if (target.path) {
      const refQuery = target.ref ? `?ref=${encodeURIComponent(target.ref)}` : '';
      const file = await githubFetch(
        `${basePath}/contents/${encodeContentPath(target.path)}${refQuery}`,
        token
      );
      result.file = {
        path: file.path,
        name: file.name,
        size: file.size,
        sha: file.sha,
        url: file.html_url,
        content: decodeFile(file)
      };
    } else {
      const [contents, commits] = await Promise.all([
        githubFetch(`${basePath}/contents`, token),
        githubFetch(`${basePath}/commits?per_page=8`, token)
      ]);
      result.rootFiles = Array.isArray(contents)
        ? contents.slice(0, 100).map((entry) => ({
            name: entry.name,
            path: entry.path,
            type: entry.type,
            size: entry.size,
            url: entry.html_url
          }))
        : [];
      result.recentCommits = Array.isArray(commits)
        ? commits.map((entry) => ({
            sha: entry.sha?.slice(0, 7),
            message: entry.commit?.message,
            author: entry.author?.login || entry.commit?.author?.name,
            date: entry.commit?.author?.date,
            url: entry.html_url
          }))
        : [];
    }

    const context = [
      'GITHUB_DATA (hasil endpoint GitHub read-only; gunakan sebagai sumber, jangan mengarang akses lain):',
      JSON.stringify(result, null, 2)
    ].join('\n');

    return res.status(200).json({
      ok: true,
      account,
      repository: `${target.owner}/${target.repo}`,
      context
    });
  } catch (error) {
    const status = error?.status === 401 ? 502 : (error?.status === 403 ? 403 : (error?.status === 404 ? 404 : 500));
    return res.status(status).json({
      error: 'GitHub API gagal.',
      message: error instanceof Error ? error.message : String(error),
      account,
      repository: `${target.owner}/${target.repo}`
    });
  }
}
