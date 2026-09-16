<template>
  <div class="tab-panel admin-users">
    <div class="panel-title">
      <Icon name="group" :size="20" />
      <h2>用户管理</h2>
    </div>

    <div class="user-search-bar">
      <input v-model="userSearch" type="text" placeholder="搜索用户名或昵称..." class="user-search-input" @input="onUserSearchInput" />
    </div>

    <div class="user-table-wrapper">
      <table class="user-table">
        <thead>
          <tr>
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
          <tr v-for="user in users" :key="user.id">
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
                <button class="btn-action promote" @click="resetPassword(user)">重置密码</button>
                <button class="btn-action delete" @click="deleteUser(user)">删除</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="users.length === 0" class="empty-table">
        <Icon name="group" :size="48" />
        <p>暂无用户数据</p>
      </div>
    </div>

    <div class="user-pagination">
      <span class="pagination-info">共 {{ userTotalElements }} 条，第 {{ userCurrentPage + 1 }} / {{ userTotalPages }} 页</span>
      <div class="pagination-btns">
        <button class="btn-page" :disabled="userCurrentPage === 0" @click="goToUserPage(0)">首页</button>
        <button class="btn-page" :disabled="userCurrentPage === 0" @click="goToUserPage(userCurrentPage - 1)">上一页</button>
        <button class="btn-page" :disabled="userCurrentPage >= userTotalPages - 1" @click="goToUserPage(userCurrentPage + 1)">下一页</button>
        <button class="btn-page" :disabled="userCurrentPage >= userTotalPages - 1" @click="goToUserPage(userTotalPages - 1)">末页</button>
      </div>
    </div>
  </div>
</template>

<script>
import { inject } from 'vue';
import Icon from '../../components/Icon.vue';

export default {
  name: 'AdminUsers',
  components: { Icon },
  setup() {
    const userMgmt = inject('adminUserMgmt');
    const formatDate = inject('adminFormatDate');
    return {
      users: userMgmt.users,
      userSearch: userMgmt.userSearch,
      userCurrentPage: userMgmt.userCurrentPage,
      userTotalElements: userMgmt.userTotalElements,
      userTotalPages: userMgmt.userTotalPages,
      onUserSearchInput: userMgmt.onUserSearchInput,
      goToUserPage: userMgmt.goToUserPage,
      toggleRole: userMgmt.toggleRole,
      toggleActive: userMgmt.toggleActive,
      deleteUser: userMgmt.deleteUser,
      resetPassword: userMgmt.resetPassword,
      formatDate,
    };
  },
};
</script>

<style scoped>
/* Shell already provides shared .user-table, .user-search-bar, .user-pagination etc. */
</style>