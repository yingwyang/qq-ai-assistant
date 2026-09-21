/**
 * 安全收口回归（批次 A）：验证机器人登录二维码与状态灯的鉴权边界。
 *
 * 背景：`GET /api/system/napcat/qrcode-image` 曾被 permitAll —— 任何人无需登录即可
 * 拉取 QQ 机器人登录二维码图片，扫码即等于接管机器人账号；`qrcode-path` 还会泄漏
 * 服务器绝对路径。修复后三个二维码入口统一为 ADMIN-only，纯状态接口保持公开。
 *
 * 断言：
 *  1. 匿名访问 qrcode / qrcode-path / qrcode-image → 401
 *  2. 匿名访问 component-status / login-status → 200（登录页状态灯仍需可见）
 *  3. ADMIN 访问 qrcode-image → 200/404（通过鉴权，404 仅表示服务器上没有二维码文件）
 *  4. 普通用户访问 qrcode-image / qrcode-path → 403（传入 USER token 时才断言）
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
  return { status: res.status, type: res.headers.get('content-type') || '' };
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

// 3. ADMIN：通过鉴权（404 仅代表没有二维码文件）
const adminQr = await probe('/api/system/napcat/qrcode-image', ADMIN_JWT);
check('ADMIN qrcode-image 通过鉴权（200/404）', adminQr.status === 200 || adminQr.status === 404,
  `实际 ${adminQr.status}${adminQr.type ? ' / ' + adminQr.type : ''}`);
const adminPath = await probe('/api/system/napcat/qrcode-path', ADMIN_JWT);
check('ADMIN qrcode-path 通过鉴权（200）', adminPath.status === 200, `实际 ${adminPath.status}`);

// 4. 普通用户：403
if (USER_JWT) {
  for (const path of ['/api/system/napcat/qrcode-image', '/api/system/napcat/qrcode-path']) {
    const r = await probe(path, USER_JWT);
    check(`普通用户 ${path} → 403`, r.status === 403, `实际 ${r.status}`);
  }
  const adminOnly = await probe('/api/admin/users?page=0&size=5', USER_JWT);
  check('普通用户 /api/admin/users → 403', adminOnly.status === 403, `实际 ${adminOnly.status}`);
} else {
  console.log('ℹ️  未提供 USER_JWT，跳过普通用户 403 断言');
}

const failed = results.filter((r) => !r.ok);
console.log(`\n通过 ${results.length - failed.length}/${results.length}`);
if (failed.length) {
  failed.forEach((f) => console.log(`  - ${f.name}${f.detail ? ' — ' + f.detail : ''}`));
  process.exit(1);
}
