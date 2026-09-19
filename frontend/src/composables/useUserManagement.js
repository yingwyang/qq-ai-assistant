import { reactive, ref } from 'vue';
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
  let userSearchTimer = null;

  const loadUsers = async () => {
    try {
      const params = {
        page: userCurrentPage.value,
        size: userPageSize.value,
      };
      if (userSearch.value.trim()) {
        params.keyword = userSearch.value.trim();
      }
      const pageData = await adminApi.getUsers(params);
      users.value = pageData.content;
      userTotalElements.value = pageData.totalElements;
      userTotalPages.value = pageData.totalPages;
      userCurrentPage.value = pageData.number;
    } catch (error) {
      logger.error('加载用户列表失败:', error);
    }
  };

  const onUserSearchInput = () => {
    if (userSearchTimer) clearTimeout(userSearchTimer);
    userSearchTimer = setTimeout(() => {
      userCurrentPage.value = 0;
      loadUsers();
    }, 300);
  };

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
    loadUsers, onUserSearchInput, goToUserPage,
    toggleRole, toggleActive, deleteUser, cleanup,
    resetPasswordModal, openResetPasswordModal, closeResetPasswordModal,
    generateResetPassword, copyResetPassword, submitResetPassword, validatePassword,
  };
}
