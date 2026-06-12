<template>
  <div class="message-display">
    <h2>群聊消息</h2>
    <div class="group-selector">
      <input 
        type="text" 
        v-model="groupId" 
        placeholder="输入群聊ID" 
        class="group-input"
      />
      <button @click="loadMessages" class="btn-load">加载消息</button>
    </div>
    <div v-if="messages.length > 0" class="messages-container">
      <div v-for="message in messages" :key="message.id" class="message-item">
        <div class="message-header">
          <span class="message-user">{{ message.userName }}</span>
          <span class="message-time">{{ formatTime(message.timestamp) }}</span>
        </div>
        <div class="message-content">{{ message.content }}</div>
        <div v-if="message.aiSummary" class="message-summary">
          <h4>AI 总结:</h4>
          <p>{{ message.aiSummary }}</p>
        </div>
      </div>
    </div>
    <div v-else-if="isLoading" class="loading">
      <p>加载消息中...</p>
    </div>
    <div v-else-if="hasAttemptedLoad" class="no-messages">
      <p>暂无消息，请输入群聊ID并点击加载按钮</p>
    </div>
  </div>
</template>

<script>
import { ref } from 'vue';
import { messageApi } from '../services/api';
import { showToast } from './Toast.vue';

export default {
  name: 'MessageDisplay',
  setup() {
    const groupId = ref('');
    const messages = ref([]);
    const isLoading = ref(false);
    const hasAttemptedLoad = ref(false);

    const loadMessages = async () => {
      if (!groupId.value) {
        showToast('请输入群聊ID', 'warning');
        return;
      }

      isLoading.value = true;
      hasAttemptedLoad.value = true;
      try {
        const response = await messageApi.getMessagesByGroupId(groupId.value);
        messages.value = response;
      } catch (error) {
        console.error('加载消息失败:', error);
        showToast('加载消息失败: ' + error.message, 'error');
      } finally {
        isLoading.value = false;
      }
    };

    const formatTime = (timestamp) => {
      if (!timestamp) return '';
      const date = new Date(timestamp);
      return date.toLocaleString();
    };

    return {
      groupId,
      messages,
      isLoading,
      hasAttemptedLoad,
      loadMessages,
      formatTime
    };
  }
};
</script>

<style scoped>
.message-display {
  max-width: 800px;
  margin: 0 auto;
  padding: 20px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  background-color: #f9f9f9;
}

.group-selector {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}

.group-input {
  flex: 1;
  padding: 8px;
  border: 1px solid #ddd;
  border-radius: 4px;
}

.btn-load {
  padding: 8px 16px;
  background-color: #2196F3;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.btn-load:hover {
  background-color: #0b7dda;
}

.messages-container {
  max-height: 500px;
  overflow-y: auto;
}

.message-item {
  padding: 10px;
  margin-bottom: 10px;
  border-bottom: 1px solid #e0e0e0;
}

.message-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 5px;
}

.message-user {
  font-weight: bold;
  color: #333;
}

.message-time {
  font-size: 12px;
  color: #666;
}

.message-content {
  margin-bottom: 5px;
  line-height: 1.4;
}

.message-summary {
  margin-top: 10px;
  padding: 10px;
  background-color: #e3f2fd;
  border-left: 4px solid #2196F3;
  border-radius: 4px;
}

.message-summary h4 {
  margin-top: 0;
  margin-bottom: 5px;
  font-size: 14px;
  color: #1976D2;
}

.loading,
.no-messages {
  padding: 40px 0;
  text-align: center;
  color: #666;
}
</style>
