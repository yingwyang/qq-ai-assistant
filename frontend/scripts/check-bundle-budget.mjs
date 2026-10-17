/**
 * 入口包体积守卫。
 *
 * 背景：批次 F 把 echarts 从入口拆出去之后，入口从 1141KB 降到 ~51KB，但「<=51KB」一直只是
 * 文档里的一条验收基线，没有任何自动断言 —— 下次谁在 main.js 里静态 import 一个重库，
 * 入口会重新膨胀而无人发现（历史上就这么发生过一次）。这里把它变成构建后的硬断言。
 *
 * 口径：
 *  - 只量**入口 chunk**，即 dist/index.html 里 type="module" 的那个 <script src>。
 *    不能用 index-*.js 通配：modulepreload 引用的 echarts 等分包很大且是故意的，误纳入会误报。
 *  - 字节数按 bytes/1000 换算成 kB，与 Vite 构建输出里的 "51.81 kB" 同一口径。
 *
 * 阈值：60 kB。当前实测 54.3 kB（2026-10-17 图片预览加缩放后从 51.8 涨到 54.3），
 * 留约 10% 余量 —— 小功能改动不会误报，而历史上那次回归是 1141 kB 量级，任何合理上限都拦得住。
 * 注：本守卫的目的是拦住「重库被静态 import 进入口」这类数量级回归，不是给每次功能增长设卡；
 * 阈值若需上调，应在提交信息里写明原因与新的实测值（2026-10-17 从 56 调到 60 即为此例）。
 * 本地可用 BUNDLE_BUDGET_KB 覆盖（仅调试用，CI 走默认值）。
 *
 * 用法: npm run budget            （需先 npm run build）
 */

import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const DIST = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'dist');
const DEFAULT_BUDGET_KB = 60;

const override = process.env.BUNDLE_BUDGET_KB;
const budgetKb = override ? Number(override) : DEFAULT_BUDGET_KB;

if (!Number.isFinite(budgetKb) || budgetKb <= 0) {
  console.error('❌ BUNDLE_BUDGET_KB 不是合法正数: ' + override);
  process.exit(2);
}

const htmlPath = path.join(DIST, 'index.html');
if (!fs.existsSync(htmlPath)) {
  console.error('❌ 找不到 ' + htmlPath);
  console.error('   这个守卫量的是构建产物，请先执行 npm run build');
  process.exit(2);
}

// 从 index.html 里取入口：<script type="module" ... src="/assets/index-xxxx.js">
const html = fs.readFileSync(htmlPath, 'utf8');
const m = html.match(/<script[^>]*\btype=["']module["'][^>]*\bsrc=["']([^"']+)["']/i);
if (!m) {
  console.error('❌ 在 dist/index.html 里找不到 type="module" 的入口脚本');
  console.error('   构建产物形态变了？请更新本守卫而不是直接放宽阈值');
  process.exit(2);
}

const entryRel = m[1].replace(/^\/+/, '');          // 去掉前导斜杠，按 dist 根解析
const entryPath = path.join(DIST, entryRel);
if (!fs.existsSync(entryPath)) {
  console.error('❌ index.html 引用的入口文件不存在: ' + entryRel);
  process.exit(2);
}

const bytes = fs.statSync(entryPath).size;
const gzip = zlib.gzipSync(fs.readFileSync(entryPath)).length;
const kb = bytes / 1000;
const budgetBytes = budgetKb * 1000;
const headroomKb = budgetKb - kb;

const fmt = (kbv) => kbv.toFixed(2);

console.log('入口 chunk: ' + entryRel);
console.log('  体积: ' + fmt(kb) + ' kB (' + bytes + ' bytes)   gzip: ' + fmt(gzip / 1000) + ' kB');
console.log('  预算: ' + fmt(budgetKb) + ' kB (' + budgetBytes + ' bytes)   余量: ' +
  (headroomKb >= 0 ? fmt(headroomKb) : '-' + fmt(-headroomKb)) + ' kB');

if (bytes > budgetBytes) {
  console.error('');
  console.error('❌ 入口包超预算: ' + fmt(kb) + ' kB > ' + fmt(budgetKb) + ' kB');
  console.error('   入口是首屏必须加载的部分，它的膨胀会直接拖慢每一个页面。');
  console.error('   常见原因：在 main.js 里全局注册了重组件，或静态 import 了 echarts / markdown-it 这类重库。');
  console.error('   处理：改成按需/懒加载（动态 import + 局部注册）后再构建；');
  console.error('   如果确实是合理的增长，请显式调高 DEFAULT_BUDGET_KB 并在提交信息里说明原因。');
  process.exit(1);
}

console.log('✅ 入口包体积在预算内（' + fmt(kb) + ' kB <= ' + fmt(budgetKb) + ' kB，余量 ' + fmt(headroomKb) + ' kB）');
