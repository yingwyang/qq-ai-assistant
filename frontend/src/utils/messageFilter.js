/**
 * 消息内容过滤工具
 * 用于过滤 AstrBot 返回的工具调用 JSON 内容
 */

/**
 * 过滤工具调用的 JSON 内容
 * @param {string} text - 原始文本
 * @returns {string} - 过滤后的文本
 */
export function filterToolJson(text) {
  if (!text || typeof text !== 'string') return text;
  
  // 匹配工具调用的 JSON 对象
  // 格式1: {"id": "...", "name": "...", "args": {...}, "ts": ...}
  // 格式2: {"id": "...", "ts": ..., "result": "..."}
  // 格式3: {"id": "...", "ts": ..., "name": "...", "args": {...}}
  const toolJsonPattern = /\{"id"\s*:\s*"[a-f0-9-]+",\s*(?:"name"\s*:\s*"[^"]+",\s*"args"\s*:\s*\{[\s\S]*?\},?\s*"ts"\s*:\s*[\d.]+|"ts"\s*:\s*[\d.]+,\s*(?:"result"\s*:\s*"[\s\S]*?"|"name"\s*:\s*"[^"]+"[^}]*))\}/g;
  
  let filtered = text.replace(toolJsonPattern, '');
  
  // 清理多余的换行和空格
  filtered = filtered.replace(/\n{3,}/g, '\n\n').trim();
  
  return filtered;
}

/**
 * 处理 AstrBot API 响应，过滤 JSON 内容
 * @param {object} response - API 响应对象
 * @returns {string} - 过滤后的回复文本
 */
export function processAstrBotResponse(response) {
  if (!response) return '抱歉，未收到回复。';

  // 如果响应本身就是字符串，直接过滤
  if (typeof response === 'string') {
    return filterToolJson(response);
  }

  // 从对象中提取回复文本，支持多种后端返回格式
  let rawText = '';

  if (typeof response.data === 'string') {
    rawText = response.data;
  } else if (response.data && typeof response.data === 'object') {
    // data 是对象时，尝试提取常见文本字段
    rawText = response.data.text || response.data.message || response.data.content || JSON.stringify(response.data);
  }

  // 支持 analyzeGroup 接口返回的 analysis 字段
  if (!rawText && typeof response.analysis === 'string') {
    rawText = response.analysis;
  }

  // 如果 data 没有可用文本，尝试 message 字段
  if (!rawText && typeof response.message === 'string') {
    rawText = response.message;
  }

  // 如果 status 为 error，使用 message 作为错误提示
  if (!rawText && response.status === 'error' && typeof response.message === 'string') {
    rawText = response.message;
  }

  // 兜底：尝试 JSON 序列化
  if (!rawText && typeof response === 'object') {
    rawText = JSON.stringify(response);
  }

  if (!rawText || typeof rawText !== 'string') {
    return '抱歉，响应格式异常。';
  }

  // 过滤 JSON 内容
  return filterToolJson(rawText);
}

export default {
  filterToolJson,
  processAstrBotResponse
};
