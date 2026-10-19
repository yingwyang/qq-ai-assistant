/**
 * 朗读/复制/转发 文本转换守卫。
 *
 * 背景（2026-10-19）：AI 回复是 Markdown，朗读和转发都需要纯文本。
 * 曾出现过：'- 近期主题' 的连字符被 TTS 读成「减」、'&#91;' 被切成 '&91;'、
 * 引号原样送进 TTS 导致断句很碎。这里用固定样例锁住转换契约。
 */
import { markdownToPlain, markdownToSpeech } from '../src/utils/markdownText.js';
import { normalizeSpeech, toSpeechText } from '../src/utils/ttsText.js';

let failed = 0;
const cases = [];

function check(name, actual, assertions) {
  const problems = [];
  for (const [desc, fn] of assertions) {
    if (!fn(actual)) problems.push(desc);
  }
  if (problems.length) {
    failed += 1;
    console.error('❌ ' + name + ' → ' + problems.join('；'));
    console.error('   实际输出: ' + JSON.stringify(actual).slice(0, 300));
  } else {
    console.log('✅ ' + name);
  }
}

const md = [
  '# 群聊速览',
  '',
  '- 近期主题：群友们围绕电竞展开讨论。',
  '- 群氛围：活跃',
  '',
  '| 指标 | 数值 |',
  '| --- | --- |',
  '| 消息量 | 48215 |',
  '',
  '```js',
  'const a = 1;',
  '```',
  '',
  '> 引用一行',
  '',
  '见 [文档](https://example.com/x)，图 ![图](a.png)，**重点**。'
].join('\n');

const speech = toSpeechText(md);
cases.push([speech, [
  ['不含 Markdown 标题符号', (s) => !s.includes('#')],
  ['不含列表连字符', (s) => !/-\s/.test(s) && !s.includes('- ')],
  ['不含表格竖线', (s) => !s.includes('|')],
  ['不含代码块内容', (s) => !s.includes('const a')],
  ['不含链接地址', (s) => !s.includes('https://')],
  ['不含图片标记', (s) => !s.includes('![图]')],
  ['不含加粗符号', (s) => !s.includes('**')],
  ['保留标题文字', (s) => s.includes('群聊速览')],
  ['保留链接文字', (s) => s.includes('文档')],
]]);
check('Markdown 全要素 → 朗读语流', speech, cases.at(-1)[1]);

check('纯文本直接朗读', normalizeSpeech('你好，世界。'), [
  ['原样保留', (s) => s === '你好，世界。']
]);

check('HTML 实体解码', toSpeechText('&#91;TOC&#93; 内容'), [
  ['不含 &', (s) => !s.includes('&')],
  ['不含 TOC', (s) => !/TOC/i.test(s)],
  ['保留正文', (s) => s.includes('内容')],
]);

check('引号被去除', toSpeechText('大量"草！给我杀！"等言语'), [
  ['不含引号', (s) => !/["“”]/.test(s)],
  ['保留正文', (s) => s.includes('给我杀')],
]);

check('复制/转发保留结构换行', markdownToPlain('- 一\n- 二'), [
  ['两行', (s) => s.split('\n').length === 2],
  ['不含连字符', (s) => !s.includes('- ')],
]);

check('工具调用信封被剥离', toSpeechText('<toolcall 群聊速览。近期主题，活跃'), [
  ['不含 toolcall', (s) => !/tool_?call|function_call/i.test(s)],
  ['保留正文', (s) => s.includes('群聊速览')],
]);

check('代码块不朗读', markdownToSpeech('```\nsecret()\n```\n正文'), [
  ['不含 secret', (s) => !s.includes('secret')],
  ['保留正文', (s) => s.includes('正文')],
]);

if (failed) {
  console.error('\n❌ 文本转换守卫失败：' + failed + ' 项');
  process.exit(1);
}
console.log('\n✅ 朗读/转发文本转换全部通过');
