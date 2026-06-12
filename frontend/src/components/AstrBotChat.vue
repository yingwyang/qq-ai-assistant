<template>
  <div class="astrbot-chat">
    <div class="chat-header">
      <div class="header-avatar">
        <img :src="botAvatar" alt="bot avatar" @error="handleBotAvatarError" />
      </div>
      <div class="header-info">
        <h3>{{ botName }}</h3>
        <span class="status" :class="{ 'online': isOnline, 'offline': !isOnline }">
          {{ isOnline ? '在线' : '离线' }}
        </span>
      </div>
      <div class="header-actions">
        <button class="action-btn" @click="showConversationList = !showConversationList" title="对话历史">
          📋
        </button>
        <button class="action-btn" @click="createNewConversation" title="新对话">
          ➕
        </button>
        <button class="action-btn" @click="showSettings = true" title="设置">
          ⚙️
        </button>
      </div>
    </div>

    <!-- 对话列表面板 -->
    <div v-if="showConversationList" class="conversation-panel">
      <div class="panel-header">
        <h4>对话历史 ({{ conversations.length }})</h4>
        <button class="close-btn" @click="showConversationList = false">✕</button>
      </div>
      <div class="conversation-list">
        <div
          v-for="conv in conversations"
          :key="conv.conversationId"
          class="conversation-item"
          :class="{ 'active': currentConversationId === conv.conversationId }"
        >
          <div class="conv-content" @click="loadConversation(conv.conversationId)">
            <div class="conv-title">{{ conv.title || '新对话' }}</div>
            <div class="conv-meta">
              <span>{{ conv.messageCount || 0 }} 条消息</span>
              <span>{{ formatDate(conv.timeUpdated) }}</span>
            </div>
          </div>
          <button 
            class="delete-btn" 
            @click="(e) => { e.preventDefault(); e.stopPropagation(); showDeleteConfirm(conv.conversationId); }" 
            title="删除"
          >
            🗑️
          </button>
        </div>
        <div v-if="conversations.length === 0" class="empty-conversations">
          暂无对话历史
        </div>
      </div>
    </div>

    <!-- 删除确认弹窗 -->
    <div v-if="showConfirmDialog" class="confirm-dialog-overlay" @click="cancelDelete">
      <div class="confirm-dialog" @click.stop>
        <div class="confirm-dialog-header">
          <span class="confirm-icon">⚠️</span>
          <h3>确认删除</h3>
        </div>
        <div class="confirm-dialog-body">
          <p>确定要删除这个对话吗？</p>
          <p class="confirm-hint">此操作不可恢复，删除后将无法找回该对话记录。</p>
        </div>
        <div class="confirm-dialog-footer">
          <button class="btn-cancel" @click="cancelDelete">取消</button>
          <button class="btn-confirm" @click="confirmDelete">确定删除</button>
        </div>
      </div>
    </div>

    <!-- 设置弹窗 -->
    <div v-if="showSettings" class="settings-dialog-overlay" @click="closeSettings">
      <div class="settings-dialog" @click.stop>
        <div class="settings-dialog-header">
          <span class="settings-icon">⚙️</span>
          <h3>聊天设置</h3>
          <button class="close-btn" @click="closeSettings">✕</button>
        </div>
        <div class="settings-dialog-body">
          <div class="settings-section">
            <h4>AI 助手设置</h4>
            <div class="setting-item">
              <label>助手名称</label>
              <input 
                type="text" 
                v-model="botName" 
                placeholder="输入助手名称"
                @keyup.enter="saveSettings"
              />
            </div>
            <div class="setting-item">
              <label>助手头像</label>
              <div class="avatar-upload">
                <img :src="botAvatar" class="avatar-preview-large" @error="handleBotAvatarError" />
                <div class="upload-actions">
                  <input 
                    type="file" 
                    ref="botAvatarInput"
                    accept="image/*"
                    style="display: none"
                    @change="handleBotAvatarUpload"
                  />
                  <button class="btn-upload" @click="$refs.botAvatarInput.click()">
                    📁 选择图片
                  </button>
                  <span class="upload-hint">或输入 URL</span>
                  <input 
                    type="text" 
                    v-model="botAvatar" 
                    placeholder="输入头像图片地址"
                    class="url-input"
                  />
                </div>
              </div>
            </div>
          </div>
          <div class="settings-section">
            <h4>用户设置</h4>
            <div class="setting-item">
              <label>用户头像</label>
              <div class="avatar-upload">
                <img :src="userAvatar" class="avatar-preview-large" @error="handleUserAvatarError" />
                <div class="upload-actions">
                  <input 
                    type="file" 
                    ref="userAvatarInput"
                    accept="image/*"
                    style="display: none"
                    @change="handleUserAvatarUpload"
                  />
                  <button class="btn-upload" @click="$refs.userAvatarInput.click()">
                    📁 选择图片
                  </button>
                  <span class="upload-hint">或输入 URL</span>
                  <input 
                    type="text" 
                    v-model="userAvatar" 
                    placeholder="输入头像图片地址"
                    class="url-input"
                  />
                </div>
              </div>
            </div>
          </div>
        </div>
        <div class="settings-dialog-footer">
          <button class="btn-cancel" @click="closeSettings">取消</button>
          <button class="btn-confirm" @click="saveSettings">保存设置</button>
        </div>
      </div>
    </div>

    <div class="chat-messages" ref="messagesContainer">
      <div v-if="messages.length === 0" class="empty-chat">
        <div class="empty-icon">💬</div>
        <p>开始与 AstrBot 对话</p>
        <p class="empty-hint">对话将自动保存，您可以随时查看历史记录</p>
      </div>

      <div
        v-for="(message, index) in messages"
        :key="index"
        class="message-wrapper"
        :class="{ 
          'message-self': message.isSelf,
          'message-system': message.isSystem 
        }"
      >
        <div 
          class="message-bubble" 
          :class="[
            message.isSelf ? 'message-right' : 'message-left',
            { 'message-system-bubble': message.isSystem }
          ]"
        >
          <div class="message-avatar" v-if="!message.isSystem">
            <img :src="message.isSelf ? userAvatar : botAvatar" alt="avatar" />
          </div>
          <div class="message-content">
            <div class="message-header" v-if="!message.isSystem">
              <span class="message-sender">{{ message.sender }}</span>
              <span class="message-time">{{ formatTime(message.time) }}</span>
            </div>
            <div class="message-text" :class="{ 'message-system-text': message.isSystem }">{{ message.text }}</div>
          </div>
        </div>
      </div>

      <div v-if="isLoading" class="loading-indicator">
        <span class="loading-dots">AstrBot 正在思考</span>
      </div>
    </div>

    <div class="chat-input-area">
      <div class="input-wrapper">
        <input
          ref="inputRef"
          v-model="inputMessage"
          type="text"
          placeholder="输入消息..."
          @keyup.enter="sendMessage"
          :disabled="isLoading"
        />
        <button class="send-btn" @click="sendMessage" :disabled="!inputMessage.trim() || isLoading">
          {{ isLoading ? '发送中...' : '发送' }}
        </button>
      </div>
      <div v-if="currentConversationId" class="conversation-info">
        当前对话: {{ currentConversationTitle || '新对话' }}
      </div>
    </div>
  </div>
</template>

<script>
import { ref, onMounted, nextTick, watch } from 'vue';
import { astrBotApi, userApi } from '../services/api';
import { filterToolJson, processAstrBotResponse } from '../utils/messageFilter';
import { showToast } from './Toast.vue';

export default {
  name: 'AstrBotChat',
  props: {
    groupId: { type: String, default: null },
    userQq: { type: String, default: null },
    userNickname: { type: String, default: null }
  },
  setup(props) {
    const messages = ref([]);
    const inputMessage = ref('');
    const messagesContainer = ref(null);
    const inputRef = ref(null);
    const isLoading = ref(false);
    const isOnline = ref(true);
    const botAvatarInput = ref(null);
    const userAvatarInput = ref(null);

    // 使用独立的过滤模块 - 导入自 ../utils/messageFilter

    // 对话管理
    const conversations = ref([]);
    const currentConversationId = ref(null);
    const currentConversationTitle = ref('');
    const showConversationList = ref(false);

    // 删除确认弹窗
    const showConfirmDialog = ref(false);
    const conversationToDelete = ref(null);

    // 头像
    const userAvatar = ref('https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100');
    const botAvatar = ref('https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100');
    
    // 名称设置
    const botName = ref('AstrBot 助手');
    
    // 设置弹窗
    const showSettings = ref(false);
    
    // 加载设置 - 从后端获取
    const loadSettings = async () => {
      // 优先从 localStorage 加载（为了快速显示）
      const savedBotName = localStorage.getItem('astrbot_bot_name');
      const savedBotAvatar = localStorage.getItem('astrbot_bot_avatar');
      const savedUserAvatar = localStorage.getItem('astrbot_user_avatar');
      
      if (savedBotName) botName.value = savedBotName;
      if (savedBotAvatar) botAvatar.value = savedBotAvatar;
      if (savedUserAvatar) userAvatar.value = savedUserAvatar;
      
      // 如果有 userQq，从后端获取设置
      if (props.userQq) {
        try {
          const response = await userApi.getSettings(props.userQq);
          if (response && response.status === 'ok' && response.data) {
            botName.value = response.data.botName || botName.value;
            botAvatar.value = response.data.botAvatar || botAvatar.value;
            userAvatar.value = response.data.userAvatar || userAvatar.value;
            
            // 同步到 localStorage
            localStorage.setItem('astrbot_bot_name', botName.value);
            localStorage.setItem('astrbot_bot_avatar', botAvatar.value);
            localStorage.setItem('astrbot_user_avatar', userAvatar.value);
          }
        } catch (error) {
          console.error('加载用户设置失败:', error);
        }
      }
    };
    
    // 保存设置 - 保存到后端
    const saveSettings = async () => {
      // 保存到 localStorage
      localStorage.setItem('astrbot_bot_name', botName.value);
      localStorage.setItem('astrbot_bot_avatar', botAvatar.value);
      localStorage.setItem('astrbot_user_avatar', userAvatar.value);
      
      // 如果有 userQq，保存到后端
      if (props.userQq) {
        try {
          await userApi.saveSettings({
            userQq: props.userQq,
            botName: botName.value,
            botAvatar: botAvatar.value,
            userAvatar: userAvatar.value
          });
        } catch (error) {
          console.error('保存用户设置到后端失败:', error);
        }
      }
      
      showSettings.value = false;
    };
    
    // 关闭设置
    const closeSettings = () => {
      // 恢复原来的值
      loadSettings();
      showSettings.value = false;
    };
    
    // 头像加载错误处理
    const handleBotAvatarError = () => {
      botAvatar.value = 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100';
    };
    
    const handleUserAvatarError = () => {
      userAvatar.value = 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100';
    };
    
    // 头像文件上传处理
    const handleBotAvatarUpload = (event) => {
      const file = event.target.files[0];
      if (file) {
        const reader = new FileReader();
        reader.onload = (e) => {
          botAvatar.value = e.target.result;
        };
        reader.readAsDataURL(file);
      }
    };
    
    const handleUserAvatarUpload = (event) => {
      const file = event.target.files[0];
      if (file) {
        const reader = new FileReader();
        reader.onload = (e) => {
          userAvatar.value = e.target.result;
        };
        reader.readAsDataURL(file);
      }
    };

    // 加载对话列表
    const loadConversations = async () => {
      try {
        const response = await astrBotApi.getConversations({});
        if (response && response.status === 'ok' && Array.isArray(response.data)) {
          conversations.value = response.data;
        }
      } catch (error) {
        console.error('加载对话列表失败:', error);
      }
    };

    // 加载特定对话的消息
    const loadConversation = async (conversationId) => {
      if (!conversationId) return;
      try {
        isLoading.value = true;
        currentConversationId.value = conversationId;
        showConversationList.value = false;

        const convResponse = await astrBotApi.getConversation(conversationId);
        if (convResponse && convResponse.status === 'ok') {
          currentConversationTitle.value = convResponse.data.title || '新对话';
        }

        const msgResponse = await astrBotApi.getConversationMessages(conversationId);
        if (msgResponse && msgResponse.status === 'ok') {
          messages.value = (msgResponse.data || []).map(msg => ({
            text: msg.content,
            sender: msg.role === 'USER' ? (props.userNickname || '我') : 'AstrBot',
            isSelf: msg.role === 'USER',
            time: new Date(msg.timeCreated)
          }));
        }

        localStorage.setItem('astrbot_current_conversation', conversationId);
        nextTick(() => scrollToBottom());
      } catch (error) {
        console.error('加载对话失败:', error);
      } finally {
        isLoading.value = false;
      }
    };

    // 创建新对话
    const createNewConversation = async () => {
      try {
        const request = {};
        if (props.groupId) request.groupId = props.groupId;
        if (props.userQq) request.userQq = props.userQq;
        if (props.userNickname) request.userNickname = props.userNickname;

        const response = await astrBotApi.createConversation(request);
        if (response && response.status === 'ok') {
          const newConv = response.data;
          conversations.value.unshift(newConv);
          currentConversationId.value = newConv.conversationId;
          currentConversationTitle.value = newConv.title || '新对话';
          messages.value = [];
          localStorage.setItem('astrbot_current_conversation', newConv.conversationId);
        }
      } catch (error) {
        console.error('创建新对话失败:', error);
      }
    };

    // 显示删除确认弹窗
    const showDeleteConfirm = (conversationId) => {
      console.log('🗑️ 显示删除确认弹窗，conversationId:', conversationId);
      conversationToDelete.value = conversationId;
      showConfirmDialog.value = true;
    };

    // 取消删除
    const cancelDelete = () => {
      console.log('❌ 用户取消删除');
      showConfirmDialog.value = false;
      conversationToDelete.value = null;
    };

    // 确认删除
    const confirmDelete = async () => {
      const conversationId = conversationToDelete.value;
      if (!conversationId) return;
      
      console.log('✅ 用户确认删除，开始执行删除操作');
      showConfirmDialog.value = false;
      isLoading.value = true;
      
      try {
        console.log('📡 调用 API 删除对话...');
        const response = await astrBotApi.deleteConversation(conversationId);
        console.log('API 响应:', response);
        
        if (response && response.status === 'ok') {
          // 从列表中移除
          const index = conversations.value.findIndex(c => c.conversationId === conversationId);
          console.log('找到对话在列表中的索引:', index);
          if (index > -1) {
            conversations.value.splice(index, 1);
            console.log('✅ 对话已从列表中移除');
          }
          // 如果删除的是当前对话，清空当前对话
          if (currentConversationId.value === conversationId) {
            currentConversationId.value = null;
            currentConversationTitle.value = '';
            messages.value = [];
            localStorage.removeItem('astrbot_current_conversation');
          }
        } else {
          console.error('❌ API 返回错误:', response);
          showToast('删除失败: ' + (response?.message || '未知错误'), 'error');
        }
      } catch (error) {
        console.error('❌ 删除对话失败:', error);
        showToast('删除对话失败: ' + error.message, 'error');
      } finally {
        isLoading.value = false;
        conversationToDelete.value = null;
      }
    };

    // 发送消息
    const sendMessage = async () => {
      if (!inputMessage.value.trim() || isLoading.value) return;
      const userMessage = inputMessage.value.trim();

      messages.value.push({
        text: userMessage,
        sender: props.userNickname || '我',
        isSelf: true,
        time: new Date()
      });

      inputMessage.value = '';
      isLoading.value = true;
      nextTick(() => scrollToBottom());

      try {
        const request = { message: userMessage };
        if (currentConversationId.value) request.conversationId = currentConversationId.value;
        if (props.groupId) request.groupId = props.groupId;
        if (props.userQq) request.userQq = props.userQq;
        if (props.userNickname) request.userNickname = props.userNickname;

        const response = await astrBotApi.sendMessage(request);
        if (response) {
          if (response.conversationId) {
            currentConversationId.value = response.conversationId;
            localStorage.setItem('astrbot_current_conversation', response.conversationId);
          }
          // 使用独立的过滤模块处理响应
          const replyText = processAstrBotResponse(response);
          messages.value.push({
            text: replyText,
            sender: 'AstrBot',
            isSelf: false,
            time: new Date()
          });
        }
      } catch (error) {
        console.error('发送消息失败:', error);
        messages.value.push({
          text: '抱歉，网络错误，请稍后重试。',
          sender: 'AstrBot',
          isSelf: false,
          time: new Date()
        });
      } finally {
        isLoading.value = false;
        nextTick(() => scrollToBottom());
      }
    };

    // 从本地存储恢复对话
    const restoreConversation = async () => {
      const savedConvId = localStorage.getItem('astrbot_current_conversation');
      if (savedConvId) {
        await loadConversation(savedConvId);
      }
    };

    // 检查 AstrBot 状态
    const checkStatus = async () => {
      try {
        const response = await astrBotApi.getStatus();
        isOnline.value = response && response.status === 'online';
      } catch (error) {
        isOnline.value = false;
      }
    };

    const scrollToBottom = () => {
      if (messagesContainer.value) {
        messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
      }
    };

    const formatTime = (time) => {
      if (!time) return '';
      const date = new Date(time);
      return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    };

    const formatDate = (time) => {
      if (!time) return '';
      const date = new Date(time);
      const now = new Date();
      const diff = now - date;
      if (diff < 3600000) {
        const minutes = Math.floor(diff / 60000);
        return minutes < 1 ? '刚刚' : `${minutes}分钟前`;
      }
      if (diff < 86400000) {
        return `${Math.floor(diff / 3600000)}小时前`;
      }
      return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
    };

    // 处理分析请求
    const handleAnalysisRequest = async (data) => {
      // 创建新对话用于分析
      await createNewConversation();
      
      // 添加系统提示消息，显示选中的消息摘要
      const summaryText = data.messages.map(m => `${m.user}: ${m.content.substring(0, 50)}${m.content.length > 50 ? '...' : ''}`).join('\n');
      
      messages.value.push({
        text: `📋 已选择 ${data.messages.length} 条消息进行分析：\n\n${summaryText}`,
        sender: '系统',
        isSelf: false,
        time: new Date(),
        isSystem: true
      });
      
      // 显示分析中提示
      isLoading.value = true;
      nextTick(() => scrollToBottom());
      
      try {
        const request = { 
          message: data.prompt,
          type: 'analysis'
        };
        if (currentConversationId.value) request.conversationId = currentConversationId.value;
        if (props.groupId) request.groupId = props.groupId;
        if (props.userQq) request.userQq = props.userQq;
        if (props.userNickname) request.userNickname = props.userNickname;

        console.log('发送分析请求:', request);
        const response = await astrBotApi.sendMessage(request);
        console.log('收到分析响应:', response);
        
        if (response) {
          if (response.conversationId) {
            currentConversationId.value = response.conversationId;
            localStorage.setItem('astrbot_current_conversation', response.conversationId);
          }
          // 使用独立的过滤模块处理响应
          const replyText = processAstrBotResponse(response);
          messages.value.push({
            text: replyText,
            sender: 'AstrBot',
            isSelf: false,
            time: new Date()
          });
        } else {
          console.error('响应为空');
          messages.value.push({
            text: '抱歉，未收到分析结果。',
            sender: 'AstrBot',
            isSelf: false,
            time: new Date()
          });
        }
      } catch (error) {
        console.error('分析失败:', error);
        messages.value.push({
          text: '抱歉，分析过程中出现错误，请稍后重试。',
          sender: 'AstrBot',
          isSelf: false,
          time: new Date()
        });
      } finally {
        isLoading.value = false;
        nextTick(() => scrollToBottom());
      }
    };

    onMounted(() => {
      loadSettings();
      checkStatus();
      loadConversations();
      restoreConversation();
      setInterval(checkStatus, 30000);
    });

    // 监听 userQq 变化，重新加载设置
    watch(() => props.userQq, (newUserQq) => {
      if (newUserQq) {
        loadSettings();
      }
    });

    return {
      messages,
      inputMessage,
      messagesContainer,
      inputRef,
      botAvatarInput,
      userAvatarInput,
      isLoading,
      isOnline,
      conversations,
      currentConversationId,
      currentConversationTitle,
      showConversationList,
      showConfirmDialog,
      userAvatar,
      botAvatar,
      botName,
      showSettings,
      sendMessage,
      formatTime,
      formatDate,
      loadConversation,
      createNewConversation,
      showDeleteConfirm,
      cancelDelete,
      confirmDelete,
      handleAnalysisRequest,
      saveSettings,
      closeSettings,
      handleBotAvatarError,
      handleUserAvatarError,
      handleBotAvatarUpload,
      handleUserAvatarUpload
    };
  }
};
</script>

<style scoped>
.astrbot-chat {
  display: flex;
  flex-direction: column;
  height: 100%;
  background-color: #f5f5f5;
  position: relative;
}

.chat-header {
  display: flex;
  align-items: center;
  padding: 15px 20px;
  background-color: white;
  border-bottom: 1px solid #e0e0e0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.header-avatar {
  width: 40px;
  height: 40px;
  margin-right: 12px;
  border-radius: 50%;
  overflow: hidden;
}

.header-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.header-info {
  flex: 1;
}

.header-info h3 {
  margin: 0;
  font-size: 16px;
  color: #2c3e50;
}

.header-info .status {
  font-size: 12px;
}

.header-info .status.online {
  color: #27ae60;
}

.header-info .status.offline {
  color: #e74c3c;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.action-btn {
  background: none;
  border: none;
  font-size: 20px;
  cursor: pointer;
  padding: 5px;
  border-radius: 4px;
  transition: background-color 0.2s;
}

.action-btn:hover {
  background-color: #f0f0f0;
}

.conversation-panel {
  position: absolute;
  top: 70px;
  right: 10px;
  width: 280px;
  max-height: 400px;
  background: white;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  z-index: 100;
  display: flex;
  flex-direction: column;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #e0e0e0;
}

.panel-header h4 {
  margin: 0;
  font-size: 14px;
  color: #2c3e50;
}

.close-btn {
  background: none;
  border: none;
  font-size: 16px;
  cursor: pointer;
  color: #7f8c8d;
}

.conversation-list {
  overflow-y: auto;
  max-height: 350px;
  padding: 8px;
}

.conversation-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-radius: 6px;
  transition: background-color 0.2s;
  margin-bottom: 4px;
}

.conversation-item:hover {
  background-color: #f5f5f5;
}

.conversation-item.active {
  background-color: #e3f2fd;
}

.conv-content {
  flex: 1;
  cursor: pointer;
  min-width: 0;
}

.delete-btn {
  background: none;
  border: none;
  font-size: 14px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  opacity: 0.6;
  transition: opacity 0.2s, background-color 0.2s;
}

.delete-btn:hover {
  opacity: 1;
  background-color: #ffebee;
}

/* 删除确认弹窗样式 */
.confirm-dialog-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.confirm-dialog {
  background: white;
  border-radius: 12px;
  width: 90%;
  max-width: 400px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
  animation: dialogSlideIn 0.2s ease-out;
}

@keyframes dialogSlideIn {
  from {
    opacity: 0;
    transform: translateY(-20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.confirm-dialog-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 20px 10px;
  border-bottom: 1px solid #f0f0f0;
}

.confirm-icon {
  font-size: 24px;
}

.confirm-dialog-header h3 {
  margin: 0;
  font-size: 18px;
  color: #2c3e50;
}

.confirm-dialog-body {
  padding: 20px;
}

.confirm-dialog-body p {
  margin: 0 0 8px;
  font-size: 15px;
  color: #2c3e50;
}

.confirm-hint {
  font-size: 13px !important;
  color: #7f8c8d !important;
}

.confirm-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 0 20px 20px;
}

.btn-cancel {
  padding: 10px 20px;
  border: 1px solid #ddd;
  background: white;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  color: #666;
  transition: all 0.2s;
}

.btn-cancel:hover {
  background: #f5f5f5;
}

.btn-confirm {
  padding: 10px 20px;
  border: none;
  background: #e74c3c;
  color: white;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
}

.btn-confirm:hover {
  background: #c0392b;
}

/* 设置弹窗样式 */
.settings-dialog-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.settings-dialog {
  background: white;
  border-radius: 12px;
  width: 90%;
  max-width: 450px;
  max-height: 80vh;
  overflow-y: auto;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
  animation: dialogSlideIn 0.2s ease-out;
}

.settings-dialog-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px;
  border-bottom: 1px solid #f0f0f0;
  position: sticky;
  top: 0;
  background: white;
}

.settings-icon {
  font-size: 24px;
}

.settings-dialog-header h3 {
  margin: 0;
  flex: 1;
  font-size: 18px;
  color: #2c3e50;
}

.settings-dialog-body {
  padding: 20px;
}

.settings-section {
  margin-bottom: 24px;
}

.settings-section:last-child {
  margin-bottom: 0;
}

.settings-section h4 {
  margin: 0 0 16px 0;
  font-size: 14px;
  color: #7f8c8d;
  text-transform: uppercase;
  letter-spacing: 1px;
}

.setting-item {
  margin-bottom: 16px;
}

.setting-item label {
  display: block;
  margin-bottom: 8px;
  font-size: 14px;
  color: #2c3e50;
  font-weight: 500;
}

.setting-item input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
  transition: border-color 0.2s;
}

.setting-item input:focus {
  outline: none;
  border-color: #3498db;
}

.avatar-preview {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  margin-top: 8px;
  object-fit: cover;
  border: 2px solid #e0e0e0;
}

.avatar-upload {
  display: flex;
  align-items: center;
  gap: 16px;
}

.avatar-preview-large {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid #e0e0e0;
  flex-shrink: 0;
}

.upload-actions {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.btn-upload {
  padding: 8px 16px;
  background: #f0f7ff;
  border: 1px solid #d0e3ff;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  color: #3498db;
  transition: all 0.2s;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: fit-content;
}

.btn-upload:hover {
  background: #e0f0ff;
  border-color: #3498db;
}

.upload-hint {
  font-size: 12px;
  color: #95a5a6;
}

.url-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 13px;
}

.settings-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 0 20px 20px;
  position: sticky;
  bottom: 0;
  background: white;
}

.conv-title {
  font-size: 14px;
  color: #2c3e50;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conv-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #7f8c8d;
  margin-top: 4px;
}

.empty-conversations {
  text-align: center;
  padding: 20px;
  color: #95a5a6;
  font-size: 14px;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background-color: #f5f5f5;
}

.empty-chat {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #95a5a6;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 10px;
}

.empty-hint {
  font-size: 12px;
  margin-top: 8px;
  color: #bdc3c7;
}

.message-wrapper {
  display: flex;
  margin-bottom: 15px;
}

.message-wrapper.message-self {
  justify-content: flex-end;
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
  background-color: white;
  border-bottom-left-radius: 4px;
  flex-direction: row;
  margin-right: auto;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
}

.message-right {
  background-color: #3498db;
  color: white;
  border-bottom-right-radius: 4px;
  flex-direction: row-reverse;
  margin-left: auto;
}

.message-avatar img {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
}

.message-content {
  display: flex;
  flex-direction: column;
}

.message-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 12px;
}

.message-left .message-header {
  color: #7f8c8d;
}

.message-right .message-header {
  color: rgba(255, 255, 255, 0.8);
}

.message-sender {
  font-weight: 500;
}

.message-text {
  word-break: break-word;
  line-height: 1.5;
  white-space: pre-wrap;
}

/* 系统消息样式 */
.message-system {
  justify-content: center;
}

.message-system-bubble {
  background-color: #f0f7ff;
  border: 1px solid #d0e3ff;
  border-radius: 12px;
  max-width: 90%;
  margin: 10px auto;
  padding: 12px 16px;
}

.message-system-text {
  color: #4a6fa5;
  font-size: 13px;
  line-height: 1.6;
}

.loading-indicator {
  text-align: center;
  padding: 10px;
  color: #7f8c8d;
}

.loading-dots::after {
  content: '...';
  animation: dots 1.5s steps(4, end) infinite;
}

@keyframes dots {
  0%, 20% { content: ''; }
  40% { content: '.'; }
  60% { content: '..'; }
  80%, 100% { content: '...'; }
}

.chat-input-area {
  padding: 15px 20px;
  background-color: white;
  border-top: 1px solid #e0e0e0;
}

.input-wrapper {
  display: flex;
  gap: 10px;
}

.input-wrapper input {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #ddd;
  border-radius: 24px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}

.input-wrapper input:focus {
  border-color: #3498db;
}

.input-wrapper input:disabled {
  background-color: #f5f5f5;
  cursor: not-allowed;
}

.send-btn {
  padding: 12px 24px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 24px;
  font-size: 14px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.send-btn:hover:not(:disabled) {
  background-color: #2980b9;
}

.send-btn:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
}

.conversation-info {
  font-size: 12px;
  color: #7f8c8d;
  margin-top: 8px;
  text-align: center;
}
</style>
