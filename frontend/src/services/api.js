// API 服务层 — 统一 fetch 封装
const API_BASE_URL = '/api';

const getToken = () => localStorage.getItem('auth_token');

function handleUnauthorized() {
  localStorage.removeItem('auth_token');
  localStorage.removeItem('isLoggedIn');
  window.location.reload();
  throw new Error('登录已过期，请重新登录');
}

async function parseResponse(response) {
  if (response.status === 401) {
    handleUnauthorized();
  }

  const responseText = await response.text();

  if (!response.ok) {
    let errorData;
    try {
      errorData = JSON.parse(responseText);
    } catch {
      errorData = { message: responseText };
    }
    throw new Error(errorData.error || errorData.message || `HTTP error! status: ${response.status}`);
  }

  if (!responseText) return null;

  try {
    return JSON.parse(responseText);
  } catch (parseError) {
    console.error('JSON parse error. Response text:', responseText.substring(0, 200));
    throw new Error(`服务器返回了无效的 JSON 数据: ${parseError.message}`);
  }
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
  manualArchive: (daysBefore = 90) => request(`/messages/archive?daysBefore=${daysBefore}`, { method: 'POST' }),
  getRecentGroups: () => request('/messages/recent-groups'),
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
  getComponentStatus: () => request('/system/component-status'),
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
  getSettings: (userQq) => request(`/user/settings?userQq=${userQq}`),
  saveSettings: (params) => request('/user/settings', {
    method: 'POST',
    body: JSON.stringify(params),
  }),
  getProfile: () => request('/user/profile'),
  updateProfile: (params) => request('/user/profile', {
    method: 'PUT',
    body: JSON.stringify(params),
  }),
  uploadAvatar: (file) => {
    const formData = new FormData();
    formData.append('file', file);
    return uploadRequest('/user/avatar', formData);
  },
  getQqBindings: () => request('/user/qq-bindings'),
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
  changePassword: (oldPassword, newPassword) => request('/auth/change-password', {
    method: 'POST',
    body: JSON.stringify({ oldPassword, newPassword }),
  }),
};

export const adminApi = {
  getUsers: () => request('/admin/users'),
  updateUserRole: (id, role) => request(`/admin/users/${id}/role`, {
    method: 'PUT',
    body: JSON.stringify({ role }),
  }),
  updateUserActive: (id, active) => request(`/admin/users/${id}/active`, {
    method: 'PUT',
    body: JSON.stringify({ active }),
  }),
  deleteUser: (id) => request(`/admin/users/${id}`, { method: 'DELETE' }),
};

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
