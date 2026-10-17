/**
 * 安全收口回归：验证机器人登录二维码与状态灯的鉴权边界。
 *
 * 背景：`GET /api/system/napcat/qrcode-image` 曾被 permitAll —— 任何人无需登录即可
 * 拉取 QQ 机器人登录二维码图片，扫码即等于接管机器人账号；`qrcode-path` 还会泄漏
 * 服务器绝对路径。修复后三个二维码入口至少要求「已登录」，纯状态接口保持公开。
 *
 * 2026-09-24 调整：二维码三入口由 ADMIN-only 放宽为「已登录即可」——
 * 此前普通用户被 403，直接表现为「普通用户登录不了 QQ」。匿名拦截保持不变。
 *
 * 断言：
 *  1. 匿名访问 qrcode / qrcode-path / qrcode-image → 401
 *  2. 匿名访问 component-status / login-status → 200（登录页状态灯仍需可见）
 *  3. ADMIN 访问三个二维码入口 → 通过鉴权（非 401/403）
 *  4. 普通用户：按机器人当前登录状态分支 ——
 *     未登录 → 通过鉴权（普通用户必须能扫码登录 QQ）；
 *     已登录 → 403 且文案来自换号守卫（继续拉二维码等于顶号换号）
 *  5. 普通用户访问 /api/admin/users → 403
 *
 * 用法: node scripts/check-security-hardening.mjs <ADMIN_JWT> [baseUrl] [USER_JWT]
 */

const ADMIN_JWT = process.argv[2];
const BASE = (process.argv[3] || 'http://127.0.0.1:8081').replace(/\/$/, '');
const USER_JWT = process.argv[4] || '';
if (!ADMIN_JWT) {
  console.error('用法: node scripts/check-security-hardening.mjs <ADMIN_JWT> [baseUrl] [USER_JWT]');
  process.exit(2);
}

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? '✅' : '❌'} ${name}${detail ? ' — ' + detail : ''}`);
};

const probe = async (path, cookie) => {
  const headers = cookie ? { Cookie: `qqai_token=${cookie}` } : {};
  const res = await fetch(`${BASE}${path}`, { headers, redirect: 'manual' });
  // 读一小段响应体：用来区分「换号守卫拦的 403」和「鉴权整体坏了的 403」
  let bodyText = '';
  try { bodyText = (await res.text()).slice(0, 400); } catch { /* 图片等二进制读失败不影响判定 */ }
  return { status: res.status, type: res.headers.get('content-type') || '', bodyText };
};

// 1. 匿名：二维码三入口必须被拦
for (const path of ['/api/system/napcat/qrcode', '/api/system/napcat/qrcode-path', '/api/system/napcat/qrcode-image']) {
  const r = await probe(path);
  check(`匿名 ${path} → 401`, r.status === 401, `实际 ${r.status}`);
}

// 2. 匿名：状态灯仍公开
for (const path of ['/api/system/component-status', '/api/system/napcat/login-status']) {
  const r = await probe(path);
  check(`匿名 ${path} → 200（状态灯保持公开）`, r.status === 200, `实际 ${r.status}`);
}

// 3. 先读机器人当前是否已登录 QQ —— 它决定了二维码入口对普通用户的预期结果。
//    2026-10-15 起语义是「区分登录与换号」：机器人已登录时普通用户再拉二维码 = 换号，守卫直接 403；
//    未登录时普通用户必须能拉，否则就是「普通用户登录不了 QQ」那个老问题复发。
//    （此前这里固定断言「非 401/403」——机器人一登录脚本就会红，而红的是合法行为。）
let botLoggedIn = null;
{
  const r = await probe('/api/system/napcat/login-status');
  if (r.status === 200) {
    try {
      const j = JSON.parse(r.bodyText);
      botLoggedIn = !!(j && j.data && j.data.loggedIn);
    } catch {
      botLoggedIn = null;
    }
  }
}
check('机器人登录状态可读（决定普通用户的预期）', botLoggedIn !== null,
  botLoggedIn === null ? '读不到 login-status，普通用户断言按「未登录」处理' : 'loggedIn=' + botLoggedIn);

const QR_PATHS = ['/api/system/napcat/qrcode', '/api/system/napcat/qrcode-path', '/api/system/napcat/qrcode-image'];

// 4. ADMIN：换号本就是管理员操作，任何时候都应通过鉴权（非 401/403）
for (const path of QR_PATHS) {
  const r = await probe(path, ADMIN_JWT);
  check('ADMIN ' + path + ' 通过鉴权（非 401/403）', r.status !== 401 && r.status !== 403,
    '实际 ' + r.status + (r.type ? ' / ' + r.type : ''));
}

// 5. 普通用户：按机器人登录状态分两种预期，避免「合法行为被判失败」
if (USER_JWT) {
  for (const path of QR_PATHS) {
    const r = await probe(path, USER_JWT);
    if (botLoggedIn === true) {
      // 已登录 → 换号守卫必须拦，且要能辨认拦的是守卫（403 + 守卫文案），而不是鉴权整体坏了
      const blockedByGuard = r.status === 403 && /管理员|换号|更换登录/.test(String(r.bodyText || ''));
      check('普通用户 ' + path + ' → 403 换号守卫（机器人已登录）', blockedByGuard,
        '实际 ' + r.status + (blockedByGuard ? '' : ' / ' + String(r.bodyText || '').slice(0, 80)));
    } else {
      check('普通用户 ' + path + ' 通过鉴权（机器人未登录，非 401/403）',
        r.status !== 401 && r.status !== 403, '实际 ' + r.status);
    }
  }
  const adminOnly = await probe('/api/admin/users?page=0&size=5', USER_JWT);
  check('普通用户 /api/admin/users → 403', adminOnly.status === 403, '实际 ' + adminOnly.status);
} else {
  console.log('ℹ️  未提供 USER_JWT，跳过普通用户断言');
}

const failed = results.filter((r) => !r.ok);
console.log(`\n通过 ${results.length - failed.length}/${results.length}`);
if (failed.length) {
  failed.forEach((f) => console.log(`  - ${f.name}${f.detail ? ' — ' + f.detail : ''}`));
  process.exit(1);
}
