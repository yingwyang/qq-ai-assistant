// API服务配置
const API_BASE_URL = '/api';

// 通用请求方法
async function request(endpoint, options = {}) {
  const url = `${API_BASE_URL}${endpoint}`;
  const defaultOptions = {
    headers: {
      'Content-Type': 'application/json',
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
  getNapCatQrCode: () => request('/system/napcat/qrcode'),
  getNapCatQrCodePath: () => request('/system/napcat/qrcode-path'),
  checkNapCatLoginStatus: () => request('/system/napcat/login-status'),
  healthCheck: () => request('/system/health'),
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
