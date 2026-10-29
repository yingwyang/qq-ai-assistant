/**
 * NapCat 远程 agent（单文件、无依赖，Node 18+）。
 * 职责：向 qq-ai-assistant 注册/心跳；暴露 /qrcode、/status 给后端代理。
 * 环境变量见 README.md。
 */
import http from 'node:http';
import fs from 'node:fs';

const env = process.env;
const BACKEND_URL = required('BACKEND_URL').replace(/\/$/, '');
const BOT_ID = required('BOT_ID');
const AGENT_TOKEN = required('AGENT_TOKEN');
const AGENT_URL = required('AGENT_URL').replace(/\/$/, '');
const ONEBOT_URL = required('ONEBOT_URL').replace(/\/$/, '');
const NAPCAT_TOKEN = env.NAPCAT_TOKEN || '';
const WEBUI_URL = env.WEBUI_URL || '';
const AGENT_PORT = Number(env.AGENT_PORT || 3100);
const QR_PATH = env.QR_PATH || '/app/napcat/cache/qrcode.png';

function required(name) {
  const value = env[name];
  if (!value) {
    console.error('[agent] 缺少环境变量 ' + name);
    process.exit(1);
  }
  return value;
}

function json(res, status, body) {
  const data = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(data);
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    return json(res, 200, { status: 'ok' });
  }
  if (req.headers['x-agent-token'] !== AGENT_TOKEN) {
    return json(res, 401, { error: 'invalid agent token' });
  }
  if (req.method === 'GET' && req.url === '/qrcode') {
    try {
      const png = fs.readFileSync(QR_PATH);
      res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'no-store' });
      return res.end(png);
    } catch {
      return json(res, 404, { error: 'qrcode not ready', path: QR_PATH });
    }
  }
  if (req.method === 'GET' && req.url === '/status') {
    try {
      const response = await fetch(ONEBOT_URL + '/get_login_info', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(NAPCAT_TOKEN ? { Authorization: 'Bearer ' + NAPCAT_TOKEN } : {}),
        },
        body: '{}',
      });
      const text = await response.text();
      let data = {};
      try { data = JSON.parse(text); } catch { /* ignore */ }
      const info = data && data.data ? data.data : null;
      const qq = info && Number(info.user_id) > 0 ? String(info.user_id) : null;
      return json(res, 200, {
        loggedIn: !!qq,
        qq,
        nickname: info ? info.nickname || '' : '',
      });
    } catch (error) {
      return json(res, 502, { loggedIn: false, error: String(error && error.message || error) });
    }
  }
  json(res, 404, { error: 'not found' });
});

server.listen(AGENT_PORT, '0.0.0.0', () => {
  console.log('[agent] listening on :' + AGENT_PORT + ' bot=' + BOT_ID);
});

async function report(path, body) {
  try {
    const response = await fetch(BACKEND_URL + '/api/bots/' + BOT_ID + path, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Agent-Token': AGENT_TOKEN,
      },
      body: JSON.stringify(body || {}),
    });
    if (!response.ok) {
      console.error('[agent] ' + path + ' 失败: HTTP ' + response.status);
      return false;
    }
    return true;
  } catch (error) {
    console.error('[agent] ' + path + ' 失败: ' + (error && error.message || error));
    return false;
  }
}

async function registerLoop() {
  const payload = { onebotUrl: ONEBOT_URL, webuiUrl: WEBUI_URL, agentUrl: AGENT_URL };
  while (!(await report('/agent/register', payload))) {
    await new Promise((r) => setTimeout(r, 10000));
  }
  console.log('[agent] registered to ' + BACKEND_URL);
}

registerLoop();
setInterval(() => report('/heartbeat', {}), 20000);
