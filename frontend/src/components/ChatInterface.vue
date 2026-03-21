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
      </div>
      <div v-if="currentGroupName" class="current-group">
        {{ currentGroupName }}
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
            'message-other': !isSelfMessage(message)
          }"
        >
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
              <!-- 文本消息 -->
              <div v-if="!isImageMessage(message)" class="message-text">{{ message.content }}</div>
              <!-- 图片消息 -->
              <div v-else class="message-image">
                <img :src="extractImageUrl(message.content)" @click="openImage(extractImageUrl(message.content))" />
              </div>
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
            <div class="message-content-wrapper">
              <div class="message-header">
                <span class="message-time">{{ formatTime(message.sendTime || message.timestamp) }}</span>
                <span class="message-user">{{ message.userNickname || message.userName || '我' }}</span>
              </div>
              <!-- 文本消息 -->
              <div v-if="!isImageMessage(message)" class="message-text">{{ message.content }}</div>
              <!-- 图片消息 -->
              <div v-else class="message-image">
                <img :src="extractImageUrl(message.content)" @click="openImage(extractImageUrl(message.content))" />
              </div>
            </div>
            <img 
              :src="getAvatar(message.userQq)" 
              class="message-avatar-img"
              @error="handleAvatarError"
            />
          </div>
        </div>
      </div>
    </div>
    
    <!-- 底部输入区域 -->
    <div class="input-area">
      <div class="input-wrapper">
        <textarea 
          v-model="inputMessage"
          placeholder="输入消息..."
          class="message-input"
          rows="1"
          @keyup.enter.prevent="sendMessage"
          @input="autoResize"
          ref="inputRef"
        ></textarea>
        <button 
          @click="sendMessage" 
          class="btn-send"
          :disabled="!inputMessage.trim()"
        >
          <span>发送</span>
          <span class="send-icon">➤</span>
        </button>
      </div>
    </div>

    <!-- 图片预览弹窗 -->
    <div v-if="previewImage" class="image-preview" @click="closeImagePreview">
      <img :src="previewImage" />
    </div>
  </div>
</template>

<script>
import { ref, nextTick, watch, onMounted } from 'vue';
import { messageApi } from '../services/api';

export default {
  name: 'ChatInterface',
  props: {
    groupId: {
      type: String,
      default: ''
    }
  },
  setup(props) {
    const groupId = ref(props.groupId);
    const messages = ref([]);
    const isLoading = ref(false);
    const inputMessage = ref('');
    const currentGroupName = ref('');
    const messagesContainer = ref(null);
    const inputRef = ref(null);
    const previewImage = ref(null);
    
    // 假设当前用户ID，实际应该从登录信息获取
    const currentUserId = 'current_user';

    const loadMessages = async () => {
      if (!groupId.value) {
        alert('请输入群聊ID');
        return;
      }
      
      isLoading.value = true;
      try {
        const response = await messageApi.getMessagesByGroupId(groupId.value);
        // 按时间正序排列（旧消息在前）
        messages.value = response.sort((a, b) => {
          const timeA = new Date(a.sendTime || a.timestamp);
          const timeB = new Date(b.sendTime || b.timestamp);
          return timeA - timeB;
        });
        currentGroupName.value = response.length > 0 ? (response[0].groupName || '群聊 ' + groupId.value) : '群聊 ' + groupId.value;
        
        // 滚动到底部
        nextTick(() => {
          scrollToBottom();
        });
      } catch (error) {
        console.error('加载消息失败:', error);
        alert('加载消息失败: ' + error.message);
      } finally {
        isLoading.value = false;
      }
    };

    const sendMessage = async () => {
      if (!inputMessage.value.trim()) return;
      if (!groupId.value) {
        alert('请先输入群聊ID');
        return;
      }

      // 创建新消息对象
      const newMessage = {
        id: Date.now(),
        userId: currentUserId,
        userName: '我',
        userNickname: '我',
        content: inputMessage.value.trim(),
        timestamp: new Date().toISOString(),
        sendTime: new Date().toISOString(),
        groupId: groupId.value,
        groupName: currentGroupName.value
      };

      // 添加到消息列表
      messages.value.push(newMessage);
      
      // 清空输入框
      inputMessage.value = '';
      
      // 重置输入框高度
      if (inputRef.value) {
        inputRef.value.style.height = 'auto';
      }
      
      // 滚动到底部
      nextTick(() => {
        scrollToBottom();
      });
    };

    const isSelfMessage = (message) => {
      // 判断是否是当前用户发送的消息
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
      // 从 CQ 码中提取图片 URL
      if (!content) {
        console.log('extractImageUrl: content is empty');
        return '';
      }
      
      console.log('extractImageUrl: content =', content.substring(0, 100));
      
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

    const autoResize = () => {
      const textarea = inputRef.value;
      if (textarea) {
        textarea.style.height = 'auto';
        textarea.style.height = Math.min(textarea.scrollHeight, 120) + 'px';
      }
    };

    const scrollToBottom = () => {
      if (messagesContainer.value) {
        messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
      }
    };

    // 监听 props.groupId 变化
    watch(() => props.groupId, (newGroupId) => {
      if (newGroupId) {
        groupId.value = newGroupId;
        loadMessages();
      } else {
        // 当没有群聊ID时，清空消息和群聊名称
        groupId.value = '';
        messages.value = [];
        currentGroupName.value = '';
      }
    }, { immediate: true });

    return {
      groupId,
      messages,
      isLoading,
      inputMessage,
      currentGroupName,
      messagesContainer,
      inputRef,
      previewImage,
      loadMessages,
      sendMessage,
      isSelfMessage,
      getAvatar,
      handleAvatarError,
      isImageMessage,
      extractImageUrl,
      openImage,
      closeImagePreview,
      formatTime,
      autoResize
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
  padding: 20px;
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
}

.message-bubble {
  max-width: 70%;
  padding: 12px 16px;
  border-radius: 18px;
  display: flex;
  gap: 10px;
}

.message-left {
  background-color: #f1f3f4;
  border-bottom-left-radius: 4px;
  align-self: flex-start;
}

.message-right {
  background-color: #3498db;
  color: white;
  border-bottom-right-radius: 4px;
  align-self: flex-end;
  margin-left: auto;
  flex-direction: row-reverse;
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

.input-area {
  padding: 20px;
  border-top: 1px solid #e0e0e0;
  background-color: #f8f9fa;
}

.input-wrapper {
  display: flex;
  gap: 10px;
}

.message-input {
  flex: 1;
  padding: 12px;
  border: 1px solid #dee2e6;
  border-radius: 20px;
  resize: none;
  font-size: 14px;
  line-height: 1.4;
  min-height: 40px;
  max-height: 120px;
  overflow-y: auto;
}

.message-input:focus {
  outline: none;
  border-color: #3498db;
  box-shadow: 0 0 0 2px rgba(52, 152, 219, 0.2);
}

.btn-send {
  padding: 0 24px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 20px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
  transition: background-color 0.2s;
  display: flex;
  align-items: center;
  gap: 5px;
}

.btn-send:hover:not(:disabled) {
  background-color: #2980b9;
}

.btn-send:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
}

.send-icon {
  font-size: 16px;
  font-weight: bold;
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
}
</style>
