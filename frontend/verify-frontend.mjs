// 前端语法验证:用 @vue/compiler-sfc 解析所有 .vue 文件 + node --check 所有 .js
// 用法: node verify-frontend.mjs
import { parse, compileScript, compileTemplate } from '@vue/compiler-sfc';
import { readFileSync, readdirSync, statSync } from 'fs';
import { join, extname } from 'path';
import { execSync } from 'child_process';

const ROOT = new URL('.', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
const SRC = join(ROOT, 'src');

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === 'dist') continue;
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, out);
    else if (extname(p) === '.vue' || extname(p) === '.js') out.push(p);
  }
  return out;
}

const files = walk(SRC);
let failed = 0;
const errors = [];

for (const f of files) {
  const rel = f.replace(ROOT, '');
  try {
    if (f.endsWith('.vue')) {
      const source = readFileSync(f, 'utf-8');
      const { descriptor, errors: parseErrs } = parse(source, { filename: f });
      if (parseErrs.length) throw new Error('parse: ' + parseErrs.map(e => e.message).join('; '));
      if (descriptor.script || descriptor.scriptSetup) {
        compileScript(descriptor, { id: rel });
      }
      if (descriptor.template) {
        const t = compileTemplate({ source: descriptor.template.content, filename: f, id: rel });
        if (t.errors.length) throw new Error('template: ' + t.errors.join('; '));
      }
    } else {
      execSync(`node --check "${f}"`, { stdio: 'pipe' });
    }
  } catch (e) {
    failed++;
    errors.push(`${rel}\n  ${String(e.message || e).split('\n')[0]}`);
  }
}

console.log(`checked ${files.length} files, ${failed} failed`);
if (errors.length) {
  console.log(errors.join('\n'));
  process.exit(1);
}
