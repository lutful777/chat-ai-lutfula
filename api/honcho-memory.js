import { Honcho } from '@honcho-ai/sdk';

const MAX_ID_LENGTH = 120;
const MAX_MESSAGE_LENGTH = 20000;

function cleanId(value, fallback) {
  const id = String(value || '').trim().replace(/[^a-zA-Z0-9._:-]/g, '-').slice(0, MAX_ID_LENGTH);
  return id || fallback;
}

function cleanMessage(value) {
  return String(value || '').trim().slice(0, MAX_MESSAGE_LENGTH);
}

function client() {
  const apiKey = process.env.HONCHO_API_KEY;
  const workspaceId = process.env.HONCHO_WORKSPACE_ID;
  if (!apiKey) throw new Error('HONCHO_API_KEY is not configured');
  if (!workspaceId) throw new Error('HONCHO_WORKSPACE_ID is not configured');
  return new Honcho({ apiKey, workspaceId, environment: 'production' });
}

function textFromOpenAI(messages) {
  if (!Array.isArray(messages)) return '';
  return messages
    .map((message) => {
      if (typeof message?.content === 'string') return message.content;
      if (Array.isArray(message?.content)) {
        return message.content.map((part) => part?.text || '').filter(Boolean).join('\n');
      }
      return '';
    })
    .filter(Boolean)
    .join('\n');
}

export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  res.setHeader('Cache-Control', 'no-store');

  if (req.method === 'OPTIONS') return res.status(204).end();
  if (req.method !== 'POST') return res.status(405).json({ error: 'Method not allowed' });

  try {
    const action = String(req.body?.action || '').toLowerCase();
    const userId = cleanId(req.body?.userId, 'anonymous-user');
    const sessionId = cleanId(req.body?.sessionId, 'default-session');
    const honcho = client();
    const user = await honcho.peer(`user-${userId}`);
    const assistant = await honcho.peer('ai-chat-assistant');
    const session = await honcho.session(`user-${userId}-session-${sessionId}`);

    if (action === 'context') {
      const query = cleanMessage(req.body?.message);
      const [sessionContext, relevantMemory] = await Promise.all([
        session.context().catch(() => null),
        query
          ? user.chat(
              `What durable preferences, facts, and prior context about this user are relevant to this request? Request: ${query.slice(0, 2000)}`
            ).catch(() => null)
          : Promise.resolve(null)
      ]);

      const recent = sessionContext ? textFromOpenAI(sessionContext.toOpenAI(assistant)) : '';
      const learned = cleanMessage(relevantMemory?.content || relevantMemory?.message || relevantMemory || '');
      const context = [
        learned && `Long-term user memory:\n${learned}`,
        recent && `Relevant conversation context:\n${recent}`
      ].filter(Boolean).join('\n\n').slice(0, 12000);

      return res.status(200).json({ enabled: true, context });
    }

    if (action === 'save') {
      const userMessage = cleanMessage(req.body?.userMessage);
      const assistantMessage = cleanMessage(req.body?.assistantMessage);
      if (!userMessage || !assistantMessage) {
        return res.status(400).json({ error: 'userMessage and assistantMessage are required' });
      }
      await session.addMessages([
        user.message(userMessage),
        assistant.message(assistantMessage)
      ]);
      return res.status(200).json({ saved: true });
    }

    if (action === 'status') {
      return res.status(200).json({ enabled: true, workspaceId: process.env.HONCHO_WORKSPACE_ID });
    }

    return res.status(400).json({ error: 'Unsupported action' });
  } catch (error) {
    console.error('Honcho memory error', error);
    return res.status(503).json({
      enabled: false,
      error: error instanceof Error ? error.message : String(error)
    });
  }
}
