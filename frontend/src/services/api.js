// API服务配置
const API_BASE_URL = '/api';

// 获取存储的token
const getToken = () => localStorage.getItem('auth_token');

// 通用请求方法
async function request(endpoint, options = {}) {
  const url = `${API_BASE_URL}${endpoint}`;
  const token = getToken();
  
  const defaultOptions = {
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
      'Accept': 'application/json; charset=UTF-8',
      ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
    },
  };
  
  const mergedOptions = {
    ...defaultOptions,
    ...options,
    headers: {
      ...defaultOptions.headers,
      ...options.headers,
    },
  };

  try {
    const response = await fetch(url, mergedOptions);
    
    // 处理401未授权错误
    if (response.status === 401) {
      localStorage.removeItem('auth_token');
      localStorage.removeItem('isLoggedIn');
      window.location.reload();
      throw new Error('登录已过期，请重新登录');
    }
    
    // 获取响应文本，用于调试
    const responseText = await response.text();
    
    if (!response.ok) {
      // 尝试解析为 JSON，失败则使用文本
      let errorData;
      try {
        errorData = JSON.parse(responseText);
      } catch {
        errorData = { message: responseText };
      }
      throw new Error(errorData.error || errorData.message || `HTTP error! status: ${response.status}`);
    }
    
    // 尝试解析 JSON
    try {
      return JSON.parse(responseText);
    } catch (parseError) {
      console.error('JSON parse error. Response text:', responseText.substring(0, 200));
      throw new Error(`服务器返回了无效的 JSON 数据: ${parseError.message}`);
    }
  } catch (error) {
    console.error('API request failed:', error);
    throw error;
  }
}

// 文件上传请求（multipart/form-data）
async function uploadRequest(endpoint, formData) {
  const url = `${API_BASE_URL}${endpoint}`;
  const token = getToken();

  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: token ? { 'Authorization': `Bearer ${token}` } : {},
      body: formData,
    });

    // 处理401未授权错误
    if (response.status === 401) {
      localStorage.removeItem('auth_token');
      localStorage.removeItem('isLoggedIn');
      window.location.reload();
      throw new Error('登录已过期，请重新登录');
    }

    // 获取响应文本
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

    try {
      return JSON.parse(responseText);
    } catch (parseError) {
      console.error('JSON parse error. Response text:', responseText.substring(0, 200));
      throw new Error(`服务器返回了无效的 JSON 数据: ${parseError.message}`);
    }
  } catch (error) {
    console.error('Upload request failed:', error);
    if (error.name === 'TypeError' && error.message === 'Failed to fetch') {
      throw new Error('无法连接到服务器，请检查后端服务是否运行');
    }
    throw error;
  }
}

// 消息相关API
export const messageApi = {
  // 创建消息
  createMessage: (message) => request('/messages', {
    method: 'POST',
    body: JSON.stringify(message),
  }),
  
  // 获取群聊消息
  getMessagesByGroupId: (groupId) => request(`/messages/group/${groupId}`),
  
  // 分页获取群聊消息
  getMessagesByGroupIdPaged: (groupId, page = 0, size = 50) => 
    request(`/messages/group/${groupId}/paged?page=${page}&size=${size}`),
  
  // 处理所有未处理消息
  processAllMessages: () => request('/messages/process', {
    method: 'POST',
  }),
  
  // 上传文件
  uploadFile: (file, fileType) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('fileType', fileType);
    return uploadRequest('/messages/upload', formData);
  },
  
  // 发送带文件的消息
  sendMessageWithFile: (params) => {
    const formData = new FormData();
    formData.append('groupId', params.groupId);
    formData.append('userQq', params.userQq);
    formData.append('userNickname', params.userNickname);
    formData.append('content', params.content || '');
    formData.append('messageType', params.messageType);
    if (params.file) {
      formData.append('file', params.file);
    }
    return uploadRequest('/messages/send-with-file', formData);
  },
  
  // 获取文件信息
  getFileInfo: (fileId) => request(`/messages/file/${fileId}`),
  
  // 删除文件
  deleteFile: (fileId) => request(`/messages/file/${fileId}`, {
    method: 'DELETE',
  }),
  
  // 手动触发归档
  manualArchive: (daysBefore = 90) => request(`/messages/archive?daysBefore=${daysBefore}`, {
    method: 'POST',
  }),
  
  // 获取最近对话的群聊（后端从JWT获取用户ID，不需要传递参数）
  getRecentGroups: () => {
    return request('/messages/recent-groups');
  },
};

// 系统相关API
export const systemApi = {
  startAllComponents: () => request('/system/start-all', {
    method: 'POST',
  }),
  stopAllComponents: () => request('/system/stop-all', {
    method: 'POST',
  }),
  // 分别启动各个组件
  startAstrBot: () => request('/system/start-astrbot', {
    method: 'POST',
  }),
  stopAstrBot: () => request('/system/stop-astrbot', {
    method: 'POST',
  }),
  startNapCat: (autoLogin = false) => request('/system/start-napcat', {
    method: 'POST',
    body: JSON.stringify({ autoLogin }),
    headers: {
      'Content-Type': 'application/json',
    },
  }),
  stopNapCat: () => request('/system/stop-napcat', {
    method: 'POST',
  }),
  startGptSovits: () => request('/system/start-gptsovits', {
    method: 'POST',
  }),
  stopGptSovits: () => request('/system/stop-gptsovits', {
    method: 'POST',
  }),
  getComponentStatus: () => request('/system/component-status'),
  getNapCatQrCode: () => request('/system/napcat/qrcode'),
  getNapCatQrCodePath: () => request('/system/napcat/qrcode-path'),
  checkNapCatLoginStatus: () => request('/system/napcat/login-status'),
  healthCheck: () => request('/system/health'),
};

// AstrBot 相关API
export const astrBotApi = {
  // 发送消息给 AstrBot（支持对话存储）
  sendMessage: (params) => request('/astrbot/send', {
    method: 'POST',
    body: JSON.stringify(params),
  }),

  // 让 AstrBot 分析群聊消息
  analyzeGroup: (groupId, messageCount = 50, type = 'summary') => request('/astrbot/analyze', {
    method: 'POST',
    body: JSON.stringify({ groupId, messageCount, type }),
  }),

  // 获取 AstrBot 状态
  getStatus: () => request('/astrbot/status'),

  // ==================== 对话管理 API ====================

  // 获取对话列表
  getConversations: (params = {}) => {
    const queryParams = new URLSearchParams();
    if (params.groupId) queryParams.append('groupId', params.groupId);
    if (params.userQq) queryParams.append('userQq', params.userQq);
    if (params.page) queryParams.append('page', params.page);
    if (params.size) queryParams.append('size', params.size);
    const query = queryParams.toString();
    return request(`/astrbot/conversations${query ? '?' + query : ''}`);
  },

  // 获取单个对话详情
  getConversation: (conversationId) => request(`/astrbot/conversations/${conversationId}`),

  // 获取对话的消息列表
  getConversationMessages: (conversationId, page = 0, size = 50) =>
    request(`/astrbot/conversations/${conversationId}/messages?page=${page}&size=${size}`),

  // 创建新对话
  createConversation: (params) => request('/astrbot/conversations', {
    method: 'POST',
    body: JSON.stringify(params),
  }),

  // 更新对话标题
  updateConversationTitle: (conversationId, title) =>
    request(`/astrbot/conversations/${conversationId}/title`, {
      method: 'PUT',
      body: JSON.stringify({ title }),
    }),

  // 归档对话
  archiveConversation: (conversationId) =>
    request(`/astrbot/conversations/${conversationId}/archive`, {
      method: 'POST',
    }),

  // 删除对话
  deleteConversation: (conversationId) =>
    request(`/astrbot/conversations/${conversationId}`, {
      method: 'DELETE',
    }),

  // 获取对话统计信息
  getConversationStats: (conversationId) =>
    request(`/astrbot/conversations/${conversationId}/stats`),
};

// 用户设置 API
export const userApi = {
  // 获取用户设置
  getSettings: (userQq) => request(`/user/settings?userQq=${userQq}`),
  
  // 保存用户设置
  saveSettings: (params) => request('/user/settings', {
    method: 'POST',
    body: JSON.stringify(params),
  }),
  
  // ==================== 用户个人信息 API ====================
  
  // 获取用户资料
  getProfile: () => request('/user/profile'),
  
  // 更新用户资料
  updateProfile: (params) => request('/user/profile', {
    method: 'PUT',
    body: JSON.stringify(params),
  }),
  
  // 上传头像
  uploadAvatar: (file) => {
    const formData = new FormData();
    formData.append('file', file);
    return uploadRequest('/user/avatar', formData);
  },
  
  // 获取QQ绑定列表
  getQqBindings: () => request('/user/qq-bindings'),
  
  // 绑定QQ账号
  bindQq: (params) => request('/user/qq-bindings', {
    method: 'POST',
    body: JSON.stringify(params),
  }),
  
  // 解绑QQ账号
  unbindQq: (bindingId) => request(`/user/qq-bindings/${bindingId}`, {
    method: 'DELETE',
  }),
  
  // 设置默认QQ账号
  setDefaultQq: (bindingId) => request(`/user/qq-bindings/${bindingId}/default`, {
    method: 'PUT',
  }),
  
  // 获取默认QQ账号
  getDefaultQq: () => request('/user/qq-bindings/default'),
};

// 仪表盘(Dashboard) API
export const dashboardApi = {
  getStats: () => request('/dashboard/stats'),
  getMessageTrend: () => request('/dashboard/message-trend'),
  getGroupRanking: () => request('/dashboard/group-ranking'),
  getMessageTypeDistribution: () => request('/dashboard/message-type-distribution'),
};

// 人格(Persona) API
export const personaApi = {
  // 获取人格列表
  list: () => request('/persona/list'),

  // 获取单个人格
  get: (id) => request(`/persona/${id}`),

  // 创建人格
  create: (params) => request('/persona', {
    method: 'POST',
    body: JSON.stringify(params),
  }),

  // 更新人格
  update: (id, params) => request(`/persona/${id}`, {
    method: 'PUT',
    body: JSON.stringify(params),
  }),

  // 删除人格
  delete: (id) => request(`/persona/${id}`, {
    method: 'DELETE',
  }),

  // 设置默认人格
  setDefault: (personaId) => request('/persona/set-default', {
    method: 'POST',
    body: JSON.stringify({ personaId }),
  }),

  // 获取默认人格
  getDefault: () => request('/persona/default'),
};

// 认证 API
export const authApi = {
  // 登录
  login: (username, password) => request('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  }),
  
  // 注册
  register: (username, password, nickname) => request('/auth/register', {
    method: 'POST',
    body: JSON.stringify({ username, password, nickname }),
  }),
  
  // 获取当前用户信息
  getCurrentUser: () => request('/auth/me'),
  
  // 修改密码
  changePassword: (oldPassword, newPassword) => request('/auth/change-password', {
    method: 'POST',
    body: JSON.stringify({ oldPassword, newPassword }),
  }),
};

// 管理员 API
export const adminApi = {
  // 获取所有用户
  getUsers: () => request('/admin/users'),

  // 更新用户角色
  updateUserRole: (id, role) => request(`/admin/users/${id}/role`, {
    method: 'PUT',
    body: JSON.stringify({ role }),
  }),

  // 更新用户状态
  updateUserActive: (id, active) => request(`/admin/users/${id}/active`, {
    method: 'PUT',
    body: JSON.stringify({ active }),
  }),

  // 删除用户
  deleteUser: (id) => request(`/admin/users/${id}`, {
    method: 'DELETE',
  }),
};

// 文件类型检测
export const detectFileType = (file) => {
  const mimeType = file.type;
  if (mimeType.startsWith('image/')) return 'IMAGE';
  if (mimeType.startsWith('video/')) return 'VIDEO';
  if (mimeType.startsWith('audio/')) return 'AUDIO';
  return 'FILE';
};

// 格式化文件大小
export const formatFileSize = (bytes) => {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
};
