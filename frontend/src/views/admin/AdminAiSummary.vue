<template>
  <div class="tab-panel admin-ai-summary">
    <AdminPageHeader title="AI 摘要" subtitle="摘要开关、生成参数与历史消息补摘要">
      <template #meta>
        <span class="page-header-meta">状态于 {{ lastStatusAt || '—' }} 更新</span>
      </template>
      <button class="btn-action" :disabled="statusLoading" @click="loadStatus">
        {{ statusLoading ? '刷新中...' : '刷新状态' }}
      </button>
    </AdminPageHeader>

    <!-- 运行状态 -->
    <div class="section-card">
      <div class="section-card-header">
        <h4>运行状态</h4>
        <span v-if="status.config?.enabled === false" class="status-badge inactive">摘要功能已关闭</span>
        <span v-else-if="status.running" class="status-badge active">任务进行中</span>
      </div>

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
      <p class="ai-refresh-note">面板打开时每 10 秒自动刷新（页面切到后台时暂停）。</p>
    </div>

    <!-- 批量补摘要 -->
    <div class="section-card" style="margin-top: 16px;">
      <div class="section-card-header">
        <h4>批量补摘要</h4>
      </div>
      <p class="ai-desc">
        为历史消息补生成 AI 摘要。必须指定「群号」或「开始日期」之一，避免一次性投递全部历史消息；
        有任务进行中时重复投递会被拒绝（409）。
      </p>

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

      <p v-if="!form.groupId && !form.start" class="ai-validate-hint">
        请至少填写「群号」或「开始日期」，否则无法投递。
      </p>
      <p v-else-if="rangeError" class="ai-validate-hint">{{ rangeError }}</p>

      <p class="ai-estimate">
        预估：最多投递 <b>{{ estimate.count }}</b> 条 ≈ <b>{{ estimate.tokens }}</b> 万 token ≈ 约 <b>{{ estimate.minutes }}</b> 分钟
        <span class="ai-estimate-note">（按剩余待处理 {{ status.totalPending ?? 0 }} 条、每条 1.5k token / 8 秒估算）</span>
      </p>

      <div class="ai-actions">
        <button class="btn-action primary" :disabled="submitting || !canSubmit" @click="submit">
          {{ submitting ? '投递中...' : '开始投递' }}
        </button>
        <button class="btn-action danger" :disabled="stopping || !status.queueDepth" @click="stop">
          {{ stopping ? '停止中...' : '停止并清空队列' }}
        </button>
      </div>
    </div>

    <!-- 摘要设置 -->
    <div class="section-card" style="margin-top: 16px;">
      <div class="section-card-header">
        <h4>
          摘要设置
          <span v-if="settingsDirty" class="dirty-badge">有未保存修改</span>
        </h4>
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

      <p v-if="settingsError" class="ai-validate-hint">{{ settingsError }}</p>

      <div class="ai-actions">
        <button class="btn-action primary" :disabled="settingsSaving || !settingsDirty || !!settingsError" @click="saveSettings">
          {{ settingsSaving ? '保存中...' : '保存设置' }}
        </button>
        <button class="btn-action" :disabled="settingsLoading" @click="loadSettings">重新读取</button>
      </div>
    </div>
  </div>
</template>

<script>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';
import { adminApi, messageApi } from '../../services/api';
import { showToast } from '../../components/Toast.vue';
import { showConfirm } from '../../components/ConfirmDialog.vue';

const STATUS_POLL_MS = 10000;

export default {
  name: 'AdminAiSummary',
  components: { AdminPageHeader },
  setup() {
    const status = ref({});
    const statusLoading = ref(false);
    const submitting = ref(false);
    const stopping = ref(false);
    const lastStatusAt = ref('');

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
        lastStatusAt.value = new Date().toLocaleTimeString('zh-CN');
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

    const rangeError = computed(() => {
      if (form.start && form.end && form.start > form.end) return '「开始日期」不能晚于「结束日期」。';
      return '';
    });

    const canSubmit = computed(() => !!(form.groupId || form.start) && !rangeError.value);

    const submit = async () => {
      if (!form.groupId && !form.start) {
        showToast('请至少填写「群号」或「开始日期」', 'warning');
        return;
      }
      if (rangeError.value) {
        showToast(rangeError.value, 'warning');
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
      const ok = await showConfirm({
        title: '清空 AI 分析队列',
        message: '确定清空 AI 分析队列吗？队列中未处理的任务会被丢弃（死信队列保留）。',
        type: 'warning',
        confirmText: '清空队列',
      });
      if (!ok) return;
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

    // ===== 摘要设置（运行时生效，不用改配置文件/重启） =====
    const DEFAULTS = {
      enabled: true,
      dailyLimit: 300,
      minLength: 8,
      maxInputChars: 12000,
      groupWhitelist: '',
      digestCron: '',
      digestGroups: '',
    };
    const settings = reactive({ ...DEFAULTS });
    const settingsSnapshot = ref(JSON.stringify(DEFAULTS));
    const settingsLoading = ref(false);
    const settingsSaving = ref(false);

    const settingsDirty = computed(() => JSON.stringify(settings) !== settingsSnapshot.value);

    const settingsError = computed(() => {
      if (!Number.isFinite(Number(settings.dailyLimit)) || Number(settings.dailyLimit) < 0) return '「每日额度」必须是不小于 0 的数字。';
      if (!Number.isFinite(Number(settings.minLength)) || Number(settings.minLength) < 1 || Number(settings.minLength) > 200) {
        return '「最短内容长度」需在 1 ~ 200 之间。';
      }
      if (!Number.isFinite(Number(settings.maxInputChars)) || Number(settings.maxInputChars) < 1000) {
        return '「日报拼接上限」需不小于 1000 字符。';
      }
      return '';
    });

    const loadSettings = async () => {
      settingsLoading.value = true;
      try {
        const cfg = await adminApi.getAiSummaryConfig() || {};
        settings.enabled = cfg.enabled !== false;
        settings.dailyLimit = cfg.dailyLimit ?? DEFAULTS.dailyLimit;
        settings.minLength = cfg.minLength ?? DEFAULTS.minLength;
        settings.maxInputChars = cfg.maxInputChars ?? DEFAULTS.maxInputChars;
        settings.groupWhitelist = cfg.groupWhitelist ?? '';
        settings.digestCron = cfg.digestCron ?? '';
        settings.digestGroups = cfg.digestGroups ?? '';
        settingsSnapshot.value = JSON.stringify(settings);
      } catch (e) {
        showToast(e?.message || '读取摘要设置失败', 'error');
      } finally {
        settingsLoading.value = false;
      }
    };

    const saveSettings = async () => {
      if (settingsError.value) {
        showToast(settingsError.value, 'warning');
        return;
      }
      settingsSaving.value = true;
      try {
        await adminApi.updateAiSummaryConfig({ ...settings });
        settingsSnapshot.value = JSON.stringify(settings);
        showToast('摘要设置已保存并生效', 'success');
        await Promise.all([loadSettings(), loadStatus()]);
      } catch (e) {
        showToast(e?.message || '保存摘要设置失败', 'error');
      } finally {
        settingsSaving.value = false;
      }
    };

    let timer = null;
    const startPolling = () => {
      stopPolling();
      timer = setInterval(() => {
        if (document.hidden) return;   // 页面切到后台时不再请求
        loadStatus();
      }, STATUS_POLL_MS);
    };
    const stopPolling = () => {
      if (timer) {
        clearInterval(timer);
        timer = null;
      }
    };

    onMounted(() => {
      loadStatus();
      loadSettings();
      startPolling();
    });
    onUnmounted(stopPolling);

    return {
      status, statusLoading, lastStatusAt, submitting, stopping, form,
      estimate, rangeError, canSubmit, loadStatus, submit, stop,
      settings, settingsLoading, settingsSaving, settingsDirty, settingsError,
      loadSettings, saveSettings,
    };
  },
};
</script>

<style scoped>
.ai-status-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 10px;
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
.ai-refresh-note { font-size: 11.5px; color: var(--text-muted, #999); margin: 0; }
.ai-desc { font-size: 13px; color: var(--text-muted, #888); margin: 0 0 14px 0; line-height: 1.6; }

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
  background: var(--input-bg, #fff);
  color: var(--text-primary, #333);
  font-size: 13px;
}

.ai-field-check { justify-content: flex-end; }
.ai-checkbox { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--text-secondary, #666); }

.ai-estimate { font-size: 13px; color: var(--text-secondary, #666); margin: 4px 0 14px 0; }
.ai-estimate b { color: var(--accent-color, #3498db); }
.ai-estimate-note { font-size: 11.5px; color: var(--text-muted, #999); }
.ai-validate-hint { font-size: 12.5px; color: #ef6c00; margin: 0 0 12px 0; }
.ai-actions { display: flex; gap: 12px; }
.ai-settings-note { font-size: 11.5px; color: var(--text-muted, #999); }
.section-card-header h4 { display: flex; align-items: center; gap: 8px; }

@media (max-width: 1100px) {
  .ai-status-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .ai-form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
