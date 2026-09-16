<template>
  <div class="tab-panel admin-config">
    <div class="panel-title">
      <Icon name="settings" :size="20" />
      <h2>配置管理</h2>
    </div>

    <div v-if="configLoading" class="loading-box">加载配置中...</div>

    <div v-else class="config-groups">
      <div v-for="group in configGroups" :key="group.name" class="config-group-card">
        <div class="config-group-header">
          <h4>{{ group.name }}</h4>
          <div class="config-group-actions">
            <button v-if="!configEditing[group.name]" class="btn-action promote" @click="startEditConfig(group.name)">编辑</button>
            <template v-else>
              <button class="btn-action promote" @click="saveConfig(group.name)">保存</button>
              <button class="btn-action disable" @click="cancelEditConfig(group.name)">取消</button>
            </template>
          </div>
        </div>
        <div class="config-items">
          <div v-for="item in group.items" :key="item.key" class="config-item">
            <div class="config-item-label">
              <span>{{ item.label }}</span>
              <span v-if="item.restartRequired" class="restart-badge">需重启</span>
            </div>
            <div class="config-item-value">
              <template v-if="configEditing[group.name]">
                <div class="config-edit-row">
                  <input v-if="item.secret" :type="configShowSecret[item.key] ? 'text' : 'password'" class="config-input" v-model="configEditValues[item.key]" :placeholder="item.value === '***' ? '输入新值（留空不修改）' : ''" />
                  <input v-else type="text" class="config-input" v-model="configEditValues[item.key]" />
                  <button v-if="item.secret" class="btn-toggle-secret" @click="configShowSecret[item.key] = !configShowSecret[item.key]" :title="configShowSecret[item.key] ? '隐藏' : '显示'">{{ configShowSecret[item.key] ? '🙈' : '👁' }}</button>
                </div>
              </template>
              <template v-else>
                <span class="config-value" :class="{ 'secret-masked': item.secret && !configShowSecret[item.key] }" @click="item.secret && (configShowSecret[item.key] = !configShowSecret[item.key])">{{ item.secret && !configShowSecret[item.key] ? item.value : (configShowSecret[item.key] ? '已隐藏（点击切换）' : item.value) }}</span>
                <button v-if="item.secret" class="btn-toggle-secret" @click="configShowSecret[item.key] = !configShowSecret[item.key]" :title="configShowSecret[item.key] ? '隐藏' : '显示'">{{ configShowSecret[item.key] ? '🙈' : '👁' }}</button>
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

export default {
  name: 'AdminConfig',
  components: { Icon },
  setup() {
    const config = inject('adminConfig');
    return {
      configGroups: config.configGroups,
      configLoading: config.configLoading,
      configEditing: config.configEditing,
      configEditValues: config.configEditValues,
      configShowSecret: config.configShowSecret,
      startEditConfig: config.startEditConfig,
      cancelEditConfig: config.cancelEditConfig,
      saveConfig: config.saveConfig,
    };
  },
};
</script>

<style scoped>
.config-groups { display: grid; grid-template-columns: repeat(auto-fill, minmax(380px, 1fr)); gap: 16px; }
.config-group-card { background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e8e8e8); border-radius: 8px; padding: 18px; }
.config-group-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; padding-bottom: 10px; border-bottom: 1px solid var(--border-color, #f0f0f0); }
.config-group-header h4 { margin: 0; font-size: 15px; color: var(--text-primary, #333); }
.config-group-actions { display: flex; gap: 6px; }
.config-items { display: flex; flex-direction: column; gap: 12px; }
.config-item { display: flex; flex-direction: column; gap: 4px; }
.config-item-label { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--text-muted, #888); }
.restart-badge { display: inline-block; padding: 1px 6px; background: #fff3e0; color: #f57c00; border-radius: 3px; font-size: 10px; font-weight: 500; }
.config-item-value { display: flex; align-items: center; gap: 6px; }
.config-value { flex: 1; font-size: 13px; color: var(--text-primary, #333); padding: 6px 0; min-height: 30px; display: flex; align-items: center; }
.config-value.secret-masked { cursor: pointer; color: var(--text-muted, #aaa); letter-spacing: 2px; }
.config-edit-row { display: flex; align-items: center; gap: 6px; flex: 1; }
.config-input { flex: 1; padding: 6px 10px; border: 1px solid var(--border-color, #e0e0e0); border-radius: 4px; font-size: 13px; color: var(--text-primary, #333); outline: none; transition: border-color 0.2s; }
.config-input:focus { border-color: var(--accent-color, #3498db); }
.btn-toggle-secret { padding: 4px 8px; border: 1px solid var(--border-color, #e0e0e0); border-radius: 4px; background: var(--card-bg, #fff); cursor: pointer; font-size: 14px; line-height: 1; transition: border-color 0.2s; }
.btn-toggle-secret:hover { border-color: var(--accent-color, #3498db); }
</style>