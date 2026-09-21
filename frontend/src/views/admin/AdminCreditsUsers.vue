<template>
  <div class="tab-panel admin-credits-users">
    <AdminPageHeader title="用户积分" subtitle="余额、累计消耗与人工调账">
      <template #meta>
        <span v-if="selectedUserCount > 0" class="dirty-badge">已选 {{ selectedUserCount }} 人</span>
      </template>
      <a class="btn-action" :href="ucExportUrl" download>导出 CSV</a>
      <button class="btn-action" :disabled="ucLoading" @click="loadUserCredits(ucPage)">{{ ucLoading ? '刷新中...' : '刷新' }}</button>
    </AdminPageHeader>

    <div class="filter-bar">
      <div class="filter-field is-grow">
        <label>搜索</label>
        <input v-model="ucKeyword" type="text" placeholder="用户名或昵称..." @input="onUcSearchInput" />
      </div>
      <div class="filter-field">
        <label>订阅层级</label>
        <select :value="ucTier" @change="setUcTier($event.target.value)">
          <option v-for="opt in ucTierOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
      </div>
      <div class="filter-field">
        <label>余额下限</label>
        <input v-model.number="ucMin" type="number" placeholder="不限" @change="setUcRange" />
      </div>
      <div class="filter-field">
        <label>余额上限</label>
        <input v-model.number="ucMax" type="number" placeholder="不限" @change="setUcRange" />
      </div>
      <div class="filter-field">
        <label>排序</label>
        <select :value="`${ucSort},${ucOrder}`" @change="setUcSort($event.target.value)">
          <option v-for="opt in ucSortOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
      </div>
      <div class="filter-field">
        <label>每页</label>
        <select :value="String(ucSize)" @change="setUcPageSize($event.target.value)">
          <option value="20">20</option>
          <option value="50">50</option>
          <option value="100">100</option>
        </select>
      </div>
      <button v-if="ucFilterActive" class="btn-action" @click="resetUcFilters">清空筛选</button>
    </div>

    <div class="data-toolbar">
      <span class="data-toolbar-info">共 {{ ucTotalElements }} 个用户</span>
      <div class="data-toolbar-actions">
        <button
          class="btn-action promote"
          :disabled="selectedUserCount === 0"
          @click="openBatchAdjustModal"
        >批量调账<span v-if="selectedUserCount">（{{ selectedUserCount }}）</span></button>
        <button v-if="selectedUserCount > 0" class="btn-action" @click="clearUserSelection">取消选择</button>
      </div>
    </div>

    <div class="user-table-wrapper">
      <table class="user-table">
        <thead>
          <tr>
            <th class="col-check"><input type="checkbox" :checked="ucList.length > 0 && selectedUserCount === ucList.length" @change="toggleSelectAllUsers" /></th>
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
          <tr v-if="ucLoading"><td colspan="12"><StatePanel state="loading" compact /></td></tr>
          <tr v-else-if="ucError"><td colspan="12"><StatePanel state="error" :title="ucError" action-text="重试" @action="loadUserCredits" compact /></td></tr>
          <tr v-else-if="ucList.length === 0"><td colspan="12"><StatePanel state="empty" :title="ucFilterActive ? '当前筛选没有用户' : '暂无用户数据'" compact /></td></tr>
          <tr v-for="row in ucList" :key="row.userId">
            <td class="col-check"><input type="checkbox" :checked="isUserSelected(row.userId)" @change="toggleUserSelection(row.userId)" /></td>
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
                <button class="btn-action" @click="viewTransactions(row)">查看流水</button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="pager">
      <span class="pager-info">第 {{ ucPage + 1 }} / {{ Math.max(1, ucTotalPages) }} 页 · 每页 {{ ucSize }} 条</span>
      <button class="btn-page" :disabled="ucPage === 0" @click="goToUcPage(0)">首页</button>
      <button class="btn-page" :disabled="ucPage === 0" @click="goToUcPage(ucPage - 1)">上一页</button>
      <button class="btn-page" :disabled="ucPage >= ucTotalPages - 1" @click="goToUcPage(ucPage + 1)">下一页</button>
      <button class="btn-page" :disabled="ucPage >= ucTotalPages - 1" @click="goToUcPage(ucTotalPages - 1)">末页</button>
    </div>
  </div>
</template>

<script>
import { inject } from 'vue';
import Icon from '../../components/Icon.vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';
import StatePanel from '../../components/common/StatePanel.vue';

export default {
  name: 'AdminCreditsUsers',
  components: { Icon, AdminPageHeader, StatePanel },
  setup() {
    const adminUserCredits = inject('adminUserCredits');
    const formatDate = inject('adminFormatDate');
    const setActiveTab = inject('adminSetActiveTab', null);
    const router = inject('adminRouter', null);

    /** 跳到资金流水并带上该用户筛选（资金流水页会读取 URL 上的 userId） */
    const viewTransactions = (row) => {
      if (setActiveTab) {
        setActiveTab('credit-transactions');
        if (router) router.replace({ query: { tab: 'credit-transactions', userId: String(row.userId) } });
      }
    };

    return {
      ucList: adminUserCredits.ucList,
      ucLoading: adminUserCredits.ucLoading,
      ucError: adminUserCredits.ucError,
      ucKeyword: adminUserCredits.ucKeyword,
      ucTier: adminUserCredits.ucTier,
      ucMin: adminUserCredits.ucMin,
      ucMax: adminUserCredits.ucMax,
      ucSort: adminUserCredits.ucSort,
      ucOrder: adminUserCredits.ucOrder,
      ucPage: adminUserCredits.ucPage,
      ucSize: adminUserCredits.ucSize,
      ucTotalElements: adminUserCredits.ucTotalElements,
      ucTotalPages: adminUserCredits.ucTotalPages,
      ucTierOptions: adminUserCredits.ucTierOptions,
      ucSortOptions: adminUserCredits.ucSortOptions,
      ucFilterActive: adminUserCredits.ucFilterActive,
      ucExportUrl: adminUserCredits.ucExportUrl,
      setUcTier: adminUserCredits.setUcTier,
      setUcRange: adminUserCredits.setUcRange,
      setUcSort: adminUserCredits.setUcSort,
      setUcPageSize: adminUserCredits.setUcPageSize,
      resetUcFilters: adminUserCredits.resetUcFilters,
      selectedUserCount: adminUserCredits.selectedUserCount,
      isUserSelected: adminUserCredits.isUserSelected,
      toggleUserSelection: adminUserCredits.toggleUserSelection,
      toggleSelectAllUsers: adminUserCredits.toggleSelectAllUsers,
      clearUserSelection: adminUserCredits.clearUserSelection,
      openBatchAdjustModal: adminUserCredits.openBatchAdjustModal,
      loadUserCredits: adminUserCredits.loadUserCredits,
      onUcSearchInput: adminUserCredits.onUcSearchInput,
      goToUcPage: adminUserCredits.goToUcPage,
      openAdjustModal: adminUserCredits.openAdjustModal,
      viewTransactions,
      formatDate,
    };
  },
};
</script>

<style scoped>
.col-check { width: 36px; text-align: center; }
.col-check input { width: 14px; height: 14px; cursor: pointer; }
</style>
