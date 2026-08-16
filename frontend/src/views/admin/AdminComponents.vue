<template>
  <div class="tab-panel admin-components">
    <div class="panel-title">
      <Icon name="settings" :size="20" />
      <h2>组件状态与控制</h2>
    </div>
    <div class="component-grid">
      <div class="component-card">
        <div class="component-header">
          <div class="component-status-dot" :class="{ active: componentStatus.astrbot?.running }"></div>
          <span class="component-title">AstrBot</span>
          <span class="component-status-text">{{ componentStatus.astrbot?.running ? '运行中' : '已停止' }}</span>
        </div>
        <div class="component-actions">
          <button class="btn-start" :disabled="isStartingAstrBot || componentStatus.astrbot?.running" @click="startAstrBot">{{ isStartingAstrBot ? '启动中...' : '启动' }}</button>
          <button class="btn-stop" :disabled="isStoppingAstrBot || !componentStatus.astrbot?.running" @click="stopAstrBot">{{ isStoppingAstrBot ? '停止中...' : '停止' }}</button>
        </div>
        <a href="http://localhost:6185" target="_blank" class="webui-link"><Icon name="globe" :size="14" /> 打开 AstrBot WebUI</a>
      </div>

      <div class="component-card">
        <div class="component-header">
          <div class="component-status-dot" :class="{ active: componentStatus.napcat?.running }"></div>
          <span class="component-title">NapCat</span>
          <span class="component-status-text">{{ componentStatus.napcat?.running ? '运行中' : '已停止' }}</span>
        </div>
        <div class="component-actions">
          <button class="btn-start" :disabled="isStartingNapCat || componentStatus.napcat?.running" @click="startNapCat">{{ isStartingNapCat ? '启动中...' : '启动' }}</button>
          <button class="btn-stop" :disabled="isStoppingNapCat || !componentStatus.napcat?.running" @click="stopNapCat">{{ isStoppingNapCat ? '停止中...' : '停止' }}</button>
        </div>
        <a :href="napCatWebUiUrl || 'http://127.0.0.1:6099/webui'" target="_blank" class="webui-link" @click.prevent="openNapCatWebUI"><Icon name="globe" :size="14" /> 打开 NapCat WebUI</a>
      </div>

      <div class="component-card">
        <div class="component-header">
          <div class="component-status-dot" :class="{ active: componentStatus.gptsovits?.running }"></div>
          <span class="component-title">GPT-SoVITS</span>
          <span class="component-status-text">{{ componentStatus.gptsovits?.running ? '运行中' : '已停止' }}</span>
        </div>
        <div class="component-actions">
          <button class="btn-start" :disabled="isStartingGptSovits || componentStatus.gptsovits?.running" @click="startGptSovits">{{ isStartingGptSovits ? '启动中...' : '启动' }}</button>
          <button class="btn-stop" :disabled="isStoppingGptSovits || !componentStatus.gptsovits?.running" @click="stopGptSovits">{{ isStoppingGptSovits ? '停止中...' : '停止' }}</button>
        </div>
        <a href="http://localhost:9874" target="_blank" class="webui-link"><Icon name="globe" :size="14" /> 打开 GPT-SoVITS WebUI</a>
      </div>
    </div>

    <div class="section-card">
      <h4>NapCat 登录</h4>
      <div class="login-area">
        <div v-if="qrCode" class="qrcode-box">
          <img :src="qrCode" alt="NapCat登录二维码" />
          <p>请使用QQ扫码登录</p>
          <button class="btn-refresh" @click="refreshQrCode">刷新二维码</button>
          <label class="auto-login-label"><input type="checkbox" v-model="autoLogin" @change="onAutoLoginChange" /> 下次自动登录</label>
        </div>
        <div v-else class="loading-box"><p>获取登录二维码中...</p></div>
      </div>
    </div>
  </div>
</template>

<script>
import { inject } from 'vue';
import Icon from '../../components/Icon.vue';

export default {
  name: 'AdminComponents',
  components: { Icon },
  setup() {
    const ctrl = inject('adminComponentCtrl');
    return {
      componentStatus: ctrl.componentStatus,
      qrCode: ctrl.qrCode,
      napCatWebUiUrl: ctrl.napCatWebUiUrl,
      autoLogin: ctrl.autoLogin,
      isStartingAstrBot: ctrl.isStartingAstrBot,
      isStoppingAstrBot: ctrl.isStoppingAstrBot,
      isStartingNapCat: ctrl.isStartingNapCat,
      isStoppingNapCat: ctrl.isStoppingNapCat,
      isStartingGptSovits: ctrl.isStartingGptSovits,
      isStoppingGptSovits: ctrl.isStoppingGptSovits,
      refreshQrCode: ctrl.refreshQrCode,
      openNapCatWebUI: ctrl.openNapCatWebUI,
      onAutoLoginChange: ctrl.onAutoLoginChange,
      startAstrBot: ctrl.startAstrBot,
      stopAstrBot: ctrl.stopAstrBot,
      startNapCat: ctrl.startNapCat,
      stopNapCat: ctrl.stopNapCat,
      startGptSovits: ctrl.startGptSovits,
      stopGptSovits: ctrl.stopGptSovits,
    };
  },
};
</script>

<style scoped>
.component-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 20px; }
.component-card { background: var(--card-bg, #fff); border: 1px solid var(--border-color, #e8e8e8); border-radius: 8px; padding: 18px; }
.component-header { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; }
.component-status-dot { width: 10px; height: 10px; border-radius: 50%; background: #e74c3c; transition: background 0.3s; }
.component-status-dot.active { background: #27ae60; }
.component-title { flex: 1; font-weight: 600; font-size: 14px; color: var(--text-primary, #333); }
.component-status-text { font-size: 12px; color: #888; }
.component-actions { display: flex; gap: 8px; margin-bottom: 12px; }
.component-actions button { flex: 1; padding: 8px 0; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; font-weight: 500; transition: all 0.2s; }
.btn-start:not(:disabled) { background: #3498db; color: #fff; }
.btn-start:not(:disabled):hover { background: #2980b9; }
.btn-stop:not(:disabled) { background: #e74c3c; color: #fff; }
.btn-stop:not(:disabled):hover { background: #c0392b; }
button:disabled { background: #ddd; color: #999; cursor: not-allowed; }
.webui-link { display: flex; align-items: center; justify-content: center; gap: 6px; padding: 8px; background: #f0f7ff; border-radius: 4px; color: #1976d2; text-decoration: none; font-size: 12px; transition: background 0.2s; }
.webui-link:hover { background: #d6e9ff; }
.login-area { display: flex; justify-content: center; }
.qrcode-box { text-align: center; }
.qrcode-box img { width: 180px; height: 180px; border: 1px solid #e0e0e0; border-radius: 6px; }
.qrcode-box p { margin: 8px 0; font-size: 13px; color: #666; }
.btn-refresh { padding: 6px 14px; background: #f8f9fa; border: 1px solid #dee2e6; border-radius: 4px; cursor: pointer; font-size: 12px; }
.auto-login-label { display: flex; align-items: center; gap: 6px; margin-top: 10px; font-size: 12px; color: #666; cursor: pointer; }
.auto-login-label input[type="checkbox"] { width: 14px; height: 14px; cursor: pointer; }
.loading-box { padding: 40px; color: #888; }
@media (max-width: 768px) { .component-grid { grid-template-columns: 1fr; } }
</style>