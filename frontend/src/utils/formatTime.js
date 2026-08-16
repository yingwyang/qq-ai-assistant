// 通用时间格式化工具

/**
 * 格式化消息时间戳为可读字符串
 * @param {string|number|Date} timestamp - 时间戳
 * @returns {string} 格式化后的时间字符串
 *   规则：1分钟内→"刚刚"；1小时内→"N分钟前"；当天内→"HH:mm"；
 *         昨天→"昨天 HH:mm"；同一年→"MM-DD HH:mm"；跨年→"YYYY-MM-DD HH:mm"
 */
export function formatMessageTime(timestamp) {
  if (!timestamp) return '';
  const date = new Date(timestamp);
  if (isNaN(date.getTime())) return '';

  const now = new Date();
  const pad = (n) => String(n).padStart(2, '0');
  const timeStr = `${pad(date.getHours())}:${pad(date.getMinutes())}`;

  const isSameDay = (d1, d2) =>
    d1.getFullYear() === d2.getFullYear() &&
    d1.getMonth() === d2.getMonth() &&
    d1.getDate() === d2.getDate();

  const diffMs = now - date;
  const diffMin = Math.floor(diffMs / 60000);
  const diffHour = Math.floor(diffMs / 3600000);

  // 1 分钟内
  if (diffMin < 1) return '刚刚';
  // 1 分钟 ~ 1 小时
  if (diffHour < 1) return `${diffMin}分钟前`;
  // 当天内超过 1 小时
  if (isSameDay(date, now)) return timeStr;

  // 昨天
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  if (isSameDay(date, yesterday)) return `昨天 ${timeStr}`;

  // 同一年显示 MM-DD HH:mm
  if (date.getFullYear() === now.getFullYear()) {
    return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${timeStr}`;
  }

  // 跨年份显示 YYYY-MM-DD HH:mm
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${timeStr}`;
}