<template>
  <div class="admin-dashboard">
    <div class="dashboard-header">
      <h2>系统管理</h2>
      <button class="close-btn" @click="$emit('close')">&times;</button>
    </div>

    <div class="dashboard-body">
      <!-- 加载状态指示器 -->
      <div v-if="isLoading" class="loading-overlay">
        <div class="loading-spinner"></div>
        <span class="loading-text">加载中...</span>
      </div>

      <!-- 组件状态与控制 -->
      <div class="section-card">
        <h4>组件状态与控制</h4>
        <div class="component-grid">
          <!-- AstrBot -->
          <div class="component-card">
            <div class="component-header">
              <div class="component-status-dot" :class="{ active: componentStatus.astrbot?.running }"></div>
              <span class="component-title">AstrBot</span>
              <span class="component-status-text">{{ componentStatus.astrbot?.running ? '运行中' : '已停止' }}</span>
            </div>
            <div class="component-actions">
              <button
                class="btn-start"
                :disabled="isStartingAstrBot || componentStatus.astrbot?.running"
                @click="startAstrBot"
              >
                {{ isStartingAstrBot ? '启动中...' : '启动' }}
              </button>
              <button
                class="btn-stop"
                :disabled="isStoppingAstrBot || !componentStatus.astrbot?.running"
                @click="stopAstrBot"
              >
                {{ isStoppingAstrBot ? '停止中...' : '停止' }}
              </button>
            </div>
            <a href="http://localhost:6185" target="_blank" class="webui-link">
              <Icon name="globe" :size="14" /> 打开 AstrBot WebUI
            </a>
          </div>

          <!-- NapCat -->
          <div class="component-card">
            <div class="component-header">
              <div class="component-status-dot" :class="{ active: componentStatus.napcat?.running }"></div>
              <span class="component-title">NapCat</span>
              <span class="component-status-text">{{ componentStatus.napcat?.running ? '运行中' : '已停止' }}</span>
            </div>
            <div class="component-actions">
              <button
                class="btn-start"
                :disabled="isStartingNapCat || componentStatus.napcat?.running"
                @click="startNapCat"
              >
                {{ isStartingNapCat ? '启动中...' : '启动' }}
              </button>
              <button
                class="btn-stop"
                :disabled="isStoppingNapCat || !componentStatus.napcat?.running"
                @click="stopNapCat"
              >
                {{ isStoppingNapCat ? '停止中...' : '停止' }}
              </button>
            </div>
            <a
              href="http://127.0.0.1:6099"
              target="_blank"
              class="webui-link"
            >
              <Icon name="globe" :size="14" /> 打开 NapCat WebUI
            </a>
          </div>

          <!-- GPT-SoVITS -->
          <div class="component-card">
            <div class="component-header">
              <div class="component-status-dot" :class="{ active: componentStatus.gptsovits?.running }"></div>
              <span class="component-title">GPT-SoVITS</span>
              <span class="component-status-text">{{ componentStatus.gptsovits?.running ? '运行中' : '已停止' }}</span>
            </div>
            <div class="component-actions">
              <button
                class="btn-start"
                :disabled="isStartingGptSovits || componentStatus.gptsovits?.running"
                @click="startGptSovits"
              >
                {{ isStartingGptSovits ? '启动中...' : '启动' }}
              </button>
              <button
                class="btn-stop"
                :disabled="isStoppingGptSovits || !componentStatus.gptsovits?.running"
                @click="stopGptSovits"
              >
                {{ isStoppingGptSovits ? '停止中...' : '停止' }}
              </button>
            </div>
            <a
              href="http://localhost:8000"
              target="_blank"
              class="webui-link"
            >
              <Icon name="globe" :size="14" /> 打开 GPT-SoVITS WebUI
            </a>
          </div>
        </div>
      </div>

      <!-- NapCat 登录 -->
      <div class="section-card">
        <h4>NapCat 登录</h4>
        <div class="login-area">
          <div v-if="qrCode" class="qrcode-box">
            <img :src="qrCode" alt="NapCat登录二维码" />
            <p>请使用QQ扫码登录</p>
            <button class="btn-refresh" @click="refreshQrCode">刷新二维码</button>
            <label class="auto-login-label">
              <input type="checkbox" v-model="autoLogin" @change="onAutoLoginChange" />
              下次自动登录
            </label>
          </div>
          <div v-else class="loading-box">
            <p>获取登录二维码中...</p>
          </div>
        </div>
      </div>

      <!-- 系统消息 -->
      <div v-if="systemMessage" class="system-message" :class="systemMessageType">
        {{ systemMessage }}
      </div>
    </div>
  </div>
</template>

<script>
import Icon from './Icon.vue';
import { useAdminDashboard } from '../composables/useAdminDashboard';

export default {
  name: 'AdminDashboard',
  components: { Icon },
  emits: ['close'],
  setup() {
    return useAdminDashboard();
  },
};
</script>

<style scoped>
.admin-dashboard {
  background: #fff;
  border-radius: 8px;
  max-height: 85vh;
  overflow-y: auto;
}

.dashboard-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #e8e8e8;
  position: sticky;
  top: 0;
  background: #fff;
  z-index: 10;
}

.dashboard-header h2 {
  margin: 0;
  font-size: 18px;
  color: #2c3e50;
}

.close-btn {
  background: none;
  border: none;
  font-size: 24px;
  cursor: pointer;
  color: #95a5a6;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
}

.close-btn:hover {
  background: #f0f0f0;
}

.dashboard-body {
  padding: 20px;
}

/* 统计卡片 */
.stats-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #f0f0f0;
}

.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
}

.stat-value {
  font-size: 22px;
  font-weight: 700;
  color: #2c3e50;
}

.stat-label {
  font-size: 12px;
  color: #888;
  margin-top: 2px;
}

.disk-card {
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
}

.disk-card .stat-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.disk-bar {
  width: 100%;
}

.disk-bar-track {
  width: 100%;
  height: 8px;
  background: #e8e8e8;
  border-radius: 4px;
  overflow: hidden;
}

.disk-bar-fill {
  height: 100%;
  background: linear-gradient(90deg, #4caf50, #ff9800, #f44336);
  border-radius: 4px;
  transition: width 0.3s ease;
}

.disk-bar-label {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  font-size: 11px;
  color: #999;
}

/* 图表行 */
.charts-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 20px;
}

.chart-card {
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #f0f0f0;
  padding: 16px;
}

.chart-card h4 {
  margin: 0 0 16px 0;
  font-size: 14px;
  color: #2c3e50;
}

.chart-large {
  grid-column: 1 / -1;
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.chart-header h4 {
  margin: 0;
}

.chart-controls {
  display: flex;
  gap: 4px;
}

.chart-btn {
  padding: 4px 10px;
  border: 1px solid #e0e0e0;
  background: #fff;
  border-radius: 4px;
  font-size: 12px;
  color: #666;
  cursor: pointer;
  transition: all 0.2s;
}

.chart-btn:hover {
  border-color: #3498db;
  color: #3498db;
}

.chart-btn.active {
  background: #3498db;
  color: #fff;
  border-color: #3498db;
}

/* 柱状图 */
.bar-chart {
  display: flex;
  align-items: flex-end;
  justify-content: space-around;
  height: 160px;
  gap: 8px;
}

.bar-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.bar-wrapper {
  width: 100%;
  height: 120px;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.bar {
  width: 60%;
  border-radius: 4px 4px 0 0;
  transition: height 0.5s ease;
}

.bar-label {
  font-size: 11px;
  color: #888;
}

.bar-value {
  font-size: 11px;
  font-weight: 600;
  color: #3498db;
}

/* 线状图 */
.line-chart {
  display: flex;
  height: 200px;
  gap: 8px;
}

.line-chart-yaxis {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  align-items: flex-end;
  padding-right: 4px;
  font-size: 10px;
  color: #aaa;
  width: 28px;
  flex-shrink: 0;
}

.line-chart-scroll-wrapper {
  flex: 1;
  overflow-x: auto;
  overflow-y: hidden;
  cursor: grab;
  position: relative;
}

.line-chart-scroll-wrapper:active {
  cursor: grabbing;
}

.line-chart-scroll-wrapper::-webkit-scrollbar {
  display: none;
}

.line-chart-scroll {
  position: relative;
  height: 100%;
}

.line-chart-scroll svg {
  width: 100%;
  height: calc(100% - 24px);
  overflow: visible;
}

.data-points-overlay {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: calc(100% - 24px);
  pointer-events: none;
}

.data-point-css {
  position: absolute;
  width: 5px;
  height: 5px;
  background: #3498db;
  border-radius: 50%;
  transform: translate(-50%, -50%);
  cursor: pointer;
  pointer-events: auto;
  transition: transform 0.2s, box-shadow 0.2s;
}

.data-point-css:hover {
  transform: translate(-50%, -50%) scale(1.8);
  box-shadow: 0 0 6px rgba(52, 152, 219, 0.5);
}

.chart-tooltip {
  position: fixed;
  background: rgba(0, 0, 0, 0.8);
  color: #fff;
  padding: 6px 10px;
  border-radius: 4px;
  font-size: 12px;
  pointer-events: none;
  z-index: 1000;
  white-space: nowrap;
}

.tooltip-date {
  font-size: 11px;
  color: #ccc;
  margin-bottom: 2px;
}

.tooltip-value {
  font-weight: 600;
}

.line-chart-labels {
  display: flex;
  justify-content: space-between;
  padding-top: 4px;
}

.line-chart-labels span {
  font-size: 11px;
  color: #888;
  text-align: center;
  flex: 1;
  white-space: nowrap;
}

.line-chart-labels.hour-labels span {
  font-size: 8px;
}

/* 排行 */
.ranking-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rank-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rank-num {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #e8e8e8;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  color: #666;
  flex-shrink: 0;
}

.rank-item:nth-child(1) .rank-num {
  background: #e74c3c;
  color: #fff;
}
.rank-item:nth-child(2) .rank-num {
  background: #e67e22;
  color: #fff;
}
.rank-item:nth-child(3) .rank-num {
  background: #f1c40f;
  color: #fff;
}

.rank-name {
  width: 90px;
  font-size: 12px;
  color: #444;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex-shrink: 0;
}

.rank-bar-wrapper {
  flex: 1;
  height: 10px;
  background: #eee;
  border-radius: 5px;
  overflow: hidden;
}

.rank-bar {
  height: 100%;
  border-radius: 5px;
  transition: width 0.5s ease;
}

.rank-count {
  width: 40px;
  font-size: 12px;
  color: #666;
  text-align: right;
  flex-shrink: 0;
}

/* 区块卡片 */
.section-card {
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #f0f0f0;
  padding: 16px;
  margin-bottom: 16px;
}

.section-card h4 {
  margin: 0 0 16px 0;
  font-size: 14px;
  color: #2c3e50;
}

/* 组件网格 */
.component-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
}

.component-card {
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  padding: 14px;
}

.component-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.component-status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #e74c3c;
  transition: background 0.3s;
}

.component-status-dot.active {
  background: #27ae60;
}

.component-title {
  flex: 1;
  font-weight: 600;
  font-size: 14px;
  color: #2c3e50;
}

.component-status-text {
  font-size: 12px;
  color: #888;
}

.component-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.component-actions button {
  flex: 1;
  padding: 6px 0;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  transition: all 0.2s;
}

.btn-start:not(:disabled) {
  background: #3498db;
  color: #fff;
}
.btn-start:not(:disabled):hover {
  background: #2980b9;
}

.btn-stop:not(:disabled) {
  background: #e74c3c;
  color: #fff;
}
.btn-stop:not(:disabled):hover {
  background: #c0392b;
}

.btn-webui {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px;
  background: #f0f7ff;
  border-radius: 4px;
  color: #1976d2;
  font-size: 12px;
  cursor: pointer;
  border: none;
  text-decoration: none;
  transition: background 0.2s;
}

.btn-webui:hover {
  background: #d6e9ff;
}

.btn-webui:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: #f0f7ff;
}



.webui-link {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px;
  background: #f0f7ff;
  border-radius: 4px;
  color: #1976d2;
  text-decoration: none;
  border: none;
  cursor: pointer;
  font-size: 12px;
  transition: background 0.2s;
}

.webui-link.disabled,
.webui-link:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  background: #f0f7ff;
  color: #1976d2;
  font-size: 12px;
  transition: background 0.2s;
  pointer-events: none;
}

.webui-link:hover {
  background: #d6e9ff;
}

/* 登录区域 */
.login-area {
  display: flex;
  justify-content: center;
}

.qrcode-box {
  text-align: center;
}

.qrcode-box img {
  width: 180px;
  height: 180px;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
}

.qrcode-box p {
  margin: 8px 0;
  font-size: 13px;
  color: #666;
}

.btn-refresh {
  padding: 6px 14px;
  background: #f8f9fa;
  border: 1px solid #dee2e6;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}

.auto-login-label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
  font-size: 12px;
  color: #666;
  cursor: pointer;
}

.auto-login-label input[type="checkbox"] {
  width: 14px;
  height: 14px;
  cursor: pointer;
}

.loading-box {
  padding: 40px;
  color: #888;
}

/* 加载状态指示器 */
.loading-overlay {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px;
  gap: 12px;
}

.loading-spinner {
  width: 32px;
  height: 32px;
  border: 3px solid #e8e8e8;
  border-top-color: #3498db;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.loading-text {
  font-size: 14px;
  color: #666;
}

/* 系统消息 */
.system-message {
  padding: 10px 14px;
  border-radius: 6px;
  font-size: 13px;
  margin-top: 10px;
}

.system-message.success {
  background: #d4edda;
  color: #155724;
  border: 1px solid #c3e6cb;
}

.system-message.error {
  background: #f8d7da;
  color: #721c24;
  border: 1px solid #f5c6cb;
}

/* 响应式 */
@media (max-width: 768px) {
  .stats-cards {
    grid-template-columns: repeat(2, 1fr);
  }
  .charts-row {
    grid-template-columns: 1fr;
  }
  .component-grid {
    grid-template-columns: 1fr;
  }
}
</style>
