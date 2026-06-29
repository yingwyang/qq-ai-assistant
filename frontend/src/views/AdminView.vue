<template>
  <div class="admin-view">
    <!-- 顶部导航栏 -->
    <header class="admin-header">
      <div class="header-brand">
        <Icon name="admin" :size="24" />
        <h1>系统管理中心</h1>
      </div>
      <div class="header-actions">
        <button class="btn-home" @click="goHome">
          <Icon name="home" :size="16" /> 返回首页
        </button>
        <button class="btn-logout" @click="logout">
          <Icon name="logout" :size="16" /> 退出登录
        </button>
      </div>
    </header>

    <div class="admin-layout">
      <!-- 左侧导航 -->
      <aside class="admin-sidebar">
        <nav class="admin-nav">
          <div
            class="nav-item"
            :class="{ active: activeTab === 'dashboard' }"
            @click="activeTab = 'dashboard'"
          >
            <Icon name="dashboard" :size="18" />
            <span>数据概览</span>
          </div>
          <div
            class="nav-item"
            :class="{ active: activeTab === 'users' }"
            @click="activeTab = 'users'"
          >
            <Icon name="group" :size="18" />
            <span>用户管理</span>
          </div>
          <div
            class="nav-item"
            :class="{ active: activeTab === 'components' }"
            @click="activeTab = 'components'"
          >
            <Icon name="settings" :size="18" />
            <span>组件控制</span>
          </div>
          <div
            class="nav-item"
            :class="{ active: activeTab === 'distribution' }"
            @click="activeTab = 'distribution'"
          >
            <Icon name="chart" :size="18" />
            <span>消息分布</span>
          </div>
        </nav>
      </aside>

      <!-- 主内容区 -->
      <main class="admin-main">
        <!-- 数据概览 -->
        <div v-if="activeTab === 'dashboard'" class="tab-panel">
          <div class="panel-title">
            <Icon name="dashboard" :size="20" />
            <h2>数据概览</h2>
          </div>

          <!-- 统计卡片 -->
          <div class="stats-cards">
            <div class="stat-card">
              <div class="stat-icon blue"><Icon name="chat" :size="28" /></div>
              <div class="stat-info">
                <div class="stat-value">{{ stats.totalMessages }}</div>
                <div class="stat-label">总消息数</div>
                <div class="stat-today">今日 {{ stats.todayMessages || 0 }}</div>
              </div>
            </div>
            <div class="stat-card">
              <div class="stat-icon purple"><Icon name="group" :size="28" /></div>
              <div class="stat-info">
                <div class="stat-value">{{ stats.totalGroups }}</div>
                <div class="stat-label">群聊总数</div>
                <div class="stat-today">活跃 {{ stats.activeGroups || 0 }}</div>
              </div>
            </div>
            <div class="stat-card">
              <div class="stat-icon green"><Icon name="group" :size="28" /></div>
              <div class="stat-info">
                <div class="stat-value">{{ stats.totalUsers }}</div>
                <div class="stat-label">用户总数</div>
              </div>
            </div>
            <div class="stat-card">
              <div class="stat-icon orange"><Icon name="robot" :size="28" /></div>
              <div class="stat-info">
                <div class="stat-value">{{ stats.totalConversations }}</div>
                <div class="stat-label">AI 对话数</div>
                <div class="stat-today">活跃 {{ stats.activeConversations || 0 }}</div>
              </div>
            </div>
            <div class="stat-card">
              <div class="stat-icon teal"><Icon name="file" :size="28" /></div>
              <div class="stat-info">
                <div class="stat-value">{{ stats.totalFiles }}</div>
                <div class="stat-label">文件总数</div>
              </div>
            </div>
            <div class="stat-card disk-card">
              <div class="stat-icon red"><Icon name="disk" :size="28" /></div>
              <div class="stat-info">
                <div class="stat-value">{{ diskUsage.uploadsSizeFormatted }}</div>
                <div class="stat-label">上传文件占用</div>
                <div class="stat-today">D盘已用 {{ diskUsage.usagePercent }}%</div>
              </div>
              <div class="disk-bar">
                <div class="disk-bar-track">
                  <div class="disk-bar-fill" :style="{ width: diskUsage.usagePercent + '%' }"></div>
                </div>
                <div class="disk-bar-label">
                  <span>已用 {{ diskUsage.usedSpaceFormatted }}</span>
                  <span>总计 {{ diskUsage.totalSpaceFormatted }}</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 图表区域 -->
          <div class="charts-row">
            <div class="chart-card chart-large">
              <div class="chart-header">
                <h4>消息趋势</h4>
                <div class="chart-controls">
                  <button
                    class="chart-btn"
                    :class="{ active: trendInterval === 'hour' }"
                    @click="setTrendDays(1, 'hour')"
                  >
                    24小时
                  </button>
                  <button
                    v-for="d in [7, 30, 90]"
                    :key="d"
                    class="chart-btn"
                    :class="{ active: trendDays === d && trendInterval === 'day' }"
                    @click="setTrendDays(d, 'day')"
                  >
                    {{ d }}天
                  </button>
                </div>
              </div>
              <div class="line-chart">
                <div class="line-chart-yaxis">
                  <span v-for="n in 5" :key="n">{{ getYAxisLabel(n - 1) }}</span>
                </div>
                <div class="line-chart-scroll-wrapper" ref="scrollWrapper">
                  <div class="line-chart-scroll" :style="{ minWidth: Math.max(messageTrend.length * (trendInterval === 'hour' ? 55 : 36), 400) + 'px' }">
                    <svg viewBox="0 0 100 60" preserveAspectRatio="none">
                      <!-- 网格横线 -->
                      <line
                        v-for="n in 5"
                        :key="'grid-h-' + n"
                        x1="0"
                        :y1="n * 12"
                        x2="100"
                        :y2="n * 12"
                        stroke="#f0f0f0"
                        stroke-width="0.3"
                      />
                      <!-- 网格竖线 -->
                      <line
                        v-for="(item, index) in messageTrend"
                        :key="'grid-v-' + index"
                        :x1="getPointX(index)"
                        y1="0"
                        :x2="getPointX(index)"
                        y2="60"
                        stroke="#f0f0f0"
                        stroke-width="0.2"
                      />
                      <!-- 面积填充 -->
                      <polygon
                        v-if="messageTrend.length > 0"
                        fill="rgba(52, 152, 219, 0.1)"
                        :points="getAreaPoints()"
                      />
                      <!-- 折线 -->
                      <polyline
                        fill="none"
                        stroke="#3498db"
                        stroke-width="0.4"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        vector-effect="non-scaling-stroke"
                        :points="getLinePoints()"
                      />
                    </svg>
                    <div class="data-points-overlay">
                      <div
                        v-for="(item, index) in messageTrend"
                        :key="'pt-' + index"
                        class="data-point-css"
                        :style="{ left: getPointXPercent(index) + '%', top: getPointYPercent(item.count) + '%' }"
                        @mouseenter="showTooltip($event, item)"
                        @mouseleave="hideTooltip"
                      ></div>
                    </div>
                    <div v-if="tooltipVisible" class="chart-tooltip" :style="tooltipStyle">
                      <div class="tooltip-date">{{ tooltipData.date }}</div>
                      <div class="tooltip-value">{{ tooltipData.count }} 条消息</div>
                    </div>
                    <div class="line-chart-labels" :class="{ 'hour-labels': trendInterval === 'hour' }">
                        <span v-for="(item, index) in messageTrend" :key="index">
                          {{ item.date }}
                        </span>
                      </div>
                  </div>
                </div>
              </div>
            </div>

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

            <div class="chart-card">
              <h4>活跃QQ账号排行 TOP5</h4>
              <div class="ranking-list">
                <div
                  v-for="(qq, index) in qqRanking.slice(0, 5)"
                  :key="qq.qq"
                  class="rank-item"
                >
                  <div class="rank-num">{{ index + 1 }}</div>
                  <div class="rank-name" :title="qq.nickname">
                    {{ truncateQQName(qq.nickname, qq.qq) }}
                  </div>
                  <div class="rank-bar-wrapper">
                    <div
                      class="rank-bar"
                      :style="{ width: getQQRankWidth(qq.messageCount) + '%', backgroundColor: getRankColor(index) }"
                    ></div>
                  </div>
                  <div class="rank-count">{{ qq.messageCount }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 用户管理 -->
        <div v-if="activeTab === 'users'" class="tab-panel">
          <div class="panel-title">
            <Icon name="group" :size="20" />
            <h2>用户管理</h2>
          </div>
          <div class="user-table-wrapper">
            <table class="user-table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>账号</th>
                  <th>昵称</th>
                  <th>角色</th>
                  <th>状态</th>
                  <th>最后登录</th>
                  <th>注册时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="user in users" :key="user.id">
                  <td>{{ user.id }}</td>
                  <td>{{ user.username }}</td>
                  <td>{{ user.nickname || '-' }}</td>
                  <td>
                    <span class="role-badge" :class="user.role?.toLowerCase()">
                      {{ user.role === 'ADMIN' ? '管理员' : '普通用户' }}
                    </span>
                  </td>
                  <td>
                    <span class="status-badge" :class="user.active ? 'active' : 'inactive'">
                      {{ user.active ? '正常' : '已禁用' }}
                    </span>
                  </td>
                  <td>{{ formatDate(user.lastLoginTime) }}</td>
                  <td>{{ formatDate(user.createdAt) }}</td>
                  <td>
                    <div class="action-btns">
                      <button
                        class="btn-action"
                        :class="user.role === 'ADMIN' ? 'demote' : 'promote'"
                        @click="toggleRole(user)"
                      >
                        {{ user.role === 'ADMIN' ? '降权' : '提权' }}
                      </button>
                      <button
                        class="btn-action"
                        :class="user.active ? 'disable' : 'enable'"
                        @click="toggleActive(user)"
                      >
                        {{ user.active ? '禁用' : '启用' }}
                      </button>
                      <button class="btn-action delete" @click="deleteUser(user)">
                        删除
                      </button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
            <div v-if="users.length === 0" class="empty-table">
              <Icon name="group" :size="48" />
              <p>暂无用户数据</p>
            </div>
          </div>
        </div>

        <!-- 组件控制 -->
        <div v-if="activeTab === 'components'" class="tab-panel">
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
                <button class="btn-start" :disabled="isStartingAstrBot || componentStatus.astrbot?.running" @click="startAstrBot">
                  {{ isStartingAstrBot ? '启动中...' : '启动' }}
                </button>
                <button class="btn-stop" :disabled="isStoppingAstrBot || !componentStatus.astrbot?.running" @click="stopAstrBot">
                  {{ isStoppingAstrBot ? '停止中...' : '停止' }}
                </button>
              </div>
              <a href="http://localhost:6185" target="_blank" class="webui-link">
                <Icon name="globe" :size="14" /> 打开 AstrBot WebUI
              </a>
            </div>

            <div class="component-card">
              <div class="component-header">
                <div class="component-status-dot" :class="{ active: componentStatus.napcat?.running }"></div>
                <span class="component-title">NapCat</span>
                <span class="component-status-text">{{ componentStatus.napcat?.running ? '运行中' : '已停止' }}</span>
              </div>
              <div class="component-actions">
                <button class="btn-start" :disabled="isStartingNapCat || componentStatus.napcat?.running" @click="startNapCat">
                  {{ isStartingNapCat ? '启动中...' : '启动' }}
                </button>
                <button class="btn-stop" :disabled="isStoppingNapCat || !componentStatus.napcat?.running" @click="stopNapCat">
                  {{ isStoppingNapCat ? '停止中...' : '停止' }}
                </button>
              </div>
              <a href="http://127.0.0.1:6099/webui?token=***REMOVED***" target="_blank" class="webui-link">
                <Icon name="globe" :size="14" /> 打开 NapCat WebUI
              </a>
            </div>

            <div class="component-card">
              <div class="component-header">
                <div class="component-status-dot" :class="{ active: componentStatus.gptsovits?.running }"></div>
                <span class="component-title">GPT-SoVITS</span>
                <span class="component-status-text">{{ componentStatus.gptsovits?.running ? '运行中' : '已停止' }}</span>
              </div>
              <div class="component-actions">
                <button class="btn-start" :disabled="isStartingGptSovits || componentStatus.gptsovits?.running" @click="startGptSovits">
                  {{ isStartingGptSovits ? '启动中...' : '启动' }}
                </button>
                <button class="btn-stop" :disabled="isStoppingGptSovits || !componentStatus.gptsovits?.running" @click="stopGptSovits">
                  {{ isStoppingGptSovits ? '停止中...' : '停止' }}
                </button>
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
        </div>

        <!-- 消息分布 -->
        <div v-if="activeTab === 'distribution'" class="tab-panel">
          <div class="panel-title">
            <Icon name="chart" :size="20" />
            <h2>消息类型分布</h2>
          </div>
          <div class="distribution-layout">
            <div class="distribution-chart">
              <div
                v-for="item in messageTypeDistribution"
                :key="item.type"
                class="dist-item"
              >
                <div class="dist-label">{{ item.type }}</div>
                <div class="dist-bar-wrapper">
                  <div
                    class="dist-bar"
                    :style="{ width: getDistWidth(item.count) + '%' }"
                  ></div>
                </div>
                <div class="dist-count">{{ item.count }}</div>
              </div>
            </div>
            <div class="pie-chart-wrapper">
              <div class="pie-chart" :style="{ background: getPieGradient() }"></div>
              <div class="pie-legend">
                <div
                  v-for="item in messageTypeDistribution"
                  :key="item.type"
                  class="legend-item"
                >
                  <span class="legend-dot" :style="{ backgroundColor: getDistColor(item.type) }"></span>
                  <span class="legend-text">{{ item.type }} {{ getDistPercent(item.count) }}%</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>

    <!-- 系统消息 -->
    <div v-if="systemMessage" class="system-message" :class="systemMessageType">
      {{ systemMessage }}
    </div>
  </div>
</template>

<script>
import { ref, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import Icon from '../components/Icon.vue';
import { dashboardApi, systemApi, adminApi } from '../services/api';

export default {
  name: 'AdminView',
  components: { Icon },
  setup() {
    const router = useRouter();
    const activeTab = ref('dashboard');

    const stats = ref({
      totalMessages: 0,
      todayMessages: 0,
      totalGroups: 0,
      activeGroups: 0,
      totalUsers: 0,
      totalConversations: 0,
      activeConversations: 0,
      totalFiles: 0,
    });
    const messageTrend = ref([]);
    const trendDays = ref(7);
    const trendInterval = ref('day');
    const groupRanking = ref([]);
    const qqRanking = ref([]);
    const messageTypeDistribution = ref([]);
    const users = ref([]);
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
    const diskUsage = ref({
      uploadsSizeFormatted: '0 B',
      totalSpaceFormatted: '0 B',
      usedSpaceFormatted: '0 B',
      freeSpaceFormatted: '0 B',
      usagePercent: 0,
    });
    const tooltipVisible = ref(false);
    const tooltipData = ref({ date: '', count: 0 });
    const tooltipStyle = ref({ left: '0px', top: '0px' });
    const scrollWrapper = ref(null);
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
        const data = await dashboardApi.getMessageTrend(trendDays.value, trendInterval.value);
        messageTrend.value = data;
      } catch (error) {
        console.error('加载消息趋势失败:', error);
      }
    };

    const setTrendDays = (days, interval = 'day') => {
      trendDays.value = days;
      trendInterval.value = interval;
      loadTrend();
    };

    const loadRanking = async () => {
      try {
        const data = await dashboardApi.getGroupRanking();
        groupRanking.value = data;
      } catch (error) {
        console.error('加载群聊排行失败:', error);
      }
    };

    const loadQQRanking = async () => {
      try {
        const data = await dashboardApi.getQQRanking();
        qqRanking.value = data;
      } catch (error) {
        console.error('加载QQ排行失败:', error);
      }
    };

    const loadDiskUsage = async () => {
      try {
        const data = await systemApi.getDiskUsage();
        diskUsage.value = data;
      } catch (error) {
        console.error('加载磁盘使用情况失败:', error);
      }
    };

    const loadDistribution = async () => {
      try {
        const data = await dashboardApi.getMessageTypeDistribution();
        messageTypeDistribution.value = data;
      } catch (error) {
        console.error('加载消息分布失败:', error);
      }
    };

    const loadUsers = async () => {
      try {
        const data = await adminApi.getUsers();
        users.value = data;
      } catch (error) {
        console.error('加载用户列表失败:', error);
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

    const getQQRankWidth = (count) => {
      const max = Math.max(...qqRanking.value.map((i) => i.messageCount), 1);
      return Math.max((count / max) * 100, 5);
    };

    const getRankColor = (index) => {
      const colors = ['#e74c3c', '#e67e22', '#f1c40f', '#3498db', '#9b59b6'];
      return colors[index % colors.length];
    };

    const getDistWidth = (count) => {
      const max = Math.max(...messageTypeDistribution.value.map((i) => i.count), 1);
      return Math.max((count / max) * 100, 2);
    };

    const getLinePoints = () => {
      if (!messageTrend.value || messageTrend.value.length === 0) return '';
      const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
      const len = messageTrend.value.length;
      return messageTrend.value.map((item, index) => {
        const x = len > 1 ? (index / (len - 1)) * 100 : 50;
        const y = 60 - (item.count / max) * 50 - 5;
        return `${x},${y}`;
      }).join(' ');
    };

    const getPointX = (index) => {
      const len = messageTrend.value.length;
      return len > 1 ? (index / (len - 1)) * 100 : 50;
    };

    const getPointY = (count) => {
      const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
      return 60 - (count / max) * 50 - 5;
    };

    const getPointXPercent = (index) => {
      const len = messageTrend.value.length;
      return len > 1 ? (index / (len - 1)) * 100 : 50;
    };

    const getPointYPercent = (count) => {
      const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
      const y = 60 - (count / max) * 50 - 5;
      return (y / 60) * 100;
    };

    const getAreaPoints = () => {
      if (!messageTrend.value || messageTrend.value.length === 0) return '';
      const len = messageTrend.value.length;
      const linePoints = getLinePoints();
      const firstX = len > 1 ? 0 : 50;
      const lastX = len > 1 ? 100 : 50;
      const bottomY = 55;
      return `${firstX},${bottomY} ${linePoints} ${lastX},${bottomY}`;
    };

    const getYAxisLabel = (index) => {
      const max = Math.max(...messageTrend.value.map((i) => i.count), 1);
      const step = max / 4;
      return Math.round(step * (4 - index));
    };

    const showTooltip = (event, item) => {
      tooltipData.value = item;
      tooltipVisible.value = true;
      const rect = event.target.getBoundingClientRect();
      tooltipStyle.value = {
        left: rect.left + rect.width / 2 - 40 + 'px',
        top: rect.top - 50 + 'px',
      };
    };

    const hideTooltip = () => {
      tooltipVisible.value = false;
    };

    const getPieGradient = () => {
      if (!messageTypeDistribution.value || messageTypeDistribution.value.length === 0) {
        return 'conic-gradient(#ccc 0% 100%)';
      }
      const total = messageTypeDistribution.value.reduce((sum, item) => sum + item.count, 0);
      if (total === 0) return 'conic-gradient(#ccc 0% 100%)';
      let current = 0;
      const segments = messageTypeDistribution.value.map((item, index) => {
        const start = current;
        const percent = (item.count / total) * 100;
        current += percent;
        return `${getDistColor(item.type)} ${start}% ${current}%`;
      });
      return `conic-gradient(${segments.join(', ')})`;
    };

    const getDistPercent = (count) => {
      const total = messageTypeDistribution.value.reduce((sum, item) => sum + item.count, 0);
      return total > 0 ? Math.round((count / total) * 100) : 0;
    };

    const getDistColor = (type) => {
      const colors = {
        '文本': '#3498db',
        '图片': '#e74c3c',
        '视频': '#2ecc71',
        '文件': '#f39c12',
        '音频': '#9b59b6',
        '语音': '#1abc9c',
        '其他': '#95a5a6',
      };
      return colors[type] || '#3498db';
    };

    const truncateName = (name) => {
      if (!name) return '未知群聊';
      return name.length > 12 ? name.substring(0, 12) + '...' : name;
    };

    const truncateQQName = (name, qq) => {
      if (!name) return qq || '未知用户';
      return name.length > 10 ? name.substring(0, 10) + '...' : name;
    };

    const formatDate = (dateStr) => {
      if (!dateStr) return '-';
      const date = new Date(dateStr);
      return date.toLocaleString('zh-CN');
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

    const toggleRole = async (user) => {
      const newRole = user.role === 'ADMIN' ? 'USER' : 'ADMIN';
      try {
        await adminApi.updateUserRole(user.id, newRole);
        showSystemMsg(`用户 ${user.username} 角色已更新为 ${newRole === 'ADMIN' ? '管理员' : '普通用户'}`);
        await loadUsers();
      } catch (error) {
        showSystemMsg('更新角色失败: ' + error.message, 'error');
      }
    };

    const toggleActive = async (user) => {
      try {
        await adminApi.updateUserActive(user.id, !user.active);
        showSystemMsg(`用户 ${user.username} 已${user.active ? '禁用' : '启用'}`);
        await loadUsers();
      } catch (error) {
        showSystemMsg('更新状态失败: ' + error.message, 'error');
      }
    };

    const deleteUser = async (user) => {
      if (!confirm(`确定要删除用户 ${user.username} 吗？此操作不可恢复！`)) return;
      try {
        await adminApi.deleteUser(user.id);
        showSystemMsg(`用户 ${user.username} 已删除`);
        await loadUsers();
      } catch (error) {
        showSystemMsg('删除失败: ' + error.message, 'error');
      }
    };

    const goHome = () => {
      router.push('/');
    };

    const logout = () => {
      localStorage.removeItem('auth_token');
      localStorage.removeItem('user_role');
      localStorage.removeItem('user_info');
      localStorage.removeItem('isLoggedIn');
      router.push('/login');
    };

    let isDown = false;
    let startX;
    let scrollLeft;

    const setupDragScroll = () => {
      const el = scrollWrapper.value;
      if (!el) return;

      const onMouseDown = (e) => {
        isDown = true;
        el.classList.add('dragging');
        startX = e.pageX - el.offsetLeft;
        scrollLeft = el.scrollLeft;
      };

      const onMouseLeave = () => {
        isDown = false;
        el.classList.remove('dragging');
      };

      const onMouseUp = () => {
        isDown = false;
        el.classList.remove('dragging');
      };

      const onMouseMove = (e) => {
        if (!isDown) return;
        e.preventDefault();
        const x = e.pageX - el.offsetLeft;
        const walk = (x - startX) * 1.5;
        el.scrollLeft = scrollLeft - walk;
      };

      el.addEventListener('mousedown', onMouseDown);
      el.addEventListener('mouseleave', onMouseLeave);
      el.addEventListener('mouseup', onMouseUp);
      el.addEventListener('mousemove', onMouseMove);

      return () => {
        el.removeEventListener('mousedown', onMouseDown);
        el.removeEventListener('mouseleave', onMouseLeave);
        el.removeEventListener('mouseup', onMouseUp);
        el.removeEventListener('mousemove', onMouseMove);
      };
    };

    let removeDragListeners = null;

    onMounted(() => {
      loadStats();
      loadTrend();
      loadRanking();
      loadQQRanking();
      loadDistribution();
      loadUsers();
      loadDiskUsage();
      getComponentStatus();
      refreshQrCode();
      // 如果勾选了自动登录，检查 NapCat 登录状态
      if (autoLogin.value) {
        checkNapCatLogin();
      }
      statusInterval = setInterval(getComponentStatus, 5000);
      removeDragListeners = setupDragScroll();
    });

    onUnmounted(() => {
      if (statusInterval) clearInterval(statusInterval);
      if (removeDragListeners) removeDragListeners();
    });

    return {
      activeTab,
      stats,
      messageTrend,
      trendDays,
      trendInterval,
      groupRanking,
      qqRanking,
      messageTypeDistribution,
      users,
      componentStatus,
      qrCode,
      systemMessage,
      systemMessageType,
      autoLogin,
      isCheckingLogin,
      diskUsage,
      tooltipVisible,
      tooltipData,
      tooltipStyle,
      scrollWrapper,
      isStartingAstrBot,
      isStoppingAstrBot,
      isStartingNapCat,
      isStoppingNapCat,
      isStartingGptSovits,
      isStoppingGptSovits,
      getBarHeight,
      getRankWidth,
      getQQRankWidth,
      getRankColor,
      getDistWidth,
      getLinePoints,
      getPointX,
      getPointY,
      getPointXPercent,
      getPointYPercent,
      getAreaPoints,
      getYAxisLabel,
      getPieGradient,
      getDistPercent,
      getDistColor,
      setTrendDays,
      showTooltip,
      hideTooltip,
      truncateName,
      truncateQQName,
      formatDate,
      startAstrBot,
      stopAstrBot,
      startNapCat,
      stopNapCat,
      startGptSovits,
      stopGptSovits,
      toggleRole,
      toggleActive,
      deleteUser,
      refreshQrCode,
      onAutoLoginChange,
      goHome,
      logout,
    };
  },
};
</script>

<style scoped>
.admin-view {
  min-height: 100vh;
  background: #f5f6fa;
  display: flex;
  flex-direction: column;
}

.admin-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 24px;
  height: 56px;
  background: #2c3e50;
  color: #fff;
}

.header-brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-brand h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.header-actions {
  display: flex;
  gap: 10px;
}

.header-actions button {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  background: rgba(255,255,255,0.1);
  color: #fff;
  transition: background 0.2s;
}

.header-actions button:hover {
  background: rgba(255,255,255,0.2);
}

.admin-layout {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.admin-sidebar {
  width: 200px;
  background: #fff;
  border-right: 1px solid #e8e8e8;
  padding: 16px 0;
}

.admin-nav {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 0 12px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  color: #555;
  transition: all 0.2s;
}

.nav-item:hover {
  background: #f5f6fa;
  color: #2c3e50;
}

.nav-item.active {
  background: #e3f2fd;
  color: #1976d2;
  font-weight: 600;
}

.admin-main {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 20px;
}

.panel-title h2 {
  margin: 0;
  font-size: 18px;
  color: #2c3e50;
}

/* 统计卡片 */
.stats-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px;
  background: #fff;
  border-radius: 8px;
  border: 1px solid #e8e8e8;
}

.stat-icon {
  width: 52px;
  height: 52px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.stat-icon.blue { background: #e3f2fd; color: #1976d2; }
.stat-icon.purple { background: #f3e5f5; color: #7b1fa2; }
.stat-icon.green { background: #e8f5e9; color: #388e3c; }
.stat-icon.orange { background: #fff3e0; color: #f57c00; }
.stat-icon.teal { background: #e0f2f1; color: #00796b; }
.stat-icon.red { background: #ffebee; color: #c62828; }

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

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #2c3e50;
}

.stat-label {
  font-size: 13px;
  color: #888;
}

.stat-today {
  font-size: 12px;
  color: #3498db;
  margin-top: 2px;
}

/* 图表 */
.charts-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 20px;
}

.chart-card {
  background: #fff;
  border-radius: 8px;
  border: 1px solid #e8e8e8;
  padding: 18px;
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
  background: #3498db;
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

/* 分布图布局 */
.distribution-layout {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  align-items: center;
}

.pie-chart-wrapper {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

.pie-chart {
  width: 120px;
  height: 120px;
  border-radius: 50%;
  transition: background 0.3s ease;
}

.pie-legend {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #666;
}

.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 2px;
  flex-shrink: 0;
}

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
  width: 24px;
  height: 24px;
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

.rank-item:nth-child(1) .rank-num { background: #e74c3c; color: #fff; }
.rank-item:nth-child(2) .rank-num { background: #e67e22; color: #fff; }
.rank-item:nth-child(3) .rank-num { background: #f1c40f; color: #fff; }

.rank-name {
  width: 100px;
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
  width: 50px;
  font-size: 12px;
  color: #666;
  text-align: right;
  flex-shrink: 0;
}

/* 用户表格 */
.user-table-wrapper {
  background: #fff;
  border-radius: 8px;
  border: 1px solid #e8e8e8;
  overflow-x: auto;
}

.user-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.user-table th {
  background: #f8f9fa;
  padding: 12px 16px;
  text-align: left;
  font-weight: 600;
  color: #555;
  border-bottom: 1px solid #e8e8e8;
  white-space: nowrap;
}

.user-table td {
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
  color: #444;
}

.user-table tbody tr:hover {
  background: #f8f9fa;
}

.role-badge {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}

.role-badge.admin {
  background: #fff3e0;
  color: #f57c00;
}

.role-badge.user {
  background: #e3f2fd;
  color: #1976d2;
}

.status-badge {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 12px;
  font-size: 12px;
}

.status-badge.active {
  background: #e8f5e9;
  color: #388e3c;
}

.status-badge.inactive {
  background: #ffebee;
  color: #c62828;
}

.action-btns {
  display: flex;
  gap: 6px;
}

.btn-action {
  padding: 4px 10px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  transition: all 0.2s;
}

.btn-action.promote {
  background: #e3f2fd;
  color: #1976d2;
}

.btn-action.demote {
  background: #fff3e0;
  color: #f57c00;
}

.btn-action.enable {
  background: #e8f5e9;
  color: #388e3c;
}

.btn-action.disable {
  background: #ffebee;
  color: #c62828;
}

.btn-action.delete {
  background: #ffebee;
  color: #c62828;
}

.btn-action:hover {
  opacity: 0.8;
}

.empty-table {
  padding: 60px;
  text-align: center;
  color: #888;
}

/* 组件控制 */
.component-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

.component-card {
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  padding: 18px;
}

.component-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
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
  margin-bottom: 12px;
}

.component-actions button {
  flex: 1;
  padding: 8px 0;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
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

/* 区块卡片 */
.section-card {
  background: #fff;
  border-radius: 8px;
  border: 1px solid #e8e8e8;
  padding: 18px;
}

.section-card h4 {
  margin: 0 0 16px 0;
  font-size: 14px;
  color: #2c3e50;
}

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

/* 消息分布 */
.distribution-chart {
  background: #fff;
  border-radius: 8px;
  border: 1px solid #e8e8e8;
  padding: 18px;
}

.dist-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid #f0f0f0;
}

.dist-item:last-child {
  border-bottom: none;
}

.dist-label {
  width: 60px;
  font-size: 13px;
  color: #555;
  flex-shrink: 0;
}

.dist-bar-wrapper {
  flex: 1;
  height: 12px;
  background: #eee;
  border-radius: 6px;
  overflow: hidden;
}

.dist-bar {
  height: 100%;
  border-radius: 6px;
  background: #3498db;
  transition: width 0.5s ease;
}

.dist-count {
  width: 60px;
  font-size: 13px;
  color: #666;
  text-align: right;
  flex-shrink: 0;
}

/* 系统消息 */
.system-message {
  position: fixed;
  bottom: 20px;
  right: 20px;
  padding: 12px 18px;
  border-radius: 6px;
  font-size: 13px;
  z-index: 1000;
  box-shadow: 0 4px 12px rgba(0,0,0,0.15);
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
  .admin-sidebar {
    display: none;
  }
  .charts-row {
    grid-template-columns: 1fr;
  }
  .component-grid {
    grid-template-columns: 1fr;
  }
  .stats-cards {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
