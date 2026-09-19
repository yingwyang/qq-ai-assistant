<template>
  <div class="tab-panel admin-components">
    <AdminPageHeader title="组件控制" subtitle="AstrBot / NapCat / GPT-SoVITS 的启停与状态">
      <template #meta>
        <span class="page-header-meta">状态更新于 {{ lastStatusAt || '—' }}</span>
      </template>
      <button class="btn-action promote" :disabled="isStartingAll" @click="startAllComponents">
        {{ isStartingAll ? '启动中...' : '一键启动全部' }}
      </button>
      <button class="btn-action delete" :disabled="isStoppingAll" @click="stopAllComponents">
        {{ isStoppingAll ? '停止中...' : '一键停止全部' }}
      </button>
      <button class="btn-action" :disabled="isRefreshing" @click="getComponentStatus">
        {{ isRefreshing ? '刷新中...' : '刷新状态' }}
      </button>
    </AdminPageHeader>

    <p class="auto-refresh-note">面板打开时每 5 秒自动刷新一次状态；离开本页后停止轮询。</p>

    <div class="component-grid">
      <div v-for="item in components" :key="item.key" class="component-card">
        <div class="component-header">
          <div class="component-status-dot" :class="{ active: item.running }"></div>
          <span class="component-title">{{ item.title }}</span>
          <span class="component-status-text">{{ item.running ? '运行中' : '已停止' }}</span>
        </div>
        <div class="component-actions">
          <button class="btn-start" :disabled="item.starting || item.running" @click="item.start()">
            {{ item.starting ? '启动中...' : '启动' }}
          </button>
          <button class="btn-stop" :disabled="item.stopping || !item.running" @click="item.stop()">
            {{ item.stopping ? '停止中...' : '停止' }}
          </button>
        </div>
        <p v-if="componentErrors[item.key]" class="component-error">
          最近一次操作失败：{{ componentErrors[item.key] }}
        </p>
        <a :href="item.webui" target="_blank" class="webui-link" @click="onOpenWebUi(item, $event)">
          <Icon name="globe" :size="14" /> 打开 {{ item.title }} WebUI
        </a>
      </div>
    </div>

    <!-- 运行配置概览（只读） -->
    <div class="component-grid two-col">
      <div class="section-card">
        <div class="section-card-header">
          <h4>AI 摘要</h4>
          <button class="btn-link" @click="setActiveTab('ai-summary')">前往设置</button>
        </div>
        <div v-if="summaryLoading" class="component-readonly">读取中...</div>
        <div v-else class="component-readonly">
          <div class="readonly-row">
            <span>功能开关</span>
            <span :class="summaryConfig.enabled === false ? 'text-danger' : 'text-ok'">
              {{ summaryConfig.enabled === false ? '已关闭（摘要接口返回 403）' : '已开启' }}
            </span>
          </div>
          <div class="readonly-row"><span>每日额度</span><span>{{ summaryConfig.dailyLimit ?? '-' }} 条</span></div>
          <div class="readonly-row"><span>最短内容长度</span><span>{{ summaryConfig.minLength ?? '-' }}</span></div>
          <div class="readonly-row"><span>群白名单</span><span>{{ summaryConfig.groupWhitelist || '全部群' }}</span></div>
          <div class="readonly-row"><span>定时日报</span><span>{{ summaryConfig.digestCron || '未配置' }}</span></div>
        </div>
      </div>

      <div class="section-card">
        <div class="section-card-header">
          <h4>AstrBot 人设</h4>
          <button class="btn-link" @click="setActiveTab('config')">前往配置</button>
        </div>
        <div v-if="personaLoading" class="component-readonly">读取中...</div>
        <div v-else-if="personaError" class="component-readonly text-danger">{{ personaError }}</div>
        <div v-else class="component-readonly">
          <div class="readonly-row"><span>可用人设</span><span>{{ personas.length }} 个</span></div>
          <ul class="persona-list">
            <li v-for="p in personas.slice(0, 6)" :key="p.id || p.name">
              {{ p.name || p.id }}
              <span v-if="p.isDefault" class="restart-badge">默认</span>
            </li>
          </ul>
          <p class="persona-note">人设即「两层提示词」中的人格层，管理员可让用户在前端自助切换。</p>
        </div>
      </div>
    </div>

    <div class="section-card">
      <div class="section-card-header">
        <h4>NapCat 登录</h4>
        <span class="page-header-meta">WebUI：{{ napCatWebUiUrl || 'http://127.0.0.1:6099/webui' }}</span>
      </div>
      <div class="login-area">
        <div v-if="qrCode" class="qrcode-box">
          <img :src="qrCode" alt="NapCat登录二维码" />
          <p>请使用 QQ 扫码登录</p>
          <button class="btn-refresh" @click="refreshQrCode">刷新二维码</button>
          <label class="auto-login-label"><input type="checkbox" v-model="autoLogin" @change="onAutoLoginChange" /> 下次自动登录</label>
        </div>
        <div v-else class="loading-box"><p>获取登录二维码中...</p></div>
      </div>
    </div>
  </div>
</template>

<script>
import { computed, inject, onMounted, ref } from 'vue';
import Icon from '../../components/Icon.vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';
import { adminApi, astrBotApi } from '../../services/api';

export default {
  name: 'AdminComponents',
  components: { Icon, AdminPageHeader },
  setup() {
    const ctrl = inject('adminComponentCtrl');
    const formatBytes = inject('adminFormatFileSize');
    const setActiveTab = inject('adminSetActiveTab', null);
    const showSystemMsg = inject('adminShowMsg', null);

    const components = computed(() => [
      {
        key: 'astrbot',
        title: 'AstrBot',
        running: !!ctrl.componentStatus.value.astrbot?.running,
        starting: ctrl.isStartingAstrBot.value,
        stopping: ctrl.isStoppingAstrBot.value,
        start: ctrl.startAstrBot,
        stop: ctrl.stopAstrBot,
        webui: 'http://localhost:6185',
      },
      {
        key: 'napcat',
        title: 'NapCat',
        running: !!ctrl.componentStatus.value.napcat?.running,
        starting: ctrl.isStartingNapCat.value,
        stopping: ctrl.isStoppingNapCat.value,
        start: ctrl.startNapCat,
        stop: ctrl.stopNapCat,
        webui: ctrl.napCatWebUiUrl.value || 'http://127.0.0.1:6099/webui',
        onOpen: ctrl.openNapCatWebUI,
      },
      {
        key: 'gptsovits',
        title: 'GPT-SoVITS',
        running: !!ctrl.componentStatus.value.gptsovits?.running,
        starting: ctrl.isStartingGptSovits.value,
        stopping: ctrl.isStoppingGptSovits.value,
        start: ctrl.startGptSovits,
        stop: ctrl.stopGptSovits,
        webui: 'http://localhost:9874',
      },
    ]);

    // AI 摘要当前配置（只读概览，设置页可改）
    const summaryConfig = ref({});
    const summaryLoading = ref(true);
    const loadSummaryConfig = async () => {
      summaryLoading.value = true;
      try {
        summaryConfig.value = (await adminApi.getAiSummaryConfig()) || {};
      } catch (e) {
        summaryConfig.value = {};
        if (showSystemMsg) showSystemMsg('读取 AI 摘要配置失败: ' + e.message, 'error');
      } finally {
        summaryLoading.value = false;
      }
    };

    // AstrBot 人设列表（只读）
    const personas = ref([]);
    const personaLoading = ref(true);
    const personaError = ref('');
    const loadPersonas = async () => {
      personaLoading.value = true;
      personaError.value = '';
      try {
        const res = await astrBotApi.getPersonas();
        personas.value = Array.isArray(res) ? res : (res && res.personas) || [];
      } catch (e) {
        personaError.value = '读取人设失败：' + e.message;
      } finally {
        personaLoading.value = false;
      }
    };

    onMounted(() => {
      loadSummaryConfig();
      loadPersonas();
    });

    /** NapCat 的 WebUI 地址是运行时探测出来的，需要走 composable 打开而不是固定 href */
    const onOpenWebUi = (item, event) => {
      if (!item.onOpen) return;
      event.preventDefault();
      item.onOpen();
    };

    return {
      components,
      onOpenWebUi,
      componentStatus: ctrl.componentStatus,
      componentErrors: ctrl.componentErrors,
      lastStatusAt: ctrl.lastStatusAt,
      isRefreshing: ctrl.isRefreshing,
      isStartingAll: ctrl.isStartingAll,
      isStoppingAll: ctrl.isStoppingAll,
      startAllComponents: ctrl.startAllComponents,
      stopAllComponents: ctrl.stopAllComponents,
      getComponentStatus: ctrl.getComponentStatus,
      qrCode: ctrl.qrCode,
      napCatWebUiUrl: ctrl.napCatWebUiUrl,
      autoLogin: ctrl.autoLogin,
      refreshQrCode: ctrl.refreshQrCode,
      onAutoLoginChange: ctrl.onAutoLoginChange,
      summaryConfig,
      summaryLoading,
      personas,
      personaLoading,
      personaError,
      formatBytes,
      setActiveTab: setActiveTab || (() => {}),
    };
  },
};
</script>

<style scoped>
.auto-refresh-note { font-size: 12px; color: var(--text-muted, #888); margin: 0 0 14px 0; }
.component-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 20px; }
.component-grid.two-col { grid-template-columns: repeat(2, 1fr); }
.component-card { background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e8e8e8); border-radius: 8px; padding: 18px; }
.component-header { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; }
.component-status-dot { width: 10px; height: 10px; border-radius: 50%; background: #e74c3c; transition: background 0.3s; }
.component-status-dot.active { background: #27ae60; }
.component-title { flex: 1; font-weight: 600; font-size: 14px; color: var(--text-primary, #333); }
.component-status-text { font-size: 12px; color: var(--text-muted, #888); }
.component-actions { display: flex; gap: 8px; margin-bottom: 12px; }
.component-actions button { flex: 1; padding: 8px 0; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; font-weight: 500; transition: all 0.2s; }
.btn-start:not(:disabled) { background: #3498db; color: #fff; }
.btn-start:not(:disabled):hover { background: #2980b9; }
.btn-stop:not(:disabled) { background: #e74c3c; color: #fff; }
.btn-stop:not(:disabled):hover { background: #c0392b; }
button:disabled { background: #ddd; color: var(--text-muted, #999); cursor: not-allowed; }
.theme-dark button:disabled { background: rgba(255, 255, 255, 0.12); color: var(--text-muted, #8c8c8c); }
.component-error { margin: 0 0 10px 0; font-size: 12px; color: #e74c3c; line-height: 1.5; word-break: break-all; }
.theme-dark .component-error { color: #ef9a9a; }
.webui-link { display: flex; align-items: center; justify-content: center; gap: 6px; padding: 8px; background: var(--bg-tertiary, #f0f7ff); border-radius: 4px; color: var(--accent-color, #1976d2); text-decoration: none; font-size: 12px; transition: background 0.2s; }
.webui-link:hover { background: rgba(52, 152, 219, 0.18); }
.login-area { display: flex; justify-content: center; }
.qrcode-box { text-align: center; }
.qrcode-box img { width: 180px; height: 180px; border: 1px solid var(--border-color, #e0e0e0); border-radius: 6px; }
.qrcode-box p { margin: 8px 0; font-size: 13px; color: var(--text-secondary, #666); }
.btn-refresh { padding: 6px 14px; background: var(--bg-tertiary, #f8f9fa); border: 1px solid var(--border-color, #dee2e6); border-radius: 4px; color: var(--text-secondary, #666); cursor: pointer; font-size: 12px; }
.auto-login-label { display: flex; align-items: center; gap: 6px; margin-top: 10px; font-size: 12px; color: var(--text-secondary, #666); cursor: pointer; }
.auto-login-label input[type="checkbox"] { width: 14px; height: 14px; cursor: pointer; }
.loading-box { padding: 40px; color: var(--text-muted, #888); }
.component-readonly { display: flex; flex-direction: column; gap: 8px; font-size: 13px; color: var(--text-primary, #333); }
.readonly-row { display: flex; justify-content: space-between; gap: 12px; }
.readonly-row > span:first-child { color: var(--text-muted, #888); }
.text-ok { color: var(--success-color, #27ae60); }
.text-danger { color: var(--danger-color, #e74c3c); }
.persona-list { margin: 0; padding-left: 18px; font-size: 13px; color: var(--text-primary, #333); display: flex; flex-direction: column; gap: 4px; }
.persona-note { margin: 4px 0 0 0; font-size: 11.5px; color: var(--text-muted, #999); line-height: 1.5; }
.restart-badge { display: inline-block; margin-left: 6px; padding: 1px 6px; background: rgba(52, 152, 219, 0.14); color: #2980b9; border-radius: 3px; font-size: 10px; }
.theme-dark .restart-badge { background: rgba(52, 152, 219, 0.22); color: #64b5f6; }
@media (max-width: 1100px) { .component-grid, .component-grid.two-col { grid-template-columns: 1fr; } }
</style>
