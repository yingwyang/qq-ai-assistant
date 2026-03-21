<template>
  <div class="app">
    <!-- 聊天页面 -->
    <div class="chat-page">
      <!-- 左侧导航栏 -->
      <Sidebar 
        :is-logged-in="isLoggedIn"
        @tab-change="handleTabChange" 
        @logout="handleLogout"
        @open-login-modal="showLoginModal = true"
        @select-group="handleSelectGroup"
      />
      
      <!-- 主内容区 -->
      <main class="chat-main">
        <!-- 最近对话 -->
        <template v-if="activeTab === 'recent'">
          <ChatInterface :groupId="selectedGroupId" />
        </template>
        
        <!-- 我的智能体 -->
        <template v-else-if="activeTab === 'agents'">
          <div class="agents-page">
            <h2>我的智能体</h2>
            <div class="agents-list">
              <div class="agent-card">
                <div class="agent-avatar">🤖</div>
                <div class="agent-info">
                  <h3>AstrBot 助手</h3>
                  <p>基于 AstrBot 的 AI 聊天助手</p>
                </div>
                <div class="agent-status active">运行中</div>
              </div>
              
              <div class="agent-card">
                <div class="agent-avatar">🎙️</div>
                <div class="agent-info">
                  <h3>GPT-SoVITS</h3>
                  <p>语音合成服务</p>
                </div>
                <div class="agent-status" :class="{ active: gptSovitsRunning }">
                  {{ gptSovitsRunning ? '运行中' : '未启动' }}
                </div>
              </div>
            </div>
          </div>
        </template>
      </main>
    </div>

    <!-- 登录/系统控制模态框 -->
    <LoginModal 
      v-model:visible="showLoginModal" 
      @login-status-changed="handleLoginStatusChanged" 
    />
  </div>
</template>

<script>
import { ref } from 'vue';
import Sidebar from './components/Sidebar.vue';
import ChatInterface from './components/ChatInterface.vue';
import LoginModal from './components/LoginModal.vue';

export default {
  name: 'App',
  components: {
    Sidebar,
    ChatInterface,
    LoginModal
  },
  setup() {
    const isLoggedIn = ref(false);
    const activeTab = ref('recent');
    const gptSovitsRunning = ref(false);
    const showLoginModal = ref(false);
    const selectedGroupId = ref('');

    const handleLoginStatusChanged = (status) => {
      isLoggedIn.value = status;
    };

    const handleTabChange = (tab) => {
      activeTab.value = tab;
    };

    const handleLogout = () => {
      isLoggedIn.value = false;
    };

    const handleSelectGroup = (groupId) => {
      selectedGroupId.value = groupId;
      // 切换到最近对话标签
      if (activeTab.value !== 'recent') {
        activeTab.value = 'recent';
      }
    };

    return {
      isLoggedIn,
      activeTab,
      gptSovitsRunning,
      showLoginModal,
      selectedGroupId,
      handleLoginStatusChanged,
      handleTabChange,
      handleLogout,
      handleSelectGroup
    };
  }
};
</script>

<style>
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  line-height: 1.6;
  color: #333;
  background-color: #f5f5f5;
}

/* 聊天页面样式 */
.chat-page {
  min-height: 100vh;
  display: flex;
}

.chat-main {
  flex: 1;
  margin-left: 240px; /* 与 sidebar 宽度一致 */
  padding: 20px;
  transition: margin-left 0.3s ease;
  background-color: #f5f5f5;
}

/* 当侧边栏收起时 */
.sidebar.collapsed + .chat-main {
  margin-left: 60px;
}

/* 智能体页面样式 */
.agents-page {
  max-width: 800px;
  margin: 0 auto;
}

.agents-page h2 {
  margin-bottom: 20px;
  color: #2c3e50;
}

.agents-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
  gap: 20px;
}

.agent-card {
  background-color: white;
  border-radius: 8px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 15px;
  transition: transform 0.2s, box-shadow 0.2s;
}

.agent-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 8px rgba(0, 0, 0, 0.15);
}

.agent-avatar {
  font-size: 48px;
  width: 60px;
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #f8f9fa;
  border-radius: 8px;
}

.agent-info {
  flex: 1;
}

.agent-info h3 {
  margin: 0 0 5px 0;
  color: #2c3e50;
}

.agent-info p {
  margin: 0;
  color: #7f8c8d;
  font-size: 14px;
}

.agent-status {
  padding: 6px 12px;
  border-radius: 16px;
  font-size: 12px;
  font-weight: 500;
  background-color: #f1f3f4;
  color: #5f6368;
}

.agent-status.active {
  background-color: #d4edda;
  color: #155724;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .chat-main {
    margin-left: 60px;
  }
  
  .agents-list {
    grid-template-columns: 1fr;
  }
}
</style>
