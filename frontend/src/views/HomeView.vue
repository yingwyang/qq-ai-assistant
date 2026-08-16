<template>
  <div class="app" :class="[layoutMode, 'theme-' + (currentTheme || 'light')]">
    <!-- 聊天页面 -->
    <div class="chat-page">
      <!-- 左侧导航栏 -->
      <Sidebar 
        ref="sidebarRef"
        :is-logged-in="isLoggedIn"
        :user-info="userInfo"
        :is-collapsed="shouldCollapseSidebar"
        @tab-change="handleTabChange" 
        @logout="handleLogout"
        @open-login-modal="showLoginModal = true"
        @open-system-modal="showSystemModal = true"
        @open-user-profile="openUserProfile"
        @open-persona-manager="showPersonaManager = true"
        @select-group="handleSelectGroup"
        @navigate-admin="handleNavigateAdmin"
        @navigate-docs="handleNavigateDocs"
      />
      
      <!-- 三栏布局主内容区 -->
      <main class="chat-main" :class="{ 'mobile': isMobile, 'tablet': isTablet, 'desktop': isDesktop }">
        <!-- 认证检查中显示加载状态 -->
        <template v-if="isAuthChecking">
          <div class="auth-loading">
            <div class="auth-loading-spinner"></div>
            <p>加载中...</p>
          </div>
        </template>
        
        <!-- 未登录状态显示登录提示 -->
        <template v-else-if="!isLoggedIn">
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
                  :group="selectedGroup" 
                  @analysis-result="handleAnalysisResult"
                  @new-message-arrived="handleNewMessageArrived"
                />
              </div>
              <div v-show="activeMobileTab === 'ai'" class="mobile-panel">
                <AstrBotChat
                  ref="astrBotChatRef"
                  :groupId="selectedGroup?.groupId"
                  :groupName="selectedGroup?.groupName"
                  :userId="userInfo?.id ? String(userInfo.id) : ''"
                  :userNickname="userInfo?.nickname || userInfo?.username || ''"
                />
              </div>
            </div>
          </template>
          
          <!-- 平板：可切换的双栏 -->
          <template v-else-if="isTablet">
            <div class="tablet-layout">
              <div class="tablet-chat" :class="{ 'hidden': showAIPanel }">
                <ChatInterface 
                  :group="selectedGroup" 
                  @analysis-result="handleAnalysisResult"
                  @new-message-arrived="handleNewMessageArrived"
                />
              </div>
              <div class="tablet-ai" :class="{ 'hidden': !showAIPanel }">
                <AstrBotChat
                  ref="astrBotChatRef"
                  :groupId="selectedGroup?.groupId"
                  :groupName="selectedGroup?.groupName"
                  :userId="userInfo?.id ? String(userInfo.id) : ''"
                  :userNickname="userInfo?.nickname || userInfo?.username || ''"
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
            <div
              ref="centerPanelRef"
              class="center-panel"
              :class="{ collapsed: centerWidth <= 0, resizing: isResizing }"
              :style="{ flex: `0 0 ${centerWidth > 0 ? centerWidth : 0}%` }"
            >
              <ChatInterface
                :group="selectedGroup"
                @analysis-result="handleAnalysisResult"
                @new-message-arrived="handleNewMessageArrived"
              />
              <!-- 右侧收起时显示恢复按钮 -->
              <button
                v-if="rightWidth <= 0"
                class="panel-expand-btn"
                title="展开 AstrBot 聊天框"
                @click="expandRightPanel"
              >
                <Icon name="chevron-left" :size="20" />
                <span>AI</span>
              </button>
            </div>

            <!-- 可拖动分隔条 -->
            <div
              v-if="rightWidth > 0"
              class="panel-resizer"
              :class="{ resizing: isResizing }"
              title="拖动调整左右面板宽度"
              @mousedown="startResize"
            ></div>

            <!-- 右侧：AstrBot 对话框 -->
            <div
              ref="rightPanelRef"
              class="right-panel"
              :class="{ collapsed: rightWidth <= 0, resizing: isResizing }"
              :style="{ flex: `0 0 ${rightWidth > 0 ? rightWidth : 0}%` }"
            >
              <AstrBotChat
                ref="astrBotChatRef"
                :groupId="selectedGroup?.groupId"
                :groupName="selectedGroup?.groupName"
                :userId="userInfo?.id ? String(userInfo.id) : ''"
                :userNickname="userInfo?.nickname || userInfo?.username || ''"
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
import { ref, onMounted, onUnmounted, computed, nextTick, provide } from 'vue';
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
import { useResponsive } from '../composables/useResponsive';
import { useTheme } from '../composables/useTheme';
import { authApi, logout } from '../services/api';
import { useAutoStartAfterLogin } from '../composables/useAutoStartAfterLogin';
import { useUserCreditsStore } from '../composables/useUserCreditsStore';

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
    PersonaManager
  },
  setup() {
    const router = useRouter();
    const { runSequence } = useAutoStartAfterLogin();
    const { reload: reloadCredits, reset: resetCredits } = useUserCreditsStore();
    const isLoggedIn = ref(false);
    const isAuthChecking = ref(true); // 认证检查中，防止"请先登录"闪现
    const activeTab = ref('recent');
    const gptSovitsRunning = ref(false);
    const showLoginModal = ref(false);
    const showSystemModal = ref(false);
    const showUserProfile = ref(false);
    const showPersonaManager = ref(false);
    const selectedGroup = ref(null); // { groupId, ownerQq }
    const astrBotChatRef = ref(null);
    const sidebarRef = ref(null); // Sidebar 组件引用，暴露了 refreshGroups 方法
    // 提供 refreshSidebar 供深层子组件触发侧边栏刷新（保持向后兼容，原 ref 调用仍保留）
    provide('refreshSidebar', () => sidebarRef.value?.refreshGroups?.());
    const userInfo = ref(null);
    const centerPanelRef = ref(null);
    const rightPanelRef = ref(null);

    // 响应式布局
    const { isMobile, isTablet, isDesktop, layoutMode, shouldCollapseSidebar } = useResponsive();

    const { theme: currentTheme } = useTheme();

    // 移动端标签页状态
    const activeMobileTab = ref('chat');

    // 平板端AI面板显示状态
    const showAIPanel = ref(false);

    // 桌面端中间/右侧面板宽度（百分比），支持拖动调整
    const centerWidth = ref(50);
    const isResizing = ref(false);
    const COLLAPSE_THRESHOLD = 10; // 小于 10% 自动收起

    const rightWidth = computed(() => 100 - centerWidth.value);

    const expandRightPanel = () => {
      // 恢复右侧默认宽度 30%，中间占 70%
      centerWidth.value = 70;
      localStorage.setItem('home_center_width', String(centerWidth.value));
    };

    const startResize = (e) => {
      if (!isDesktop.value) return;
      e.preventDefault();
      isResizing.value = true;
      document.body.style.userSelect = 'none';

      const mainEl = centerPanelRef.value?.parentElement;
      if (!mainEl) return;
      const mainRect = mainEl.getBoundingClientRect();

      const onMouseMove = (moveEvent) => {
        // 鼠标移出窗口或按键丢失时自动结束拖动
        if ((moveEvent.buttons & 1) === 0) {
          onMouseUp();
          return;
        }
        const x = moveEvent.clientX - mainRect.left;
        const percent = (x / mainRect.width) * 100;
        let next = Math.max(0, Math.min(100, percent));

        // 接近阈值时自动收起小的一边
        if (next < COLLAPSE_THRESHOLD) {
          next = 0;
        } else if (100 - next < COLLAPSE_THRESHOLD) {
          next = 100;
        }

        centerWidth.value = next;
      };

      const onMouseUp = () => {
        isResizing.value = false;
        document.body.style.userSelect = '';
        document.removeEventListener('mousemove', onMouseMove);
        document.removeEventListener('mouseup', onMouseUp);
        window.removeEventListener('mouseup', onMouseUp);
        // 持久化用户偏好
        localStorage.setItem('home_center_width', String(centerWidth.value));
      };

      document.addEventListener('mousemove', onMouseMove);
      document.addEventListener('mouseup', onMouseUp);
      window.addEventListener('mouseup', onMouseUp);
    };

    // 从 localStorage 恢复登录状态和群号
    let _handleAuthLogout = null;

    onMounted(async () => {
      const savedGroupRaw = localStorage.getItem('selectedGroup');
      const savedUserInfo = localStorage.getItem('user_info');
      const savedCenterWidth = localStorage.getItem('home_center_width');

      if (savedCenterWidth) {
        const parsed = parseFloat(savedCenterWidth);
        if (!isNaN(parsed) && parsed >= 0 && parsed <= 100) {
          centerWidth.value = parsed;
        }
      }

      if (savedGroupRaw) {
        try {
          selectedGroup.value = JSON.parse(savedGroupRaw);
        } catch (e) {
          selectedGroup.value = null;
        }
      }

      // 先从localStorage恢复userInfo（包含id），避免刷新时AstrBotChat props瞬间为空
      if (savedUserInfo) {
        try {
          const parsed = JSON.parse(savedUserInfo);
          userInfo.value = parsed;
        } catch (e) {
          userInfo.value = null;
        }
      }

      // 监听 token 失效事件
      _handleAuthLogout = () => {
        // 直接跳转，避免中间状态导致闪屏
        router.replace('/login');
      };
      window.addEventListener('auth:logout', _handleAuthLogout);

      // 检查用户登录状态(同源 Cookie 自动携带,直接向后端验证)
      {
        try {
          // 验证登录态有效性
          const userData = await authApi.getCurrentUser();
          if (userData) {
            userInfo.value = {
              id: userData.id,
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
          } else {
            console.log('登录验证失败，跳转登录页');
            router.replace('/login');
            return;
          }
        } catch (e) {
          console.log('登录验证异常:', e);
          router.replace('/login');
          return;
        }
      }
      // 认证检查完成，无论成功失败都关闭加载状态
      isAuthChecking.value = false;
    });

    onUnmounted(() => {
      // 清理事件监听器，防止组件卸载后仍触发路由跳转
      if (_handleAuthLogout) {
        window.removeEventListener('auth:logout', _handleAuthLogout);
        _handleAuthLogout = null;
      }
    });

    const handleLoginSuccess = (userData) => {
      userInfo.value = {
        id: userData.id,
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
      reloadCredits();
      nextTick(() => {
        runSequence();
      });
    };

    const handleNapCatStatusChanged = (status) => {
      // NapCat登录状态变化，可以在这里处理相关逻辑
      console.log('NapCat登录状态:', status);
    };

    const handleTabChange = (tab) => {
      activeTab.value = tab;
    };

    const handleLogout = async () => {
      // 1. 先调用后端 logout 停止插件 + 注销 JWT（此时 token 还在，请求能通过认证）
      try {
        await logout();
      } catch (e) {
        console.warn('后端登出失败（忽略）:', e);
      }

      // 2. 清除状态和 localStorage
      isLoggedIn.value = false;
      userInfo.value = null;
      selectedGroup.value = null;
      resetCredits();
      localStorage.removeItem('user_role');
      localStorage.removeItem('user_info');
      localStorage.removeItem('isLoggedIn');
      localStorage.removeItem('selectedGroup');
      // 清除 auto_start_attempted 标记，下次登录重新启动
      sessionStorage.removeItem('auto_start_attempted');

      // 3. 最后跳转到登录页（此时 token 已清除，LoginPage 不会跳回）
      router.replace('/login');
    };

    const handleSelectGroup = (group) => {
      selectedGroup.value = group;
      if (group) {
        localStorage.setItem('selectedGroup', JSON.stringify(group));
      } else {
        localStorage.removeItem('selectedGroup');
      }
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

    // ChatInterface 收到新消息时，刷新 Sidebar 群聊列表（按最新消息时间排序）
    const handleNewMessageArrived = () => {
      if (sidebarRef.value && sidebarRef.value.refreshGroups) {
        sidebarRef.value.refreshGroups();
      }
    };

    // 打开用户个人信息页面
    const openUserProfile = () => {
      showUserProfile.value = true;
    };

    const handleNavigateAdmin = () => {
      if (router.currentRoute.value.path === '/admin') {
        window.dispatchEvent(new CustomEvent('app:refresh'));
      } else {
        router.push('/admin');
      }
    };

    const handleNavigateDocs = () => {
      const currentPath = router.currentRoute.value.path;
      if (currentPath.startsWith('/docs')) {
        window.dispatchEvent(new CustomEvent('app:refresh'));
      } else {
        router.push('/docs');
      }
    };

    // 处理个人信息更新
    const handleProfileUpdated = async () => {
      // 刷新用户信息
      try {
        const userData = await authApi.getCurrentUser();
        userInfo.value = {
          id: userData.id,
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
      isAuthChecking,
      activeTab,
      gptSovitsRunning,
      showLoginModal,
      showSystemModal,
      showUserProfile,
      showPersonaManager,
      selectedGroup,
      astrBotChatRef,
      userInfo,
      sidebarRef,
      centerPanelRef,
      rightPanelRef,
      // 响应式布局
      isMobile,
      isTablet,
      isDesktop,
      layoutMode,
      shouldCollapseSidebar,
      currentTheme,
      activeMobileTab,
      showAIPanel,
      // 面板拖拽
      centerWidth,
      rightWidth,
      isResizing,
      startResize,
      expandRightPanel,
      // 方法
      handleLoginSuccess,
      handleNapCatStatusChanged,
      handleTabChange,
      handleLogout,
      handleSelectGroup,
      handleAnalysisResult,
      handleNewMessageArrived,
      openUserProfile,
      handleNavigateAdmin,
      handleNavigateDocs,
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
  background-color: var(--bg-primary, #f5f5f5);
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* 中间面板：群消息 - 占50% */
.center-panel {
  flex: 0 0 50%;
  border-right: 1px solid var(--border-color, #e0e0e0);
  overflow: hidden;
  transition: flex-basis 0.15s ease;
  position: relative;
}

.center-panel.collapsed {
  flex: 0 0 0 !important;
  min-width: 0;
}

.center-panel.resizing,
.right-panel.resizing {
  transition: none;
}

/* 右侧面板：AstrBot - 占50% */
.right-panel {
  flex: 0 0 50%;
  overflow: hidden;
  transition: flex-basis 0.15s ease;
}

.right-panel.collapsed {
  flex: 0 0 0 !important;
  min-width: 0;
}

/* 可拖动分隔条 */
.panel-resizer {
  width: 6px;
  flex-shrink: 0;
  cursor: col-resize;
  background-color: var(--border-color, #e0e0e0);
  transition: background-color 0.2s;
  position: relative;
  z-index: 10;
}

.panel-resizer:hover,
.panel-resizer.resizing {
  background-color: var(--accent-color, #3498db);
}

/* 右侧面板收起后显示的展开按钮 */
.panel-expand-btn {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 28px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 10px 0;
  background-color: var(--bg-secondary, #fff);
  border: 1px solid var(--border-color, #e0e0e0);
  border-right: none;
  border-radius: 8px 0 0 8px;
  box-shadow: -2px 0 8px var(--card-shadow, rgba(0, 0, 0, 0.08));
  cursor: pointer;
  color: var(--text-secondary, #666);
  font-size: 12px;
  transition: background-color 0.2s, color 0.2s, width 0.2s;
  z-index: 100;
}

.panel-expand-btn:hover {
  background-color: var(--bg-tertiary, #e8f4fc);
  color: var(--accent-color, #3498db);
  width: 32px;
}

.panel-expand-btn span {
  writing-mode: vertical-rl;
  letter-spacing: 2px;
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

/* 认证加载状态 */
.auth-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  min-height: 100%;
  gap: 16px;
  color: #999;
}

.auth-loading-spinner {
  width: 40px;
  height: 40px;
  border: 3px solid #e0e0e0;
  border-top-color: #3498db;
  border-radius: 50%;
  animation: auth-spin 0.8s linear infinite;
}

@keyframes auth-spin {
  to {
    transform: rotate(360deg);
  }
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
  background-color: var(--bg-secondary, white);
  border-radius: 12px;
  box-shadow: 0 4px 12px var(--card-shadow, rgba(0, 0, 0, 0.1));
}

.login-icon {
  font-size: 64px;
  margin-bottom: 20px;
}

.login-prompt-content h2 {
  color: var(--sidebar-bg, #2c3e50);
  margin-bottom: 10px;
  font-size: 24px;
}

.login-prompt-content p {
  color: var(--text-secondary, #7f8c8d);
  margin-bottom: 24px;
  font-size: 16px;
}

.login-btn {
  background-color: var(--accent-color, #3498db);
  color: white;
  border: none;
  padding: 12px 32px;
  border-radius: 6px;
  font-size: 16px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.login-btn:hover {
  background-color: var(--accent-hover, #2980b9);
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
  background: var(--accent-color, #1890ff);
  color: white;
  border: none;
  border-radius: 20px;
  cursor: pointer;
  font-size: 14px;
  box-shadow: 0 2px 8px var(--card-shadow, rgba(0, 0, 0, 0.15));
  z-index: 100;
  transition: all 0.2s;
}

.tablet-toggle:hover {
  background: var(--accent-hover, #40a9ff);
  transform: translateY(-2px);
  box-shadow: 0 4px 12px var(--card-shadow, rgba(0, 0, 0, 0.2));
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

.persona-modal-content {
  background-color: var(--bg-secondary, white);
  border-radius: 8px;
  max-width: 90%;
  max-height: 90%;
  overflow: auto;
  box-shadow: 0 4px 12px var(--card-shadow, rgba(0, 0, 0, 0.15));
}
</style>
