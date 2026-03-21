<template>
  <div class="sidebar" :class="{ collapsed: isCollapsed }">
    <div class="sidebar-header">
      <button class="toggle-btn" @click="toggleSidebar">
        <span v-if="isCollapsed">☰</span>
        <span v-else>✕</span>
      </button>
      <h3 v-if="!isCollapsed" class="sidebar-title">铃音QQ对话</h3>
    </div>
    
    <nav class="sidebar-nav">
      <div class="nav-section">
        <ul class="nav-list">
          <li class="nav-item" :class="{ active: activeTab === 'recent' }" @click="selectTab('recent')">
            <span class="nav-icon">💬</span>
            <span v-if="!isCollapsed" class="nav-text">对话</span>
          </li>
          
          <!-- 最近对话列表 -->
          <li 
            v-for="group in recentGroups" 
            :key="group.groupId"
            class="nav-item group-item"
            @click="selectGroup(group.groupId)"
          >
            <div class="group-avatar">
              <img 
                :src="getGroupAvatar(group.groupId)" 
                :alt="group.groupName"
                @error="handleAvatarError"
              />
            </div>
            <div v-if="!isCollapsed" class="group-info">
              <div class="group-name">{{ group.groupName || '群聊 ' + group.groupId }}</div>
              <div class="group-id">{{ group.groupId }}</div>
            </div>
          </li>
        </ul>
      </div>
      
      <div class="nav-section">
        <div v-if="!isCollapsed" class="nav-section-title"></div>
        <ul class="nav-list">
          <li class="nav-item" :class="{ active: activeTab === 'agents' }" @click="selectTab('agents')">
            <span class="nav-icon">🤖</span>
            <span v-if="!isCollapsed" class="nav-text">我的智能体</span>
          </li>
        </ul>
      </div>
    </nav>
    
    <div class="sidebar-footer">
      <!-- 登录/系统控制按钮 -->
      <button class="nav-item login-btn" @click="openLoginModal">
        <span class="nav-icon">🔐</span>
        <span v-if="!isCollapsed" class="nav-text">{{ isLoggedIn ? '系统控制' : '登录' }}</span>
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
import { ref, onMounted } from 'vue';
import { messageApi } from '../services/api';

export default {
  name: 'Sidebar',
  props: {
    isLoggedIn: {
      type: Boolean,
      default: false
    }
  },
  emits: ['tab-change', 'logout', 'open-login-modal', 'select-group'],
  setup(props, { emit }) {
    const isCollapsed = ref(false);
    const activeTab = ref('recent');
    const recentGroups = ref([]);

    const toggleSidebar = () => {
      isCollapsed.value = !isCollapsed.value;
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

    const selectGroup = (groupId) => {
      emit('select-group', groupId);
    };

    const handleAvatarError = (e) => {
      // 头像加载失败时使用默认头像
      e.target.src = 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100';
    };

    const getGroupAvatar = (groupId) => {
      // 使用 QQ 群头像 API
      if (groupId) {
        return `https://q.qlogo.cn/headimg_dl?dst_uin=${groupId}&spec=100`;
      }
      return 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100';
    };

    const loadRecentGroups = async () => {
      try {
        // 从 API 获取最近对话的群聊
        // 暂时传递null，后续从登录状态中获取userId
        console.log('开始加载最近对话...');
        const groups = await messageApi.getRecentGroups(null);
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
    });

    return {
      isCollapsed,
      activeTab,
      recentGroups,
      isLoggedIn: props.isLoggedIn,
      toggleSidebar,
      selectTab,
      logout,
      openLoginModal,
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
  position: fixed;
  left: 0;
  top: 0;
  z-index: 1000;
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
  padding: 12px 20px;
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
  font-size: 20px;
  min-width: 20px;
  text-align: center;
}

.nav-text {
  white-space: nowrap;
  overflow: hidden;
  font-size: 14px;
}

/* 群聊项样式 */
.group-item {
  padding: 10px 20px;
}

.group-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
  background-color: #34495e;
}

.group-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
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
