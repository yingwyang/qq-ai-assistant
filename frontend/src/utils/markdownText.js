/**
 * Markdown → 文本 的统一转换层。
 *
 * 背景（2026-10-19）：AI 回复是 Markdown 源码，而「朗读 / 复制 / 转发」都要的是纯文本。
 * 之前的做法是把 Markdown 原文直接塞进 innerHTML 再取 innerText，于是 '#'、'- '、'**'
 * 原样留下：朗读会把 '- ' 读成「减」、把表格读成一串；转发出去也是源码。
 *
 * 这里用与 RichTextRenderer 完全相同的 markdown-it 配置渲染，再按**块级语义**摊平：
 *   段落/标题/列表项/引用 → 句号
 *   表格单元格 → 逗号，表格行 → 句号
 *   代码块 → 跳过（读代码无意义）
 *   链接 → 只保留文字；图片 → 跳过
 * 产出两种形态：
 *   markdownToPlain()  —— 复制/转发用，保留换行
 *   markdownToSpeech() —— 朗读用，压成语流（无换行，交给 TTS 断句）
 */
import MarkdownIt from 'markdown-it';

const md = new MarkdownIt({
  html: true,
  linkify: true,
  typographer: false,
  breaks: true
});

const ENTITY_MAP = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', '#39': "'" };

/** 解码 HTML 实体；必须在任何「去符号」之前调用 */
export function decodeEntities(text) {
  return String(text)
    .replace(/&#(\d+);/g, (_, d) => String.fromCharCode(Number(d)))
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCharCode(parseInt(h, 16)))
    .replace(/&([a-z]+);/gi, (m, name) => ENTITY_MAP[name.toLowerCase()] ?? m);
}

/**
 * 渲染后的 HTML → 结构化纯文本（保留换行，供复制/转发）。
 */
export function markdownToPlain(source) {
  if (!source) return '';
  let html = md.render(String(source));
  // 代码块整体丢弃
  html = html.replace(/<pre[\s\S]*?<\/pre>/gi, ' ');
  // 表格：单元格逗号，行句号
  html = html.replace(/<\/(td|th)>/gi, '，').replace(/<\/tr>/gi, '。');
  // 块级结束换行
  html = html.replace(/<\/(p|h[1-6]|li|blockquote|div)>/gi, '\n');
  html = html.replace(/<br\s*\/??>/gi, '\n');
  // 其余标签去掉
  html = html.replace(/<[^>]+>/g, '');
  html = decodeEntities(html);
  return html
    .split('\n')
    .map((line) => line.replace(/[ \t]+/g, ' ').trim())
    .filter(Boolean)
    .join('\n');
}

/**
 * 渲染后的 HTML → 语流文本（供 TTS）。块级结束用句号，不做换行。
 */
export function markdownToSpeech(source) {
  if (!source) return '';
  let html = md.render(String(source));
  html = html.replace(/<pre[\s\S]*?<\/pre>/gi, ' ');
  html = html.replace(/<\/(td|th)>/gi, '，').replace(/<\/tr>/gi, '。');
  html = html.replace(/<\/(p|h[1-6]|li|blockquote|div)>/gi, '。');
  html = html.replace(/<br\s*\/??>/gi, '。');
  html = html.replace(/<[^>]+>/g, '');
  return decodeEntities(html);
}
