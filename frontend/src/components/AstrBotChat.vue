<template>
  <div class="astrbot-chat">
    <div class="chat-header">
      <div class="header-avatar">🤖</div>
      <div class="header-info">
        <h3>AstrBot 助手</h3>
        <span class="status">在线</span>
      </div>
    </div>
    
    <div class="chat-messages" ref="messagesContainer">
      <div v-if="messages.length === 0" class="empty-chat">
        <div class="empty-icon">💬</div>
        <p>开始与 AstrBot 对话</p>
      </div>
      
      <div
        v-for="(message, index) in messages"
        :key="index"
        class="message-wrapper"
        :class="{ 'message-self': message.isSelf }"
      >
        <div class="message-bubble" :class="message.isSelf ? 'message-right' : 'message-left'">
          <div class="message-avatar">
            <img :src="message.isSelf ? 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100' : 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100'" alt="avatar" />
          </div>
          <div class="message-content">
            <div class="message-header">
              <span class="message-sender">{{ message.sender }}</span>
              <span class="message-time">{{ formatTime(message.time) }}</span>
            </div>
            <div class="message-text">{{ message.text }}</div>
          </div>
        </div>
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
        />
        <button class="send-btn" @click="sendMessage" :disabled="!inputMessage.trim()">
          发送
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, nextTick } from 'vue';

export default {
  name: 'AstrBotChat',
  setup() {
    const messages = ref([]);
    const inputMessage = ref('');
    const messagesContainer = ref(null);
    const inputRef = ref(null);

    const sendMessage = async () => {
      if (!inputMessage.value.trim()) return;
      
      // 添加用户消息
      messages.value.push({
        text: inputMessage.value,
        sender: '我',
        isSelf: true,
        time: new Date()
      });
      
      const userMessage = inputMessage.value;
      inputMessage.value = '';
      
      // 滚动到底部
      nextTick(() => {
        scrollToBottom();
      });
      
      // TODO: 调用 AstrBot API 获取回复
      // 模拟 AstrBot 回复
      setTimeout(() => {
        messages.value.push({
          text: `这是 AstrBot 对 "${userMessage}" 的回复`,
          sender: 'AstrBot',
          isSelf: false,
          time: new Date()
        });
        nextTick(() => {
          scrollToBottom();
        });
      }, 1000);
    };

    const scrollToBottom = () => {
      if (messagesContainer.value) {
        messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
      }
    };

    const formatTime = (time) => {
      if (!time) return '';
      const date = new Date(time);
      return date.toLocaleTimeString('zh-CN', { 
        hour: '2-digit', 
        minute: '2-digit' 
      });
    };

    return {
      messages,
      inputMessage,
      messagesContainer,
      inputRef,
      sendMessage,
      formatTime
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
  font-size: 40px;
  margin-right: 12px;
}

.header-info h3 {
  margin: 0;
  font-size: 16px;
  color: #2c3e50;
}

.header-info .status {
  font-size: 12px;
  color: #27ae60;
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
</style>
