<template>
  <div class="tab-panel admin-backup">
    <div class="panel-title">
      <Icon name="file" :size="20" />
      <h2>数据维护</h2>
    </div>

    <!-- 数据库备份 -->
    <div class="section-card">
      <div class="maintenance-section-header">
        <h4>数据库备份</h4>
        <button class="btn-action promote" :disabled="isBackingUp" @click="triggerBackup">{{ isBackingUp ? '备份中...' : '立即备份' }}</button>
      </div>
      <div class="backup-list">
        <div v-if="backupList.length === 0" class="empty-backup"><p>暂无备份文件</p></div>
        <div v-for="file in backupList" :key="file.fileName" class="backup-item">
          <div class="backup-info">
            <span class="backup-name">{{ file.fileName }}</span>
            <span class="backup-meta">{{ formatFileSize(file.size) }} · {{ formatDate(new Date(file.lastModified)) }}</span>
          </div>
          <a :href="getDownloadUrl(file.fileName)" class="btn-action promote" download>下载</a>
        </div>
      </div>
    </div>

    <!-- AI 摘要（批量补摘要） -->
    <div class="section-card" style="margin-top: 16px;">
      <div class="maintenance-section-header">
        <h4>AI 摘要（批量补摘要）</h4>
        <button class="btn-action" :disabled="statusLoading" @click="loadStatus">刷新状态</button>
      </div>
      <p class="maintenance-desc">
        为历史消息补生成 AI 摘要。必须指定「群号」或「开始日期」之一，避免一次性投递全部历史消息；
        有任务进行中时重复投递会被拒绝（409）。
      </p>

      <!-- 进度 -->
      <div class="ai-status-grid">
        <div class="ai-status-item">
          <span class="ai-status-value">{{ status.queueDepth ?? '-' }}</span>
          <span class="ai-status-label">队列深度</span>
        </div>
        <div class="ai-status-item">
          <span class="ai-status-value">{{ status.doneToday ?? '-' }}</span>
          <span class="ai-status-label">今日已完成</span>
        </div>
        <div class="ai-status-item">
          <span class="ai-status-value">{{ status.totalPending ?? '-' }}</span>
          <span class="ai-status-label">剩余待处理</span>
        </div>
        <div class="ai-status-item">
          <span class="ai-status-value" :class="status.running ? 'is-running' : ''">{{ status.running ? '进行中' : '空闲' }}</span>
          <span class="ai-status-label">运行状态</span>
        </div>
        <div class="ai-status-item">
          <span class="ai-status-value">{{ status.config?.dailyLimit ?? '-' }}</span>
          <span class="ai-status-label">每日额度</span>
        </div>
        <div class="ai-status-item">
          <span class="ai-status-value">{{ status.config?.enabled === false ? '已关闭' : '开启' }}</span>
          <span class="ai-status-label">功能开关</span>
        </div>
      </div>

      <!-- 表单 -->
      <div class="ai-form-grid">
        <div class="ai-field">
          <label>群号</label>
          <input v-model.trim="form.groupId" type="text" placeholder="如 697381755（可留空）" />
        </div>
        <div class="ai-field">
          <label>开始日期</label>
          <input v-model="form.start" type="date" />
        </div>
        <div class="ai-field">
          <label>结束日期</label>
          <input v-model="form.end" type="date" />
        </div>
        <div class="ai-field">
          <label>条数上限</label>
          <input v-model.number="form.limit" type="number" min="1" max="2000" />
        </div>
        <div class="ai-field">
          <label>最短长度</label>
          <input v-model.number="form.minLength" type="number" min="1" max="200" />
        </div>
        <div class="ai-field ai-field-check">
          <label>仅处理纯文本</label>
          <label class="ai-checkbox"><input type="checkbox" v-model="form.onlyText" /> 跳过图片/视频等占位符</label>
        </div>
      </div>

      <p class="ai-estimate">
        预估：最多投递 <b>{{ estimate.count }}</b> 条 ≈ <b>{{ estimate.tokens }}</b> 万 token ≈ 约 <b>{{ estimate.minutes }}</b> 分钟
        <span class="ai-estimate-note">（按剩余待处理 {{ status.totalPending ?? 0 }} 条、每条 1.5k token / 8 秒估算）</span>
      </p>

      <div class="ai-actions">
        <button class="btn-action promote" :disabled="submitting" @click="submit">
          {{ submitting ? '投递中...' : '开始投递' }}
        </button>
        <button class="btn-action danger" :disabled="stopping || !status.queueDepth" @click="stop">
          {{ stopping ? '停止中...' : '停止并清空队列' }}
        </button>
      </div>

      <!-- 摘要设置（运行时生效，不用改配置文件/重启） -->
      <div class="ai-settings">
        <div class="ai-settings-header">
          <span class="ai-settings-title">摘要设置</span>
          <span class="ai-settings-note">保存后立即生效，无需重启后端</span>
        </div>
        <div class="ai-form-grid">
          <div class="ai-field">
            <label>功能开关</label>
            <label class="ai-checkbox">
              <input type="checkbox" v-model="settings.enabled" /> 开启 AI 摘要（关闭后所有摘要接口返回 403）
            </label>
          </div>
          <div class="ai-field">
            <label>每日额度（条）</label>
            <input v-model.number="settings.dailyLimit" type="number" min="0" max="100000" />
          </div>
          <div class="ai-field">
            <label>最短内容长度</label>
            <input v-model.number="settings.minLength" type="number" min="1" max="200" />
          </div>
          <div class="ai-field">
            <label>日报拼接上限（字符）</label>
            <input v-model.number="settings.maxInputChars" type="number" min="1000" max="200000" />
          </div>
          <div class="ai-field">
            <label>群白名单（逗号分隔，空=全部）</label>
            <input v-model.trim="settings.groupWhitelist" type="text" placeholder="如 674405515,697381755" />
          </div>
          <div class="ai-field">
            <label>定时日报 cron（Spring 表达式，空=不定时）</label>
            <input v-model.trim="settings.digestCron" type="text" placeholder="如 0 50 23 * * ?" />
          </div>
          <div class="ai-field">
            <label>定时日报的群（逗号分隔，空=当天有消息的群）</label>
            <input v-model.trim="settings.digestGroups" type="text" placeholder="如 674405515" />
          </div>
        </div>
        <div class="ai-actions">
          <button class="btn-action promote" :disabled="settingsSaving" @click="saveSettings">
            {{ settingsSaving ? '保存中...' : '保存设置' }}
          </button>
          <button class="btn-action" :disabled="settingsLoading" @click="loadSettings">重新读取</button>
        </div>
      </div>
    </div>

    <!-- 消息归档 -->
    <div class="section-card" style="margin-top: 16px;">
      <div class="maintenance-section-header"><h4>消息归档</h4></div>
      <p class="maintenance-desc">将指定天数之前的消息标记为已归档，归档后的消息不再在聊天界面中显示。</p>
      <div class="archive-form">
        <div class="archive-input-group">
          <label>归档多少天前的消息</label>
          <input type="number" v-model.number="archiveDays" min="1" max="3650" class="config-input archive-input" />
          <span class="archive-unit">天</span>
        </div>
        <button class="btn-action promote" :disabled="isArchiving" @click="triggerArchive">{{ isArchiving ? '归档中...' : '执行归档' }}</button>
      </div>
    </div>
  </div>
</template>

<script>
import { computed, inject, onMounted, onUnmounted, reactive, ref } from 'vue';
import Icon from '../../components/Icon.vue';
import { adminApi, messageApi } from '../../services/api';
import { showToast } from '../../components/Toast.vue';

export default {
  name: 'AdminBackup',
  components: { Icon },
  setup() {
    const maintenance = inject('adminMaintenance');
    const formatDate = inject('adminFormatDate');
    const formatFileSize = inject('adminFormatFileSize');

    // ===== AI 摘要批量面板（阶段 2） =====
    const status = ref({});
    const statusLoading = ref(false);
    const submitting = ref(false);
    const stopping = ref(false);
    const form = reactive({
      groupId: '',
      start: '',
      end: '',
      limit: 200,
      minLength: 8,
      onlyText: true,
    });

    const loadStatus = async () => {
      statusLoading.value = true;
      try {
        status.value = await messageApi.batchSummarizeStatus() || {};
      } catch (e) {
        showToast(e?.message || '获取摘要任务状态失败', 'error');
      } finally {
        statusLoading.value = false;
      }
    };

    // 预估（投递上限取 limit 与剩余待处理的较小值）
    const estimate = computed(() => {
      const pending = Number(status.value?.totalPending || 0);
      const limit = Number(form.limit || 0);
      const count = Math.max(0, Math.min(limit > 0 ? limit : pending, pending));
      return {
        count,
        tokens: ((count * 1500) / 10000).toFixed(1),
        minutes: Math.max(1, Math.round((count * 8) / 60)),
      };
    });

    const submit = async () => {
      if (!form.groupId && !form.start) {
        showToast('请至少填写「群号」或「开始日期」', 'warning');
        return;
      }
      submitting.value = true;
      try {
        const payload = {
          groupId: form.groupId || null,
          start: form.start || null,
          end: form.end || null,
          limit: form.limit || null,
          minLength: form.minLength || null,
          onlyText: form.onlyText,
        };
        const res = await messageApi.batchSummarize(payload);
        showToast(`已投递 ${res?.queued ?? 0} 条（跳过 ${res?.skipped ?? 0} 条），约 ${res?.estimatedMinutes ?? '-'} 分钟完成`, 'success');
        await loadStatus();
      } catch (e) {
        showToast(e?.message || '投递失败', 'error');
      } finally {
        submitting.value = false;
      }
    };

    const stop = async () => {
      if (!window.confirm('确定清空 AI 分析队列吗？队列中未处理的任务会被丢弃（死信队列保留）。')) return;
      stopping.value = true;
      try {
        const res = await messageApi.batchSummarizeStop();
        showToast(`已停止，丢弃 ${res?.purged ?? 0} 条待处理任务`, 'success');
        await loadStatus();
      } catch (e) {
        showToast(e?.message || '停止失败', 'error');
      } finally {
        stopping.value = false;
      }
    };

    let timer = null;

    // ===== 摘要设置（阶段 2/3 收尾：后台可视化配置，PUT 后运行时生效） =====
    const settings = reactive({
      enabled: true,
      dailyLimit: 300,
      minLength: 8,
      maxInputChars: 12000,
      groupWhitelist: '',
      digestCron: '',
      digestGroups: '',
    });
    const settingsLoading = ref(false);
    const settingsSaving = ref(false);

    const loadSettings = async () => {
      settingsLoading.value = true;
      try {
        const cfg = await adminApi.getAiSummaryConfig() || {};
        settings.enabled = cfg.enabled !== false;
        settings.dailyLimit = cfg.dailyLimit ?? 300;
        settings.minLength = cfg.minLength ?? 8;
        settings.maxInputChars = cfg.maxInputChars ?? 12000;
        settings.groupWhitelist = cfg.groupWhitelist ?? '';
        settings.digestCron = cfg.digestCron ?? '';
        settings.digestGroups = cfg.digestGroups ?? '';
      } catch (e) {
        showToast(e?.message || '读取摘要设置失败', 'error');
      } finally {
        settingsLoading.value = false;
      }
    };

    const saveSettings = async () => {
      settingsSaving.value = true;
      try {
        await adminApi.updateAiSummaryConfig({ ...settings });
        showToast('摘要设置已保存并生效', 'success');
        await Promise.all([loadSettings(), loadStatus()]);
      } catch (e) {
        showToast(e?.message || '保存摘要设置失败', 'error');
      } finally {
        settingsSaving.value = false;
      }
    };

    onMounted(() => {
      loadStatus();
      loadSettings();
      timer = setInterval(loadStatus, 10000);   // 面板打开时每 10 秒刷新进度
    });
    onUnmounted(() => { if (timer) clearInterval(timer); });

    return {
      isBackingUp: maintenance.isBackingUp,
      backupList: maintenance.backupList,
      isArchiving: maintenance.isArchiving,
      archiveDays: maintenance.archiveDays,
      triggerBackup: maintenance.triggerBackup,
      getDownloadUrl: maintenance.getDownloadUrl,
      triggerArchive: maintenance.triggerArchive,
      formatDate,
      formatFileSize,
      // AI 摘要
      status,
      statusLoading,
      submitting,
      stopping,
      form,
      estimate,
      loadStatus,
      submit,
      stop,
      settings,
      settingsLoading,
      settingsSaving,
      loadSettings,
      saveSettings,
    };
  },
};
</script>

<style scoped>
.maintenance-section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.maintenance-section-header h4 { margin: 0; }
.maintenance-desc { font-size: 13px; color: var(--text-muted, #888); margin: 0 0 14px 0; }
.backup-list { display: flex; flex-direction: column; gap: 8px; }
.empty-backup { text-align: center; padding: 30px; color: var(--text-muted, #aaa); font-size: 13px; }
.backup-item { display: flex; justify-content: space-between; align-items: center; padding: 10px 14px; background: var(--bg-tertiary, #f8f9fa); border-radius: 6px; transition: background 0.2s; }
.backup-item:hover { background: var(--bg-tertiary, #f0f1f3); }
.backup-info { display: flex; flex-direction: column; gap: 2px; }
.backup-name { font-size: 13px; font-weight: 500; color: var(--text-primary, #333); }
.backup-meta { font-size: 11px; color: var(--text-muted, #999); }
.archive-form { display: flex; align-items: flex-end; gap: 14px; }
.archive-input-group { display: flex; flex-direction: column; gap: 4px; }
.archive-input-group label { font-size: 12px; color: var(--text-muted, #888); }
.archive-input { width: 100px; }
.archive-unit { font-size: 13px; color: var(--text-secondary, #666); align-self: flex-end; margin-bottom: 6px; }

/* ===== AI 摘要批量面板 ===== */
.ai-status-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 16px;
}
.ai-status-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--bg-tertiary, #f8f9fa);
  border: 1px solid var(--border-color, #eceff3);
}
.ai-status-value { font-size: 16px; font-weight: 600; color: var(--text-primary, #333); }
.ai-status-value.is-running { color: var(--accent-color, #3498db); }
.ai-status-label { font-size: 11.5px; color: var(--text-muted, #888); }

.ai-form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px 16px;
  margin-bottom: 12px;
}
.ai-field { display: flex; flex-direction: column; gap: 5px; }
.ai-field label { font-size: 12px; color: var(--text-secondary, #666); }
.ai-field input[type="text"],
.ai-field input[type="number"],
.ai-field input[type="date"] {
  padding: 7px 10px;
  border: 1px solid var(--border-color, #d8dee9);
  border-radius: 6px;
  background: var(--card-bg, #fff);
  color: var(--text-primary, #333);
  font-size: 13px;
}
.ai-field-check { justify-content: flex-end; }
.ai-checkbox { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--text-secondary, #666); }

.ai-estimate { font-size: 13px; color: var(--text-secondary, #666); margin: 4px 0 14px 0; }
.ai-estimate b { color: var(--accent-color, #3498db); }
.ai-estimate-note { font-size: 11.5px; color: var(--text-muted, #999); }

.ai-actions { display: flex; gap: 12px; }
.btn-action.danger { color: #e74c3c; border-color: #e74c3c; }

/* 摘要设置区块 */
.ai-settings {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--border-color, #e0e0e0);
}
.ai-settings-header { display: flex; align-items: baseline; gap: 10px; margin-bottom: 12px; }
.ai-settings-title { font-size: 14px; font-weight: 600; color: var(--text-primary, #333); }
.ai-settings-note { font-size: 11.5px; color: var(--text-muted, #999); }

@media (max-width: 1100px) {
  .ai-status-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .ai-form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
