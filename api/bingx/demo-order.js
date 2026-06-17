const { bingxPrivateRequest } = require('../utils/bingx');

export default async function handler(req, res) {
  if (req.method !== 'POST') {
    return res.status(405).json({ error: 'Method Not Allowed' });
  }

  try {
    const { action, symbol, quantity = 0.001 } = req.body;
    let params = {
      symbol: symbol || 'BTC-USDT',
      type: 'MARKET',
      quantity: quantity
    };

    if (action === 'OPEN_LONG') {
      params.side = 'BUY';
      params.positionSide = 'LONG';
    } else if (action === 'OPEN_SHORT') {
      params.side = 'SELL';
      params.positionSide = 'SHORT';
    } else if (action === 'CLOSE') {
      // For closing, BingX V2 API uses positionSide and side opposite
      // It's safer to use POST /openApi/swap/v2/trade/closeAllPositions if just closing all
      const result = await bingxPrivateRequest('/openApi/swap/v2/trade/closeAllPositions', {}, 'POST', 'https://open-api-vst.bingx.com');
      return res.status(200).json(result.data);
    } else {
      return res.status(400).json({ error: 'Invalid action' });
    }

    const result = await bingxPrivateRequest('/openApi/swap/v2/trade/order', params, 'POST', 'https://open-api-vst.bingx.com');
    
    return res.status(200).json(result.data);
  } catch (error) {
    if (error.message === 'BINGX env belum disetting di Vercel') {
      return res.status(400).json({ error: error.message });
    }
    return res.status(500).json({ error: error.message || 'Internal Server Error' });
  }
}
