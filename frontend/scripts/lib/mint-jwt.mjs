/**
 * 为本地 e2e 验收签发 JWT —— 等价于后端 com.qqai.security.JwtUtil#generateToken。
 *
 * 为什么不用固定 token：claims 里的 tv 必须等于 users.token_version，uid 必须是真实用户 id，
 * 且有效期只有 6 小时 —— 手抄一个令牌隔天就废，还容易抄错。
 *
 * 依赖：Node 内置 crypto（签名）+ 本机 MySQL 客户端（读 uid / token_version）+ backend/.env（密钥与口令）。
 * 全程不落盘、不进仓库。
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
// lib -> scripts -> frontend -> 仓库根
const REPO = path.resolve(HERE, '..', '..', '..');
const ENV_FILE = path.join(REPO, 'backend', '.env');

/** 解析 .env：只按第一个 = 切分；**不剥行内注释**（本项目的 JWT_SECRET 里就含 # 与 &）。 */
function parseEnv(file) {
  const out = {};
  if (!fs.existsSync(file)) return out;
  for (let line of fs.readFileSync(file, 'utf8').split('\n')) {
    if (line.endsWith('\r')) line = line.slice(0, -1);
    const t = line.trim();
    if (!t || t.startsWith('#')) continue;
    const i = t.indexOf('=');
    if (i <= 0) continue;
    let v = t.slice(i + 1).trim();
    if (v.length >= 2 && ((v[0] === '"' && v[v.length - 1] === '"') || (v[0] === "'" && v[v.length - 1] === "'"))) {
      v = v.slice(1, -1);
    }
    out[t.slice(0, i).trim()] = v;
  }
  return out;
}

/** 与 jjwt 的 Keys.hmacShaKeyFor 同口径：按密钥字节数选算法 */
function algFor(byteLen) {
  if (byteLen >= 64) return { jws: 'HS512', hash: 'sha512' };
  if (byteLen >= 48) return { jws: 'HS384', hash: 'sha384' };
  return { jws: 'HS256', hash: 'sha256' };
}

const b64 = (input) => Buffer.from(input).toString('base64url');

/** 用后端同一个密钥签一枚令牌（6 小时有效，与后端默认 jwt.expiration 无关的短票） */
export function signToken(secret, claims, ttlSeconds) {
  const key = Buffer.from(secret, 'utf8');
  if (key.length < 32) throw new Error('JWT_SECRET 不足 32 字节，后端会拒绝签名，请检查 backend/.env');
  const alg = algFor(key.length);
  const now = Math.floor(Date.now() / 1000);
  const payload = Object.assign(
    { iat: now, exp: now + (ttlSeconds || 6 * 3600) },
    claims,
  );
  const signingInput = b64(JSON.stringify({ alg: alg.jws, typ: 'JWT' })) + '.' + b64(JSON.stringify(payload));
  const sig = crypto.createHmac(alg.hash, key).update(signingInput).digest('base64url');
  return signingInput + '.' + sig;
}

function resolveMysqlBin() {
  if (process.env.MYSQL_BIN) return process.env.MYSQL_BIN;
  const candidates = [
    'D:/Program Files/MySQL/MySQL Server 9.5/bin/mysql.exe',
    'C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe',
    'mysql',
  ];
  for (const c of candidates) {
    if (c === 'mysql' || fs.existsSync(c)) return c;
  }
  return 'mysql';
}

/** 读一个指定角色的可用账号（id / username / token_version） */
function fetchUser(env, role) {
  const bin = resolveMysqlBin();
  const sql = "SELECT id, username, token_version FROM users WHERE role='" + role + "' AND active=1 ORDER BY id LIMIT 1;";
  const db = env.DB_NAME || 'qq_chat';
  let out = '';
  try {
    out = execFileSync(bin, [
      '--host=' + (env.DB_HOST || '127.0.0.1'),
      '--user=' + (env.DB_USERNAME || 'root'),
      '-N', '-B', '-e', sql, db,
    ], {
      encoding: 'utf8',
      env: Object.assign({}, process.env, { MYSQL_PWD: env.DB_PASSWORD || '' }),
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } catch (e) {
    const detail = e && e.stderr ? String(e.stderr).trim().slice(0, 200) : String(e && e.message);
    throw new Error('查库失败（role=' + role + '）：' + detail
      + '\n  提示：可用 MYSQL_BIN 指定 mysql 客户端路径，或先用后端账号确认库连通。');
  }
  const line = out.split('\n').map((x) => x.trim()).filter(Boolean)[0];
  if (!line) throw new Error('库里没有 active 的 ' + role + ' 账号；请先注册/启用一个 ' + role + ' 用户。');
  const parts = line.split('\t');
  return { id: Number(parts[0]), username: parts[1], tokenVersion: Number(parts[2] || 0), role };
}

/** 签发 admin 与普通用户两枚令牌 */
export function mintTokens(options) {
  const env = parseEnv((options && options.envFile) || ENV_FILE);
  if (!env.JWT_SECRET) {
    throw new Error('没读到 JWT_SECRET：请确认 ' + ENV_FILE + ' 存在且包含 JWT_SECRET');
  }
  const adminUser = fetchUser(env, 'ADMIN');
  const userUser = fetchUser(env, env.E2E_USER_ROLE || 'USER');
  const make = (u) => signToken(env.JWT_SECRET, {
    sub: u.username,
    uid: u.id,
    tv: u.tokenVersion,
    role: u.role,
    jti: crypto.randomUUID(),
  });
  return {
    admin: { token: make(adminUser), username: adminUser.username, id: adminUser.id },
    user: { token: make(userUser), username: userUser.username, id: userUser.id },
  };
}

// 单独运行时：直接打印两枚令牌，方便手工跑某个脚本
if (process.argv[1] && process.argv[1].endsWith('mint-jwt.mjs')) {
  try {
    const t = mintTokens();
    console.log('ADMIN=' + t.admin.token);
    console.log('USER=' + t.user.token);
    console.error('（ADMIN=' + t.admin.username + ' id=' + t.admin.id + '，USER=' + t.user.username + ' id=' + t.user.id + '）');
  } catch (e) {
    console.error('签发失败: ' + e.message);
    process.exit(1);
  }
}
