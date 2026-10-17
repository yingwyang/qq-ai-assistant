/**
 * 一站式前端验收：自动签 JWT → 预检服务 → 串行跑全部浏览器脚本 → 汇总成一张表。
 *
 * 解决的问题：这些脚本此前只能一个个手工执行 `node scripts/xxx.mjs <手工造的 JWT>`，
 * 而 JWT 的 tv 必须等于库里的 token_version、有效期只有 6 小时，隔天就得重签 ——
 * 结果是「验证写了但没人跑」。现在一条 `npm run e2e` 全跑完，有失败就非零退出。
 *
 * 用法：
 *   npm run e2e                      全部用例
 *   npm run e2e -- --only=check-payment-flow.mjs
 *   npm run e2e -- --base=http://127.0.0.1:5173 --api=http://127.0.0.1:8081
 *   npm run e2e -- --list            只列出用例
 *   npm run e2e -- --no-preflight    跳过服务预检
 *
 * 注意：截图类脚本（shot-*.mjs）与三个非浏览器守卫（guard/budget）不在此列 ——
 * 前者只出图不断言，后者是 npm run guard / npm run budget 的事。
 */
import path from 'node:path';
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mintTokens } from './lib/mint-jwt.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FRONTEND = path.resolve(HERE, '..');

/** 用例清单：needs=admin 只需 ADMIN 令牌；admin+user 额外需要普通用户令牌 */
const CASES = [
  { file: 'check-security-hardening.mjs', needs: 'admin+user', base: 'api', label: '安全收口：匿名 401 / 媒体归属 / 越权 403' },
  { file: 'check-admin-shell.mjs', needs: 'admin', label: '管理后台外壳：14 页可开 / 深链接 / 按需加载' },
  { file: 'check-admin-dark-theme.mjs', needs: 'admin', label: '管理后台暗色主题对比度扫描' },
  { file: 'e2e-admin-export.mjs', needs: 'admin', label: '四类导出（含资金流水 CSV/JSON）' },
  { file: 'check-admin-last-admin.mjs', needs: 'admin', label: '最后一位可用管理员守卫' },
  { file: 'check-payment-flow.mjs', needs: 'admin', label: '支付链路按钮化（purchase 被拦截，不产生订单）' },
  { file: 'check-dashboard-charts.mjs', needs: 'admin', label: '图表懒加载后仍渲染 canvas' },
  { file: 'check-dialog-a11y.mjs', needs: 'admin', label: '共享弹窗无障碍（aria/Esc/焦点）' },
  { file: 'check-error-state.mjs', needs: 'admin', label: '列表页错误态与重试' },
  { file: 'check-overlay-containment.mjs', needs: 'admin', label: '浮层不溢出容器' },
  { file: 'check-image-preview-zoom.mjs', needs: 'admin', label: '图片浏览缩放（放大/缩小/复位/滚轮/拖拽/禁用态）' },
  { file: 'e2e-forward-to-group.mjs', needs: 'admin', label: 'AstrBot 转发到群（send 被拦截，不真发群消息）' },
];

const stripSlash = (u) => (u.endsWith('/') ? u.slice(0, -1) : u);
function argOf(name, fallback) {
  const eq = process.argv.find((a) => a.startsWith('--' + name + '='));
  if (eq) return eq.slice(name.length + 3);
  const i = process.argv.indexOf('--' + name);
  if (i >= 0 && process.argv[i + 1] && !process.argv[i + 1].startsWith('--')) return process.argv[i + 1];
  return fallback;
}

const BASE = stripSlash(argOf('base', 'http://127.0.0.1:5173'));
const API = stripSlash(argOf('api', 'http://127.0.0.1:8081'));
const ONLY = argOf('only', '');
const NO_PREFLIGHT = process.argv.includes('--no-preflight');

if (process.argv.includes('--list')) {
  console.log('可用用例：');
  CASES.forEach((c, i) => console.log('  ' + String(i + 1).padStart(2) + '. ' + c.file.padEnd(34) + c.label));
  process.exit(0);
}

async function probe(url, timeoutMs) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs || 3000);
  try {
    const res = await fetch(url, { signal: ctl.signal });
    return res.status;
  } catch {
    return 0;
  } finally {
    clearTimeout(timer);
  }
}

/** 没设 CHROME_PATH 时自动找一个本机浏览器（playwright-core 不带浏览器） */
function ensureChromePath() {
  if (process.env.CHROME_PATH && fs.existsSync(process.env.CHROME_PATH)) return process.env.CHROME_PATH;
  const cands = [
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  ];
  for (const c of cands) {
    if (fs.existsSync(c)) { process.env.CHROME_PATH = c; return c; }
  }
  return null;
}

function tail(text, n) {
  const lines = String(text || '').split('\n').filter((x) => x.trim());
  return lines.slice(-n).join('\n');
}

function runCase(c, tokens) {
  const file = path.join(HERE, c.file);
  const args = [file];
  if (c.needs === 'admin+user') args.push(tokens.admin.token, API, tokens.user.token);
  else args.push(tokens.admin.token, BASE);
  const started = Date.now();
  const res = spawnSync(process.execPath, args, {
    cwd: FRONTEND,
    encoding: 'utf8',
    timeout: 300000,
    env: process.env,
  });
  return {
    ok: res.status === 0,
    status: res.status,
    signal: res.signal,
    ms: Date.now() - started,
    out: String(res.stdout || '') + String(res.stderr || ''),
  };
}

(async () => {
  const selected = ONLY
    ? CASES.filter((c) => ONLY.split(',').map((x) => x.trim()).includes(c.file))
    : CASES;
  if (!selected.length) {
    console.error('--only 没有匹配到任何用例；用 --list 看可用名单');
    process.exit(2);
  }

  // ---- 1. 预检：服务没起直接给可操作的提示，别让 11 个用例各报一遍连接失败 ----
  if (!NO_PREFLIGHT) {
    const apiStatus = await probe(API + '/api/system/health');
    const webStatus = await probe(BASE + '/');
    if (apiStatus !== 200 || webStatus !== 200) {
      console.error('❌ 服务未就绪，e2e 无法开始：');
      console.error('   后端 ' + API + '  -> ' + (apiStatus || '连接失败') + (apiStatus === 200 ? '' : '（期望 200）'));
      console.error('   前端 ' + BASE + '  -> ' + (webStatus || '连接失败') + (webStatus === 200 ? '' : '（期望 200）'));
      console.error('');
      console.error('   启动方式（两个都要）：');
      console.error('     后端： cd backend && mvn -o -B -ntp spring-boot:run');
      console.error('     前端： cd frontend && npm run dev');
      console.error('   或直接用 qqai-launcher 的「启动 qq-ai-assistant」。');
      console.error('   确认服务已在跑但仍报错时，可加 --no-preflight 跳过本检查。');
      process.exit(2);
    }
    console.log('✅ 预检通过：后端 ' + API + ' / 前端 ' + BASE);
  }

  const chrome = ensureChromePath();
  if (chrome) console.log('   浏览器：' + chrome);
  else console.warn('   ⚠️  没找到 Edge/Chrome，也没设 CHROME_PATH —— 起浏览器的用例会失败');

  // ---- 2. 签令牌（tv 必须与库里一致，所以每次现签，不复用旧票） ----
  let tokens;
  try {
    tokens = mintTokens();
  } catch (e) {
    console.error('❌ 签发 JWT 失败：' + e.message);
    process.exit(2);
  }
  console.log('   令牌：ADMIN=' + tokens.admin.username + '(id=' + tokens.admin.id + ')  USER='
    + tokens.user.username + '(id=' + tokens.user.id + ')');
  console.log('');

  // ---- 3. 串行跑（这些脚本都开真实浏览器，并行会互相抢焦点与端口） ----
  const results = [];
  for (const c of selected) {
    process.stdout.write('▶ ' + c.file.padEnd(34) + ' ... ');
    const r = runCase(c, tokens);
    results.push({ c, r });
    const sec = (r.ms / 1000).toFixed(1);
    console.log((r.ok ? '✅' : '❌') + ' ' + sec + 's' + (r.ok ? '' : '（exit=' + r.status + (r.signal ? ' signal=' + r.signal : '') + '）'));
    if (!r.ok) {
      console.log('   ---- 失败输出尾部 ----');
      console.log(tail(r.out, 15).split('\n').map((l) => '   ' + l).join('\n'));
      console.log('   ----------------------');
    }
  }

  // ---- 4. 汇总 ----
  const failed = results.filter((x) => !x.r.ok);
  console.log('');
  console.log('======== e2e 汇总 ========');
  for (const { c, r } of results) {
    console.log((r.ok ? '  ✅' : '  ❌') + ' ' + c.file.padEnd(34) + (r.ms / 1000).toFixed(1) + 's');
  }
  console.log('通过 ' + (results.length - failed.length) + '/' + results.length
    + '，总耗时 ' + (results.reduce((s, x) => s + x.r.ms, 0) / 1000).toFixed(1) + 's');
  if (failed.length) {
    console.log('失败的用例：' + failed.map((x) => x.c.file).join(', '));
    process.exit(1);
  }
  console.log('全部通过 ✅');
})();
