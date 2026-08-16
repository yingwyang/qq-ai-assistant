<template>
  <div class="sidebar" :class="{ collapsed: isCollapsed }">
    <div class="sidebar-header">
      <button class="toggle-btn" @click="toggleSidebar">
        <Icon v-if="isCollapsed" name="menu" :size="16" />
        <Icon v-else name="close" :size="16" />
      </button>
      <h3 v-if="!isCollapsed" class="sidebar-title">铃音QQ对话</h3>
    </div>
    
    
    
    <nav v-if="isLoggedIn" class="sidebar-nav">
      <div class="nav-section">
        <ul class="nav-list">
          <li class="nav-item" :class="{ active: activeTab === 'recent' }" @click="toggleRecentGroups">
            <span class="nav-icon"><Icon name="chat" :size="16" /></span>
            <span v-if="!isCollapsed" class="nav-text">对话</span>
            <span v-if="!isCollapsed" class="expand-icon"><Icon :name="isRecentExpanded ? 'expand' : 'collapse'" :size="12" /></span>
          </li>
          
          <!-- 最近对话列表 - 按QQ绑定分组显示 -->
          <template v-if="isLoggedIn && isRecentExpanded">
            <template v-for="binding in qqBindings" :key="binding.id">
              <!-- 该QQ下有群才显示分组 -->
              <template v-if="getGroupsByQq(binding.qqNumber).length > 0">
                <li class="nav-item qq-binding-header" @click="toggleQqBindingGroups(binding.qqNumber)">
                  <div class="qq-binding-avatar">
                    <img 
                      :src="binding.avatar || 'https://q.qlogo.cn/headimg_dl?dst_uin=' + binding.qqNumber + '&spec=100'" 
                      :alt="binding.nickname"
                      @error="handleAvatarError"
                    />
                  </div>
                  <div v-if="!isCollapsed" class="qq-binding-info">
                    <div class="qq-binding-name">{{ binding.nickname || binding.qqNumber }}</div>
                    <div class="qq-binding-number">{{ binding.qqNumber }}</div>
                    <span v-if="binding.isDefault" class="default-badge">默认</span>
                  </div>
                  <span v-if="!isCollapsed" class="expand-icon">
                    <Icon :name="isQqBindingExpanded(binding.qqNumber) ? 'expand' : 'collapse'" :size="12" />
                  </span>
                </li>
                <li
                  v-show="isQqBindingExpanded(binding.qqNumber)"
                  v-for="group in getGroupsByQq(binding.qqNumber)"
                  :key="`${group.groupId}_${group.ownerQq}`"
                  class="nav-item group-item"
                  :class="{ active: isGroupActive(group) }"
                  @click="selectGroup(group)"
                  @contextmenu.prevent="showContextMenu($event, group)"
                >
                  <div class="group-avatar">
                    <img 
                      :src="getGroupAvatar(group)" 
                      :alt="group.groupName"
                      @error="handleAvatarError"
                    />
                    <span
                      v-if="!isCollapsed && group.groupType && group.groupType !== 'OTHER'"
                      :class="['gt-badge', `gt-badge-${group.groupType}`]"
                      :title="GT_MAP[group.groupType]?.label || group.groupType"
                    ><Icon :name="GT_MAP[group.groupType]?.iconName || 'tag'" :size="12" /></span>
                  </div>
                  <div v-if="!isCollapsed" class="group-info">
                    <div class="group-name">
                      {{ group.groupName || '群聊 ' + group.groupId }}
                      <span
                        v-if="group.groupType && group.groupType !== 'OTHER' && GT_MAP[group.groupType]"
                        :class="['gt-mini', `gt-mini-${group.groupType}`]"
                        :title="GT_MAP[group.groupType].desc"
                      >{{ GT_MAP[group.groupType].label }}</span>
                    </div>
                    <div class="group-id">{{ group.groupId }}</div>
                  </div>
                  <span v-if="!isCollapsed && group.unreadCount && group.unreadCount > 0" class="unread-badge">
                    {{ group.unreadCount > 99 ? '99+' : group.unreadCount }}
                  </span>
                </li>
              </template>
            </template>
          </template>
        </ul>
      </div>
      
    </nav>
    
    <div class="sidebar-footer">
      <div v-if="isLoggedIn && userInfo" class="user-info footer-user-info" @click="showMenu = true">
        <div class="user-avatar">
          <img :src="userAvatarUrl" alt="avatar" @error="handleAvatarError" />
        </div>
        <div v-if="!isCollapsed" class="user-name-footer">{{ userInfo.nickname || userInfo.username }}</div>
        <div v-if="!isCollapsed" class="profile-arrow">›</div>
      </div>

      <!-- 积分徽章：点击跳转用量管理 -->
      <div
        v-if="isLoggedIn && userInfo && !isCollapsed"
        class="credits-badge"
        @click="goToCreditsCenter"
        title="查看用量管理"
      >
        <span class="credits-badge-gem"><Icon name="diamond" :size="14" /></span>
        <span class="credits-badge-num">{{ formatCreditsNumber(balance) }}</span>
        <span class="credits-badge-label">积分</span>
      </div>

      <button v-if="!isLoggedIn" class="nav-item login-btn" @click="openLoginModal">
        <span class="nav-icon"><Icon name="lock" :size="16" /></span>
        <span v-if="!isCollapsed" class="nav-text">登录</span>
      </button>
    </div>

    <UserMenuPopover
      :visible="showMenu"
      :user-info="userInfo"
      :user-avatar-url="userAvatarUrl"
      @close="showMenu = false"
      @open-user-center="handleOpenUserCenter"
      @open-admin="handleOpenAdmin"
      @open-docs="handleOpenDocs"
      @logout="handleLogout"
    />

    <!-- 群类型设置弹窗 -->
    <GroupTypeSelector
      v-model:visible="groupTypeSelectorVisible"
      :group-id="groupTypeTarget?.groupId ?? null"
      :owner-qq="groupTypeTarget?.ownerQq ?? null"
      :group-name="groupTypeTarget?.groupName ?? null"
      :initial-group-type="computedTargetGroupType"
      @save="handleGroupTypeSaved"
      @close="closeGroupTypeSelector"
    />

    <!-- 右键删除菜单 -->
    <div
      v-if="contextMenuVisible"
      class="context-menu"
      :style="{ top: contextMenuPosition.top + 'px', left: contextMenuPosition.left + 'px' }"
      @click.stop
    >
      <div class="context-menu-header">{{ contextMenuGroup?.groupName ? (contextMenuGroup.groupName.length > 14 ? contextMenuGroup.groupName.slice(0,14)+'…' : contextMenuGroup.groupName) : '群聊操作' }}</div>
      <div class="context-menu-item" @click="openGroupTypeSelector">
        <span class="context-menu-icon"><Icon name="tag" :size="14" /></span>
        设置群类型（AI 融入）
      </div>
      <div class="context-menu-divider"></div>
      <div class="context-menu-header">一键删除群消息</div>
      <div class="context-menu-item" @click="deleteMessagesByType('IMAGE')">
        <span class="context-menu-icon"><Icon name="image" :size="14" /></span>
        删除图片消息
      </div>
      <div class="context-menu-item" @click="deleteMessagesByType('VIDEO')">
        <span class="context-menu-icon"><Icon name="video" :size="14" /></span>
        删除视频消息
      </div>
      <div class="context-menu-item" @click="deleteMessagesByType('AUDIO')">
        <span class="context-menu-icon"><Icon name="audio" :size="14" /></span>
        删除音频/语音消息
      </div>
      <div class="context-menu-item" @click="deleteMessagesByType('TEXT')">
        <span class="context-menu-icon"><Icon name="text" :size="14" /></span>
        删除文本消息
      </div>
      <div class="context-menu-item" @click="deleteMessagesByType('FORWARD')">
        <span class="context-menu-icon"><Icon name="forward" :size="14" /></span>
        删除聊天记录
      </div>
      <div class="context-menu-divider"></div>
      <div class="context-menu-item danger" @click="deleteMessagesByType('ALL')">
        <span class="context-menu-icon"><Icon name="delete" :size="14" /></span>
        删除所有消息
      </div>
      <div class="context-menu-divider"></div>
      <div class="context-menu-item danger" @click="deleteGroupConversation">
        <span class="context-menu-icon"><Icon name="delete" :size="14" /></span>
        删除群聊
      </div>
    </div>
  </div>
</template>

<script>
import { ref, onMounted, onUnmounted, watch, computed } from 'vue';
import { useRouter } from 'vue-router';
import { messageApi, userApi } from '../services/api';
import Icon from './Icon.vue';
import { showToast } from './Toast.vue';
import { showConfirm } from './ConfirmDialog.vue';
import { useUserCreditsStore } from '../composables/useUserCreditsStore';
import UserMenuPopover from './UserMenuPopover.vue';
import GroupTypeSelector from './GroupTypeSelector.vue';

// 群类型元数据（与后端/其他组件保持一致）
const GT_MAP = {
  GAME:   { label: '游戏群', iconName: 'gamepad', color: '#6366f1' },
  STUDY:  { label: '学习群', iconName: 'book', color: '#10b981' },
  WORK:   { label: '工作群', iconName: 'briefcase', color: '#f59e0b' },
  HOBBY:  { label: '兴趣群', iconName: 'palette', color: '#ec4899' },
  LIFE:   { label: '生活群', iconName: 'home', color: '#14b8a6' },
  SOCIAL: { label: '社交群', iconName: 'chat', color: '#3b82f6' },
  OTHER:  { label: '其他群', iconName: 'tag', color: '#6b7280' }
};

export default {
    name: 'Sidebar',
    components: { Icon, UserMenuPopover, GroupTypeSelector },
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
  emits: ['tab-change', 'logout', 'open-login-modal', 'open-system-modal', 'open-user-profile', 'select-group', 'toggle-sidebar', 'show-context-menu', 'navigate-admin', 'navigate-docs', 'group-type-change'],
  setup(props, { emit }) {
    const router = useRouter();

    // 全局共享积分余额（与 UserMenuPopover / UserCenter 同步）
    const { balance, reload: reloadCredits } = useUserCreditsStore();

    const formatCreditsNumber = (n) => {
      if (n === null || n === undefined) return '0';
      return Number(n).toLocaleString('zh-CN');
    };

    const goToCreditsCenter = () => {
      router.push('/user-center?tab=credits');
    };

    const isCollapsedLocal = ref(false);
    const showMenu = ref(false);
    const activeTab = ref('recent');
    const recentGroups = ref([]);
    const STORAGE_KEY_RECENT = 'sidebar_recent_expanded';
    const STORAGE_KEY_QQ = 'sidebar_qq_expanded';

    const isRecentExpanded = ref(true);
    const qqBindings = ref([]);
    const expandedQqBindings = ref(new Set());
    const selectedGroup = ref(null); // { groupId, ownerQq }
    let refreshInterval = null;

    // 从 localStorage 读取展开状态
    const loadExpandedState = () => {
      try {
        const recent = localStorage.getItem(STORAGE_KEY_RECENT);
        if (recent !== null) isRecentExpanded.value = recent === 'true';

        const qq = localStorage.getItem(STORAGE_KEY_QQ);
        if (qq) expandedQqBindings.value = new Set(JSON.parse(qq));
      } catch (e) {
        console.error('读取侧边栏展开状态失败:', e);
      }
    };

    const saveRecentExpanded = () => {
      try {
        localStorage.setItem(STORAGE_KEY_RECENT, String(isRecentExpanded.value));
      } catch (e) {
        console.error('保存最近对话展开状态失败:', e);
      }
    };

    const saveQqExpanded = () => {
      try {
        localStorage.setItem(STORAGE_KEY_QQ, JSON.stringify([...expandedQqBindings.value]));
      } catch (e) {
        console.error('保存QQ分组展开状态失败:', e);
      }
    };

    // 右键菜单状态
    const contextMenuVisible = ref(false);
    const contextMenuPosition = ref({ top: 0, left: 0 });
    const contextMenuGroup = ref(null); // { groupId, ownerQq, groupName, groupType }

    // 群类型选择器状态
    const groupTypeSelectorVisible = ref(false);
    const groupTypeTarget = ref(null); // { groupId, ownerQq, groupName }
    // 取目标群的当前类型（优先从 recentGroups 中取，保证是最新的）
    const computedTargetGroupType = computed(() => {
      const t = groupTypeTarget.value;
      if (!t) return null;
      const g = recentGroups.value.find(
        r => r.groupId === t.groupId && r.ownerQq === t.ownerQq
      );
      return (g && g.groupType) ? g.groupType : null;
    });

    const openGroupTypeSelector = () => {
      const g = contextMenuGroup.value;
      hideContextMenu();
      if (!g) return;
      groupTypeTarget.value = {
        groupId: g.groupId,
        ownerQq: g.ownerQq,
        groupName: g.groupName || null
      };
      groupTypeSelectorVisible.value = true;
    };

    const closeGroupTypeSelector = () => {
      groupTypeSelectorVisible.value = false;
      groupTypeTarget.value = null;
    };

    // 保存群类型后：更新 recentGroups 中的对应群，并且如果当前选中的是这个群，通知父组件（父组件通知 ChatInterface 更新展示）
    const handleGroupTypeSaved = ({ groupType, groupId, ownerQq }) => {
      const g = recentGroups.value.find(r => r.groupId === groupId && r.ownerQq === ownerQq);
      if (g) g.groupType = groupType;
      // 如果当前选中群就是这个群，emit 通知父组件
      if (selectedGroup.value && selectedGroup.value.groupId === groupId && selectedGroup.value.ownerQq === ownerQq) {
        emit('group-type-change', { groupId, ownerQq, groupType, groupName: g?.groupName });
      }
      closeGroupTypeSelector();
    };

    // 暴露给父组件的方法
    const refreshGroups = () => {
      loadRecentGroups();
      loadQqBindings();
    };
    
    // 使用 computed 确保响应式
    const isLoggedInComputed = computed(() => props.isLoggedIn);
    
    // 合并外部传入的 isCollapsed 和本地状态
    const isCollapsed = computed(() => props.isCollapsed || isCollapsedLocal.value);

    const toggleSidebar = () => {
      isCollapsedLocal.value = !isCollapsedLocal.value;
    };

    const toggleRecentGroups = () => {
      isRecentExpanded.value = !isRecentExpanded.value;
      saveRecentExpanded();
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

    const logout = async () => {
      const confirmed = await showConfirm({
        title: '确认退出',
        message: '确定要退出登录吗？',
        confirmText: '确认',
        cancelText: '取消'
      });
      if (confirmed) {
        emit('logout');
      }
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

    const openAdminDashboard = () => {
      const targetPath = props.userInfo?.role === 'ADMIN' ? '/admin' : '/user-center';
      if (router.currentRoute.value.path === targetPath) {
        window.dispatchEvent(new CustomEvent('app:refresh'));
      } else {
        router.push(targetPath);
      }
    };

    const openHelpPage = () => {
      window.open('https://www.touchgal.ink/doc/notice/feedback', '_blank');
    };

    const handleOpenUserCenter = () => {
      showMenu.value = false;
      const targetPath = '/user-center';
      if (router.currentRoute.value.path === targetPath) {
        window.dispatchEvent(new CustomEvent('app:refresh'));
      } else {
        router.push(targetPath);
      }
    };

    const handleOpenAdmin = () => {
      showMenu.value = false;
      openAdminDashboard();
    };

    const handleOpenDocs = () => {
      showMenu.value = false;
      emit('navigate-docs');
    };

    const handleGoHome = () => {
      showMenu.value = false;
      if (router.currentRoute.value.path === '/') {
        window.dispatchEvent(new CustomEvent('app:refresh'));
      } else {
        router.push('/');
      }
    };

    const handleLogout = () => {
      showMenu.value = false;
      logout();
    };

    const selectGroup = async (group) => {
      selectedGroup.value = {
        groupId: group.groupId,
        ownerQq: group.ownerQq
      };

      // 标记该群聊为已读（服务端维护 lastReadTime，刷新后未读计数清零）
      try {
        await messageApi.markGroupAsRead(group.groupId);
        // 本地乐观更新：直接把该群的未读数置 0，立即刷新 UI
        const g = recentGroups.value.find(
          r => r.groupId === group.groupId && r.ownerQq === group.ownerQq
        );
        if (g) g.unreadCount = 0;
      } catch (e) {
        // 失败时不阻塞，刷新列表由后台定时任务完成
        console.warn('标记群聊 ' + group.groupId + ' 为已读失败:', e);
      }

      emit('select-group', { groupId: group.groupId, ownerQq: group.ownerQq, groupName: group.groupName });
    };

    const showContextMenu = (event, group) => {
      contextMenuGroup.value = {
        groupId: group.groupId,
        ownerQq: group.ownerQq,
        groupName: group.groupName || null,
        groupType: group.groupType || null
      };
      contextMenuPosition.value = {
        top: event.clientY,
        left: event.clientX
      };
      contextMenuVisible.value = true;
    };

    const hideContextMenu = () => {
      contextMenuVisible.value = false;
      contextMenuGroup.value = null;
    };

    const deleteMessagesByType = async (type) => {
      const group = contextMenuGroup.value;
      hideContextMenu();
      if (!group) return;

      const typeMap = {
        IMAGE: { types: ['IMAGE'], label: '图片消息', hasMedia: true },
        VIDEO: { types: ['VIDEO'], label: '视频消息', hasMedia: true },
        AUDIO: { types: ['AUDIO', 'VOICE'], label: '音频/语音消息', hasMedia: true },
        TEXT: { types: ['TEXT'], label: '文本消息', hasMedia: false },
        FORWARD: { types: ['FORWARD'], label: '聊天记录', hasMedia: false },
        ALL: { types: ['TEXT', 'IMAGE', 'VIDEO', 'AUDIO', 'VOICE', 'FILE', 'FORWARD', 'AT', 'REPLY'], label: '所有消息', hasMedia: true }
      };
      const config = typeMap[type];
      if (!config) return;

      const confirmed = await showConfirm({
        title: `确认删除${config.label}`,
        message: `确定要删除该群聊的${config.label}吗？此操作不可恢复。`,
        confirmText: '删除',
        cancelText: '取消'
      });
      if (!confirmed) return;

      try {
        const result = await messageApi.deleteMessagesByTypes(group.groupId, config.types, config.hasMedia);
        if (result && typeof result.deletedCount === 'number') {
          showToast(`已删除 ${result.deletedCount} 条${config.label}`, 'success');
          // 立即刷新群聊列表（最后消息时间、未读数等）
          loadRecentGroups();
          // 通知父组件重新加载当前群聊消息
          emit('select-group', { groupId: group.groupId, ownerQq: group.ownerQq, groupName: group.groupName });
        } else {
          showToast(result?.message || '删除失败', 'error');
        }
      } catch (error) {
        console.error('删除消息失败:', error);
        showToast('删除失败: ' + error.message, 'error');
      }
    };

    const deleteGroupConversation = async () => {
      const group = contextMenuGroup.value;
      hideContextMenu();
      if (!group) return;

      const displayName = group.groupName || ('群聊 ' + group.groupId);
      const confirmed = await showConfirm({
        title: '确认删除群聊',
        message: `确定要彻底删除群聊「${displayName}」吗？\n将删除该QQ下此群的所有消息、图片、视频、音频及群记录，操作不可恢复！`,
        confirmText: '彻底删除',
        cancelText: '取消'
      });
      if (!confirmed) return;

      try {
        const result = await messageApi.deleteGroupConversation(group.groupId, group.ownerQq);
        showToast(result?.message || '群聊已删除', 'success');
        // 刷新群聊列表（被删除的群会从列表中消失）
        loadRecentGroups();
        // 通知父组件清空当前聊天视图
        emit('select-group', null);
      } catch (error) {
        console.error('删除群聊失败:', error);
        showToast('删除群聊失败: ' + error.message, 'error');
      }
    };

    const getGroupsByQq = (qqNumber) => {
      return recentGroups.value.filter(g => String(g.ownerQq) === String(qqNumber));
    };

    const isQqBindingExpanded = (qqNumber) => {
      return expandedQqBindings.value.has(String(qqNumber));
    };

    const toggleQqBindingGroups = (qqNumber) => {
      const qq = String(qqNumber);
      if (expandedQqBindings.value.has(qq)) {
        expandedQqBindings.value.delete(qq);
      } else {
        expandedQqBindings.value.add(qq);
      }
      saveQqExpanded();
    };

    const loadQqBindings = async () => {
      if (!props.isLoggedIn) {
        qqBindings.value = [];
        return;
      }
      try {
        const bindings = await userApi.getQqBindings();
        // 只清理已不存在的QQ号，保留用户手动的展开/折叠状态，不再自动展开所有新QQ号
        const validQqs = new Set(bindings.map(b => String(b.qqNumber)));
        [...expandedQqBindings.value].forEach(qq => {
          if (!validQqs.has(qq)) {
            expandedQqBindings.value.delete(qq);
          }
        });
        qqBindings.value = bindings;
        saveQqExpanded();
      } catch (error) {
        console.error('加载QQ绑定失败:', error);
        qqBindings.value = [];
      }
    };

    const handleAvatarError = (e) => {
      // 头像加载失败时使用本地默认头像
      e.target.src = '/default-avatar.svg';
    };

    // 用户头像完整 URL（优先本地文件，失败时走 handleAvatarError 兜底）
    const userAvatarUrl = computed(() => {
      if (!props.userInfo || !props.userInfo.avatar) return '/default-avatar.svg';
      const avatar = props.userInfo.avatar;
      if (avatar.startsWith('http')) return avatar;
      // 兼容相对路径是否带前导斜杠
      const normalized = avatar.startsWith('/') ? avatar : '/' + avatar;
      return `http://localhost:8081${normalized}`;
    });

    const getGroupAvatar = (group) => {
      // 优先使用后端返回的 avatar 字段（本地存储路径）
      if (group && group.avatar) {
        // 如果 avatar 已经是完整 URL，直接返回
        if (group.avatar.startsWith('http')) {
          return group.avatar;
        }
        // 如果 avatar 是相对路径，添加 API 基础 URL
        if (group.avatar.startsWith('/images/') || group.avatar.startsWith('/uploads/avatars/')) {
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
        // 从 API 获取最近对话的群聊（后端从 JWT 自动获取用户ID，按 group_read_state 计算未读）
        console.log('开始加载最近对话...');
        const res = await messageApi.getRecentGroups();
        console.log('获取到的群聊数据:', res);
        recentGroups.value = Array.isArray(res) ? res : [];
        console.log('recentGroups.value:', recentGroups.value);
      } catch (error) {
        console.error('加载最近对话失败:', error);
        // 清空数据，确保只显示真实数据
        recentGroups.value = [];
      }
    };

    const syncExpandedState = (e) => {
      if (e.key === STORAGE_KEY_RECENT) {
        isRecentExpanded.value = e.newValue === 'true';
      } else if (e.key === STORAGE_KEY_QQ) {
        try {
          expandedQqBindings.value = e.newValue ? new Set(JSON.parse(e.newValue)) : new Set();
        } catch (err) {
          console.error('同步QQ展开状态失败:', err);
        }
      }
    };

    onMounted(() => {
      loadExpandedState();
      loadRecentGroups();
      loadQqBindings();

      // 每5秒自动刷新群聊列表，确保收到新消息的群能及时移至顶层
      refreshInterval = setInterval(() => {
        if (props.isLoggedIn) {
          loadRecentGroups();
          loadQqBindings();
        }
      }, 5000);

      // 监听 localStorage 变化，实现同一浏览器多个标签页同步
      window.addEventListener('storage', syncExpandedState);

      // 点击页面其他区域关闭右键菜单
      document.addEventListener('click', hideContextMenu);
      document.addEventListener('scroll', hideContextMenu, true);
    });

    // 组件卸载时清除定时器
    onUnmounted(() => {
      if (refreshInterval) {
        clearInterval(refreshInterval);
      }
      window.removeEventListener('storage', syncExpandedState);
      document.removeEventListener('click', hideContextMenu);
      document.removeEventListener('scroll', hideContextMenu, true);
    });

    // 监听登录状态变化，当登录状态改变时重新加载群聊列表和QQ绑定
    watch(() => props.isLoggedIn, (newValue) => {
      console.log('登录状态变化:', newValue);
      if (newValue) {
        loadRecentGroups();
        loadQqBindings();
        reloadCredits();
      } else {
        recentGroups.value = [];
        qqBindings.value = [];
      }
    });

    const isGroupActive = (group) => {
      if (!selectedGroup.value) return false;
      return (
        selectedGroup.value.groupId === group.groupId &&
        selectedGroup.value.ownerQq === group.ownerQq
      );
    };

    return {
      isCollapsed,
      activeTab,
      recentGroups,
      isRecentExpanded,
      qqBindings,
      selectedGroup,
      isLoggedIn: isLoggedInComputed,
      toggleSidebar,
      toggleRecentGroups,
      selectTab,
      logout,
      openLoginModal,
      openSystemModal,
      openUserProfile,
      openAdminDashboard,
      openHelpPage,
      showMenu,
      handleOpenUserCenter,
      handleOpenAdmin,
      handleOpenDocs,
      handleGoHome,
      handleLogout,
      selectGroup,
      getGroupsByQq,
      isQqBindingExpanded,
      toggleQqBindingGroups,
      isGroupActive,
      handleAvatarError,
      getGroupAvatar,
      refreshGroups,
      userAvatarUrl,
      contextMenuVisible,
      contextMenuPosition,
      contextMenuGroup,
      showContextMenu,
      hideContextMenu,
      deleteMessagesByType,
      deleteGroupConversation,
      balance,
      formatCreditsNumber,
      goToCreditsCenter,
      // 群类型设置相关
      GT_MAP,
      groupTypeSelectorVisible,
      groupTypeTarget,
      computedTargetGroupType,
      openGroupTypeSelector,
      closeGroupTypeSelector,
      handleGroupTypeSaved
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
  /* 收起后隐藏滚动条，避免滚动条占用内容宽度导致头像/图标看起来不居中 */
  scrollbar-width: none;
  -ms-overflow-style: none;
}

.sidebar.collapsed::-webkit-scrollbar {
  display: none;
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
  font-size: 20px;
  font-weight: bold;
  white-space: nowrap;
  overflow: hidden;
  font-family: 'Georgia', 'Times New Roman', serif;
  background: linear-gradient(135deg, #ff6b6b, #feca57, #48dbfb, #ff9ff3);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.1);
  letter-spacing: 2px;
}

/* 用户信息区域 */
.user-info {
  padding: 12px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  background-color: transparent;
  cursor: pointer;
  transition: background-color 0.2s;
  border-bottom: none;
}

.user-info:hover {
  background-color: var(--sidebar-hover, #34495e);
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

/* 侧边栏收起时，顶部用户头像居中显示 */
.sidebar.collapsed .user-info {
  width: 60px;
  justify-content: center;
  padding: 15px 0;
  gap: 0;
}

.sidebar.collapsed .profile-arrow {
  display: none;
}

/* 收起后所有导航项的图标/头像也水平居中 */
.sidebar.collapsed .nav-item {
  justify-content: center;
  padding-left: 0;
  padding-right: 0;
}

.sidebar.collapsed .nav-item .nav-icon {
  min-width: auto;
}

.sidebar.collapsed .group-item,
.sidebar.collapsed .qq-binding-header {
  padding-left: 0;
  padding-right: 0;
  justify-content: center;
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

.nav-link {
  display: flex;
  align-items: center;
  gap: 12px;
  color: inherit;
  text-decoration: none;
  flex: 1;
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
  position: relative;
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

.unread-badge {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  background-color: #ff3b30;
  color: white;
  font-size: 11px;
  font-weight: bold;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-left: auto;
  flex-shrink: 0;
}

/* QQ绑定分组标题样式 */
.qq-binding-header {
  padding: 8px 20px;
  background-color: #263545;
  border-top: 1px solid #34495e;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 10px;
}

.qq-binding-header:hover {
  background-color: #2c3e50;
}

.qq-binding-header .expand-icon {
  margin-left: auto;
  flex-shrink: 0;
}

.qq-binding-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
  background-color: #34495e;
  display: flex;
  align-items: center;
  justify-content: center;
}

.qq-binding-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.qq-binding-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
  position: relative;
}

.qq-binding-name {
  font-size: 13px;
  font-weight: 600;
  color: #ecf0f1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.qq-binding-number {
  font-size: 11px;
  color: #95a5a6;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.qq-binding-header .default-badge {
  position: absolute;
  right: 0;
  top: 2px;
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  background-color: #3498db;
  color: white;
}

.sidebar-footer {
  padding: 20px 0;
  border-top: 1px solid #34495e;
}

.footer-user-info {
  border-bottom: 1px solid var(--border-color, #34495e);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 积分徽章 */
.credits-badge {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0 16px 10px;
  padding: 6px 12px;
  background: linear-gradient(135deg, rgba(52, 152, 219, 0.18), rgba(155, 89, 182, 0.18));
  border: 1px solid rgba(52, 152, 219, 0.35);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  user-select: none;
}
.credits-badge:hover {
  background: linear-gradient(135deg, rgba(52, 152, 219, 0.32), rgba(155, 89, 182, 0.32));
  border-color: rgba(52, 152, 219, 0.6);
  transform: translateY(-1px);
}
.credits-badge-gem {
  font-size: 13px;
  line-height: 1;
}
.credits-badge-num {
  font-size: 13px;
  font-weight: 700;
  color: #ecf0f1;
}
.credits-badge-label {
  font-size: 11px;
  color: #95a5a6;
  margin-left: 1px;
}

.user-name-footer {
  flex: 1;
  color: #ecf0f1;
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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

/* 右键菜单 */
.context-menu {
  position: fixed;
  background: white;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  min-width: 160px;
  z-index: 2000;
  padding: 6px 0;
  font-size: 13px;
  color: #2c3e50;
}

.context-menu-header {
  padding: 6px 14px;
  font-weight: 600;
  color: #7f8c8d;
  border-bottom: 1px solid #eee;
  margin-bottom: 4px;
}

.context-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  cursor: pointer;
  transition: background-color 0.15s;
}

.context-menu-item:hover {
  background-color: #f5f5f5;
}

.context-menu-item.danger {
  color: #e74c3c;
}

.context-menu-item.danger:hover {
  background-color: #ffebee;
}

.context-menu-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  color: #7f8c8d;
}

.context-menu-divider {
  height: 1px;
  background-color: #eee;
  margin: 4px 0;
}

/* 群类型展示：头像右下角徽章 + 群名旁迷你标签 */
.gt-badge {
  position: absolute;
  right: -3px;
  bottom: -3px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 9px;
  color: white;
  border: 2px solid #2c3e50;
  box-shadow: 0 0 0 1px rgba(0,0,0,0.1);
  line-height: 1;
}
.gt-badge-GAME   { background: #6366f1; }
.gt-badge-STUDY  { background: #10b981; }
.gt-badge-WORK   { background: #f59e0b; }
.gt-badge-HOBBY  { background: #ec4899; }
.gt-badge-LIFE   { background: #14b8a6; }
.gt-badge-SOCIAL { background: #3b82f6; }
.gt-badge-OTHER  { background: #6b7280; }

.gt-mini {
  display: inline-block;
  margin-left: 5px;
  padding: 0 5px;
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  border-radius: 4px;
  color: white;
  vertical-align: middle;
  transform: translateY(-1px);
}
.gt-mini-GAME   { background: #6366f1; }
.gt-mini-STUDY  { background: #10b981; }
.gt-mini-WORK   { background: #f59e0b; }
.gt-mini-HOBBY  { background: #ec4899; }
.gt-mini-LIFE   { background: #14b8a6; }
.gt-mini-SOCIAL { background: #3b82f6; }
.gt-mini-OTHER  { background: #6b7280; }
</style>
