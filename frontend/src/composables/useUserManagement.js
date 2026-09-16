import { ref } from 'vue';
import { adminApi } from '../services/api';
import logger from '../utils/logger';

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
    if (!confirm(`确定要删除用户 ${user.username} 吗？此操作不可恢复！`)) return;
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
  const resetPassword = async (user) => {
    const pwd = window.prompt(
      `重置用户 ${user.username} 的密码\n\n规则：8-64 位，且同时包含大写字母、小写字母与数字\n重置后该用户需用新密码重新登录：`
    );
    if (pwd === null) return;
    if (!/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)[A-Za-z\d@$!%*?&]{8,64}$/.test(pwd)) {
      if (showSystemMsg) showSystemMsg('密码不符合规则：需 8-64 位且含大小写字母与数字', 'error');
      return;
    }
    try {
      await adminApi.resetUserPassword(user.id, pwd);
      if (showSystemMsg) showSystemMsg(`用户 ${user.username} 的密码已重置`);
    } catch (error) {
      if (showSystemMsg) showSystemMsg('重置密码失败: ' + error.message, 'error');
    }
  };

  const cleanup = () => {
    if (userSearchTimer) clearTimeout(userSearchTimer);
  };

  return {
    users, userSearch, userCurrentPage, userPageSize, userTotalElements, userTotalPages,
    loadUsers, onUserSearchInput, goToUserPage,
    toggleRole, toggleActive, deleteUser, resetPassword, cleanup,
  };
}
