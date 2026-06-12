<template>
  <div class="chat-interface">
    <!-- 顶部群聊选择器 -->
    <div class="chat-header">
      <div class="group-selector">
        <input 
          type="text" 
          v-model="groupId" 
          placeholder="输入群聊ID" 
          class="group-input"
          @keyup.enter="loadMessages"
        />
        <button @click="loadMessages" class="btn-load" :disabled="isLoading">
          <span v-if="isLoading">加载中...</span>
          <span v-else>加载消息</span>
        </button>
        <button 
          @click="toggleSelectionMode" 
          :class="['btn-selection', { active: isSelectionMode }]"
        >
          {{ isSelectionMode ? '退出选择' : '选择消息' }}
        </button>
      </div>
      <div v-if="currentGroupName" class="current-group">
        {{ currentGroupName }}
      </div>
    </div>
    
    <!-- 选择模式操作栏 -->
    <div v-if="isSelectionMode" class="selection-toolbar">
      <div class="selection-info">
        <span class="selected-count">已选择 {{ selectedMessages.length }} 条消息</span>
        <label class="selection-mode-label">
          <input type="checkbox" v-model="isMultiSelect" />
          多选模式
        </label>
      </div>
      <div class="selection-actions">
        <button @click="clearSelection" class="btn-clear">清空</button>
        <button 
          @click="analyzeSelected" 
          :disabled="selectedMessages.length === 0"
          class="btn-analyze"
        >
          🤖 AI 分析
        </button>
      </div>
    </div>
    
    <!-- 消息列表区域 -->
    <div class="messages-area" ref="messagesContainer">
      <div v-if="messages.length === 0 && !isLoading" class="empty-state">
        <div class="empty-icon">💬</div>
        <p>请输入群聊ID开始对话</p>
      </div>
      
      <div v-else-if="isLoading" class="loading-state">
        <div class="loading-spinner"></div>
        <p>加载消息中...</p>
      </div>
      
      <div v-else class="messages-list">
        <div 
          v-for="message in messages" 
          :key="message.id" 
          class="message-wrapper"
          :class="{ 
            'message-self': isSelfMessage(message),
            'message-other': !isSelfMessage(message),
            'message-selected': isSelected(message.id),
            'selection-mode': isSelectionMode
          }"
          @click="isSelectionMode && toggleMessageSelection(message.id)"
        >
          <!-- 选择框 -->
          <div v-if="isSelectionMode" class="message-checkbox" @click.stop>
            <input 
              type="checkbox" 
              :checked="isSelected(message.id)"
              @change="toggleMessageSelection(message.id)"
            />
          </div>
          
          <!-- 群消息（左对齐） -->
          <div v-if="!isSelfMessage(message)" class="message-bubble message-left">
            <img 
              :src="getAvatar(message.userQq)" 
              class="message-avatar-img"
              @error="handleAvatarError"
            />
            <div class="message-content-wrapper">
              <div class="message-header">
                <span class="message-user">{{ message.userNickname || message.userName || '未知用户' }}</span>
                <span class="message-time">{{ formatTime(message.sendTime || message.timestamp) }}</span>
              </div>
              <!-- 消息内容 -->
              <MessageContent :message="message" />
              <!-- AI 总结（左对齐） -->
              <div v-if="message.aiSummary" class="ai-summary">
                <div class="ai-summary-header">
                  <span class="ai-icon">🤖</span>
                  <span>AI 总结</span>
                </div>
                <div class="ai-summary-content">{{ message.aiSummary }}</div>
              </div>
            </div>
          </div>
          
          <!-- 用户消息（右对齐） -->
          <div v-else class="message-bubble message-right">
            <img 
              :src="getAvatar(message.userQq)" 
              class="message-avatar-img"
              @error="handleAvatarError"
            />
            <div class="message-content-wrapper">
              <div class="message-header">
                <span class="message-time">{{ formatTime(message.sendTime || message.timestamp) }}</span>
                <span class="message-user">{{ message.userNickname || message.userName || '我' }}</span>
              </div>
              <!-- 消息内容 -->
              <MessageContent :message="message" />
            </div>
          </div>
        </div>
      </div>
    </div>
    
    <!-- 图片预览弹窗 -->
    <div v-if="previewImage" class="image-preview" @click="closeImagePreview">
      <img :src="previewImage" />
    </div>
    
  </div>
</template>

<script>
import { ref, nextTick, watch, onMounted, onUnmounted, computed } from 'vue';
import { messageApi, astrBotApi } from '../services/api';
import MessageContent from './MessageContent.vue';
import { showToast } from './Toast.vue';

export default {
  name: 'ChatInterface',
  components: {
    MessageContent
  },
  props: {
    groupId: {
      type: String,
      default: ''
    }
  },
  emits: ['analysis-result'],
  
  setup(props, { emit }) {
    const groupId = ref(props.groupId);
    const messages = ref([]);
    const isLoading = ref(false);
    const currentGroupName = ref('');
    const messagesContainer = ref(null);
    const previewImage = ref(null);
    let autoRefreshInterval = null;
    
    // 假设当前用户ID，实际应该从登录信息获取
    const currentUserId = 'current_user';
    
    // 选择模式相关状态
    const isSelectionMode = ref(false);
    const isMultiSelect = ref(false);
    const selectedMessageIds = ref(new Set());
    
    // 计算选中的消息数量
    const selectedMessages = computed(() => Array.from(selectedMessageIds.value));
    
    // 计算选中的消息数据
    const selectedMessagesData = computed(() => {
      return messages.value.filter(msg => selectedMessageIds.value.has(msg.id));
    });

    const loadMessages = async (showLoading = true, scrollToBottomFlag = true) => {
      if (!groupId.value) {
        showToast('请输入群聊ID', 'warning');
        return;
      }
      
      // 只有在需要显示加载状态时才设置 isLoading
      if (showLoading) {
        isLoading.value = true;
      }
      try {
        const response = await messageApi.getMessagesByGroupId(groupId.value);
        // 按时间正序排列（旧消息在前）
        messages.value = response.sort((a, b) => {
          const timeA = new Date(a.sendTime || a.timestamp);
          const timeB = new Date(b.sendTime || b.timestamp);
          return timeA - timeB;
        });
        currentGroupName.value = response.length > 0 ? (response[0].groupName || '群聊 ' + groupId.value) : '群聊 ' + groupId.value;
        
        // 只有在需要时才滚动到底部
        if (scrollToBottomFlag) {
          nextTick(() => {
            scrollToBottom();
          });
        }
      } catch (error) {
        console.error('加载消息失败:', error);
        if (showLoading) {
          showToast('加载消息失败: ' + error.message, 'error');
        }
      } finally {
        if (showLoading) {
          isLoading.value = false;
        }
      }
    };

    const isSelfMessage = (message) => {
      // 判断是否是登录账号发送的消息
      // 通过比较 userQq 和 selfQq 是否相等来判断
      if (message.userQq && message.selfQq) {
        return message.userQq === message.selfQq;
      }
      // 备用方案：检查 isSelfMessage 字段
      if (message.isSelfMessage !== undefined) {
        return message.isSelfMessage;
      }
      // 兼容旧数据
      return message.userId === currentUserId || message.userName === '我' || message.userNickname === '我';
    };

    const getAvatar = (userQq) => {
      // 使用 QQ 头像 API
      if (userQq) {
        return `https://q.qlogo.cn/headimg_dl?dst_uin=${userQq}&spec=100`;
      }
      return 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100';
    };

    const handleAvatarError = (e) => {
      // 头像加载失败时使用默认头像
      e.target.src = 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100';
    };

    const isImageMessage = (message) => {
      // 判断是否是图片消息
      if (message.messageType === 'IMAGE') return true;
      // 检查内容是否包含 CQ:image 码
      if (message.content && message.content.includes('[CQ:image')) return true;
      return false;
    };

    const extractImageUrl = (content) => {
      // 处理本地存储的图片路径
      if (!content) {
        console.log('extractImageUrl: content is empty');
        return '';
      }
      
      console.log('extractImageUrl: content =', content.substring(0, 100));
      
      // 如果 content 已经是本地图片路径（以 /images/ 开头），直接返回
      if (content.startsWith('/images/')) {
        console.log('extractImageUrl: local image path =', content);
        return content;
      }
      
      // 从 CQ 码中提取图片 URL
      // 匹配 [CQ:image,...url=xxx...,file_size=...]
      // URL 可能包含逗号，所以匹配到 ,file_size= 或 ]
      const urlMatch = content.match(/url=([^\]]+?)(?:,file_size=|$)/);
      if (urlMatch && urlMatch[1]) {
        // 解码 HTML 实体
        let url = urlMatch[1].replace(/&amp;/g, '&');
        // 移除末尾可能的逗号
        url = url.replace(/,$/, '');
        console.log('extractImageUrl: matched with file_size pattern, url =', url.substring(0, 100));
        return url;
      }
      
      // 备用方案：直接匹配 url= 到下一个逗号或右方括号
      const simpleMatch = content.match(/url=([^,\]]+)/);
      if (simpleMatch && simpleMatch[1]) {
        let url = simpleMatch[1].replace(/&amp;/g, '&');
        console.log('extractImageUrl: matched with simple pattern, url =', url.substring(0, 100));
        return url;
      }
      
      console.log('extractImageUrl: no match found');
      return '';
    };

    const openImage = (url) => {
      previewImage.value = url;
    };

    const closeImagePreview = () => {
      previewImage.value = null;
    };

    const formatTime = (timestamp) => {
      if (!timestamp) return '';
      const date = new Date(timestamp);
      return date.toLocaleTimeString('zh-CN', { 
        hour: '2-digit', 
        minute: '2-digit',
        hour12: false 
      });
    };

    const scrollToBottom = () => {
      if (messagesContainer.value) {
        messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
      }
    };
    
    // 检查是否在底部（允许10px的误差）
    const isAtBottom = () => {
      if (!messagesContainer.value) return false;
      const container = messagesContainer.value;
      const threshold = 10;
      return container.scrollHeight - container.scrollTop - container.clientHeight <= threshold;
    };
    
    // 启动自动刷新消息
    const startAutoRefresh = () => {
      // 先清除已有的定时器
      stopAutoRefresh();
      // 每5秒自动刷新一次消息（不显示加载状态，有新消息时自动滚动到底部）
      autoRefreshInterval = setInterval(async () => {
        if (groupId.value) {
          const previousMessageCount = messages.value.length;
          const wasAtBottom = isAtBottom();
          await loadMessages(false, false);
          // 如果之前在底部且有新消息，自动滚动到底部
          if (wasAtBottom && messages.value.length > previousMessageCount) {
            // 使用 setTimeout 确保 DOM 完全更新后再滚动
            setTimeout(() => {
              scrollToBottom();
            }, 100);
          }
        }
      }, 5000);
    };
    
    // 停止自动刷新消息
    const stopAutoRefresh = () => {
      if (autoRefreshInterval) {
        clearInterval(autoRefreshInterval);
        autoRefreshInterval = null;
      }
    };

    // 监听 props.groupId 变化
    watch(() => props.groupId, (newGroupId) => {
      if (newGroupId) {
        groupId.value = newGroupId;
        loadMessages();
        // 启动自动刷新
        startAutoRefresh();
      } else {
        // 停止自动刷新
        stopAutoRefresh();
      }
    }, { immediate: true });
    
    // 组件挂载时，如果有 groupId 则加载消息
    onMounted(() => {
      if (groupId.value) {
        loadMessages();
        startAutoRefresh();
      }
    });
    
    // 组件卸载时停止自动刷新
    onUnmounted(() => {
      stopAutoRefresh();
    });
    
    // 切换选择模式
    const toggleSelectionMode = () => {
      isSelectionMode.value = !isSelectionMode.value;
      if (!isSelectionMode.value) {
        // 退出选择模式时清空选择
        clearSelection();
      }
    };
    
    // 切换消息选择状态
    const toggleMessageSelection = (messageId) => {
      if (!isMultiSelect.value) {
        // 单选模式：只保留当前选中的消息
        if (selectedMessageIds.value.has(messageId)) {
          selectedMessageIds.value.clear();
        } else {
          selectedMessageIds.value.clear();
          selectedMessageIds.value.add(messageId);
        }
      } else {
        // 多选模式：切换选中状态
        if (selectedMessageIds.value.has(messageId)) {
          selectedMessageIds.value.delete(messageId);
        } else {
          selectedMessageIds.value.add(messageId);
        }
      }
    };
    
    // 检查消息是否被选中
    const isSelected = (messageId) => {
      return selectedMessageIds.value.has(messageId);
    };
    
    // 清空选择
    const clearSelection = () => {
      selectedMessageIds.value.clear();
    };
    
    // 分析选中的消息
    const analyzeSelected = async () => {
      if (selectedMessages.value.length === 0) {
        showToast('请先选择要分析的消息', 'warning');
        return;
      }
      
      // 构建选中的消息数据
      const selectedData = selectedMessagesData.value.map(msg => ({
        user: msg.userNickname || msg.userName || '未知用户',
        content: msg.content || '[无内容]'
      }));
      
      // 构建消息内容
      const messageContents = selectedData.map(msg => `${msg.user}: ${msg.content}`).join('\n');
      
      // 发送分析请求事件给父组件
      emit('analysis-result', {
        type: 'request',
        messages: selectedData,
        prompt: `请分析以下群聊消息，总结主要内容、讨论话题和关键信息：\n\n${messageContents}`
      });
      
      // 退出选择模式
      isSelectionMode.value = false;
      clearSelection();
    };

    return {
      groupId,
      messages,
      isLoading,
      currentGroupName,
      messagesContainer,
      previewImage,
      loadMessages,
      isSelfMessage,
      getAvatar,
      handleAvatarError,
      openImage,
      closeImagePreview,
      formatTime,
      // 选择模式相关
      isSelectionMode,
      isMultiSelect,
      selectedMessages,
      selectedMessagesData,
      toggleSelectionMode,
      toggleMessageSelection,
      isSelected,
      clearSelection,
      analyzeSelected
    };
  }
};
</script>

<style scoped>
.chat-interface {
  background-color: white;
  border-radius: 8px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-header {
  padding: 12px 20px;
  border-bottom: 1px solid #e0e0e0;
  background-color: #f8f9fa;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.group-selector {
  display: flex;
  gap: 10px;
  flex: 1;
  max-width: 400px;
}

.group-input {
  flex: 1;
  padding: 10px;
  border: 1px solid #dee2e6;
  border-radius: 4px;
  font-size: 14px;
}

.btn-load {
  padding: 10px 20px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  transition: background-color 0.2s;
}

.btn-load:hover:not(:disabled) {
  background-color: #2980b9;
}

.btn-load:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
}

.current-group {
  font-weight: 500;
  color: #2c3e50;
  font-size: 16px;
}

.messages-area {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.empty-state, .loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #666;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 10px;
}

.loading-spinner {
  border: 4px solid #f3f3f3;
  border-top: 4px solid #3498db;
  border-radius: 50%;
  width: 40px;
  height: 40px;
  animation: spin 1s linear infinite;
  margin-bottom: 10px;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

.messages-list {
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.message-wrapper {
  display: flex;
  margin-bottom: 10px;
  width: 100%;
}

.message-wrapper.message-self {
  justify-content: flex-end;
}

.message-wrapper.message-other {
  justify-content: flex-start;
}

.message-bubble {
  max-width: 70%;
  padding: 12px 16px;
  border-radius: 18px;
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.message-left {
  background-color: #f1f3f4;
  border-bottom-left-radius: 4px;
  flex-direction: row;
  margin-right: auto;
}

.message-right {
  background-color: #3498db;
  color: white;
  border-bottom-right-radius: 4px;
  flex-direction: row-reverse;
  margin-left: auto;
}

.message-avatar-img {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}

.message-content-wrapper {
  flex: 1;
  min-width: 0;
}

.message-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
  font-size: 12px;
  color: #666;
}

.message-right .message-header {
  color: rgba(255, 255, 255, 0.8);
}

.message-user {
  font-weight: 500;
}

.message-time {
  color: #95a5a6;
}

.message-right .message-time {
  color: rgba(255, 255, 255, 0.6);
}

.message-text {
  line-height: 1.4;
  word-wrap: break-word;
}

.message-image {
  margin-top: 5px;
}

.message-image img {
  max-width: 100%;
  max-height: 300px;
  border-radius: 8px;
  cursor: pointer;
  transition: transform 0.2s;
}

.message-image img:hover {
  transform: scale(1.02);
}

.ai-summary {
  margin-top: 8px;
  padding: 10px;
  background-color: #e3f2fd;
  border-radius: 8px;
  font-size: 14px;
}

.ai-summary-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  font-weight: 500;
  color: #1976d2;
}

.ai-summary-content {
  line-height: 1.4;
  color: #333;
}

.image-preview {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.9);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  cursor: pointer;
}

.image-preview img {
  max-width: 90%;
  max-height: 90%;
  object-fit: contain;
  border-radius: 8px;
}

/* 滚动条样式 */
.messages-area::-webkit-scrollbar {
  width: 6px;
}

.messages-area::-webkit-scrollbar-track {
  background: #f1f1f1;
  border-radius: 3px;
}

.messages-area::-webkit-scrollbar-thumb {
  background: #c1c1c1;
  border-radius: 3px;
}

.messages-area::-webkit-scrollbar-thumb:hover {
  background: #a8a8a8;
}

/* 选择模式按钮 */
.btn-selection {
  padding: 10px 16px;
  background-color: #9b59b6;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
  white-space: nowrap;
}

.btn-selection:hover {
  background-color: #8e44ad;
}

.btn-selection.active {
  background-color: #e74c3c;
}

/* 选择工具栏 */
.selection-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  background-color: #f8f9fa;
  border-bottom: 1px solid #e0e0e0;
  gap: 15px;
  flex-wrap: wrap;
}

.selection-info {
  display: flex;
  align-items: center;
  gap: 20px;
}

.selected-count {
  font-weight: 500;
  color: #2c3e50;
}

.selection-mode-label {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: #666;
  font-size: 14px;
}

.selection-mode-label input[type="checkbox"] {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

.selection-actions {
  display: flex;
  gap: 10px;
}

.btn-clear {
  padding: 8px 16px;
  background-color: #95a5a6;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  transition: background-color 0.2s;
}

.btn-clear:hover {
  background-color: #7f8c8d;
}

.btn-analyze {
  padding: 8px 16px;
  background-color: #27ae60;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  gap: 6px;
}

.btn-analyze:hover:not(:disabled) {
  background-color: #219a52;
}

.btn-analyze:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
}

/* 消息选择框 */
.message-checkbox {
  display: flex;
  align-items: center;
  padding: 0 10px;
  cursor: pointer;
}

.message-checkbox input[type="checkbox"] {
  width: 20px;
  height: 20px;
  cursor: pointer;
  accent-color: #3498db;
}

/* 选择模式下的消息样式 */
.message-wrapper.selection-mode {
  cursor: pointer;
  transition: background-color 0.2s;
  border-radius: 8px;
}

.message-wrapper.selection-mode:hover {
  background-color: rgba(52, 152, 219, 0.1);
}

.message-wrapper.message-selected {
  background-color: rgba(52, 152, 219, 0.15);
  border-radius: 8px;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .message-bubble {
    max-width: 85%;
  }
  
  .chat-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
  
  .group-selector {
    width: 100%;
    max-width: none;
  }
  
  .selection-toolbar {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
  
  .selection-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
