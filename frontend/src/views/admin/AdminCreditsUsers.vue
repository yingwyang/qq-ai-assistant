<template>
  <div class="tab-panel admin-credits-users">
    <AdminPageHeader title="用户积分" subtitle="余额、累计消耗与人工调账" />

    <div class="user-search-bar">
      <input v-model="ucKeyword" type="text" placeholder="搜索用户名或昵称..." class="user-search-input" @input="onUcSearchInput" />
    </div>

    <div class="user-table-wrapper">
      <table class="user-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>用户名</th>
            <th>昵称</th>
            <th>角色</th>
            <th>余额</th>
            <th>订阅等级</th>
            <th>订阅到期</th>
            <th>累计收入</th>
            <th>累计支出</th>
            <th>消费封禁</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="ucLoading"><td colspan="11" class="audit-loading">加载中...</td></tr>
          <tr v-else-if="ucList.length === 0"><td colspan="11" class="audit-empty">暂无用户数据</td></tr>
          <tr v-for="row in ucList" :key="row.userId">
            <td>{{ row.userId }}</td>
            <td>{{ row.username }}</td>
            <td>{{ row.nickname || '-' }}</td>
            <td><span class="role-badge" :class="(row.role || 'USER').toLowerCase()">{{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}</span></td>
            <td class="amount-cell">{{ row.balance }}</td>
            <td><span class="tier-badge" :class="('tier-' + (row.subscriptionTier || 'FREE')).toLowerCase()">{{ row.subscriptionTier || 'FREE' }}</span></td>
            <td>{{ formatDate(row.subscriptionExpiresAt) }}</td>
            <td class="credits-cell">{{ row.totalEarned }}</td>
            <td class="spent-cell">{{ row.totalSpent }}</td>
            <td><span class="status-badge" :class="row.consumptionBanned ? 'inactive' : 'active'">{{ row.consumptionBanned ? '已封禁' : '正常' }}</span></td>
            <td>
              <div class="action-btns">
                <button class="btn-action promote" @click="openAdjustModal(row)">调整积分</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="user-pagination">
      <span class="pagination-info">共 {{ ucTotalElements }} 条，第 {{ ucPage + 1 }} / {{ Math.max(1, ucTotalPages) }} 页</span>
      <div class="pagination-btns">
        <button class="btn-page" :disabled="ucPage === 0" @click="goToUcPage(0)">首页</button>
        <button class="btn-page" :disabled="ucPage === 0" @click="goToUcPage(ucPage - 1)">上一页</button>
        <button class="btn-page" :disabled="ucPage >= ucTotalPages - 1" @click="goToUcPage(ucPage + 1)">下一页</button>
        <button class="btn-page" :disabled="ucPage >= ucTotalPages - 1" @click="goToUcPage(ucTotalPages - 1)">末页</button>
      </div>
    </div>
  </div>
</template>

<script>
import { inject } from 'vue';
import Icon from '../../components/Icon.vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';

export default {
  name: 'AdminCreditsUsers',
  components: { Icon, AdminPageHeader },
  setup() {
    const adminUserCredits = inject('adminUserCredits');
    const formatDate = inject('adminFormatDate');
    return {
      ucList: adminUserCredits.ucList,
      ucLoading: adminUserCredits.ucLoading,
      ucKeyword: adminUserCredits.ucKeyword,
      ucPage: adminUserCredits.ucPage,
      ucTotalElements: adminUserCredits.ucTotalElements,
      ucTotalPages: adminUserCredits.ucTotalPages,
      loadUserCredits: adminUserCredits.loadUserCredits,
      onUcSearchInput: adminUserCredits.onUcSearchInput,
      goToUcPage: adminUserCredits.goToUcPage,
      openAdjustModal: adminUserCredits.openAdjustModal,
      formatDate,
    };
  },
};
</script>

<style scoped>
</style>