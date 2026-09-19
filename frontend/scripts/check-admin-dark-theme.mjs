/**
 * 管理后台暗色主题体检：逐个打开 14 个页面，在 theme-dark 下扫描真实的计算样式，
 * 报告「低对比度文本」与「近白底色块」，并把每页整屏截图保存下来人工复核。
 *
 * 用法: node scripts/check-admin-dark-theme.mjs <JWT> [baseUrl] [outDir]
 */

import { chromium } from 'playwright-core';
import path from 'node:path';
import fs from 'node:fs';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
const OUT = process.argv[4] || path.join(process.env.TEMP || '.', 'admin-dark');
if (!TOKEN) {
  console.error('用法: node scripts/check-admin-dark-theme.mjs <JWT> [baseUrl] [outDir]');
  process.exit(2);
}
fs.mkdirSync(OUT, { recursive: true });

const TABS = [
  ['dashboard', '数据概览', '.admin-dashboard'],
  ['users', '用户管理', '.admin-users'],
  ['components', '组件控制', '.admin-components'],
  ['config', '配置管理', '.admin-config'],
  ['log', '系统日志', '.admin-logs'],
  ['media', '媒体管理', '.admin-media'],
  ['maintenance', '数据维护', '.admin-backup'],
  ['ai-summary', 'AI 摘要', '.admin-ai-summary'],
  ['credit-rule', '规则配置', '.admin-credits-rule'],
  ['credit-users', '用户积分', '.admin-credits-users'],
  ['credit-transactions', '资金流水', '.admin-transactions'],
  ['credit-orders', '订单管理', '.admin-orders'],
  ['credit-refund-approve', '退款审批', '.admin-refund-approve'],
  ['credit-dispute', '纠纷处理', '.admin-dispute'],
];

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1400 } });
await ctx.addCookies([{ name: 'qqai_token', value: TOKEN, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' }]);
// 必须通过 localStorage 预设主题：useTheme() 在组件挂载时会把 <html> 的 class 重写为模块单例的值，
// 若只用 evaluate 强改 class，异步页面组件挂载后就会被改回浅色，扫描结果全是浅色底（假阳性）。
await ctx.addInitScript(() => {
  try {
    localStorage.setItem('app_theme', 'dark');
  } catch (e) {
    /* ignore */
  }
});
const page = await ctx.newPage();

// 扫描逻辑在页面内执行：只统计真正渲染出来、且有文本的元素
const SCAN = () => {
  const parse = (c) => {
    const m = /rgba?\(([^)]+)\)/.exec(c || '');
    if (!m) return null;
    const p = m[1].split(',').map((v) => parseFloat(v));
    if (p.length === 4 && p[3] === 0) return null;      // 完全透明＝不参与
    return { r: p[0], g: p[1], b: p[2], a: p.length === 4 ? p[3] : 1 };
  };
  const lum = ({ r, g, b }) => {
    const f = (v) => {
      const s = v / 255;
      return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    };
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
  };
  const over = (fg, bg) => ({
    r: fg.r * fg.a + bg.r * (1 - fg.a),
    g: fg.g * fg.a + bg.g * (1 - fg.a),
    b: fg.b * fg.a + bg.b * (1 - fg.a),
    a: 1,
  });
  const effectiveBg = (el) => {
    // 自下而上合成：先收集自身到 <html> 的背景层（含 alpha），再从最底层往上叠加，
    // 否则会把半透明层（如 rgba(52,152,219,.1) 的提示条）误当成不透明底色。
    const layers = [];
    let node = el;
    while (node && node !== document.documentElement) {
      layers.push(parse(getComputedStyle(node).backgroundColor));
      node = node.parentElement;
    }
    const htmlBg = parse(getComputedStyle(document.documentElement).backgroundColor);
    layers.push(htmlBg || { r: 255, g: 255, b: 255, a: 1 });
    let bg = { r: 255, g: 255, b: 255, a: 1 };
    for (let i = layers.length - 1; i >= 0; i--) {
      const layer = layers[i];
      if (!layer) continue;
      bg = over(layer, bg);
    }
    return bg;
  };
  const contrast = (a, b) => {
    const l1 = lum(a);
    const l2 = lum(b);
    return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
  };

  const lowContrast = [];
  const lightBlocks = [];
  const seen = new Set();
  document.querySelectorAll('.admin-main *').forEach((el) => {
    const text = (el.textContent || '').trim();
    const own = Array.from(el.childNodes).some((n) => n.nodeType === 3 && n.textContent.trim().length > 1);
    if (!own || text.length < 2) return;
    const rect = el.getBoundingClientRect();
    if (rect.width < 12 || rect.height < 8) return;
    if (el.offsetParent === null && getComputedStyle(el).position !== 'fixed') return;

    const cs = getComputedStyle(el);
    const fg = parse(cs.color);
    if (!fg) return;
    const bg = effectiveBg(el);
    const ratio = contrast(over(fg, bg), bg);
    const key = `${cs.color}|${Math.round(bg.r)},${Math.round(bg.g)},${Math.round(bg.b)}|${cs.fontSize}`;
    if (seen.has(key)) return;
    if (ratio < 3) {
      seen.add(key);
      lowContrast.push({
        selector: el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).join('.') : el.tagName,
        text: text.slice(0, 28),
        color: cs.color,
        bg: `rgb(${Math.round(bg.r)},${Math.round(bg.g)},${Math.round(bg.b)})`,
        ratio: Number(ratio.toFixed(2)),
        fontSize: cs.fontSize,
      });
    }
    // 近白底色块（暗色下非常刺眼）
    if (lum(bg) > 0.8 && elementIsBlockLevel(el)) {
      const bkey = `bg|${Math.round(bg.r)},${Math.round(bg.g)},${Math.round(bg.b)}|${el.className}`;
      if (!seen.has(bkey)) {
        seen.add(bkey);
        lightBlocks.push({
          selector: el.className && typeof el.className === 'string' ? '.' + el.className.trim().split(/\s+/).join('.') : el.tagName,
          bg: `rgb(${Math.round(bg.r)},${Math.round(bg.g)},${Math.round(bg.b)})`,
          text: text.slice(0, 24),
        });
      }
    }
  });

  function elementIsBlockLevel(el) {
    const d = getComputedStyle(el).display;
    return d === 'block' || d === 'flex' || d === 'grid' || d === 'inline-block' || d === 'table';
  }

  return { lowContrast: lowContrast.slice(0, 12), lightBlocks: lightBlocks.slice(0, 12) };
};

const report = [];
for (const [key, label, selector] of TABS) {
  await page.goto(`${BASE}/admin?tab=${key}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2200);

  const themeClass = await page.evaluate(() => document.documentElement.className);
  if (!themeClass.includes('theme-dark')) {
    console.log(`⚠ ${label}: <html> 未处于暗色（class=${themeClass}），本次结果不可信`);
  }

  const present = await page.locator(selector).count();
  const scan = present ? await page.evaluate(SCAN) : { lowContrast: [], lightBlocks: [] };
  report.push({ key, label, present: present === 1, ...scan });

  await page.screenshot({ path: path.join(OUT, `${key}.png`), fullPage: true });
}

await browser.close();

let problems = 0;
for (const r of report) {
  const bad = r.lowContrast.length + r.lightBlocks.length;
  problems += bad;
  console.log(`${bad ? '⚠' : '✅'} ${r.label} (${r.key})${r.present ? '' : ' — 页面未渲染'}`);
  for (const c of r.lowContrast) {
    console.log(`   低对比度 ${c.ratio}:1  ${c.selector} 「${c.text}」 color=${c.color} bg=${c.bg} ${c.fontSize}`);
  }
  for (const b of r.lightBlocks) {
    console.log(`   近白底色块  ${b.selector} bg=${b.bg} 「${b.text}」`);
  }
}
console.log(`\n截图目录: ${OUT}`);
console.log(problems ? `\n共 ${problems} 处待修` : '\n未发现暗色主题问题');
process.exit(problems ? 1 : 0);
