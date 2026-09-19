<template>
  <div class="tab-panel admin-config">
    <AdminPageHeader title="配置管理" subtitle="运行时配置项（保存后按标记决定是否需重启）">
      <template #meta>
        <span v-if="hasUnsavedChanges" class="dirty-badge">有未保存的修改</span>
      </template>
      <input
        v-model="configKeyword"
        type="text"
        class="config-search"
        placeholder="搜索配置项（名称或 key）"
      />
      <button class="btn-action" @click="toggleShowAllSecrets">
        {{ showAllSecrets ? '隐藏全部密钥' : '显示全部密钥' }}
      </button>
      <button class="btn-action promote" :disabled="configLoading" @click="loadConfig">重新加载</button>
    </AdminPageHeader>

    <div v-if="restartRequired.length" class="notice-bar is-warn">
      <span>以下配置已保存，但需要<strong>重启后端</strong>才会生效：{{ restartRequired.join('、') }}</span>
      <button class="btn-link" @click="dismissRestartBanner">知道了</button>
    </div>

    <div v-if="configLoading" class="loading-box">加载配置中...</div>
    <div v-else-if="configError" class="state-box is-error">
      <span class="state-title">加载配置失败</span>
      <span>{{ configError }}</span>
      <div class="state-actions"><button class="btn-action promote" @click="loadConfig">重试</button></div>
    </div>
    <div v-else-if="filteredGroups.length === 0" class="state-box">
      <span class="state-title">没有匹配的配置项</span>
      <span>换个关键字试试，或清空搜索框</span>
    </div>

    <div v-else class="config-groups">
      <div v-for="group in filteredGroups" :key="group.name" class="config-group-card">
        <div class="config-group-header">
          <h4>
            <button class="collapse-toggle" @click="toggleGroup(group.name)">
              <Icon :name="collapsedGroups[group.name] ? 'expand' : 'collapse'" :size="14" />
            </button>
            {{ group.name }}
            <span v-if="isGroupDirty(group)" class="dirty-badge">未保存</span>
          </h4>
          <div class="config-group-actions">
            <button v-if="!configEditing[group.name]" class="btn-action promote" @click="startEditConfig(group.name)">编辑</button>
            <template v-else>
              <button
                class="btn-action promote"
                :disabled="configSaving || !canSaveGroup(group.name)"
                :title="canSaveGroup(group.name) ? '' : '请先修正校验不通过的字段'"
                @click="saveConfig(group.name)"
              >{{ configSaving ? '保存中...' : '保存' }}</button>
              <button class="btn-action disable" @click="cancelEditConfig(group.name)">取消</button>
            </template>
          </div>
        </div>

        <div v-show="!collapsedGroups[group.name]" class="config-items">
          <div v-for="item in group.items" :key="item.key" class="config-item">
            <div class="config-item-label">
              <span>{{ item.label }}</span>
              <code class="config-key">{{ item.key }}</code>
              <span v-if="item.restartRequired" class="restart-badge">需重启</span>
              <span v-if="item.secret" class="restart-badge is-secret">密钥</span>
            </div>
            <p v-if="item.description" class="config-desc">{{ item.description }}</p>

            <div class="config-item-value">
              <template v-if="configEditing[group.name]">
                <div class="config-edit-row">
                  <input
                    :type="item.secret && !configShowSecret[item.key] ? 'password' : 'text'"
                    class="config-input"
                    :class="{ 'has-error': groupErrors(group.name)[item.key] }"
                    v-model="configEditValues[item.key]"
                    :placeholder="item.secret ? (item.value === '***' ? '输入新值（留空不修改）' : '') : ''"
                  />
                  <button
                    v-if="item.secret"
                    class="btn-toggle-secret"
                    @click="toggleSecret(item.key)"
                    :title="configShowSecret[item.key] ? '隐藏' : '显示'"
                  >{{ configShowSecret[item.key] ? '隐藏' : '显示' }}</button>
                </div>
                <p v-if="groupErrors(group.name)[item.key]" class="config-field-error">
                  {{ groupErrors(group.name)[item.key] }}
                </p>
              </template>
              <template v-else>
                <span class="config-value" :class="{ 'secret-masked': item.secret }">
                  {{ item.value || '（未配置）' }}
                </span>
                <span v-if="item.secret" class="config-secret-note">后端不回显真实值，编辑时留空表示不修改</span>
              </template>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { inject } from 'vue';
import Icon from '../../components/Icon.vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';

export default {
  name: 'AdminConfig',
  components: { Icon, AdminPageHeader },
  setup() {
    const config = inject('adminConfig');
    return {
      configGroups: config.configGroups,
      filteredGroups: config.filteredGroups,
      configLoading: config.configLoading,
      configError: config.configError,
      configEditing: config.configEditing,
      configEditValues: config.configEditValues,
      configShowSecret: config.configShowSecret,
      collapsedGroups: config.collapsedGroups,
      configKeyword: config.configKeyword,
      showAllSecrets: config.showAllSecrets,
      restartRequired: config.restartRequired,
      configSaving: config.configSaving,
      hasUnsavedChanges: config.hasUnsavedChanges,
      startEditConfig: config.startEditConfig,
      cancelEditConfig: config.cancelEditConfig,
      saveConfig: config.saveConfig,
      isGroupDirty: config.isGroupDirty,
      groupErrors: config.groupErrors,
      canSaveGroup: config.canSaveGroup,
      toggleGroup: config.toggleGroup,
      toggleSecret: config.toggleSecret,
      toggleShowAllSecrets: config.toggleShowAllSecrets,
      dismissRestartBanner: config.dismissRestartBanner,
      loadConfig: config.loadConfig,
    };
  },
};
</script>

<style scoped>
.config-search { padding: 6px 12px; border: 1px solid var(--border-color, #e0e0e0); border-radius: 6px; background: var(--input-bg, #fff); color: var(--text-primary, #333); font-size: 13px; width: 240px; }
.config-groups { display: grid; grid-template-columns: repeat(auto-fill, minmax(380px, 1fr)); gap: 16px; }
.config-group-card { background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e8e8e8); border-radius: 8px; padding: 18px; }
.config-group-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; padding-bottom: 10px; border-bottom: 1px solid var(--border-color, #f0f0f0); }
.config-group-header h4 { display: flex; align-items: center; gap: 8px; margin: 0; font-size: 15px; color: var(--text-primary, #333); }
.config-group-header .collapse-toggle { padding: 2px 6px; }
.config-group-actions { display: flex; gap: 6px; }
.config-items { display: flex; flex-direction: column; gap: 14px; }
.config-item { display: flex; flex-direction: column; gap: 4px; }
.config-item-label { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--text-muted, #888); flex-wrap: wrap; }
.config-key { font-size: 11px; color: var(--text-muted, #999); background: var(--bg-tertiary, #f5f6fa); padding: 1px 5px; border-radius: 3px; }
.config-desc { margin: 0; font-size: 11.5px; color: var(--text-muted, #999); line-height: 1.5; }
.restart-badge { display: inline-block; padding: 1px 6px; background: #fff3e0; color: #f57c00; border-radius: 3px; font-size: 10px; font-weight: 500; }
.restart-badge.is-secret { background: rgba(52, 152, 219, 0.14); color: #2980b9; }
.theme-dark .restart-badge { background: rgba(245, 124, 0, 0.2); color: #ffb74d; }
.theme-dark .restart-badge.is-secret { background: rgba(52, 152, 219, 0.22); color: #64b5f6; }
.config-item-value { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.config-value { flex: 1; font-size: 13px; color: var(--text-primary, #333); padding: 6px 0; min-height: 30px; display: flex; align-items: center; word-break: break-all; }
.config-value.secret-masked { color: var(--text-muted, #aaa); letter-spacing: 2px; }
.config-secret-note { font-size: 11px; color: var(--text-muted, #999); }
.config-edit-row { display: flex; align-items: center; gap: 6px; flex: 1; }
.config-input { flex: 1; padding: 6px 10px; border: 1px solid var(--border-color, #e0e0e0); border-radius: 4px; background: var(--input-bg, #fff); font-size: 13px; color: var(--text-primary, #333); outline: none; transition: border-color 0.2s; }
.config-input:focus { border-color: var(--accent-color, #3498db); }
.config-input.has-error { border-color: #e74c3c; }
.config-field-error { margin: 0; font-size: 11.5px; color: #e74c3c; }
.btn-toggle-secret { padding: 4px 8px; border: 1px solid var(--border-color, #e0e0e0); border-radius: 4px; background: var(--card-bg, #fff); color: var(--text-secondary, #666); cursor: pointer; font-size: 12px; line-height: 1.4; transition: border-color 0.2s; }
.btn-toggle-secret:hover { border-color: var(--accent-color, #3498db); }
</style>
