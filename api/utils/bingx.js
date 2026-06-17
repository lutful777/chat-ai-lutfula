const crypto = require('crypto');

function getBingXEnv() {
  const apiKey = process.env.BINGX_API_KEY;
  const secretKey = process.env.BINGX_SECRET_KEY;
  const baseUrl = process.env.BINGX_BASE_URL || 'https://open-api.bingx.com';

  if (!apiKey || !secretKey) {
    throw new Error('BINGX env belum disetting di Vercel');
  }

  return { apiKey, secretKey, baseUrl };
}

function buildQueryString(params) {
  if (!params) return '';
  const sortedKeys = Object.keys(params).sort();
  return sortedKeys.map(k => `${k}=${encodeURIComponent(params[k])}`).join('&');
}

function signBingX(queryString, secretKey) {
  return crypto.createHmac('sha256', secretKey).update(queryString).digest('hex');
}

async function bingxPublicRequest(path, params = {}) {
  // Public requests may not need strict API keys but we grab baseUrl
  const baseUrl = process.env.BINGX_BASE_URL || 'https://open-api.bingx.com';
  
  const query = buildQueryString(params);
  const url = `${baseUrl}${path}${query ? '?' + query : ''}`;
  
  const res = await fetch(url);
  const data = await res.json();
  return { status: res.status, data };
}

async function bingxPrivateRequest(path, params = {}, method = 'GET', customBaseUrl = null) {
  const envInfo = getBingXEnv();
  const apiKey = envInfo.apiKey;
  const secretKey = envInfo.secretKey;
  const baseUrl = customBaseUrl || envInfo.baseUrl;
  
  params.timestamp = Date.now();
  params.recvWindow = params.recvWindow || 5000;
  
  const queryString = buildQueryString(params);
  const signature = signBingX(queryString, secretKey);
  const finalQueryString = `${queryString}&signature=${signature}`;
  
  const url = method === 'GET' ? `${baseUrl}${path}?${finalQueryString}` : `${baseUrl}${path}`;
  
  const options = {
    method: method,
    headers: {
      'X-BX-APIKEY': apiKey,
    }
  };
  
  if (method === 'POST') {
    options.headers['Content-Type'] = 'application/x-www-form-urlencoded';
    options.body = finalQueryString;
  }
  
  const res = await fetch(url, options);
  
  const data = await res.json();
  return { status: res.status, data };
}

module.exports = {
  getBingXEnv,
  buildQueryString,
  signBingX,
  bingxPublicRequest,
  bingxPrivateRequest
};
