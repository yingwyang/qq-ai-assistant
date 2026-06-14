<template>
  <div class="app" :class="layoutMode">
    <!-- 聊天页面 -->
    <div class="chat-page">
      <!-- 左侧导航栏 -->
      <Sidebar 
        :is-logged-in="isLoggedIn"
        :user-info="userInfo"
        :is-collapsed="shouldCollapseSidebar"
        @tab-change="handleTabChange" 
        @logout="handleLogout"
        @open-login-modal="showLoginModal = true"
        @open-system-modal="showSystemModal = true"
        @open-user-profile="openUserProfile"
        @open-persona-manager="showPersonaManager = true"
        @open-admin-dashboard="handleAdminDashboard"
        @select-group="handleSelectGroup"
      />
      
      <!-- 三栏布局主内容区 -->
      <main class="chat-main" :class="{ 'mobile': isMobile, 'tablet': isTablet, 'desktop': isDesktop }">
        <!-- 未登录状态显示登录提示 -->
        <template v-if="!isLoggedIn">
          <div class="login-prompt">
            <div class="login-prompt-content">
              <div class="login-icon"><Icon name="lock" :size="64" /></div>
              <h2>请先登录</h2>
              <p>登录后即可查看群聊消息</p>
              <button class="login-btn" @click="showLoginModal = true">立即登录</button>
            </div>
          </div>
        </template>
        
        <!-- 已登录状态显示布局 -->
        <template v-else>
          <!-- 移动端：标签页切换 -->
          <template v-if="isMobile">
            <div class="mobile-tabs">
              <button 
                :class="['mobile-tab', { active: activeMobileTab === 'chat' }]"
                @click="activeMobileTab = 'chat'"
              >
                群聊
              </button>
              <button 
                :class="['mobile-tab', { active: activeMobileTab === 'ai' }]"
                @click="activeMobileTab = 'ai'"
              >
                AI助手
              </button>
            </div>
            <div class="mobile-content">
              <div v-show="activeMobileTab === 'chat'" class="mobile-panel">
                <ChatInterface 
                  :groupId="selectedGroupId" 
                  @analysis-result="handleAnalysisResult"
                />
              </div>
              <div v-show="activeMobileTab === 'ai'" class="mobile-panel">
                <AstrBotChat 
                  ref="astrBotChatRef"
                  :groupId="selectedGroupId"
                />
              </div>
            </div>
          </template>
          
          <!-- 平板：可切换的双栏 -->
          <template v-else-if="isTablet">
            <div class="tablet-layout">
              <div class="tablet-chat" :class="{ 'hidden': showAIPanel }">
                <ChatInterface 
                  :groupId="selectedGroupId" 
                  @analysis-result="handleAnalysisResult"
                />
              </div>
              <div class="tablet-ai" :class="{ 'hidden': !showAIPanel }">
                <AstrBotChat 
                  ref="astrBotChatRef"
                  :groupId="selectedGroupId"
                />
              </div>
              <button class="tablet-toggle" @click="showAIPanel = !showAIPanel">
                {{ showAIPanel ? '← 返回群聊' : 'AI助手 →' }}
              </button>
            </div>
          </template>
          
          <!-- 桌面端：三栏布局 -->
          <template v-else>
            <!-- 中间：群消息列表 -->
            <div class="center-panel">
              <ChatInterface 
                :groupId="selectedGroupId" 
                @analysis-result="handleAnalysisResult"
              />
            </div>
            
            <!-- 右侧：AstrBot 对话框 -->
            <div class="right-panel">
              <AstrBotChat 
                ref="astrBotChatRef"
                :groupId="selectedGroupId"
              />
            </div>
          </template>
        </template>
      </main>
    </div>

    <!-- 用户登录模态框 -->
    <UserLogin 
      v-model:visible="showLoginModal" 
      @login-success="handleLoginSuccess" 
    />
    
    <!-- 系统控制模态框（NapCat登录） -->
    <LoginModal 
      v-model:visible="showSystemModal" 
      @login-status-changed="handleNapCatStatusChanged" 
    />
    
    <!-- 管理员仪表盘弹窗 -->
    <div v-if="showAdminDashboard" class="admin-modal-overlay" @click="showAdminDashboard = false">
      <div class="admin-modal-content" @click.stop>
        <AdminDashboard @close="showAdminDashboard = false" />
      </div>
    </div>
    
    <!-- 用户个人信息页面 -->
    <UserProfile
      v-model:visible="showUserProfile"
      @profile-updated="handleProfileUpdated"
    />
    
    <!-- 人格管理弹窗 -->
    <div v-if="showPersonaManager" class="persona-modal-overlay" @click="showPersonaManager = false">
      <div class="persona-modal-content" @click.stop>
        <PersonaManager />
      </div>
    </div>
    
    <!-- 全局 Toast 通知 -->
    <Toast />
    
    <!-- 全局确认对话框 -->
    <ConfirmDialog />
  </div>
</template>

<script>
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import Icon from '../components/Icon.vue';
import Sidebar from '../components/Sidebar.vue';
import ChatInterface from '../components/ChatInterface.vue';
import AstrBotChat from '../components/AstrBotChat.vue';
import LoginModal from '../components/LoginModal.vue';
import UserLogin from '../components/UserLogin.vue';
import UserProfile from '../components/UserProfile.vue';
import Toast from '../components/Toast.vue';
import ConfirmDialog from '../components/ConfirmDialog.vue';
import PersonaManager from '../components/PersonaManager.vue';
import AdminDashboard from '../components/AdminDashboard.vue';
import { useResponsive } from '../composables/useResponsive';
import { authApi } from '../services/api';

export default {
  name: 'HomeView',
  components: {
    Icon,
    Sidebar,
    ChatInterface,
    AstrBotChat,
    LoginModal,
    UserLogin,
    UserProfile,
    Toast,
    ConfirmDialog,
    PersonaManager,
    AdminDashboard
  },
  setup() {
    const router = useRouter();
    const isLoggedIn = ref(false);
    const activeTab = ref('recent');
    const gptSovitsRunning = ref(false);
    const showLoginModal = ref(false);
    const showSystemModal = ref(false);
    const showUserProfile = ref(false);
    const showPersonaManager = ref(false);
    const showAdminDashboard = ref(false);
    const selectedGroupId = ref('');
    const astrBotChatRef = ref(null);
    const userInfo = ref(null);
    
    // 响应式布局
    const { isMobile, isTablet, isDesktop, layoutMode, shouldCollapseSidebar } = useResponsive();
    
    // 移动端标签页状态
    const activeMobileTab = ref('chat');
    
    // 平板端AI面板显示状态
    const showAIPanel = ref(false);

    // 从 localStorage 恢复登录状态和群号
    onMounted(async () => {
      const savedGroupId = localStorage.getItem('selectedGroupId');
      const savedUserInfo = localStorage.getItem('user_info');
      const token = localStorage.getItem('auth_token');
      
      if (savedGroupId) {
        selectedGroupId.value = savedGroupId;
      }
      
      // 检查用户登录状态
      if (token) {
        try {
          // 验证token有效性
          const userData = await authApi.getCurrentUser();
          userInfo.value = userData;
          isLoggedIn.value = true;
          localStorage.setItem('isLoggedIn', 'true');
          if (userData.role) {
            localStorage.setItem('user_role', userData.role);
          }
        } catch (error) {
          console.log('Token验证失败:', error);
          // Token无效，清除登录状态
          handleLogout();
        }
      } else {
        isLoggedIn.value = false;
      }
    });

    const handleLoginSuccess = (userData) => {
      userInfo.value = {
        username: userData.username,
        nickname: userData.nickname,
        role: userData.role,
        avatar: userData.avatar
      };
      isLoggedIn.value = true;
      localStorage.setItem('isLoggedIn', 'true');
      if (userData.role) {
        localStorage.setItem('user_role', userData.role);
      }
    };

    const handleNapCatStatusChanged = (status) => {
      // NapCat登录状态变化，可以在这里处理相关逻辑
      console.log('NapCat登录状态:', status);
    };

    const handleTabChange = (tab) => {
      activeTab.value = tab;
    };

    const handleLogout = () => {
      isLoggedIn.value = false;
      userInfo.value = null;
      selectedGroupId.value = '';
      // 清除 localStorage
      localStorage.removeItem('auth_token');
      localStorage.removeItem('user_role');
      localStorage.removeItem('user_info');
      localStorage.removeItem('isLoggedIn');
      localStorage.removeItem('selectedGroupId');
      // 跳转到登录页
      router.push('/login');
    };

    const handleAdminDashboard = () => {
      const role = localStorage.getItem('user_role');
      if (role === 'ADMIN') {
        router.push('/admin');
      } else {
        showAdminDashboard.value = true;
      }
    };

    const handleSelectGroup = (groupId) => {
      selectedGroupId.value = groupId;
      // 保存群号到 localStorage
      localStorage.setItem('selectedGroupId', groupId);
      // 切换到最近对话标签
      if (activeTab.value !== 'recent') {
        activeTab.value = 'recent';
      }
    };

    // 处理分析结果 - 转发给 AstrBotChat
    const handleAnalysisResult = (data) => {
      if (astrBotChatRef.value && data.type === 'request') {
        astrBotChatRef.value.handleAnalysisRequest(data);
      }
    };

    // 打开用户个人信息页面
    const openUserProfile = () => {
      showUserProfile.value = true;
    };

    // 处理个人信息更新
    const handleProfileUpdated = async () => {
      // 刷新用户信息
      try {
        const userData = await authApi.getCurrentUser();
        userInfo.value = {
          username: userData.username,
          nickname: userData.nickname,
          role: userData.role,
          avatar: userData.avatar
        };
      } catch (error) {
        console.error('刷新用户信息失败:', error);
      }
    };

    return {
      isLoggedIn,
      activeTab,
      gptSovitsRunning,
      showLoginModal,
      showSystemModal,
      showUserProfile,
      showPersonaManager,
      showAdminDashboard,
      selectedGroupId,
      astrBotChatRef,
      userInfo,
      // 响应式布局
      isMobile,
      isTablet,
      isDesktop,
      layoutMode,
      shouldCollapseSidebar,
      activeMobileTab,
      showAIPanel,
      // 方法
      handleLoginSuccess,
      handleNapCatStatusChanged,
      handleTabChange,
      handleLogout,
      handleAdminDashboard,
      handleSelectGroup,
      handleAnalysisResult,
      openUserProfile,
      handleProfileUpdated
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

html, body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  line-height: 1.6;
  color: #333;
  background-color: #f5f5f5;
  margin: 0 !important;
  padding: 0 !important;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

#app {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
}

/* 聊天页面样式 */
.chat-page {
  min-height: 100vh;
  display: flex;
  width: 100vw;
  overflow: hidden;
}

.chat-main {
  flex: 1;
  background-color: #f5f5f5;
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* 中间面板：群消息 - 占50% */
.center-panel {
  flex: 0 0 50%;
  border-right: 1px solid #e0e0e0;
  overflow: hidden;
}

/* 右侧面板：AstrBot - 占50% */
.right-panel {
  flex: 0 0 50%;
  overflow: hidden;
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

/* 登录提示样式 */
.login-prompt {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  min-height: 100%;
}

.login-prompt-content {
  text-align: center;
  padding: 40px;
  background-color: white;
  border-radius: 12px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.login-icon {
  font-size: 64px;
  margin-bottom: 20px;
}

.login-prompt-content h2 {
  color: #2c3e50;
  margin-bottom: 10px;
  font-size: 24px;
}

.login-prompt-content p {
  color: #7f8c8d;
  margin-bottom: 24px;
  font-size: 16px;
}

.login-btn {
  background-color: #3498db;
  color: white;
  border: none;
  padding: 12px 32px;
  border-radius: 6px;
  font-size: 16px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.login-btn:hover {
  background-color: #2980b9;
}

/* ==================== 响应式布局样式 ==================== */

/* 移动端布局 (< 768px) */
.app.mobile .chat-main {
  flex-direction: column;
}

.mobile-tabs {
  display: flex;
  background: #fff;
  border-bottom: 1px solid #e0e0e0;
  flex-shrink: 0;
}

.mobile-tab {
  flex: 1;
  padding: 12px;
  border: none;
  background: #f5f5f5;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
}

.mobile-tab.active {
  background: #fff;
  color: #1890ff;
  border-bottom: 2px solid #1890ff;
}

.mobile-content {
  flex: 1;
  overflow: hidden;
  position: relative;
}

.mobile-panel {
  height: 100%;
  overflow: hidden;
}

/* 平板布局 (768px - 1023px) */
.app.tablet .chat-main {
  position: relative;
}

.tablet-layout {
  display: flex;
  width: 100%;
  height: 100%;
  position: relative;
}

.tablet-chat,
.tablet-ai {
  flex: 1;
  height: 100%;
  overflow: hidden;
  transition: transform 0.3s ease;
}

.tablet-chat.hidden {
  display: none;
}

.tablet-ai.hidden {
  display: none;
}

.tablet-toggle {
  position: absolute;
  bottom: 20px;
  right: 20px;
  padding: 10px 20px;
  background: #1890ff;
  color: white;
  border: none;
  border-radius: 20px;
  cursor: pointer;
  font-size: 14px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  z-index: 100;
  transition: all 0.2s;
}

.tablet-toggle:hover {
  background: #40a9ff;
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
}

/* 桌面端布局 (>= 1024px) */
.app.desktop .chat-main {
  flex-direction: row;
}

/* 大屏幕优化 (>= 1280px) */
@media (min-width: 1280px) {
  .center-panel {
    flex: 0 0 55%;
  }
  
  .right-panel {
    flex: 0 0 45%;
  }
}

/* 超大屏幕优化 (>= 1536px) */
@media (min-width: 1536px) {
  .center-panel {
    flex: 0 0 60%;
  }

  .right-panel {
    flex: 0 0 40%;
  }
}

/* 管理员弹窗 overlay */
.admin-modal-overlay,
.persona-modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.admin-modal-content,
.persona-modal-content {
  background-color: white;
  border-radius: 8px;
  max-width: 90%;
  max-height: 90%;
  overflow: auto;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}
</style>
