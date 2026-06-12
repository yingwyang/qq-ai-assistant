<template>
  <div class="sidebar" :class="{ collapsed: isCollapsed }">
    <div class="sidebar-header">
      <button class="toggle-btn" @click="toggleSidebar">
        <span v-if="isCollapsed">☰</span>
        <span v-else>✕</span>
      </button>
      <h3 v-if="!isCollapsed" class="sidebar-title">铃音QQ对话</h3>
    </div>
    
    <!-- 用户信息区域 -->
    <div v-if="isLoggedIn && userInfo" class="user-info" @click="openUserProfile">
      <div class="user-avatar">
        <img :src="(userInfo.avatar ? (userInfo.avatar.startsWith('http') ? userInfo.avatar : 'http://localhost:8081' + userInfo.avatar) : 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100')" alt="avatar" />
      </div>
      <div v-if="!isCollapsed" class="user-details">
        <div class="user-nickname">{{ userInfo.nickname || userInfo.username }}</div>
        <div class="user-role">{{ userInfo.role === 'ADMIN' ? '管理员' : '用户' }}</div>
      </div>
      <div v-if="!isCollapsed" class="profile-arrow">›</div>
    </div>
    
    <nav class="sidebar-nav">
      <div class="nav-section">
        <ul class="nav-list">
          <li class="nav-item" :class="{ active: activeTab === 'recent' }" @click="toggleRecentGroups">
            <span class="nav-icon">💬</span>
            <span v-if="!isCollapsed" class="nav-text">对话</span>
            <span v-if="!isCollapsed" class="expand-icon">{{ isRecentExpanded ? '▼' : '▶' }}</span>
          </li>
          
          <!-- 最近对话列表 - 仅在登录状态下且展开时显示 -->
          <template v-if="isLoggedIn && isRecentExpanded">
            <li 
              v-for="group in recentGroups" 
              :key="group.groupId"
              class="nav-item group-item"
              @click="selectGroup(group.groupId)"
            >
              <div class="group-avatar">
                <img 
                  :src="getGroupAvatar(group)" 
                  :alt="group.groupName"
                  @error="handleAvatarError"
                />
              </div>
              <div v-if="!isCollapsed" class="group-info">
                <div class="group-name">{{ group.groupName || '群聊 ' + group.groupId }}</div>
                <div class="group-id">{{ group.groupId }}</div>
              </div>
            </li>
          </template>
        </ul>
      </div>
      
      <div class="nav-section">
        <div v-if="!isCollapsed" class="nav-section-title"></div>
        <ul class="nav-list">
          <li class="nav-item" @click="openAstrBot">
            <span class="nav-icon">🤖</span>
            <span v-if="!isCollapsed" class="nav-text">我的智能体</span>
          </li>
        </ul>
      </div>
    </nav>
    
    <div class="sidebar-footer">
      <!-- 系统控制按钮（仅已登录用户可见） -->
      <button v-if="isLoggedIn" class="nav-item system-btn" @click="openSystemModal">
        <span class="nav-icon">⚙️</span>
        <span v-if="!isCollapsed" class="nav-text">系统控制</span>
      </button>
      
      <!-- 登录按钮（仅未登录用户可见） -->
      <button v-if="!isLoggedIn" class="nav-item login-btn" @click="openLoginModal">
        <span class="nav-icon">🔐</span>
        <span v-if="!isCollapsed" class="nav-text">登录</span>
      </button>
      
      <!-- 退出登录按钮 -->
      <button v-if="isLoggedIn" class="nav-item logout-btn" @click="logout">
        <span class="nav-icon">🚪</span>
        <span v-if="!isCollapsed" class="nav-text">退出登录</span>
      </button>
    </div>
  </div>
</template>

<script>
import { ref, onMounted, onUnmounted, watch, computed } from 'vue';
import { messageApi } from '../services/api';

export default {
  name: 'Sidebar',
  props: {
    isLoggedIn: {
      type: Boolean,
      default: false
    },
    userInfo: {
      type: Object,
      default: null
    },
    isCollapsed: {
      type: Boolean,
      default: false
    }
  },
  emits: ['tab-change', 'logout', 'open-login-modal', 'open-system-modal', 'open-user-profile', 'select-group'],
  setup(props, { emit }) {
    const isCollapsedLocal = ref(false);
    const activeTab = ref('recent');
    const recentGroups = ref([]);
    const isRecentExpanded = ref(true); // 默认展开群列表
    let refreshInterval = null;
    
    // 使用 computed 确保响应式
    const isLoggedInComputed = computed(() => props.isLoggedIn);
    
    // 合并外部传入的 isCollapsed 和本地状态
    const isCollapsed = computed(() => props.isCollapsed || isCollapsedLocal.value);

    const toggleSidebar = () => {
      isCollapsedLocal.value = !isCollapsedLocal.value;
    };

    const toggleRecentGroups = () => {
      isRecentExpanded.value = !isRecentExpanded.value;
      // 同时触发 tab 切换
      activeTab.value = 'recent';
      emit('tab-change', 'recent');
      // 刷新群聊列表
      loadRecentGroups();
    };

    const selectTab = (tab) => {
      activeTab.value = tab;
      emit('tab-change', tab);
    };

    const logout = () => {
      emit('logout');
    };

    const openLoginModal = () => {
      emit('open-login-modal');
    };

    const openSystemModal = () => {
      emit('open-system-modal');
    };

    const openUserProfile = () => {
      emit('open-user-profile');
    };

    const openAstrBot = () => {
      window.open('http://localhost:6185', '_blank');
    };

    const selectGroup = (groupId) => {
      emit('select-group', groupId);
    };

    const handleAvatarError = (e) => {
      // 头像加载失败时使用默认头像
      e.target.src = 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100';
    };

    const getGroupAvatar = (group) => {
      // 优先使用后端返回的 avatar 字段（本地存储路径）
      if (group && group.avatar) {
        // 如果 avatar 已经是完整 URL，直接返回
        if (group.avatar.startsWith('http')) {
          return group.avatar;
        }
        // 如果 avatar 是相对路径，添加 API 基础 URL
        if (group.avatar.startsWith('/images/')) {
          return `http://localhost:8081${group.avatar}`;
        }
        return group.avatar;
      }
      // 如果没有 avatar，使用 QQ 群头像 API（正确的群头像地址）
      if (group && group.groupId) {
        return `https://p.qlogo.cn/gh/${group.groupId}/${group.groupId}/100`;
      }
      return 'https://p.qlogo.cn/gh/0/0/100';
    };

    const loadRecentGroups = async () => {
      // 仅在登录状态下加载群聊列表
      if (!props.isLoggedIn) {
        recentGroups.value = [];
        return;
      }
      
      try {
        // 从 API 获取最近对话的群聊（后端从JWT自动获取用户ID）
        console.log('开始加载最近对话...');
        const groups = await messageApi.getRecentGroups();
        console.log('获取到的群聊数据:', groups);
        recentGroups.value = groups;
        console.log('recentGroups.value:', recentGroups.value);
      } catch (error) {
        console.error('加载最近对话失败:', error);
        // 清空数据，确保只显示真实数据
        recentGroups.value = [];
      }
    };

    onMounted(() => {
      loadRecentGroups();
      
      // 每30秒自动刷新群聊列表
      refreshInterval = setInterval(() => {
        if (props.isLoggedIn) {
          loadRecentGroups();
        }
      }, 30000);
    });
    
    // 组件卸载时清除定时器
    onUnmounted(() => {
      if (refreshInterval) {
        clearInterval(refreshInterval);
      }
    });

    // 监听登录状态变化，当登录状态改变时重新加载群聊列表
    watch(() => props.isLoggedIn, (newValue) => {
      console.log('登录状态变化:', newValue);
      if (newValue) {
        loadRecentGroups();
      } else {
        recentGroups.value = [];
      }
    });

    return {
      isCollapsed,
      activeTab,
      recentGroups,
      isRecentExpanded,
      isLoggedIn: isLoggedInComputed,
      toggleSidebar,
      toggleRecentGroups,
      selectTab,
      logout,
      openLoginModal,
      openSystemModal,
      openUserProfile,
      openAstrBot,
      selectGroup,
      handleAvatarError,
      getGroupAvatar
    };
  }
};
</script>

<style scoped>
.sidebar {
  width: 240px;
  height: 100vh;
  background-color: #2c3e50;
  color: #ecf0f1;
  display: flex;
  flex-direction: column;
  transition: width 0.3s ease;
  flex-shrink: 0;
  overflow-y: auto;
}

.sidebar.collapsed {
  width: 60px;
}

.sidebar-header {
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid #34495e;
}

.toggle-btn {
  background: none;
  border: none;
  color: #ecf0f1;
  font-size: 20px;
  cursor: pointer;
  padding: 5px;
  border-radius: 4px;
  transition: background-color 0.2s;
}

.toggle-btn:hover {
  background-color: #34495e;
}

.sidebar-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
}

/* 用户信息区域 */
.user-info {
  padding: 15px 20px;
  display: flex;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid #34495e;
  background-color: #34495e;
  cursor: pointer;
  transition: background-color 0.2s;
}

.user-info:hover {
  background-color: #3d566e;
}

.profile-arrow {
  font-size: 20px;
  color: #95a5a6;
  margin-left: auto;
  transition: transform 0.2s;
}

.user-info:hover .profile-arrow {
  transform: translateX(4px);
  color: #ecf0f1;
}

.user-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
  background-color: #2c3e50;
}

.user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.user-details {
  flex: 1;
  min-width: 0;
}

.user-nickname {
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #ecf0f1;
}

.user-role {
  font-size: 12px;
  color: #95a5a6;
  margin-top: 2px;
}

.sidebar-nav {
  flex: 1;
  padding: 20px 0;
}

.nav-section {
  margin-bottom: 20px;
}

.nav-section-title {
  padding: 0 20px;
  font-size: 12px;
  text-transform: uppercase;
  color: #95a5a6;
  margin-bottom: 10px;
  letter-spacing: 1px;
}

.nav-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.nav-item {
  display: flex;
  align-items: center;
  padding: 8px 20px;
  cursor: pointer;
  transition: all 0.2s;
  gap: 12px;
}

.nav-item:hover {
  background-color: #34495e;
}

.nav-item.active {
  background-color: #3498db;
  border-right: 3px solid #ecf0f1;
}

.nav-icon {
  font-size: 16px;
  min-width: 20px;
  text-align: center;
  display: flex;
  align-items: center;
  justify-content: center;
}

.nav-text {
  white-space: nowrap;
  overflow: hidden;
  font-size: 14px;
  flex: 1;
}

.expand-icon {
  font-size: 12px;
  color: #95a5a6;
  margin-left: auto;
  transition: transform 0.2s;
}

/* 群聊项样式 */
.group-item {
  padding: 6px 20px;
}

.group-avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
  background-color: #34495e;
  display: flex;
  align-items: center;
  justify-content: center;
}

.group-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.group-info {
  flex: 1;
  min-width: 0;
}

.group-name {
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 2px;
}

.group-id {
  font-size: 12px;
  color: #95a5a6;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sidebar-footer {
  padding: 20px 0;
  border-top: 1px solid #34495e;
}

.login-btn {
  width: 100%;
  background: none;
  border: none;
  color: #ecf0f1;
  font-size: 14px;
  margin-bottom: 10px;
}

.login-btn:hover {
  background-color: #3498db;
}

.system-btn {
  width: 100%;
  background: none;
  border: none;
  color: #ecf0f1;
  font-size: 14px;
  margin-bottom: 10px;
}

.system-btn:hover {
  background-color: #27ae60;
}

.logout-btn {
  width: 100%;
  background: none;
  border: none;
  color: #ecf0f1;
  font-size: 14px;
}

.logout-btn:hover {
  background-color: #e74c3c;
}

/* 滚动条样式 */
.sidebar::-webkit-scrollbar {
  width: 6px;
}

.sidebar::-webkit-scrollbar-track {
  background: #2c3e50;
}

.sidebar::-webkit-scrollbar-thumb {
  background: #34495e;
  border-radius: 3px;
}

.sidebar::-webkit-scrollbar-thumb:hover {
  background: #4a637a;
}
</style>
