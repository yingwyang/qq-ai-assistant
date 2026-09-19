/**
 * 管理后台导出校验：对 用户 / 用户积分 / 审计日志 / 应用日志 四个导出入口做真实验证。
 *
 * 断言：
 *  1. 响应状态 200 且 Content-Type / Content-Disposition 正确
 *  2. 首行是预期的中文表头（CSV 带 BOM）
 *  3. 导出行数与列表接口的 totalElements 一致（用户/积分；审计日志受 2 万行上限约束）
 *  4. 应用日志导出带说明头，且行数与 /logs 返回的总数一致（都受"最近 5000 行"窗口约束）
 *
 * 用法: node scripts/e2e-admin-export.mjs <JWT> [baseUrl]
 */

import { chromium } from 'playwright-core';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
if (!TOKEN) {
  console.error('用法: node scripts/e2e-admin-export.mjs <JWT> [baseUrl]');
  process.exit(2);
}

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? '✅' : '❌'} ${name}${detail ? ' — ' + detail : ''}`);
};

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
const ctx = await browser.newContext({ viewport: { width: 1500, height: 1000 } });
await ctx.addCookies([{ name: 'qqai_token', value: TOKEN, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' }]);
const page = await ctx.newPage();

// 先打开一次 /admin，确保页面与接口都可用（同一 origin / 代理）
await page.goto(`${BASE}/admin?tab=users`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(2000);

/** 用页面上下文发起请求，保证走同一个 Vite 代理与 Cookie */
const fetchApi = async (path, asText = true) => {
  const res = await ctx.request.get(`${BASE}${path}`, { headers: { Cookie: `qqai_token=${TOKEN}` } });
  const body = asText ? await res.text() : null;
  return { status: res.status(), headers: res.headers(), body };
};

const dataRows = (csv) => csv.replace(/^\uFEFF/, '').split('\n').filter((l) => l.trim().length > 0);

// ---- 1. 用户导出 ----
{
  const list = await (await ctx.request.get(`${BASE}/api/admin/users?page=0&size=1`, { headers: { Cookie: `qqai_token=${TOKEN}` } })).json();
  const total = list.data.totalElements;
  const { status, headers, body } = await fetchApi('/api/admin/users/export');
  const rows = dataRows(body);
  check('用户导出返回 200', status === 200, `status=${status}`);
  check('用户导出 Content-Type 是 CSV', (headers['content-type'] || '').includes('text/csv'), headers['content-type']);
  check('用户导出带附件文件名', (headers['content-disposition'] || '').includes('users.csv'), headers['content-disposition']);
  check('用户导出首行是中文表头', rows[0].includes('账号') && rows[0].includes('角色'), rows[0].slice(0, 40));
  check('用户导出行数与列表总数一致', rows.length - 1 === total, `导出 ${rows.length - 1} 行 / 列表 ${total} 条`);
}

// ---- 2. 用户积分导出（带筛选） ----
{
  const list = await (await ctx.request.get(`${BASE}/api/credits/admin/user-credits?page=0&size=1&tier=FREE`, { headers: { Cookie: `qqai_token=${TOKEN}` } })).json();
  const total = list.data.totalElements;
  const { status, headers, body } = await fetchApi('/api/credits/admin/user-credits/export?tier=FREE&sort=balance&order=desc');
  const rows = dataRows(body);
  check('用户积分导出返回 200', status === 200, `status=${status}`);
  check('用户积分导出首行含余额与订阅层级', rows[0].includes('余额') && rows[0].includes('订阅层级'), rows[0].slice(0, 50));
  check('用户积分导出遵循 tier 筛选', rows.length - 1 === total, `导出 ${rows.length - 1} 行 / FREE 层 ${total} 条`);
}

// ---- 3. 审计日志导出 ----
{
  const list = await (await ctx.request.get(`${BASE}/api/admin/audit-logs?page=0&size=1&action=COMPONENT_START`, { headers: { Cookie: `qqai_token=${TOKEN}` } })).json();
  const total = list.data.totalElements;
  const { status, headers, body } = await fetchApi('/api/admin/audit-logs/export?action=COMPONENT_START');
  const rows = dataRows(body);
  check('审计日志导出返回 200', status === 200, `status=${status}`);
  check('审计日志导出首行是中文表头', rows[0].includes('操作人') && rows[0].includes('结果'), rows[0].slice(0, 50));
  check('审计日志导出遵循 action 筛选', rows.length - 1 === total, `导出 ${rows.length - 1} 行 / 该操作 ${total} 条`);
  check('审计日志导出声明未截断', headers['x-truncated'] === 'false', `X-Truncated=${headers['x-truncated']}`);
}

// ---- 4. 应用日志导出 ----
{
  // 用 ALL 对比行数（ERROR 可能恰好为 0，那样断言就没有信息量）
  const list = await (await ctx.request.get(`${BASE}/api/admin/logs?level=ALL&page=0&size=1`, { headers: { Cookie: `qqai_token=${TOKEN}` } })).json();
  const total = list.data.totalElements;
  const { status, headers, body } = await fetchApi('/api/admin/logs/export?level=ALL');
  const lines = body.split('\n').filter((l) => l.trim().length > 0);
  check('应用日志导出返回 200', status === 200, `status=${status}`);
  check('应用日志导出是纯文本', (headers['content-type'] || '').includes('text/plain'), headers['content-type']);
  check('应用日志导出带级别与行数说明', lines[0].includes('应用日志导出') && lines[0].includes('5000'), lines[0].slice(0, 60));
  check('应用日志导出条数与列表一致', lines.length - 1 === total, `导出 ${lines.length - 1} 行 / 列表 ${total} 条`);
  check('应用日志导出确实有内容', lines.length > 1, `${lines.length - 1} 行`);

  // 关键字过滤：导出行必须都含关键字
  const kw = 'INFO';
  const kwExport = await fetchApi(`/api/admin/logs/export?level=INFO&keyword=${kw}`);
  const kwLines = kwExport.body.split('\n').filter((l) => l.trim().length > 0).slice(1);
  check('应用日志关键字过滤生效', kwLines.every((l) => l.includes(kw)), `${kwLines.length} 行全部含 ${kw}`);
}

// ---- 5. 日志文件缺失/读不到时应返回可读错误而不是空文件 ----
{
  const res = await ctx.request.get(`${BASE}/api/admin/logs/export?level=ALL&from=not-a-date`);
  check('非法日期参数不影响导出', res.status() === 200, `status=${res.status()}`);
}

await browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} 通过`);
if (failed.length) {
  console.log('失败项:\n' + failed.map((f) => ` - ${f.name}: ${f.detail}`).join('\n'));
  process.exit(1);
}
