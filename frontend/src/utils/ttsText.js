/**
 * 朗读文本规范化（最终形态）。
 *
 * 分工：
 *   markdownText.js —— Markdown/HTML → 结构化文本（块级语义、表格、代码块）
 *   ttsText.js      —— 结构化文本 → 可朗读语流（引号、列表符、连用标点、emoji）
 *
 * 顺序约束（有单测锁住）：
 *   实体解码必须最先（否则 &#91; 会被去 # 规则切成 &91;）
 *   标点收敛必须在去标点两侧空格之后（否则 ' ， ' 不匹配连续逗号规则）
 */
import { decodeEntities, markdownToSpeech } from './markdownText.js';

const CJK_PUNCT = '，。！？、；：';

/** 把「结构化文本」规范成适合 TTS 的语流 */
export function normalizeSpeech(input) {
  let text = decodeEntities(String(input ?? ''));

  // 工具调用信封（AstrBot Agent 管线残留）：
  //   <toolcall ...> / <tool_call>…</tool_call> / <function_call>，以及被去符号后只剩
  //   「toolcall 正文」的形态。它们不是内容，必须整段剥掉，否则会被念成 "toolcall"。
  text = text
    .replace(/<\/?(?:tool_?call|function_call)[^>]*>/gi, '')
    .replace(/^\s*(?:tool_?call|function_call)\b[\s:：-]*/i, '')
    .replace(/\b(?:tool_?call|function_call)\b[\s:：-]*/gi, '');

  // Markdown 残留符号（兜底，正常已由 markdownToSpeech 处理）
  text = text.replace(/[#>*_~`]/g, '');
  // 引号 / 括号类包裹符
  text = text.replace(/["'“”‘’「」『』\[\]【】]/g, '');
  // 列表符 → 逗号
  text = text.replace(/[·•▪▫◦]/g, '，');
  // 括号只去符号
  text = text.replace(/[（）()]/g, '');
  // 目录标记
  text = text.replace(/\bTOC\b/gi, '');
  // 冒号 → 逗号
  text = text.replace(/[：:]/g, '，');
  // 标点两侧空白（必须在收敛之前）
  text = text.replace(/\s*([，。！？、；：])\s*/g, '$1');
  // 连用标点收敛
  text = text
    .replace(/[。！？；]+[，、]/g, (m) => m[0])
    .replace(/[，、][。！？；]/g, (m) => m[1])
    .replace(/[，、]{2,}/g, '，');
  // 换行 → 逗号（列表项之间要停顿）
  text = text.replace(/\n+/g, '，');
  // 空白归一
  text = text.replace(/\s+/g, ' ').trim();
  // emoji / 装饰符号
  text = text.replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}\u{2B00}-\u{2BFF}]/gu, '');
  // 去首尾孤立标点：句号保留（是正常句末），只去掉首尾多余的逗号类分隔符
  return text
    .replace(new RegExp('^[' + CJK_PUNCT + '\\s]+'), '')
    .replace(/[，、；：\s]+$/, '');
}

/**
 * 消息原文（Markdown / 富文本 / 纯文本）→ 可直接交给 TTS 的语流。
 * 纯文本走 markdown-it 渲染也无副作用（会包成 <p>），因此无需区分来源。
 */
export function toSpeechText(raw) {
  if (!raw) return '';
  return normalizeSpeech(markdownToSpeech(raw));
}
