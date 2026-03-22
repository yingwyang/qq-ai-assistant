// API服务配置
const API_BASE_URL = '/api';

// 通用请求方法
async function request(endpoint, options = {}) {
  const url = `${API_BASE_URL}${endpoint}`;
  const defaultOptions = {
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
      'Accept': 'application/json; charset=UTF-8',
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
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }
    return await response.json();
  } catch (error) {
    console.error('API request failed:', error);
    throw error;
  }
}

// 文件上传请求（multipart/form-data）
async function uploadRequest(endpoint, formData) {
  const url = `${API_BASE_URL}${endpoint}`;
  
  try {
    const response = await fetch(url, {
      method: 'POST',
      body: formData,
    });
    
    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }
    return await response.json();
  } catch (error) {
    console.error('Upload request failed:', error);
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
  
  // 获取最近对话的群聊
  getRecentGroups: (userId) => {
    const params = userId ? `?userId=${userId}` : '';
    return request(`/messages/recent-groups${params}`);
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
  startNapCat: () => request('/system/start-napcat', {
    method: 'POST',
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
