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

// 消息相关API
export const messageApi = {
  createMessage: (message) => request('/messages', {
    method: 'POST',
    body: JSON.stringify(message),
  }),
  getMessagesByGroupId: (groupId) => request(`/messages/group/${groupId}`),
  processAllMessages: () => request('/messages/process', {
    method: 'POST',
  }),
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
