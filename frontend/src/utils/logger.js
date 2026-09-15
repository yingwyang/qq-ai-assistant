/**
 * 统一日志工具
 * 开发环境：error/warn/info/debug 全部输出，带 [App] 前缀
 * 生产环境：仅 error 输出，warn/info/debug 静默
 */
const isProd = import.meta.env.PROD;
const PREFIX = '[App]';

export const logger = {
  error(...args) {
    console.error(PREFIX, ...args);
  },
  warn(...args) {
    if (!isProd) console.warn(PREFIX, ...args);
  },
  info(...args) {
    if (!isProd) console.info(PREFIX, ...args);
  },
  debug(...args) {
    if (!isProd) console.debug(PREFIX, ...args);
  },
};

export default logger;
