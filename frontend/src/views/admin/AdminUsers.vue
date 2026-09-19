<template>
  <div class="tab-panel admin-users">
    <AdminPageHeader title="用户管理" subtitle="账号、角色、启用状态与最后登录信息">
      <template #meta>
        <span v-if="selectedUserCount > 0" class="dirty-badge">已选 {{ selectedUserCount }} 人</span>
      </template>
      <a class="btn-action" :href="userExportUrl" download>导出 CSV</a>
      <button class="btn-action" :disabled="userLoading" @click="loadUsers">{{ userLoading ? '刷新中...' : '刷新' }}</button>
    </AdminPageHeader>

    <div class="filter-bar">
      <div class="filter-field is-grow">
        <label>搜索</label>
        <input v-model="userSearch" type="text" placeholder="账号或昵称..." @input="onUserSearchInput" />
      </div>
      <div class="filter-field">
        <label>角色</label>
        <select :value="userRole" @change="setUserRole($event.target.value)">
          <option v-for="opt in userRoleOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
      </div>
      <div class="filter-field">
        <label>状态</label>
        <select :value="userActive" @change="setUserActive($event.target.value)">
          <option v-for="opt in userActiveOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
      </div>
      <div class="filter-field">
        <label>排序</label>
        <select :value="`${userSort},${userOrder}`" @change="setUserSort($event.target.value)">
          <option v-for="opt in userSortOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
      </div>
      <div class="filter-field">
        <label>每页</label>
        <select :value="String(userPageSize)" @change="setUserPageSize($event.target.value)">
          <option value="20">20</option>
          <option value="50">50</option>
          <option value="100">100</option>
        </select>
      </div>
      <button v-if="userFilterActive" class="btn-action" @click="resetUserFilters">清空筛选</button>
    </div>

    <div class="data-toolbar">
      <span class="data-toolbar-info">共 {{ userTotalElements }} 个用户</span>
      <div class="data-toolbar-actions">
        <button
          class="btn-action enable"
          :disabled="batchRunning || selectedUserCount === 0"
          @click="batchSetUserActive(true)"
        >批量启用</button>
        <button
          class="btn-action disable"
          :disabled="batchRunning || selectedUserCount === 0"
          @click="batchSetUserActive(false)"
        >批量禁用</button>
        <button
          v-if="selectedUserCount > 0"
          class="btn-action"
          :disabled="batchRunning"
          @click="clearUserSelection"
        >取消选择</button>
      </div>
    </div>

    <div class="user-table-wrapper">
      <table class="user-table">
        <thead>
          <tr>
            <th class="col-check">
              <input
                type="checkbox"
                :checked="userTotalElements > 0 && selectedUserCount === users.length && users.length > 0"
                :disabled="userLoading || users.length === 0"
                @change="toggleSelectAllUsers"
              />
            </th>
            <th>ID</th>
            <th>账号</th>
            <th>昵称</th>
            <th>角色</th>
            <th>状态</th>
            <th>最后登录</th>
            <th>注册时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="userLoading"><td colspan="9" class="audit-loading">加载中...</td></tr>
          <tr v-else-if="userError"><td colspan="9" class="audit-empty">{{ userError }}</td></tr>
          <tr v-else-if="users.length === 0">
            <td colspan="9" class="audit-empty">{{ userFilterActive ? '当前筛选没有用户' : '暂无用户数据' }}</td>
          </tr>
          <tr v-for="user in users" :key="user.id">
            <td class="col-check">
              <input type="checkbox" :checked="isUserSelected(user.id)" @change="toggleUserSelection(user.id)" />
            </td>
            <td>{{ user.id }}</td>
            <td>{{ user.username }}</td>
            <td>{{ user.nickname || '-' }}</td>
            <td><span class="role-badge" :class="user.role?.toLowerCase()">{{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}</span></td>
            <td><span class="status-badge" :class="user.active ? 'active' : 'inactive'">{{ user.active ? '正常' : '已禁用' }}</span></td>
            <td>{{ formatDate(user.lastLoginTime) }}</td>
            <td>{{ formatDate(user.createdAt) }}</td>
            <td>
              <div class="action-btns">
                <button class="btn-action" :class="user.role === 'ADMIN' ? 'demote' : 'promote'" @click="toggleRole(user)">{{ user.role === 'ADMIN' ? '降权' : '提权' }}</button>
                <button class="btn-action" :class="user.active ? 'disable' : 'enable'" @click="toggleActive(user)">{{ user.active ? '禁用' : '启用' }}</button>
                <button class="btn-action promote" @click="openResetPasswordModal(user)">重置密码</button>
                <button class="btn-action delete" @click="deleteUser(user)">删除</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager">
      <span class="pager-info">第 {{ userCurrentPage + 1 }} / {{ Math.max(1, userTotalPages) }} 页 · 每页 {{ userPageSize }} 条</span>
      <button class="btn-page" :disabled="userCurrentPage === 0" @click="goToUserPage(0)">首页</button>
      <button class="btn-page" :disabled="userCurrentPage === 0" @click="goToUserPage(userCurrentPage - 1)">上一页</button>
      <button class="btn-page" :disabled="userCurrentPage >= userTotalPages - 1" @click="goToUserPage(userCurrentPage + 1)">下一页</button>
      <button class="btn-page" :disabled="userCurrentPage >= userTotalPages - 1" @click="goToUserPage(userTotalPages - 1)">末页</button>
    </div>

    <AdminResetPasswordModal
      :state="resetPasswordModal"
      @close="closeResetPasswordModal"
      @submit="submitResetPassword"
      @generate="generateResetPassword"
      @copy="copyResetPassword"
      @validate="validatePassword"
    />
  </div>
</template>

<script>
import { inject } from 'vue';
import Icon from '../../components/Icon.vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';
import AdminResetPasswordModal from '../../components/admin/AdminResetPasswordModal.vue';

export default {
  name: 'AdminUsers',
  components: { Icon, AdminPageHeader, AdminResetPasswordModal },
  setup() {
    const userMgmt = inject('adminUserMgmt');
    const formatDate = inject('adminFormatDate');
    return {
      users: userMgmt.users,
      userSearch: userMgmt.userSearch,
      userCurrentPage: userMgmt.userCurrentPage,
      userPageSize: userMgmt.userPageSize,
      userTotalElements: userMgmt.userTotalElements,
      userTotalPages: userMgmt.userTotalPages,
      userLoading: userMgmt.userLoading,
      userError: userMgmt.userError,
      userRole: userMgmt.userRole,
      userActive: userMgmt.userActive,
      userSort: userMgmt.userSort,
      userOrder: userMgmt.userOrder,
      userRoleOptions: userMgmt.userRoleOptions,
      userActiveOptions: userMgmt.userActiveOptions,
      userSortOptions: userMgmt.userSortOptions,
      userFilterActive: userMgmt.userFilterActive,
      userExportUrl: userMgmt.userExportUrl,
      setUserRole: userMgmt.setUserRole,
      setUserActive: userMgmt.setUserActive,
      setUserSort: userMgmt.setUserSort,
      setUserPageSize: userMgmt.setUserPageSize,
      resetUserFilters: userMgmt.resetUserFilters,
      selectedUserCount: userMgmt.selectedUserCount,
      isUserSelected: userMgmt.isUserSelected,
      toggleUserSelection: userMgmt.toggleUserSelection,
      toggleSelectAllUsers: userMgmt.toggleSelectAllUsers,
      clearUserSelection: userMgmt.clearUserSelection,
      batchSetUserActive: userMgmt.batchSetUserActive,
      batchRunning: userMgmt.batchRunning,
      onUserSearchInput: userMgmt.onUserSearchInput,
      goToUserPage: userMgmt.goToUserPage,
      loadUsers: userMgmt.loadUsers,
      toggleRole: userMgmt.toggleRole,
      toggleActive: userMgmt.toggleActive,
      deleteUser: userMgmt.deleteUser,
      resetPasswordModal: userMgmt.resetPasswordModal,
      openResetPasswordModal: userMgmt.openResetPasswordModal,
      closeResetPasswordModal: userMgmt.closeResetPasswordModal,
      generateResetPassword: userMgmt.generateResetPassword,
      copyResetPassword: userMgmt.copyResetPassword,
      submitResetPassword: userMgmt.submitResetPassword,
      validatePassword: userMgmt.validatePassword,
      formatDate,
    };
  },
};
</script>

<style scoped>
.col-check { width: 36px; text-align: center; }
.col-check input { width: 14px; height: 14px; cursor: pointer; }
</style>
