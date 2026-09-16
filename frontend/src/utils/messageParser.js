// 消息内容解析工具 — 提取合并转发消息/XML 标题等

/**
 * 从 XML 字符串中提取所有 <item> 下的 <title> 文本
 * @param {string} xmlStr - 原始 XML 字符串（可能含 HTML 实体）
 * @returns {string[]} 标题数组
 */
export function extractForwardXmlTitles(xmlStr) {
  if (!xmlStr || !xmlStr.includes('<msg')) return [];
  const msgMatch = xmlStr.match(/<msg[\s\S]*?<\/msg>/);
  if (!msgMatch) return [];
  let xml = msgMatch[0]
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'");
  try {
    const parser = new DOMParser();
    const doc = parser.parseFromString(xml, 'text/xml');
    return Array.from(doc.querySelectorAll('item title')).map(t => t.textContent || '');
  } catch (e) {
    return [];
  }
}

/**
 * 解析嵌套的转发消息列表
 * @param {object} msg - 消息对象（含 message.content / message.forwardMessages）
 * @returns {Array} 子消息数组
 */
export function extractForwardMessages(msg) {
  if (!msg) return [];
  if (Array.isArray(msg.forwardMessages)) {
    return msg.forwardMessages;
  }
  const c = (msg.content || '').trim();
  if (!c) return [];

  // 尝试从内容中解析 JSON 数组或对象
  try {
    const jsonMatch = c.match(/(\{[\s\S]*\}|\[[\s\S]*\])/);
    if (jsonMatch) {
      const parsed = JSON.parse(jsonMatch[1]);
      if (Array.isArray(parsed)) return parsed;
      if (parsed.messages && Array.isArray(parsed.messages)) return parsed.messages;
      if (parsed.content && Array.isArray(parsed.content)) return parsed.content;
      if (parsed.xmlContent && typeof parsed.xmlContent === 'string') {
        const titles = extractForwardXmlTitles(parsed.xmlContent);
        return titles.slice(1).map((text, index) => ({
          id: `forward-${msg.id}-${index}`,
          userNickname: '',
          content: text,
          messageType: 'TEXT'
        }));
      }
    }
  } catch (e) {
    // 解析失败则忽略
  }

  // 尝试直接解析 XML 内容
  if (c.includes('<msg') && c.includes('</msg>')) {
    const titles = extractForwardXmlTitles(c);
    return titles.slice(1).map((text, index) => ({
      id: `forward-${msg.id}-${index}`,
      userNickname: '',
      content: text,
      messageType: 'TEXT'
    }));
  }

  return [];
}

/** 本地媒体路径 → 可访问 URL（与 MessageContent 内的实现保持一致，保证图片预览能匹配索引） */
export function toMediaUrl(path) {
  if (!path) return '';
  if (path.startsWith('http')) return path;
  if (path.startsWith('/images/') || path.startsWith('/uploads/')) {
    return `http://localhost:8081${path}`;
  }
  return path;
}

/**
 * 从一条消息中提取图片 URL（与 MessageContent 的 imageUrl 计算逻辑保持一致）。
 * 用于组装"当前会话的图片列表"，供图片预览左右切换使用。
 * @param {Object} msg - 消息对象
 * @returns {string} 图片 URL，取不到时返回空串
 */
export function extractImageUrl(msg) {
  if (!msg) return '';
  if (msg.localUrl) return toMediaUrl(msg.localUrl);
  const c = typeof msg.content === 'string' ? msg.content : '';
  if (!c) return '';
  if (/^\/images\//.test(c) || /^\/uploads\//.test(c)) return toMediaUrl(c);
  // 只认图片类 CQ 码，避免把视频/语音混进图片预览
  if (c.includes('[CQ:image') || /^\[CQ:image/.test(c) || msg.messageType === 'IMAGE') {
    const backtick = c.match(/url=`([^`]+)`/);
    if (backtick && backtick[1]) return backtick[1].replace(/&amp;/g, '&').trim();
    const urlMatch = c.match(/url=([^,\]]+)/);
    if (urlMatch && urlMatch[1]) return urlMatch[1].replace(/&amp;/g, '&').trim();
    const fileMatch = c.match(/file=([^,\]]+)/);
    if (fileMatch && fileMatch[1]) return toMediaUrl(fileMatch[1].replace(/&amp;/g, '&').trim());
  }
  return '';
}