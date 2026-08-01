export default async function handler(req, res) {
  // CORS headers
  res.setHeader('Access-Control-Allow-Credentials', true);
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,OPTIONS');
  res.setHeader(
    'Access-Control-Allow-Headers',
    'X-CSRF-Token, X-Requested-With, Accept, Accept-Version, Content-Length, Content-MD5, Content-Type, Date, X-Api-Version, X-GitHub-Proxy-Secret'
  );

  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }

  try {
    const providedSecret = req.headers['x-github-proxy-secret'] || req.headers['X-GitHub-Proxy-Secret'];
    const expectedSecret = process.env.APP_GITHUB_PROXY_SECRET;

    if (!expectedSecret) {
      return res.status(500).json({ error: 'Server misconfiguration: Proxy secret not set' });
    }

    if (providedSecret !== expectedSecret) {
      return res.status(401).json({ error: 'Unauthorized: Invalid proxy secret' });
    }

    if (req.method !== 'GET') {
       return res.status(405).json({ error: 'Method Not Allowed. Only GET is allowed for read-only operations.' });
    }
    
    let { endpoint, useLutfulToken, q, ...otherParams } = req.query;
    
    // If endpoint is not provided, we can try to guess from q
    if (!endpoint && q) {
      const qLower = q.toLowerCase();
      const repo = 'lutful777/chat-ai-lutfula'; // default repo
      if (qLower.includes('commit')) {
        endpoint = `/repos/${repo}/commits`;
      } else if (qLower.includes('issue')) {
        endpoint = `/repos/${repo}/issues`;
      } else if (qLower.includes('pull') || qLower.includes('pr')) {
        endpoint = `/repos/${repo}/pulls`;
      } else if (qLower.includes('file') || qLower.includes('daftar')) {
        endpoint = `/repos/${repo}/contents`;
      } else {
        endpoint = `/repos/${repo}`;
      }
    }
    
    if (!endpoint) {
        return res.status(400).json({ error: 'Missing endpoint parameter' });
    }
    
    if (!endpoint.startsWith('/')) {
        endpoint = '/' + endpoint;
    }

    const token = process.env.GITHUB_TOKEN_Soprat123;
        
    if (!token) {
         return res.status(500).json({
           error: 'Server misconfiguration: GITHUB_TOKEN_Soprat123 not set'
         });
    }

    const queryParams = new URLSearchParams(otherParams).toString();
    const targetUrl = `https://api.github.com${endpoint}${queryParams ? '?' + queryParams : ''}`;

    const response = await fetch(targetUrl, {
        method: 'GET',
        headers: {
            'Authorization': `Bearer ${token}`,
            'Accept': 'application/vnd.github.v3+json',
            'User-Agent': 'Chat-AI-Lutfula-App'
        }
    });

    let data;
    const contentType = response.headers.get('content-type');
    if (contentType && contentType.includes('application/json')) {
        data = await response.json();
    } else {
        data = { content: await response.text() };
    }
    
    return res.status(response.status).json({ data, endpoint });

  } catch (error) {
    console.error('GitHub API error:', error);
    return res.status(500).json({ error: 'Failed to process request', details: error.message });
  }
}
