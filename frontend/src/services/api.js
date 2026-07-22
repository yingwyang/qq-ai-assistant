// API 服务层 — 统一 fetch 封装
const API_BASE_URL = '/api';

const getToken = () => localStorage.getItem('auth_token');

function handleUnauthorized() {
  localStorage.removeItem('auth_token');
  localStorage.removeItem('isLoggedIn');
  localStorage.removeItem('user_role');
}

async function parseResponse(response) {
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

  // 401/403 统一处理为未授权
  if (response.status === 401 || response.status === 403) {
    handleUnauthorized();
    const msg = parsed?.error || parsed?.message || '登录已过期，请重新登录';
    throw new Error(msg);
  }

  // 其他错误状态码
  if (!response.ok) {
    const msg = parsed?.error || parsed?.message || `HTTP error! status: ${response.status}`;
    throw new Error(msg);
  }

  // 空响应体
  if (!parsed) return null;

  // 统一返回 data 字段；无 data 字段时返回整个响应体
  return parsed.data !== undefined ? parsed.data : parsed;
}

async function request(endpoint, options = {}) {
  const url = `${API_BASE_URL}${endpoint}`;
  const token = getToken();

  const headers = {
    'Content-Type': 'application/json; charset=UTF-8',
    'Accept': 'application/json; charset=UTF-8',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...options.headers,
  };

  try {
    const response = await fetch(url, { ...options, headers });
    return await parseResponse(response);
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
      console.error('API request failed:', error);
    }
    throw error;
  }
}

async function uploadRequest(endpoint, formData) {
  const url = `${API_BASE_URL}${endpoint}`;
  const token = getToken();

  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      body: formData,
    });
    return await parseResponse(response);
  } catch (error) {
    console.error('Upload request failed:', error);
    if (error.name === 'TypeError' && error.message === 'Failed to fetch') {
      throw new Error('无法连接到服务器，请检查后端服务是否运行');
    }
    throw error;
  }
}

export const messageApi = {
  createMessage: (message) => request('/messages', {
    method: 'POST',
    body: JSON.stringify(message),
  }),

  getMessagesByGroupId: (groupId) => request(`/messages/group/${groupId}`),

  getMessagesByGroupIdPaged: (groupId, page = 0, size = 50) =>
    request(`/messages/group/${groupId}/paged?page=${page}&size=${size}`),

  getMessagesSince: (groupId, afterId) =>
    request(`/messages/group/${groupId}/since?afterId=${afterId}`),

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
};

export const systemApi = {
  startAllComponents: () => request('/system/start-all', { method: 'POST' }),
  stopAllComponents: () => request('/system/stop-all', { method: 'POST' }),
  startAstrBot: () => request('/system/start-astrbot', { method: 'POST' }),
  stopAstrBot: () => request('/system/stop-astrbot', { method: 'POST' }),
  startNapCat: (autoLogin = false) => request('/system/start-napcat', {
    method: 'POST',
    body: JSON.stringify({ autoLogin }),
  }),
  stopNapCat: () => request('/system/stop-napcat', { method: 'POST' }),
  startGptSovits: () => request('/system/start-gptsovits', { method: 'POST' }),
  stopGptSovits: () => request('/system/stop-gptsovits', { method: 'POST' }),
  generateVoice: (text) => request('/system/tts', {
    method: 'POST',
    body: JSON.stringify({ text }),
  }),
  convertVoice: (path) => request('/system/convert-voice', {
    method: 'POST',
    body: JSON.stringify({ path }),
  }),
  getComponentStatus: () => request('/system/component-status'),
  getNapCatWebUiUrl: () => request('/system/napcat/webui-url'),
  getNapCatQrCode: () => request('/system/napcat/qrcode'),
  getNapCatQrCodePath: () => request('/system/napcat/qrcode-path'),
  checkNapCatLoginStatus: () => request('/system/napcat/login-status'),
  healthCheck: () => request('/system/health'),
  getDiskUsage: () => request('/system/disk-usage'),
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
    formData.append('type', type);
    return uploadRequest('/avatar/upload', formData);
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
    const token = getToken();
    return `${API_BASE_URL}/admin/backup/${encodeURIComponent(fileName)}/download${token ? '?token=' + token : ''}`;
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

export { getToken };
