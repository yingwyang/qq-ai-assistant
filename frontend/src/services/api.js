// API 服务层 — 统一 fetch 封装
import { showToast } from '../components/Toast.vue';
const API_BASE_URL = '/api';

// 防止短时间内多个 401 响应重复触发登出事件导致闪屏
let _unauthorizedHandled = false;

function handleUnauthorized() {
  // 如果已经处理过登出，不再重复触发（避免多个并发请求同时返回 401 时重复 dispatch 事件）
  if (_unauthorizedHandled) return;
  _unauthorizedHandled = true;

  // 通知后端清除 HttpOnly Cookie（fire-and-forget，不 await，不重试）
  fetch('/api/auth/logout', {
    method: 'POST',
    keepalive: true,
  }).catch(() => {});

  // 只清理 localStorage 中的非敏感缓存（auth_token 已不再使用 HttpOnly Cookie 方案）
  localStorage.removeItem('user_role');
  localStorage.removeItem('user_info');
  window.dispatchEvent(new CustomEvent('auth:logout'));

  // 1 秒后重置标志，允许后续登录态失效时再次触发
  setTimeout(() => { _unauthorizedHandled = false; }, 1000);
}

async function parseResponse(response, skipUnauthorizedHandling = false) {
  // 读取响应体文本
  const responseText = await response.text();

  // 解析 JSON（如果可能）
  let parsed = null;
  if (responseText) {
    try {
      parsed = JSON.parse(responseText);
    } catch {
      parsed = null;
    }
  }

  // 401 = 未认证/登录过期 → 默认清除登录状态；skipUnauthorizedHandling=true 时仅抛错，不触发全局登出
  if (response.status === 401) {
    if (!skipUnauthorizedHandling) {
      handleUnauthorized();
    }
    const msg = parsed?.error || parsed?.message || '登录已过期，请重新登录';
    const err = new Error(msg);
    if (parsed?.errorCode) err.errorCode = parsed.errorCode;
    if (parsed?.details) err.details = parsed.details;
    throw err;
  }

  // 403 = 已登录但权限不足 → 不清除登录状态
  if (response.status === 403) {
    const msg = parsed?.error || parsed?.message || '权限不足，无法执行此操作';
    const err = new Error(msg);
    if (parsed?.errorCode) err.errorCode = parsed.errorCode;
    if (parsed?.details) err.details = parsed.details;
    throw err;
  }

  // 其他错误状态码
  if (!response.ok) {
    const msg = parsed?.error || parsed?.message || `HTTP error! status: ${response.status}`;
    const err = new Error(msg);
    if (parsed?.errorCode) err.errorCode = parsed.errorCode;
    if (parsed?.details) err.details = parsed.details;
    throw err;
  }

  // 空响应体
  if (!parsed) return null;

  // 标准 ApiResponse 格式（有 code 字段且有 data 字段）→ 返回 data
  // 否则（如 AstrBot 控制器自定义格式 {status,data,cost,balance,conversationId,...}）→ 返回整个响应对象
  const hasStandardCode = typeof parsed.code === 'number';
  const hasDataField = parsed.data !== undefined;
  if (hasStandardCode && hasDataField) {
    return parsed.data;
  }
  return parsed;
}

async function request(endpoint, options = {}) {
  const url = `${API_BASE_URL}${endpoint}`;
  const { skipUnauthorizedHandling, ...restOptions } = options;

  const headers = {
    'Content-Type': 'application/json; charset=UTF-8',
    'Accept': 'application/json; charset=UTF-8',
    ...restOptions.headers,
  };

  try {
    const response = await fetch(url, { ...restOptions, headers });
    return await parseResponse(response, skipUnauthorizedHandling);
  } catch (error) {
    // 处理网络错误（服务未启动等情况）
    if (error.name === 'TypeError' && error.message === 'Failed to fetch') {
      throw new Error('服务暂时不可用，请检查后端服务是否已启动');
    }
    // 处理连接被拒绝错误
    if (error.message && error.message.includes('Connection refused')) {
      throw new Error('无法连接到服务器，请检查后端服务是否运行');
    }
    // 隐藏技术堆栈，显示友好提示
    if (error.message && error.message.includes('getsockopt')) {
      throw new Error('服务暂时不可用，请稍后重试');
    }
    if (error.message !== '登录已过期，请重新登录') {
      showToast(error.message || '请求失败', 'error');
    }
    throw error;
  }
}

async function uploadRequest(endpoint, formData) {
  const url = `${API_BASE_URL}${endpoint}`;

  try {
    const response = await fetch(url, {
      method: 'POST',
      body: formData,
    });
    return await parseResponse(response);
  } catch (error) {
    showToast(error.message || '上传失败', 'error');
    if (error.name === 'TypeError' && error.message === 'Failed to fetch') {
      throw new Error('无法连接到服务器，请检查后端服务是否运行');
    }
    throw error;
  }
}

// 带鉴权的文件下载（用于积分流水/订单导出等返回文件流的接口）
// endpoint 已包含 query string；fallbackName 用于响应头无 Content-Disposition 时
async function downloadWithAuth(endpoint, fallbackName = 'download.json') {
  const url = `${API_BASE_URL}${endpoint}`;
  const headers = {
    'Accept': 'application/json, text/plain, */*',
  };
  let response;
  try {
    response = await fetch(url, { headers });
  } catch (error) {
    if (error.name === 'TypeError' && error.message === 'Failed to fetch') {
      throw new Error('服务暂时不可用，请检查后端服务是否已启动');
    }
    throw error;
  }
  if (response.status === 401) {
    handleUnauthorized();
    throw new Error('登录已过期，请重新登录');
  }
  if (response.status === 403) {
    throw new Error('权限不足，无法执行此操作');
  }
  if (!response.ok) {
    let msg = `HTTP error! status: ${response.status}`;
    try {
      const text = await response.text();
      const parsed = text ? JSON.parse(text) : null;
      msg = parsed?.error || parsed?.message || msg;
    } catch {}
    throw new Error(msg);
  }
  const blob = await response.blob();
  const cd = response.headers.get('Content-Disposition') || '';
  let filename = fallbackName;
  const star = cd.match(/filename\*=UTF-8''([^;]+)/i);
  const plain = cd.match(/filename="?([^";]+)"?/i);
  if (star) {
    try { filename = decodeURIComponent(star[1]); } catch { filename = star[1]; }
  } else if (plain) {
    filename = plain[1];
  }
  const objectUrl = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = objectUrl;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(objectUrl);
}

export const messageApi = {
  createMessage: (message) => request('/messages', {
    method: 'POST',
    body: JSON.stringify(message),
  }),

  getMessagesByGroupId: (groupId, selfQq) => {
    const params = new URLSearchParams();
    if (selfQq) params.append('selfQq', selfQq);
    const qs = params.toString();
    return request(`/messages/group/${groupId}${qs ? '?' + qs : ''}`);
  },

  getMessagesByGroupIdPaged: (groupId, page = 0, size = 50, selfQq) => {
    const params = new URLSearchParams();
    params.append('page', page);
    params.append('size', size);
    if (selfQq) params.append('selfQq', selfQq);
    return request(`/messages/group/${groupId}/paged?${params.toString()}`);
  },

  getMessagesSince: (groupId, afterId, selfQq) => {
    const params = new URLSearchParams();
    params.append('afterId', afterId);
    if (selfQq) params.append('selfQq', selfQq);
    return request(`/messages/group/${groupId}/since?${params.toString()}`);
  },

  getGroupMembers: (groupId) =>
    request(`/messages/group/${groupId}/members`),

  processAllMessages: () => request('/messages/process', { method: 'POST' }),

  uploadFile: (file, fileType) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('fileType', fileType);
    return uploadRequest('/messages/upload', formData);
  },

  sendMessageWithFile: (params) => {
    const formData = new FormData();
    formData.append('groupId', params.groupId);
    formData.append('userQq', params.userQq);
    formData.append('userNickname', params.userNickname);
    formData.append('content', params.content || '');
    formData.append('messageType', params.messageType);
    if (params.file) formData.append('file', params.file);
    return uploadRequest('/messages/send-with-file', formData);
  },

  getFileInfo: (fileId) => request(`/messages/file/${fileId}`),
  deleteFile: (fileId) => request(`/messages/file/${fileId}`, { method: 'DELETE' }),
  deleteMessage: (messageId) => request(`/messages/${messageId}`, { method: 'DELETE' }),
  deleteMessagesBatch: (messageIds, deleteMedia) => request('/messages/delete-batch', {
    method: 'POST',
    body: JSON.stringify({ messageIds, deleteMedia: !!deleteMedia }),
  }),
  deleteMessagesByTypes: (groupId, types, deleteMedia) =>
    request(`/messages/group/${encodeURIComponent(groupId)}/delete-by-types`, {
      method: 'POST',
      body: JSON.stringify({ types, deleteMedia: !!deleteMedia }),
    }),
  deleteGroupConversation: (groupId, ownerQq) =>
    request(`/messages/group/${encodeURIComponent(groupId)}/delete-conversation`, {
      method: 'POST',
      body: JSON.stringify({ ownerQq }),
    }),
  purgeMedia: (types) => request('/messages/purge-media', {
    method: 'POST',
    body: JSON.stringify({ types }),
  }),
  manualArchive: (daysBefore = 90) => request(`/messages/archive?daysBefore=${daysBefore}`, { method: 'POST' }),

  getMediaFiles: (type, page = 0, size = 20) =>
    request(`/messages/media-files?type=${encodeURIComponent(type || 'ALL')}&page=${page}&size=${size}`),

  deleteMediaFiles: (ids) => request('/messages/delete-media-files', {
    method: 'POST',
    body: JSON.stringify({ ids }),
  }),

  getRecentGroups: (sinceTime) => {
    // sinceTime 参数已弃用，现由后端根据 group_read_state 计算未读
    return request('/messages/recent-groups');
  },

  markGroupAsRead: (groupId) => request(`/messages/read/${encodeURIComponent(groupId)}`, { method: 'POST' }),
  markAllAsRead: () => request('/messages/read-all', { method: 'POST' }),

  // 群类型管理（走 /api/groups/**）
  getGroupType: (groupId) => request(`/groups/${encodeURIComponent(groupId)}/type`),
  setGroupType: (groupId, groupType) =>
    request(`/groups/${encodeURIComponent(groupId)}/type`, {
      method: 'PUT',
      body: JSON.stringify({ groupType }),
    }),
  recognizeGroupType: (groupId, force = false) => {
    const qs = force ? '?forceRefresh=true' : '';
    return request(`/groups/${encodeURIComponent(groupId)}/recognize-type${qs}`, { method: 'POST' });
  },
};

// 系统控制类接口：失败时仅抛错给调用方显示 toast，不触发全局 401 登出/清存储/跳转
const systemRequest = (endpoint, options = {}) =>
  request(endpoint, { ...options, skipUnauthorizedHandling: true });

export const systemApi = {
  startAllComponents: () => systemRequest('/system/start-all', { method: 'POST' }),
  stopAllComponents: () => systemRequest('/system/stop-all', { method: 'POST' }),
  startAstrBot: () => systemRequest('/system/start-astrbot', { method: 'POST' }),
  stopAstrBot: () => systemRequest('/system/stop-astrbot', { method: 'POST' }),
  startNapCat: () => systemRequest('/system/start-napcat', { method: 'POST' }),
  stopNapCat: () => systemRequest('/system/stop-napcat', { method: 'POST' }),
  autoConfigureNapCat: () => systemRequest('/system/napcat/auto-configure', { method: 'POST' }),
  startGptSovits: () => systemRequest('/system/start-gptsovits', { method: 'POST' }),
  stopGptSovits: () => systemRequest('/system/stop-gptsovits', { method: 'POST' }),
  generateVoice: (text, character = null) => systemRequest('/system/tts', {
    method: 'POST',
    body: JSON.stringify({ text, character }),
  }),
  getTtsCharacters: () => systemRequest('/system/tts/characters', { method: 'GET' }),
  switchTtsCharacter: (character) => systemRequest('/system/tts/switch-character', {
    method: 'POST',
    body: JSON.stringify({ character }),
  }),
  convertVoice: (path) => systemRequest('/system/convert-voice', {
    method: 'POST',
    body: JSON.stringify({ path }),
  }),
  getComponentStatus: () => systemRequest('/system/component-status'),
  getNapCatWebUiUrl: () => systemRequest('/system/napcat/webui-url'),
  getNapCatQrCode: () => systemRequest('/system/napcat/qrcode'),
  getNapCatQrCodePath: () => systemRequest('/system/napcat/qrcode-path'),
  checkNapCatLoginStatus: () => systemRequest('/system/napcat/login-status'),
  healthCheck: () => systemRequest('/system/health'),
  getDiskUsage: () => systemRequest('/system/disk-usage'),
};

export const astrBotApi = {
  sendMessage: (params) => request('/astrbot/send', {
    method: 'POST',
    body: JSON.stringify(params),
  }),
  analyzeGroup: (groupId, messageCount = 50, type = 'summary') => request('/astrbot/analyze', {
    method: 'POST',
    body: JSON.stringify({ groupId, messageCount, type }),
  }),
  // 分析前端选中的消息（按 analysisType 定制模板（6 种融入导向分析）
  analyzeSelected: (params) => request('/astrbot/analyze-selected', {
    method: 'POST',
    body: JSON.stringify(params),
  }),
  getStatus: () => request('/astrbot/status'),
  getConversations: (params = {}) => {
    const queryParams = new URLSearchParams();
    if (params.groupId) queryParams.append('groupId', params.groupId);
    if (params.userQq) queryParams.append('userQq', params.userQq);
    if (params.page) queryParams.append('page', params.page);
    if (params.size) queryParams.append('size', params.size);
    const query = queryParams.toString();
    return request(`/astrbot/conversations${query ? '?' + query : ''}`);
  },
  getConversation: (conversationId) => request(`/astrbot/conversations/${conversationId}`),
  getConversationMessages: (conversationId, page = 0, size = 50) =>
    request(`/astrbot/conversations/${conversationId}/messages?page=${page}&size=${size}`),
  createConversation: (params) => request('/astrbot/conversations', {
    method: 'POST',
    body: JSON.stringify(params),
  }),
  updateConversationTitle: (conversationId, title) =>
    request(`/astrbot/conversations/${conversationId}/title`, {
      method: 'PUT',
      body: JSON.stringify({ title }),
    }),
  archiveConversation: (conversationId) =>
    request(`/astrbot/conversations/${conversationId}/archive`, { method: 'POST' }),
  deleteConversation: (conversationId) =>
    request(`/astrbot/conversations/${conversationId}`, { method: 'DELETE' }),
  getConversationStats: (conversationId) =>
    request(`/astrbot/conversations/${conversationId}/stats`),
  // 模型管理
  getModels: () => request('/astrbot/models'),
  setModel: (model) => request('/astrbot/set-model', {
    method: 'POST',
    body: JSON.stringify({ model }),
  }),
};

export const userApi = {
  getSettings: (userId) => request(`/user/settings?userId=${userId}`),
  saveSettings: (params) => request('/user/settings', {
    method: 'POST',
    body: JSON.stringify(params),
  }),
  getProfile: () => request('/user/profile'),
  updateProfile: (params) => request('/user/profile', {
    method: 'PUT',
    body: JSON.stringify(params),
  }),
  uploadAvatar: (file, type = 'user') => {
    const formData = new FormData();
    formData.append('file', file);
    // 用户头像走 /api/user/avatar（普通用户可用）；群头像等管理员场景走 /api/avatar/upload
    if (type === 'group' || type === 'bot') {
      formData.append('type', type);
      return uploadRequest('/avatar/upload', formData);
    }
    return uploadRequest('/user/avatar', formData);
  },
  getQqBindings: () => request('/user/qq-bindings'),
  sendQqBindingCode: (qqNumber) => request('/user/qq-bindings/send-code', {
    method: 'POST',
    body: JSON.stringify({ qqNumber }),
  }),
  bindQq: (params) => request('/user/qq-bindings', {
    method: 'POST',
    body: JSON.stringify(params),
  }),
  unbindQq: (bindingId) => request(`/user/qq-bindings/${bindingId}`, { method: 'DELETE' }),
  setDefaultQq: (bindingId) => request(`/user/qq-bindings/${bindingId}/default`, { method: 'PUT' }),
  getDefaultQq: () => request('/user/qq-bindings/default'),
};

export const dashboardApi = {
  getStats: () => request('/dashboard/stats'),
  getMessageTrend: (days = 7, interval = 'day') => request(`/dashboard/message-trend?days=${days}&interval=${interval}`),
  getGroupRanking: () => request('/dashboard/group-ranking'),
  getQQRanking: () => request('/dashboard/qq-ranking'),
  getMessageTypeDistribution: () => request('/dashboard/message-type-distribution'),
  getHourlyDistribution: () => request('/dashboard/hourly-distribution'),
  getAiTrend: () => request('/dashboard/ai-trend'),
};

export const userDashboardApi = {
  getStats: () => request('/user/dashboard/stats'),
  getMessageTrend: (days, interval) => request(`/user/dashboard/message-trend?days=${days}&interval=${interval}`),
  getGroupRanking: () => request('/user/dashboard/group-ranking'),
  getMessageTypeDistribution: () => request('/user/dashboard/message-type-distribution'),
  getAiTrend: () => request('/user/dashboard/ai-trend'),
};

export const creditsApi = {
  /** GET /api/credits/balance → { balance, totalEarned, totalSpent, subscriptionTier, subscriptionExpiresAt, ... } */
  getBalance: () => request('/credits/balance'),
  /** POST /api/credits/sign-in → { points, streakDays, newBalance } */
  signIn: () => request('/credits/sign-in', { method: 'POST' }),
  /** @deprecated 使用 signIn() */
  doSignIn: () => request('/credits/sign-in', { method: 'POST' }),
  /** GET /api/credits/sign-in/status?range= → { todayDone, streakDays, calendar } */
  getSignInStatus: (range = 7) =>
    request(`/credits/sign-in/status?range=${encodeURIComponent(range)}`),
  /** GET /api/credits/rewards → 奖励列表 */
  getRewards: () => request('/credits/rewards'),
  /**
   * GET /api/credits/transactions
   * 支持 { type, direction, start, end, page, size, relatedId }
   * 兼容旧字段 startDate/endDate
   */
  getTransactions: (params = {}) => {
    const qs = new URLSearchParams();
    if (params.page !== undefined) qs.append('page', params.page);
    if (params.size !== undefined) qs.append('size', params.size);
    if (params.direction) qs.append('direction', params.direction);
    if (params.type) qs.append('type', params.type);
    const start = params.start || params.startDate;
    const end = params.end || params.endDate;
    if (start) qs.append('start', start);
    if (end) qs.append('end', end);
    if (params.relatedId) qs.append('relatedId', params.relatedId);
    const query = qs.toString();
    return request(`/credits/transactions${query ? '?' + query : ''}`);
  },
  /** GET /api/credits/trend?days= → { series: [{ date, earned, spent, net }] } */
  getTrend: (days = 7) => request(`/credits/trend?days=${days}`),
};

export const personaApi = {
  list: () => request('/persona/list'),
  get: (id) => request(`/persona/${id}`),
  create: (params) => request('/persona', { method: 'POST', body: JSON.stringify(params) }),
  update: (id, params) => request(`/persona/${id}`, { method: 'PUT', body: JSON.stringify(params) }),
  delete: (id) => request(`/persona/${id}`, { method: 'DELETE' }),
  setDefault: (personaId) => request('/persona/set-default', {
    method: 'POST',
    body: JSON.stringify({ personaId }),
  }),
  getDefault: () => request('/persona/default'),
};

export const authApi = {
  login: (username, password) => request('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  }),
  register: (username, password, nickname) => request('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ username, password, nickname }),
  }),
  getCurrentUser: () => request('/auth/me'),
  updateProfile: (nickname, email) => request('/auth/profile', {
    method: 'PUT',
    body: JSON.stringify({ nickname, email }),
  }),
  changePassword: (oldPassword, newPassword) => request('/auth/change-password', {
    method: 'POST',
    body: JSON.stringify({ oldPassword, newPassword }),
  }),
  logout: () => request('/auth/logout', {
    method: 'POST',
    body: JSON.stringify({}),
  }),
};

export const adminApi = {
  getUsers: (params = {}) => {
    const queryParams = new URLSearchParams();
    if (params.page !== undefined) queryParams.append('page', params.page);
    if (params.size !== undefined) queryParams.append('size', params.size);
    if (params.keyword) queryParams.append('keyword', params.keyword);
    const query = queryParams.toString();
    return request(`/admin/users${query ? '?' + query : ''}`);
  },
  updateUserRole: (id, role) => request(`/admin/users/${id}/role`, {
    method: 'PUT',
    body: JSON.stringify({ role }),
  }),
  updateUserActive: (id, active) => request(`/admin/users/${id}/active`, {
    method: 'PUT',
    body: JSON.stringify({ active }),
  }),
  deleteUser: (id) => request(`/admin/users/${id}`, { method: 'DELETE' }),
  getConfig: () => request('/admin/config'),
  updateConfig: (data) => request('/admin/config', {
    method: 'PUT',
    body: JSON.stringify(data),
  }),
  triggerBackup: () => request('/admin/backup', { method: 'POST' }),
  getBackupList: () => request('/admin/backup/list'),
  downloadBackupUrl: (fileName) => {
    return `${API_BASE_URL}/admin/backup/${encodeURIComponent(fileName)}/download`;
  },
  triggerArchive: (days) => request(`/admin/archive?days=${days}`, { method: 'POST' }),
  getLogs: (params = {}) => {
    const query = new URLSearchParams();
    if (params.level) query.append('level', params.level);
    if (params.page != null) query.append('page', params.page);
    if (params.size != null) query.append('size', params.size);
    const qs = query.toString();
    return request(`/admin/logs${qs ? '?' + qs : ''}`);
  },
  getAuditLogs: (params = {}) => {
    const query = new URLSearchParams();
    if (params.keyword) query.append('keyword', params.keyword);
    if (params.action) query.append('action', params.action);
    if (params.page != null) query.append('page', params.page);
    if (params.size != null) query.append('size', params.size);
    const qs = query.toString();
    return request(`/admin/audit-logs${qs ? '?' + qs : ''}`);
  },
};

export const subscriptionApi = {
  /** GET /api/subscriptions/plans → [{ planCode, planName, priceYuan, pointsGranted, durationDays, tier, benefits }] */
  getPlans: () => request('/subscriptions/plans'),
  /** @deprecated 后端未提供 /current，改用 creditsApi.getBalance 获取 tier */
  getCurrentPlan: () => request('/subscriptions/current'),
  /**
   * POST /api/subscriptions/purchase
   * 入参 { planCode, paymentMethod='MANUAL' }
   * 返回 { orderNo, status, newBalance, expiresAt, pointsGranted }
   */
  purchase: (params) => {
    const body = typeof params === 'string'
      ? { planCode: params, paymentMethod: 'MANUAL' }
      : { planCode: params?.planCode, paymentMethod: params?.paymentMethod || 'MANUAL' };
    return request('/subscriptions/purchase', {
      method: 'POST',
      body: JSON.stringify(body),
    });
  },
  /** GET /api/subscriptions/orders → { content, totalElements, totalPages, ... } */
  getMyOrders: (params = {}) => {
    const query = new URLSearchParams();
    if (params.page !== undefined) query.append('page', params.page);
    if (params.size !== undefined) query.append('size', params.size);
    if (params.status) query.append('status', params.status);
    const qs = query.toString();
    return request(`/subscriptions/orders${qs ? '?' + qs : ''}`);
  },
  /** @deprecated 使用 getMyOrders() */
  getOrders: (params = {}) => {
    const query = new URLSearchParams();
    if (params.page !== undefined) query.append('page', params.page);
    if (params.size !== undefined) query.append('size', params.size);
    if (params.status) query.append('status', params.status);
    const qs = query.toString();
    return request(`/subscriptions/orders${qs ? '?' + qs : ''}`);
  },
  /** GET /api/subscriptions/orders/{orderNo} → 订单详情 + relatedTransactions */
  getOrderDetail: (orderNo) => request(`/subscriptions/orders/${orderNo}`),
  /** POST /api/subscriptions/orders/{orderNo}/cancel，body 可选 { reason } */
  cancelOrder: (orderNo, reason) => request(`/subscriptions/orders/${orderNo}/cancel`, {
    method: 'POST',
    body: JSON.stringify(reason ? { reason } : {}),
  }),
  /** POST /api/subscriptions/orders/{orderNo}/refund-request，body { reason } */
  /** POST /api/subscriptions/orders/{orderNo}/dispute，body { reason } — 用户申诉 */
  disputeOrder: (orderNo, reason) => request(`/subscriptions/orders/${orderNo}/dispute`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    }),
  refundRequest: (orderNo, reason) => request(`/subscriptions/orders/${orderNo}/refund-request`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  }),
  /** @deprecated 使用 refundRequest()，旧 URL /refund 已废弃 */
  requestRefund: (orderNo, reason) => request(`/subscriptions/orders/${orderNo}/refund-request`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  }),
  /** 关联流水：后端在 orderDetail 中一并返回 relatedTransactions，这里做兼容封装 */
  getRelatedTransactions: (orderNo) =>
    request(`/subscriptions/orders/${orderNo}`).then((data) => {
      if (data && Array.isArray(data.relatedTransactions)) return data.relatedTransactions;
      return [];
    }).catch(() => []),
};

/** 任务规范命名（带 s），与 subscriptionApi 同义 */
export const subscriptionsApi = subscriptionApi;

/** 管理员积分管理 API — 走 /api/credits/admin/* 前缀（后端 SecurityConfig hasRole ADMIN） */
export const adminCreditsApi = {
  /** GET /api/credits/admin/rule → CreditRule 完整规则 */
  getRule: () => request('/credits/admin/rule'),
  /** PUT /api/credits/admin/rule → 保存规则（立即生效） */
  updateRule: (data) => request('/credits/admin/rule', {
    method: 'PUT',
    body: JSON.stringify(data),
  }),
  /** GET /api/credits/admin/user-credits?keyword=&page=&size= → 分页用户积分视图 */
  getUserCredits: (params = {}) => {
    const qs = new URLSearchParams();
    if (params.keyword) qs.append('keyword', params.keyword);
    if (params.page !== undefined) qs.append('page', params.page);
    if (params.size !== undefined) qs.append('size', params.size);
    const query = qs.toString();
    return request(`/credits/admin/user-credits${query ? '?' + query : ''}`);
  },
  /** POST /api/credits/admin/adjust { userId, amount, reason } → 调账结果 */
  adjust: ({ userId, amount, reason }) => request('/credits/admin/adjust', {
    method: 'POST',
    body: JSON.stringify({ userId, amount, reason }),
  }),
  /**
   * GET /api/credits/admin/transactions
   * 支持 { userId, type, direction, start, end, min, max, page, size, relatedId, export: 1 }
   * 当 export 为真时触发文件下载
   */
  getTransactions: (params = {}) => {
    const qs = new URLSearchParams();
    if (params.userId !== undefined && params.userId !== null && params.userId !== '')
      qs.append('userId', params.userId);
    if (params.type) qs.append('type', params.type);
    if (params.direction) qs.append('direction', params.direction);
    if (params.start) qs.append('start', params.start);
    if (params.end) qs.append('end', params.end);
    if (params.min !== undefined && params.min !== null && params.min !== '')
      qs.append('min', params.min);
    if (params.max !== undefined && params.max !== null && params.max !== '')
      qs.append('max', params.max);
    if (params.page !== undefined) qs.append('page', params.page);
    if (params.size !== undefined) qs.append('size', params.size);
    if (params.relatedId) qs.append('relatedId', params.relatedId);
    if (params.export) qs.append('export', '1');
    const query = qs.toString();
    const endpoint = `/credits/admin/transactions${query ? '?' + query : ''}`;
    if (params.export) {
      return downloadWithAuth(endpoint, `credit-transactions-${new Date().toISOString().slice(0, 10)}.json`);
    }
    return request(endpoint);
  },
};

/** 管理员订单管理 API — 走 /api/credits/admin/orders/* 前缀 */
export const adminOrdersApi = {
  /** GET /api/credits/admin/orders?orderNo=&keyword=&status=&start=&end=&minPrice=&maxPrice=&page=&size= */
  list: (params = {}) => {
    const qs = new URLSearchParams();
    if (params.orderNo) qs.append('orderNo', params.orderNo);
    if (params.keyword) qs.append('keyword', params.keyword);
    if (params.status) qs.append('status', params.status);
    if (params.start) qs.append('start', params.start);
    if (params.end) qs.append('end', params.end);
    if (params.minPrice !== undefined && params.minPrice !== null && params.minPrice !== '')
      qs.append('minPrice', params.minPrice);
    if (params.maxPrice !== undefined && params.maxPrice !== null && params.maxPrice !== '')
      qs.append('maxPrice', params.maxPrice);
    if (params.page !== undefined) qs.append('page', params.page);
    if (params.size !== undefined) qs.append('size', params.size);
    const query = qs.toString();
    return request(`/credits/admin/orders${query ? '?' + query : ''}`);
  },
  /** GET /api/credits/admin/orders/export?... → 下载 JSON 文件 */
  export: (filters = {}) => {
    const qs = new URLSearchParams();
    if (filters.orderNo) qs.append('orderNo', filters.orderNo);
    if (filters.keyword) qs.append('keyword', filters.keyword);
    if (filters.status) qs.append('status', filters.status);
    if (filters.start) qs.append('start', filters.start);
    if (filters.end) qs.append('end', filters.end);
    if (filters.minPrice !== undefined && filters.minPrice !== null && filters.minPrice !== '')
      qs.append('minPrice', filters.minPrice);
    if (filters.maxPrice !== undefined && filters.maxPrice !== null && filters.maxPrice !== '')
      qs.append('maxPrice', filters.maxPrice);
    const query = qs.toString();
    const endpoint = `/credits/admin/orders/export${query ? '?' + query : ''}`;
    return downloadWithAuth(endpoint, `subscription-orders-${new Date().toISOString().slice(0, 10)}.json`);
  },
  /** GET /api/credits/admin/orders/{orderNo} → 订单详情 + relatedTransactions */
  detail: (orderNo) => request(`/credits/admin/orders/${encodeURIComponent(orderNo)}`),
  /** POST /api/credits/admin/orders/manual-create → 直接创建 PAID 订单 */
  manualCreate: ({ userId, planCode, priceCents, pointsGranted, durationDays, orderNo, remark }) =>
    request('/credits/admin/orders/manual-create', {
      method: 'POST',
      body: JSON.stringify({
        userId, planCode, priceCents, pointsGranted, durationDays, orderNo, remark,
      }),
    }),
  /** POST /api/credits/admin/orders/{orderNo}/cancel { reason } */
  cancel: (orderNo, reason) => request(`/credits/admin/orders/${encodeURIComponent(orderNo)}/cancel`, {
    method: 'POST',
    body: JSON.stringify(reason ? { reason } : {}),
  }),
  /** POST /api/credits/admin/orders/{orderNo}/resolve-dispute { agree, reason } — 处理纠纷 */
  resolveDispute: (orderNo, { agree, reason } = {}) =>
    request(`/credits/admin/orders/${encodeURIComponent(orderNo)}/resolve-dispute`, {
      method: "POST",
      body: JSON.stringify({ agree, reason }),
    }),
  /** POST /api/credits/admin/orders/{orderNo}/refund { reason, refundRatio } */
  refund: (orderNo, { reason, refundRatio } = {}) =>
    request(`/credits/admin/orders/${encodeURIComponent(orderNo)}/refund`, {
      method: 'POST',
      body: JSON.stringify({
        reason: reason || '管理员强制退款',
        refundRatio: typeof refundRatio === 'number' ? refundRatio : 1.0,
      }),
    }),
  // POST /api/credits/admin/orders/{orderNo}/approve-refund
  approveRefund: (orderNo, reason = '') => request(`/credits/admin/orders/${encodeURIComponent(orderNo)}/approve-refund`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  }),
  // POST /api/credits/admin/orders/{orderNo}/reject-refund
  rejectRefund: (orderNo, reason = '') => request(`/credits/admin/orders/${encodeURIComponent(orderNo)}/reject-refund`, {
    method: 'POST',
    body: JSON.stringify({ reason }),
  }),
  // POST /api/credits/admin/orders/{orderNo}/approve-payment
  approvePayment: (orderNo) => request(`/credits/admin/orders/${encodeURIComponent(orderNo)}/approve-payment`, {
    method: 'POST',
  }),
  // GET /api/credits/admin/orders/pending-count
  getPendingCount: () => request('/credits/admin/orders/pending-count'),
};

export async function logout() {
  const res = await request('/auth/logout', {
    method: 'POST',
    body: JSON.stringify({}),
  });
  return res;
}

export const detectFileType = (file) => {
  const mimeType = file.type;
  if (mimeType.startsWith('image/')) return 'IMAGE';
  if (mimeType.startsWith('video/')) return 'VIDEO';
  if (mimeType.startsWith('audio/')) return 'AUDIO';
  return 'FILE';
};

export const formatFileSize = (bytes) => {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(2))} ${sizes[i]}`;
};

export { handleUnauthorized };
