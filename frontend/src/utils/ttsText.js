/**
 * 把聊天消息（Markdown / 富文本）转成适合语音合成的口语化文本。
 *
 * 背景：TTS 会把标点当停顿、把引号/列表符读成怪音。线上实测摘要文本进来时是
 * 「情况。 · 群氛围，活跃…大量"草！给我杀！"等」这种形态，合成结果断句很碎。
 *
 * 处理顺序（顺序很重要）：
 *   实体解码 → 富文本取文本 → 去 Markdown 结构 → 去引号/括号 → 列表符归一
 *   → 去标点两侧空格 → 标点收敛 → 空白归一 → 去 emoji
 */

const ENTITY_MAP = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };
const CJK_PUNCT = '，。！？、；：';

function decodeEntities(text) {
  return text
    .replace(/&#(\d+);/g, (_, d) => String.fromCharCode(Number(d)))
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCharCode(parseInt(h, 16)))
    .replace(/&([a-z]+);/gi, (m, name) => ENTITY_MAP[name.toLowerCase()] ?? m);
}

function htmlToText(html) {
  const temp = document.createElement('div');
  temp.innerHTML = html;
  temp.querySelectorAll('summary').forEach((el) => el.insertAdjacentText('afterend', '\n'));
  temp.querySelectorAll('br, p, div, li').forEach((el) => el.insertAdjacentText('afterend', '\n'));
  return temp.innerText || temp.textContent || '';
}

/**
 * @param {string} raw 消息原文（Markdown 或富文本）
 * @returns {string} 可直接交给 /api/system/tts 的口语化文本
 */
export function toSpeechText(raw) {
  if (!raw) return '';

  // 1) 先解码实体，否则 '&#91;' 会被后面的 '#' 规则切成 '&91;'
  let text = decodeEntities(String(raw));

  // 2) 富文本取纯文本
  if (/<[a-z][^>]*>/i.test(text)) {
    text = htmlToText(text);
  }

  // 3) Markdown 结构
  text = text
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')      // 图片
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')     // 链接保留文字
    .replace(/^\s*[-*_]{3,}\s*$/gm, ' ')            // 分割线
    .replace(/^\s*\d+[.)、]\s*/gm, '')              // 行首编号
    .replace(/[#>*_~`]/g, '');                       // 其余标记符

  // 4) 引号 / 括号类包裹符（TTS 会读成停顿或杂音）
  text = text.replace(/["'“”‘’「」『』\[\]【】]/g, '');

  // 5) 列表符号 → 逗号（· 常被读成「点」）
  text = text.replace(/[·•▪▫◦]/g, '，');

  // 5.1) 括号只去符号保留内容；TOC 这类目录标记无朗读意义
  text = text.replace(/[（）()]/g, '');
  text = text.replace(/\bTOC\b/gi, '');
  // 5.2) 冒号 → 逗号（TTS 对冒号的停顿处理不稳定）
  text = text.replace(/[：:]/g, '，');

  // 6) 去掉标点两侧空白（· 换逗号后会残留空格，不先清就出现「 ， 」）
  text = text.replace(new RegExp('\\s*([，。！？、；：])\\s*', 'g'), '$1');

  // 7) 标点收敛：去掉「。，」「！，」「，。」这类连用
  text = text
    .replace(/[。！？；]+[，、]/g, (m) => m[0])
    .replace(/[，、][。！？；]/g, (m) => m[1])
    .replace(/[，、]{2,}/g, '，');

  // 7.1) 换行 → 逗号（列表项之间要有停顿，否则会连读成一串）
  text = text.replace(/\n+/g, '，');

  // 8) 空白归一
  text = text.replace(/\s+/g, ' ').trim();

  // 9) 去 emoji / 装饰符号
  text = text.replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}\u{2B00}-\u{2BFF}]/gu, '');

  // 10) 去掉首尾的孤立标点
  return text.replace(new RegExp('^[' + CJK_PUNCT + '\\s]+'), '').replace(new RegExp('[' + CJK_PUNCT + '\\s]+$'), '');
}
