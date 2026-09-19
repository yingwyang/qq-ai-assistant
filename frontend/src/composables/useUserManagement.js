import { computed, reactive, ref } from 'vue';
import { adminApi } from '../services/api';
import logger from '../utils/logger';
import { showConfirm } from '../components/ConfirmDialog.vue';

/** 与后端一致：8-64 位，同时包含大写字母、小写字母与数字 */
const PASSWORD_PATTERN = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)[A-Za-z\d@$!%*?&]{8,64}$/;

/** 生成满足规则的随机密码（去掉容易混淆的 0/O/1/l/I） */
function generatePassword(length = 12) {
  const upper = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
  const lower = 'abcdefghijkmnopqrstuvwxyz';
  const digits = '23456789';
  const all = upper + lower + digits;
  const pick = (set) => set[Math.floor(Math.random() * set.length)];
  const chars = [pick(upper), pick(lower), pick(digits)];
  for (let i = chars.length; i < length; i++) chars.push(pick(all));
  for (let i = chars.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}

export function useUserManagement({ showSystemMsg } = {}) {
  const users = ref([]);
  const userSearch = ref('');
  const userCurrentPage = ref(0);
  const userPageSize = ref(20);
  const userTotalElements = ref(0);
  const userTotalPages = ref(0);
  const userLoading = ref(false);
  const userError = ref('');

  // 角色 / 状态 / 排序筛选（后端 Specification + 排序白名单）
  const userRole = ref('');
  const userActive = ref('');
  const userSort = ref('createdAt');
  const userOrder = ref('desc');
  /** 系统里「可用管理员」数量：<=1 时最后一位管理员不可降权/禁用/删除 */
  const activeAdminCount = ref(0);

  // 批量操作：勾选 + 执行中
  const selectedUserIds = ref(new Set());
  const batchRunning = ref(false);

  const userRoleOptions = [
    { value: '', label: '全部角色' },
    { value: 'ADMIN', label: '管理员' },
    { value: 'USER', label: '普通用户' },
  ];
  const userActiveOptions = [
    { value: '', label: '全部状态' },
    { value: 'true', label: '正常' },
    { value: 'false', label: '已禁用' },
  ];
  const userSortOptions = [
    { value: 'createdAt,desc', label: '注册时间（新→旧）' },
    { value: 'createdAt,asc', label: '注册时间（旧→新）' },
    { value: 'lastLoginTime,desc', label: '最近登录（新→旧）' },
    { value: 'username,asc', label: '账号（A→Z）' },
    { value: 'id,desc', label: 'ID（大→小）' },
  ];

  let userSearchTimer = null;

  const buildParams = (extra = {}) => {
    const params = {
      page: userCurrentPage.value,
      size: userPageSize.value,
      ...extra,
    };
    if (userSearch.value.trim()) params.keyword = userSearch.value.trim();
    if (userRole.value) params.role = userRole.value;
    if (userActive.value !== '') params.active = userActive.value;
    params.sort = userSort.value;
    params.order = userOrder.value;
    return params;
  };

  const loadUsers = async () => {
    userLoading.value = true;
    userError.value = '';
    try {
      const pageData = await adminApi.getUsers(buildParams());
      users.value = pageData.content || [];
      userTotalElements.value = pageData.totalElements || 0;
      userTotalPages.value = pageData.totalPages || 0;
      userCurrentPage.value = pageData.number || 0;
      activeAdminCount.value = pageData.activeAdminCount ?? activeAdminCount.value;
    } catch (error) {
      logger.error('加载用户列表失败:', error);
      userError.value = error.message || '加载用户列表失败';
      users.value = [];
      userTotalElements.value = 0;
      userTotalPages.value = 0;
    } finally {
      userLoading.value = false;
    }
  };

  /**
   * 是否是「最后一位可用管理员」。
   * 这类账号不能降权 / 禁用 / 删除——否则没人能再进 /admin（后端也会拦，这里提前禁按钮并说明原因）。
   */
  const isLastActiveAdmin = (user) => {
    if (!user || !user.active) return false;
    if (!user.role || user.role.toUpperCase() !== 'ADMIN') return false;
    return activeAdminCount.value <= 1;
  };

  const lastAdminTip = '系统至少要保留一位可登录的管理员：请先给其它账号提权，再操作这个账号';

  const onUserSearchInput = () => {
    if (userSearchTimer) clearTimeout(userSearchTimer);
    userSearchTimer = setTimeout(() => {
      userCurrentPage.value = 0;
      loadUsers();
    }, 300);
  };

  const setUserRole = (role) => {
    userRole.value = role || '';
    userCurrentPage.value = 0;
    loadUsers();
  };

  const setUserActive = (active) => {
    userActive.value = active === undefined || active === null ? '' : String(active);
    userCurrentPage.value = 0;
    loadUsers();
  };

  const setUserSort = (value) => {
    const [field, order] = String(value || 'createdAt,desc').split(',');
    userSort.value = field || 'createdAt';
    userOrder.value = order || 'desc';
    userCurrentPage.value = 0;
    loadUsers();
  };

  const setUserPageSize = (size) => {
    userPageSize.value = Number(size) || 20;
    userCurrentPage.value = 0;
    loadUsers();
  };

  const resetUserFilters = () => {
    userSearch.value = '';
    userRole.value = '';
    userActive.value = '';
    userSort.value = 'createdAt';
    userOrder.value = 'desc';
    userCurrentPage.value = 0;
    loadUsers();
  };

  const userFilterActive = computed(() =>
    !!userSearch.value.trim() || !!userRole.value || userActive.value !== '');

  // ===== 批量选择与批量启停 =====
  const selectedUserCount = computed(() => selectedUserIds.value.size);

  const toggleUserSelection = (id) => {
    const next = new Set(selectedUserIds.value);
    if (next.has(id)) next.delete(id); else next.add(id);
    selectedUserIds.value = next;
  };

  const isUserSelected = (id) => selectedUserIds.value.has(id);

  const toggleSelectAllUsers = () => {
    if (selectedUserIds.value.size === users.value.length && users.value.length > 0) {
      selectedUserIds.value = new Set();
      return;
    }
    selectedUserIds.value = new Set(users.value.map((u) => u.id));
  };

  const clearUserSelection = () => {
    selectedUserIds.value = new Set();
  };

  /**
   * 批量启用 / 禁用。逐个提交并汇总结果，部分失败时不允许静默跳过。
   */
  const batchSetUserActive = async (active) => {
    const targets = users.value.filter((u) => selectedUserIds.value.has(u.id) && u.active !== active);
    if (targets.length === 0) {
      if (showSystemMsg) showSystemMsg('没有需要变更状态的用户', 'error');
      return;
    }
    const ok = await showConfirm({
      title: active ? '批量启用用户' : '批量禁用用户',
      message: `将对选中的 ${targets.length} 个用户执行「${active ? '启用' : '禁用'}」，是否继续？`,
      type: 'warning',
      confirmText: active ? '批量启用' : '批量禁用',
    });
    if (!ok) return;

    batchRunning.value = true;
    const failed = [];
    let succeeded = 0;
    try {
      for (const user of targets) {
        try {
          await adminApi.updateUserActive(user.id, active);
          succeeded++;
        } catch (e) {
          failed.push(`${user.username}: ${e.message}`);
        }
      }
      if (showSystemMsg) {
        if (failed.length === 0) {
          showSystemMsg(`已${active ? '启用' : '禁用'} ${succeeded} 个用户`);
        } else {
          showSystemMsg(`成功 ${succeeded} 个，失败 ${failed.length} 个（${failed.slice(0, 3).join('；')}）`, 'error');
        }
      }
      clearUserSelection();
      await loadUsers();
    } finally {
      batchRunning.value = false;
    }
  };

  /** 导出 CSV：直链下载，沿用当前筛选与排序 */
  const userExportUrl = computed(() => adminApi.exportUsersUrl({
    keyword: userSearch.value.trim() || undefined,
    role: userRole.value || undefined,
    active: userActive.value !== '' ? userActive.value : undefined,
    sort: userSort.value,
    order: userOrder.value,
  }));

  const goToUserPage = (page) => {
    if (page < 0 || page >= userTotalPages.value) return;
    userCurrentPage.value = page;
    loadUsers();
  };

  const toggleRole = async (user) => {
    const newRole = user.role === 'ADMIN' ? 'USER' : 'ADMIN';
    try {
      await adminApi.updateUserRole(user.id, newRole);
      if (showSystemMsg) showSystemMsg(`用户 ${user.username} 角色已更新为 ${newRole === 'ADMIN' ? '管理员' : '普通用户'}`);
      await loadUsers();
    } catch (error) {
      if (showSystemMsg) showSystemMsg('更新角色失败: ' + error.message, 'error');
    }
  };

  const toggleActive = async (user) => {
    try {
      await adminApi.updateUserActive(user.id, !user.active);
      if (showSystemMsg) showSystemMsg(`用户 ${user.username} 已${user.active ? '禁用' : '启用'}`);
      await loadUsers();
    } catch (error) {
      if (showSystemMsg) showSystemMsg('更新状态失败: ' + error.message, 'error');
    }
  };

  const deleteUser = async (user) => {
    const ok = await showConfirm({
      title: '删除用户',
      message: `确定要删除用户 ${user.username} 吗？该用户的积分、订单等关联数据会一并清理，且不可恢复。`,
      type: 'error',
      confirmText: '删除用户',
    });
    if (!ok) return;
    try {
      await adminApi.deleteUser(user.id);
      if (showSystemMsg) showSystemMsg(`用户 ${user.username} 已删除`);
      await loadUsers();
    } catch (error) {
      if (showSystemMsg) showSystemMsg('删除失败: ' + error.message, 'error');
    }
  };

  /**
   * 重置用户密码（用于用户"忘记密码"）。
   * 密码规则与后端一致：8-64 位，同时包含大写字母、小写字母与数字。
   * 重置后该用户已签发的登录态会立即失效。
   */
  const resetPasswordModal = reactive({
    visible: false,
    userId: null,
    username: '',
    password: '',
    valid: false,
    submitting: false,
  });

  const validatePassword = () => {
    resetPasswordModal.valid = PASSWORD_PATTERN.test(resetPasswordModal.password || '');
    return resetPasswordModal.valid;
  };

  const openResetPasswordModal = (user) => {
    resetPasswordModal.visible = true;
    resetPasswordModal.userId = user.id;
    resetPasswordModal.username = user.username;
    resetPasswordModal.password = generatePassword();
    resetPasswordModal.submitting = false;
    validatePassword();
  };

  const closeResetPasswordModal = () => {
    resetPasswordModal.visible = false;
  };

  const generateResetPassword = () => {
    resetPasswordModal.password = generatePassword();
    validatePassword();
  };

  const copyResetPassword = async () => {
    const pwd = resetPasswordModal.password || '';
    if (!pwd) return;
    try {
      await navigator.clipboard.writeText(pwd);
      if (showSystemMsg) showSystemMsg('新密码已复制到剪贴板');
    } catch (e) {
      if (showSystemMsg) showSystemMsg('复制失败，请手动选中复制', 'error');
    }
  };

  const submitResetPassword = async () => {
    if (!validatePassword()) {
      if (showSystemMsg) showSystemMsg('密码不符合规则：需 8-64 位且含大小写字母与数字', 'error');
      return;
    }
    resetPasswordModal.submitting = true;
    try {
      await adminApi.resetUserPassword(resetPasswordModal.userId, resetPasswordModal.password);
      if (showSystemMsg) showSystemMsg(`用户 ${resetPasswordModal.username} 的密码已重置`);
      closeResetPasswordModal();
    } catch (error) {
      if (showSystemMsg) showSystemMsg('重置密码失败: ' + error.message, 'error');
    } finally {
      resetPasswordModal.submitting = false;
    }
  };

  const cleanup = () => {
    if (userSearchTimer) clearTimeout(userSearchTimer);
  };

  return {
    users, userSearch, userCurrentPage, userPageSize, userTotalElements, userTotalPages,
    userLoading, userError,
    userRole, userActive, userSort, userOrder,
    userRoleOptions, userActiveOptions, userSortOptions, userFilterActive, userExportUrl,
    activeAdminCount, isLastActiveAdmin, lastAdminTip,
    setUserRole, setUserActive, setUserSort, setUserPageSize, resetUserFilters,
    selectedUserIds, selectedUserCount, isUserSelected, toggleUserSelection,
    toggleSelectAllUsers, clearUserSelection, batchSetUserActive, batchRunning,
    loadUsers, onUserSearchInput, goToUserPage,
    toggleRole, toggleActive, deleteUser, cleanup,
    resetPasswordModal, openResetPasswordModal, closeResetPasswordModal,
    generateResetPassword, copyResetPassword, submitResetPassword, validatePassword,
  };
}
