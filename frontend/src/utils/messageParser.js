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