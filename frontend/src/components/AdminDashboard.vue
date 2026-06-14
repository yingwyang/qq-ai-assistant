<template>
  <div class="admin-dashboard">
    <div class="dashboard-header">
      <h2>系统管理</h2>
      <button class="close-btn" @click="$emit('close')">&times;</button>
    </div>

    <div class="dashboard-body">
      <!-- 统计卡片 -->
      <div class="stats-cards">
        <div class="stat-card">
          <div class="stat-icon" style="background: #e3f2fd; color: #1976d2;"><Icon name="chat" :size="24" /></div>
          <div class="stat-info">
            <div class="stat-value">{{ stats.totalMessages }}</div>
            <div class="stat-label">总消息数</div>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon" style="background: #f3e5f5; color: #7b1fa2;"><Icon name="group" :size="24" /></div>
          <div class="stat-info">
            <div class="stat-value">{{ stats.totalGroups }}</div>
            <div class="stat-label">群聊总数</div>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon" style="background: #e8f5e9; color: #388e3c;"><Icon name="user" :size="24" /></div>
          <div class="stat-info">
            <div class="stat-value">{{ stats.totalUsers }}</div>
            <div class="stat-label">用户总数</div>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon" style="background: #fff3e0; color: #f57c00;"><Icon name="robot" :size="24" /></div>
          <div class="stat-info">
            <div class="stat-value">{{ stats.totalConversations }}</div>
            <div class="stat-label">AI 对话数</div>
          </div>
        </div>
      </div>

      <!-- 图表区域 -->
      <div class="charts-row">
        <!-- 消息趋势 -->
        <div class="chart-card">
          <h4>近7天消息趋势</h4>
          <div class="bar-chart">
            <div
              v-for="(item, index) in messageTrend"
              :key="index"
              class="bar-item"
            >
              <div class="bar-wrapper">
                <div
                  class="bar"
                  :style="{ height: getBarHeight(item.count) + '%', backgroundColor: '#3498db' }"
                ></div>
              </div>
              <div class="bar-label">{{ item.date }}</div>
              <div class="bar-value">{{ item.count }}</div>
            </div>
          </div>
        </div>

        <!-- 群聊排行 -->
        <div class="chart-card">
          <h4>活跃群聊排行 TOP5</h4>
          <div class="ranking-list">
            <div
              v-for="(group, index) in groupRanking.slice(0, 5)"
              :key="group.groupId"
              class="rank-item"
            >
              <div class="rank-num">{{ index + 1 }}</div>
              <div class="rank-name" :title="group.groupName">
                {{ truncateName(group.groupName) }}
              </div>
              <div class="rank-bar-wrapper">
                <div
                  class="rank-bar"
                  :style="{ width: getRankWidth(group.messageCount) + '%', backgroundColor: getRankColor(index) }"
                ></div>
              </div>
              <div class="rank-count">{{ group.messageCount }}</div>
            </div>
          </div>
        </div>
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
            <a href="http://127.0.0.1:6099/webui?token=***REMOVED***" target="_blank" class="webui-link">
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
import { ref, onMounted, onUnmounted } from 'vue';
import Icon from './Icon.vue';
import { dashboardApi, systemApi } from '../services/api';
import { showToast } from './Toast.vue';

export default {
  name: 'AdminDashboard',
  components: { Icon },
  emits: ['close'],
  setup() {
    const stats = ref({
      totalMessages: 0,
      totalGroups: 0,
      activeGroups: 0,
      totalUsers: 0,
      totalConversations: 0,
      activeConversations: 0,
      totalFiles: 0,
    });
    const messageTrend = ref([]);
    const groupRanking = ref([]);
    const componentStatus = ref({
      astrbot: { running: false },
      napcat: { running: false },
      gptsovits: { running: false },
    });
    const qrCode = ref('');
    const systemMessage = ref('');
    const systemMessageType = ref('');
    const autoLogin = ref(localStorage.getItem('napcat_auto_login') === 'true');
    const isCheckingLogin = ref(false);

    const isStartingAstrBot = ref(false);
    const isStoppingAstrBot = ref(false);
    const isStartingNapCat = ref(false);
    const isStoppingNapCat = ref(false);
    const isStartingGptSovits = ref(false);
    const isStoppingGptSovits = ref(false);

    let statusInterval = null;

    const loadStats = async () => {
      try {
        const data = await dashboardApi.getStats();
        stats.value = data;
      } catch (error) {
        console.error('加载统计数据失败:', error);
      }
    };

    const loadTrend = async () => {
      try {
        const data = await dashboardApi.getMessageTrend();
        messageTrend.value = data;
      } catch (error) {
        console.error('加载消息趋势失败:', error);
      }
    };

    const loadRanking = async () => {
      try {
        const data = await dashboardApi.getGroupRanking();
        groupRanking.value = data;
      } catch (error) {
        console.error('加载群聊排行失败:', error);
      }
    };

    const getComponentStatus = async () => {
      try {
        const response = await systemApi.getComponentStatus();
        componentStatus.value = response;
      } catch (error) {
        console.error('获取组件状态失败:', error);
      }
    };

    const refreshQrCode = () => {
      const timestamp = new Date().getTime();
      qrCode.value = `http://localhost:8081/api/system/napcat/qrcode-image?timestamp=${timestamp}`;
    };

    const onAutoLoginChange = () => {
      localStorage.setItem('napcat_auto_login', autoLogin.value);
    };

    const checkNapCatLogin = async () => {
      if (!autoLogin.value) return;
      isCheckingLogin.value = true;
      try {
        const res = await systemApi.checkNapCatLoginStatus();
        if (res.loggedIn) {
          showSystemMsg(`NapCat 已自动登录: ${res.qq || ''}`, 'success');
        }
      } catch (error) {
        console.error('检查 NapCat 登录状态失败:', error);
      } finally {
        isCheckingLogin.value = false;
      }
    };

    const getBarHeight = (count) => {
      const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
      return Math.max((count / max) * 100, 10);
    };

    const getRankWidth = (count) => {
      const max = Math.max(...groupRanking.value.map((i) => i.messageCount), 1);
      return Math.max((count / max) * 100, 5);
    };

    const getRankColor = (index) => {
      const colors = ['#e74c3c', '#e67e22', '#f1c40f', '#3498db', '#9b59b6'];
      return colors[index % colors.length];
    };

    const truncateName = (name) => {
      if (!name) return '未知群聊';
      return name.length > 12 ? name.substring(0, 12) + '...' : name;
    };

    const showSystemMsg = (msg, type = 'success') => {
      systemMessage.value = msg;
      systemMessageType.value = type;
      setTimeout(() => {
        systemMessage.value = '';
      }, 5000);
    };

    const startAstrBot = async () => {
      isStartingAstrBot.value = true;
      try {
        const res = await systemApi.startAstrBot();
        showSystemMsg(res.message || 'AstrBot 启动成功');
        await getComponentStatus();
      } catch (error) {
        showSystemMsg('AstrBot 启动失败: ' + error.message, 'error');
      } finally {
        isStartingAstrBot.value = false;
      }
    };

    const stopAstrBot = async () => {
      isStoppingAstrBot.value = true;
      try {
        const res = await systemApi.stopAstrBot();
        showSystemMsg(res.message || 'AstrBot 停止成功');
        await getComponentStatus();
      } catch (error) {
        showSystemMsg('AstrBot 停止失败: ' + error.message, 'error');
      } finally {
        isStoppingAstrBot.value = false;
      }
    };

    const startNapCat = async () => {
      isStartingNapCat.value = true;
      try {
        const res = await systemApi.startNapCat(autoLogin.value);
        showSystemMsg(res.message || 'NapCat 启动成功');
        await getComponentStatus();
        setTimeout(refreshQrCode, 3000);
      } catch (error) {
        showSystemMsg('NapCat 启动失败: ' + error.message, 'error');
      } finally {
        isStartingNapCat.value = false;
      }
    };

    const stopNapCat = async () => {
      isStoppingNapCat.value = true;
      try {
        const res = await systemApi.stopNapCat();
        showSystemMsg(res.message || 'NapCat 停止成功');
        await getComponentStatus();
      } catch (error) {
        showSystemMsg('NapCat 停止失败: ' + error.message, 'error');
      } finally {
        isStoppingNapCat.value = false;
      }
    };

    const startGptSovits = async () => {
      isStartingGptSovits.value = true;
      try {
        const res = await systemApi.startGptSovits();
        showSystemMsg(res.message || 'GPT-SoVITS 启动成功');
        await getComponentStatus();
      } catch (error) {
        showSystemMsg('GPT-SoVITS 启动失败: ' + error.message, 'error');
      } finally {
        isStartingGptSovits.value = false;
      }
    };

    const stopGptSovits = async () => {
      isStoppingGptSovits.value = true;
      try {
        const res = await systemApi.stopGptSovits();
        showSystemMsg(res.message || 'GPT-SoVITS 停止成功');
        await getComponentStatus();
      } catch (error) {
        showSystemMsg('GPT-SoVITS 停止失败: ' + error.message, 'error');
      } finally {
        isStoppingGptSovits.value = false;
      }
    };

    onMounted(() => {
      loadStats();
      loadTrend();
      loadRanking();
      getComponentStatus();
      refreshQrCode();
      // 如果勾选了自动登录，检查 NapCat 登录状态
      if (autoLogin.value) {
        checkNapCatLogin();
      }
      statusInterval = setInterval(getComponentStatus, 5000);
    });

    onUnmounted(() => {
      if (statusInterval) clearInterval(statusInterval);
    });

    return {
      stats,
      messageTrend,
      groupRanking,
      componentStatus,
      qrCode,
      systemMessage,
      systemMessageType,
      autoLogin,
      isCheckingLogin,
      isStartingAstrBot,
      isStoppingAstrBot,
      isStartingNapCat,
      isStoppingNapCat,
      isStartingGptSovits,
      isStoppingGptSovits,
      refreshQrCode,
      onAutoLoginChange,
      getBarHeight,
      getRankWidth,
      getRankColor,
      truncateName,
      startAstrBot,
      stopAstrBot,
      startNapCat,
      stopNapCat,
      startGptSovits,
      stopGptSovits,
    };
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

button:disabled {
  background: #ddd;
  color: #999;
  cursor: not-allowed;
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
  font-size: 12px;
  transition: background 0.2s;
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
