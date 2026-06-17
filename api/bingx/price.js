const { bingxPublicRequest } = require('../utils/bingx');

export default async function handler(req, res) {
  try {
    const symbol = req.query.symbol || 'BTC-USDT';
    const result = await bingxPublicRequest('/openApi/swap/v2/quote/ticker', { symbol });
    
    return res.status(200).json(result.data);
  } catch (error) {
    if (error.message === 'BINGX env belum disetting di Vercel') {
      return res.status(400).json({ error: error.message });
    }
    return res.status(500).json({ error: error.message || 'Internal Server Error' });
  }
}
