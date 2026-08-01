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
            v-for="item in navItems"
            :key="item.key"
            class="nav-item"
            :class="{ active: activeTab === item.key }"
            @click="activeTab = item.key"
          >
            <Icon :name="item.icon" :size="18" />
            <span>{{ item.label }}</span>
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

          <!-- 搜索栏 -->
          <div class="user-search-bar">
            <input
              v-model="userSearch"
              type="text"
              placeholder="搜索用户名或昵称..."
              class="user-search-input"
              @input="onUserSearchInput"
            />
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

          <!-- 分页控件 -->
          <div class="user-pagination">
            <span class="pagination-info">
              共 {{ userTotalElements }} 条，第 {{ userCurrentPage + 1 }} / {{ userTotalPages }} 页
            </span>
            <div class="pagination-btns">
              <button
                class="btn-page"
                :disabled="userCurrentPage === 0"
                @click="goToUserPage(0)"
              >首页</button>
              <button
                class="btn-page"
                :disabled="userCurrentPage === 0"
                @click="goToUserPage(userCurrentPage - 1)"
              >上一页</button>
              <button
                class="btn-page"
                :disabled="userCurrentPage >= userTotalPages - 1"
                @click="goToUserPage(userCurrentPage + 1)"
              >下一页</button>
              <button
                class="btn-page"
                :disabled="userCurrentPage >= userTotalPages - 1"
                @click="goToUserPage(userTotalPages - 1)"
              >末页</button>
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
              <a
                :href="napCatWebUiUrl || 'http://127.0.0.1:6099/webui'"
                target="_blank"
                class="webui-link"
                @click.prevent="openNapCatWebUI"
              >
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
              <a href="http://localhost:9874" target="_blank" class="webui-link">
                <Icon name="globe" :size="14" /> 打开 GPT-SoVITS WebUI
              </a>
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

        <!-- 媒体管理 -->
        <div v-if="activeTab === 'media'" class="tab-panel">
          <div class="panel-title">
            <Icon name="image" :size="20" />
            <h2>媒体文件管理</h2>
          </div>
          <div class="section-card">
            <div class="section-card-header">
              <h4>媒体文件列表</h4>
              <button class="collapse-toggle" @click="isMediaCollapsed = !isMediaCollapsed">
                <Icon :name="isMediaCollapsed ? 'expand' : 'collapse'" :size="14" />
                <span>{{ isMediaCollapsed ? '展开' : '收起' }}</span>
              </button>
            </div>
            <div v-show="!isMediaCollapsed" class="media-manager-body">
              <div class="media-manager-card">
                <div class="media-summary">
                  <span>共 {{ mediaFilesTotal }} 个文件，占用 {{ formatBytes(currentFilesTotalSize) }}</span>
                  <span v-if="selectedMediaFilesCount > 0" class="media-selected">
                    已选 {{ selectedMediaFilesCount }} 个，{{ formatBytes(selectedMediaFilesTotalSize) }}
                  </span>
                  <span v-else class="media-selected">已选 0 个，0 B</span>
                </div>

                <div class="media-filter">
                  <button
                    v-for="type in ['ALL', 'IMAGE', 'VIDEO', 'AUDIO']"
                    :key="type"
                    class="chart-btn"
                    :class="{ active: mediaFileFilter === type }"
                    @click="setMediaFileFilter(type)"
                  >
                    {{ { ALL: '全部', IMAGE: '图片', VIDEO: '视频', AUDIO: '音频' }[type] }}
                  </button>
                </div>

                <div v-if="mediaFilesLoading" class="media-loading">
                  <div class="loading-spinner"></div>
                  <span>加载中...</span>
                </div>

                <div v-else-if="mediaFiles.length === 0" class="media-empty">暂无文件</div>

                <table v-else class="media-table">
                  <thead>
                    <tr>
                      <th class="col-checkbox">
                        <input
                          type="checkbox"
                          :checked="selectedMediaFilesCount === mediaFiles.length && mediaFiles.length > 0"
                          :disabled="mediaFilesLoading || isPurging"
                          @change="selectedMediaFilesCount === mediaFiles.length ? clearMediaFileSelection() : selectAllMediaFiles()"
                        />
                      </th>
                      <th>文件名</th>
                      <th>类型</th>
                      <th>大小</th>
                      <th>创建时间</th>
                      <th class="col-action">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="file in mediaFiles" :key="file.id">
                      <td class="col-checkbox">
                        <input
                          type="checkbox"
                          :checked="selectedMediaFileIds.has(file.id)"
                          :disabled="mediaFilesLoading || isPurging"
                          @change="toggleMediaFileSelection(file.id)"
                        />
                      </td>
                      <td :title="file.fileName" class="file-name-cell" @click="openPreview(file)">{{ file.fileName }}</td>
                      <td>{{ file.fileType }}</td>
                      <td>{{ formatBytes(file.fileSize) }}</td>
                      <td>{{ formatDate(file.createdAt) }}</td>
                      <td class="col-action">
                        <button class="btn-preview" @click="openPreview(file)">预览</button>
                      </td>
                    </tr>
                  </tbody>
                </table>

                <div v-if="mediaFiles.length > 0" class="media-pagination">
                  <button
                    :disabled="mediaFilePage <= 0 || mediaFilesLoading || isPurging"
                    @click="mediaFilePage--; loadMediaFiles()"
                  >
                    上一页
                  </button>
                  <span>{{ mediaFilePage + 1 }} / {{ totalMediaPages }}</span>
                  <button
                    :disabled="mediaFilePage >= totalMediaPages - 1 || mediaFilesLoading || isPurging"
                    @click="mediaFilePage++; loadMediaFiles()"
                  >
                    下一页
                  </button>
                </div>

                <div v-if="purgeResult" class="purge-result">
                  成功清理 {{ purgeResult.totalDeleted }} 个文件（约 {{ purgeResult.freedMB }}）
                </div>

                <div class="media-actions">
                  <button
                    class="btn-preview"
                    :disabled="mediaFilesLoading || isPurging || mediaFiles.length === 0"
                    @click="previewAllFiles"
                  >
                    {{ mediaFilesLoading ? '加载中...' : '预览全部' }}
                  </button>
                  <button
                    class="btn-delete"
                    :disabled="selectedMediaFileIds.size === 0 || mediaFilesLoading || isPurging"
                    @click="confirmDeleteSelected"
                  >
                    {{ isPurging ? '处理中...' : '删除选中' }}
                  </button>
                  <button
                    class="btn-purge"
                    :disabled="isPurging || mediaFilesLoading"
                    @click="confirmPurgeByFilter"
                  >
                    {{ isPurging ? '清理中...' : '全部清理' }}
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 配置管理 -->
        <div v-if="activeTab === 'config'" class="tab-panel">
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
                  <button
                    v-if="!configEditing[group.name]"
                    class="btn-action promote"
                    @click="startEditConfig(group.name)"
                  >编辑</button>
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
                        <input
                          v-if="item.secret"
                          :type="configShowSecret[item.key] ? 'text' : 'password'"
                          class="config-input"
                          v-model="configEditValues[item.key]"
                          :placeholder="item.value === '***' ? '输入新值（留空不修改）' : ''"
                        />
                        <input
                          v-else
                          type="text"
                          class="config-input"
                          v-model="configEditValues[item.key]"
                        />
                        <button
                          v-if="item.secret"
                          class="btn-toggle-secret"
                          @click="configShowSecret[item.key] = !configShowSecret[item.key]"
                          :title="configShowSecret[item.key] ? '隐藏' : '显示'"
                        >
                          {{ configShowSecret[item.key] ? '🙈' : '👁' }}
                        </button>
                      </div>
                    </template>
                    <template v-else>
                      <span
                        class="config-value"
                        :class="{ 'secret-masked': item.secret && !configShowSecret[item.key] }"
                        @click="item.secret && (configShowSecret[item.key] = !configShowSecret[item.key])"
                      >
                        {{ item.secret && !configShowSecret[item.key] ? item.value : (configShowSecret[item.key] ? '已隐藏（点击切换）' : item.value) }}
                      </span>
                      <button
                        v-if="item.secret"
                        class="btn-toggle-secret"
                        @click="configShowSecret[item.key] = !configShowSecret[item.key]"
                        :title="configShowSecret[item.key] ? '隐藏' : '显示'"
                      >
                        {{ configShowSecret[item.key] ? '🙈' : '👁' }}
                      </button>
                    </template>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- 数据维护 -->
        <div v-if="activeTab === 'maintenance'" class="tab-panel">
          <div class="panel-title">
            <Icon name="file" :size="20" />
            <h2>数据维护</h2>
          </div>

          <!-- 数据库备份 -->
          <div class="section-card">
            <div class="maintenance-section-header">
              <h4>数据库备份</h4>
              <button class="btn-action promote" :disabled="isBackingUp" @click="triggerBackup">
                {{ isBackingUp ? '备份中...' : '立即备份' }}
              </button>
            </div>
            <div class="backup-list">
              <div v-if="backupList.length === 0" class="empty-backup">
                <p>暂无备份文件</p>
              </div>
              <div v-for="file in backupList" :key="file.fileName" class="backup-item">
                <div class="backup-info">
                  <span class="backup-name">{{ file.fileName }}</span>
                  <span class="backup-meta">{{ formatFileSize(file.size) }} · {{ formatDate(new Date(file.lastModified)) }}</span>
                </div>
                <a :href="getDownloadUrl(file.fileName)" class="btn-action promote" download>下载</a>
              </div>
            </div>
          </div>

          <!-- 消息归档 -->
          <div class="section-card" style="margin-top: 16px;">
            <div class="maintenance-section-header">
              <h4>消息归档</h4>
            </div>
            <p class="maintenance-desc">将指定天数之前的消息标记为已归档，归档后的消息不再在聊天界面中显示。</p>
            <div class="archive-form">
              <div class="archive-input-group">
                <label>归档多少天前的消息</label>
                <input type="number" v-model.number="archiveDays" min="1" max="3650" class="config-input archive-input" />
                <span class="archive-unit">天</span>
              </div>
              <button class="btn-action promote" :disabled="isArchiving" @click="triggerArchive">
                {{ isArchiving ? '归档中...' : '执行归档' }}
              </button>
            </div>
          </div>
        </div>

        <!-- 系统日志 -->
        <div v-if="activeTab === 'log'" class="tab-panel">
          <div class="panel-title">
            <Icon name="file" :size="20" />
            <h2>系统日志</h2>
          </div>

          <!-- 子标签切换 -->
          <div class="log-sub-tabs">
            <button
              class="log-sub-tab"
              :class="{ active: logSubTab === 'app' }"
              @click="switchSubTab('app')"
            >
              应用日志
            </button>
            <button
              class="log-sub-tab"
              :class="{ active: logSubTab === 'audit' }"
              @click="switchSubTab('audit')"
            >
              审计日志
            </button>
          </div>

          <!-- 应用日志 -->
          <div v-if="logSubTab === 'app'" class="log-section">
            <div class="log-toolbar">
              <div class="log-level-filter">
                <button
                  v-for="opt in appLogLevelOptions"
                  :key="opt.value"
                  class="log-level-btn"
                  :class="[
                    { active: appLogLevel === opt.value },
                    'level-' + opt.value.toLowerCase(),
                  ]"
                  @click="setAppLogLevel(opt.value)"
                >
                  {{ opt.label }}
                </button>
              </div>
              <button class="btn-action promote" @click="refreshAppLogs">刷新</button>
            </div>

            <div v-if="appLogsError" class="log-error-msg">{{ appLogsError }}</div>

            <div class="log-viewer-wrapper">
              <div v-if="appLogsLoading" class="log-loading">加载中...</div>
              <div v-else-if="appLogs.length === 0" class="log-empty">暂无日志</div>
              <div v-else class="log-viewer">
                <div
                  v-for="(entry, index) in appLogs"
                  :key="index"
                  class="log-line"
                  :class="levelClass(entry.level)"
                >
                  <span class="log-level-tag">{{ entry.level }}</span>
                  <span class="log-content">{{ entry.message }}</span>
                </div>
              </div>
            </div>

            <div v-if="appLogTotalPages > 1" class="log-pagination">
              <button
                class="btn-action promote"
                :disabled="appLogPage <= 0"
                @click="goToAppLogPage(appLogPage - 1)"
              >
                上一页
              </button>
              <span class="page-info">
                第 {{ appLogPage + 1 }} / {{ appLogTotalPages }} 页（共 {{ appLogTotal }} 条）
              </span>
              <button
                class="btn-action promote"
                :disabled="appLogPage >= appLogTotalPages - 1"
                @click="goToAppLogPage(appLogPage + 1)"
              >
                下一页
              </button>
            </div>
          </div>

          <!-- 审计日志 -->
          <div v-if="logSubTab === 'audit'" class="log-section">
            <div class="log-toolbar">
              <input
                type="text"
                class="audit-search-input"
                v-model="auditKeyword"
                placeholder="按操作人搜索..."
                @input="onAuditKeywordInput"
              />
              <select
                class="audit-action-select"
                :value="auditAction"
                @change="setAuditAction($event.target.value)"
              >
                <option
                  v-for="opt in auditActionOptions"
                  :key="opt.value"
                  :value="opt.value"
                >
                  {{ opt.label }}
                </option>
              </select>
              <button class="btn-action promote" @click="refreshAuditLogs">刷新</button>
            </div>

            <div v-if="auditLogsError" class="log-error-msg">{{ auditLogsError }}</div>

            <div class="audit-table-wrapper">
              <table class="audit-table">
                <thead>
                  <tr>
                    <th>时间</th>
                    <th>操作人</th>
                    <th>操作类型</th>
                    <th>目标</th>
                    <th>结果</th>
                    <th>详情</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="auditLogsLoading">
                    <td colspan="6" class="audit-loading">加载中...</td>
                  </tr>
                  <tr v-else-if="auditLogs.length === 0">
                    <td colspan="6" class="audit-empty">暂无审计日志</td>
                  </tr>
                  <tr v-for="log in auditLogs" :key="log.id">
                    <td>{{ formatTimestamp(log.timestamp) }}</td>
                    <td>{{ log.username || '-' }}</td>
                    <td>
                      <span class="audit-action-tag">{{ actionLabel(log.action) }}</span>
                    </td>
                    <td class="audit-target">{{ log.target || '-' }}</td>
                    <td>
                      <span class="audit-result-badge" :class="resultBadgeClass(log.result)">
                        {{ log.result || '-' }}
                      </span>
                    </td>
                    <td class="audit-detail">{{ log.detail || '-' }}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div v-if="auditTotalPages > 1" class="log-pagination">
              <button
                class="btn-action promote"
                :disabled="auditPage <= 0"
                @click="goToAuditPage(auditPage - 1)"
              >
                上一页
              </button>
              <span class="page-info">
                第 {{ auditPage + 1 }} / {{ auditTotalPages }} 页（共 {{ auditTotal }} 条）
              </span>
              <button
                class="btn-action promote"
                :disabled="auditPage >= auditTotalPages - 1"
                @click="goToAuditPage(auditPage + 1)"
              >
                下一页
              </button>
            </div>
          </div>
        </div>
      </main>
    </div>

    <!-- 媒体文件预览弹窗（相册风格） -->
    <div v-if="showPreviewModal" class="preview-modal" @click.self="closePreview">
      <div class="preview-content" :class="{ 'gallery-mode': previewMode === 'gallery' }">
        <button class="preview-close" @click="closePreview">×</button>

        <!-- 相册网格模式 -->
        <div v-if="previewMode === 'gallery'" class="preview-gallery">
          <div class="preview-gallery-header">
            <span class="preview-gallery-title">媒体文件预览</span>
            <small>{{ previewFiles.length }} 个文件 · 已选 {{ selectedMediaFileIds.size }} 个</small>
          </div>

          <div class="preview-gallery-body">
            <div v-if="previewFiles.length === 0" class="preview-empty">暂无文件</div>
            <div v-else class="preview-grid">
              <div
                v-for="(file, index) in previewFiles"
                :key="file.id"
                class="preview-grid-item"
                @click="enterSingleView(index)"
              >
                <div
                  class="preview-select-circle"
                  :class="{ selected: selectedMediaFileIds.has(file.id) }"
                  @click.stop="togglePreviewSelection(file.id)"
                >
                  <span v-if="selectedMediaFileIds.has(file.id)">✓</span>
                </div>

                <div class="preview-thumbnail">
                  <img
                    v-if="file.fileType === 'IMAGE' && file.url && !isMediaError(file.id)"
                    :src="file.url"
                    :alt="file.fileName"
                    loading="lazy"
                    @error="markMediaError(file.id)"
                  />
                  <img
                    v-else-if="file.fileType === 'IMAGE'"
                    src="/deleted-image.svg"
                    :alt="file.fileName + '（已删除）'"
                    class="preview-thumbnail-placeholder"
                    style="width: 64px; height: 64px; object-fit: contain;"
                  />
                  <video
                    v-else-if="file.fileType === 'VIDEO' && file.url && !isMediaError(file.id)"
                    :src="file.url"
                    preload="metadata"
                    @error="markMediaError(file.id)"
                  ></video>
                  <img
                    v-else-if="file.fileType === 'VIDEO'"
                    src="/deleted-video.svg"
                    :alt="file.fileName + '（已删除）'"
                    class="preview-thumbnail-placeholder"
                    style="width: 64px; height: 64px; object-fit: contain;"
                  />
                  <div v-else-if="file.fileType === 'AUDIO'" class="preview-thumbnail-audio">
                    <span>🎵</span>
                  </div>
                  <div v-else class="preview-thumbnail-file">
                    <span>📄</span>
                  </div>
                </div>

                <div class="preview-grid-info">
                  <span class="preview-grid-name" :title="file.fileName">{{ file.fileName }}</span>
                  <small>{{ formatBytes(file.fileSize) }}</small>
                </div>
              </div>
            </div>
          </div>

          <div class="preview-gallery-footer">
            <button class="preview-nav-btn" @click="toggleSelectAllInPreview">
              {{ isAllPreviewSelected ? '取消全选' : '全选' }}
            </button>
            <button class="preview-nav-btn" @click="backToList">关闭预览</button>
          </div>
        </div>

        <!-- 单张查看模式 -->
        <div v-else-if="currentPreviewFile" class="preview-single">
          <div class="preview-title">
            <span>{{ currentPreviewFile.fileName }}</span>
            <small>{{ formatBytes(currentPreviewFile.fileSize) }} · {{ currentPreviewIndex + 1 }} / {{ previewFiles.length }}</small>
          </div>

          <div class="preview-media" @click.self="backToGallery">
            <img
              v-if="currentPreviewFile.fileType === 'IMAGE' && currentPreviewFile.url && !isMediaError(currentPreviewFile.id)"
              :src="currentPreviewFile.url"
              :alt="currentPreviewFile.fileName"
              class="preview-img"
              @error="markMediaError(currentPreviewFile.id)"
            />
            <img
              v-else-if="currentPreviewFile.fileType === 'IMAGE'"
              src="/deleted-image.svg"
              :alt="currentPreviewFile.fileName + '（已删除）'"
              class="preview-img"
            />
            <video
              v-else-if="currentPreviewFile.fileType === 'VIDEO' && currentPreviewFile.url && !isMediaError(currentPreviewFile.id)"
              :src="currentPreviewFile.url"
              controls
              class="preview-video"
              @error="markMediaError(currentPreviewFile.id)"
            ></video>
            <img
              v-else-if="currentPreviewFile.fileType === 'VIDEO'"
              src="/deleted-video.svg"
              :alt="currentPreviewFile.fileName + '（已删除）'"
              class="preview-video"
            />
            <audio
              v-else-if="currentPreviewFile.fileType === 'AUDIO'"
              :src="currentPreviewFile.url"
              controls
              class="preview-audio"
            ></audio>
            <div v-else class="preview-unsupported">
              暂不支持预览该类型文件
            </div>
          </div>

          <div class="preview-nav">
            <button
              class="preview-nav-btn"
              :disabled="currentPreviewIndex <= 0"
              @click="previewPrev"
            >
              ← 上一个
            </button>
            <button class="preview-nav-btn" @click="backToGallery">
              返回相册
            </button>
            <button
              class="preview-nav-btn"
              :disabled="currentPreviewIndex >= previewFiles.length - 1"
              @click="previewNext"
            >
              下一个 →
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- 系统消息 -->
    <div v-if="systemMessage" class="system-message" :class="systemMessageType">
      {{ systemMessage }}
    </div>
  </div>
</template>

<script>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import Icon from '../components/Icon.vue';
import { formatFileSize } from '../services/api';
import { useDashboardData } from '../composables/useDashboardData';
import { useComponentControl } from '../composables/useComponentControl';
import { useUserManagement } from '../composables/useUserManagement';
import { useMediaManager } from '../composables/useMediaManager';
import { useSystemLog } from '../composables/useSystemLog';
import { useConfigManagement } from '../composables/useConfigManagement';
import { useMaintenance } from '../composables/useMaintenance';

export default {
  name: 'AdminView',
  components: { Icon },
  setup() {
    const router = useRouter();
    const activeTab = ref('dashboard');

    const systemMessage = ref('');
    const systemMessageType = ref('');
    const showSystemMsg = (msg, type = 'success') => {
      systemMessage.value = msg;
      systemMessageType.value = type;
      setTimeout(() => { systemMessage.value = ''; }, 5000);
    };

    const dashboard = useDashboardData();
    const componentCtrl = useComponentControl({ showSystemMsg });
    const userMgmt = useUserManagement({ showSystemMsg });
    const isMediaCollapsed = ref(false);
    const media = useMediaManager({ showSystemMsg, loadDiskUsage: dashboard.loadDiskUsage });
    const systemLog = useSystemLog({ showSystemMsg });
    const configMgmt = useConfigManagement({ showSystemMsg });
    const maintenance = useMaintenance({ showSystemMsg });

    const navItems = [
      { key: 'dashboard', icon: 'dashboard', label: '数据概览' },
      { key: 'users', icon: 'group', label: '用户管理' },
      { key: 'components', icon: 'settings', label: '组件控制' },
      { key: 'media', icon: 'image', label: '媒体管理' },
      { key: 'distribution', icon: 'chart', label: '消息分布' },
      { key: 'log', icon: 'file', label: '系统日志' },
      { key: 'config', icon: 'config', label: '配置管理' },
      { key: 'maintenance', icon: 'backup', label: '数据维护' },
    ];

    const currentFilesTotalSize = computed(() =>
      media.mediaFiles.value.reduce((sum, f) => sum + (f.fileSize || 0), 0)
    );
    const totalMediaPages = computed(() =>
      Math.ceil(media.mediaFilesTotal.value / media.mediaFileSize.value) || 1
    );
    const confirmDeleteSelected = () => {
      if (!window.confirm(`确定删除选中的 ${media.selectedMediaFileIds.value.size} 个文件吗？`)) return;
      media.deleteSelectedMediaFiles();
    };
    const confirmPurgeByFilter = () => {
      const filter = media.mediaFileFilter.value;
      const labels = { ALL: '全部', IMAGE: '图片', VIDEO: '视频', AUDIO: '音频' };
      if (!window.confirm(`确定清理${labels[filter] || ''}媒体文件吗？清理后不可恢复。`)) return;
      media.purgeTypes.value = {
        IMAGE: filter === 'ALL' || filter === 'IMAGE',
        VIDEO: filter === 'ALL' || filter === 'VIDEO',
        AUDIO: filter === 'ALL' || filter === 'AUDIO',
      };
      media.confirmPurgeMedia();
    };

    const formatDate = (dateStr) => {
      if (!dateStr) return '-';
      return new Date(dateStr).toLocaleString('zh-CN');
    };
    const goHome = () => router.push('/');
    const logout = () => {
      localStorage.removeItem('auth_token');
      localStorage.removeItem('user_role');
      localStorage.removeItem('user_info');
      localStorage.removeItem('isLoggedIn');
      router.push('/login');
    };

    onMounted(() => {
      dashboard.loadAll();
      userMgmt.loadUsers();
      componentCtrl.startPolling();
      componentCtrl.loadNapCatWebUiUrl();
      componentCtrl.refreshQrCode();
      if (componentCtrl.autoLogin.value) componentCtrl.checkNapCatLogin();
      media.loadMediaFiles();
      configMgmt.loadConfig();
      maintenance.loadBackupList();
      dashboard.setupDragScroll();
      window.addEventListener('keydown', media.onPreviewKeydown);
    });
    onUnmounted(() => {
      componentCtrl.stopPolling();
      dashboard.cleanupDragScroll();
      userMgmt.cleanup();
      window.removeEventListener('keydown', media.onPreviewKeydown);
    });

    return {
      activeTab, navItems, systemMessage, systemMessageType,
      isMediaCollapsed, formatDate, formatFileSize, goHome, logout,
      // Dashboard
      stats: dashboard.stats, messageTrend: dashboard.messageTrend,
      trendDays: dashboard.trendDays, trendInterval: dashboard.trendInterval,
      groupRanking: dashboard.groupRanking, qqRanking: dashboard.qqRanking,
      messageTypeDistribution: dashboard.messageTypeDistribution,
      diskUsage: dashboard.diskUsage,
      tooltipVisible: dashboard.tooltipVisible, tooltipData: dashboard.tooltipData,
      tooltipStyle: dashboard.tooltipStyle, scrollWrapper: dashboard.scrollWrapper,
      setTrendDays: dashboard.setTrendDays,
      getBarHeight: dashboard.getBarHeight, getLinePoints: dashboard.getLinePoints,
      getPointX: dashboard.getPointX, getPointY: dashboard.getPointY,
      getPointXPercent: dashboard.getPointXPercent, getPointYPercent: dashboard.getPointYPercent,
      getAreaPoints: dashboard.getAreaPoints, getYAxisLabel: dashboard.getYAxisLabel,
      getRankWidth: dashboard.getRankWidth, getQQRankWidth: dashboard.getQQRankWidth,
      getRankColor: dashboard.getRankColor,
      getDistWidth: dashboard.getDistWidth, getPieGradient: dashboard.getPieGradient,
      getDistPercent: dashboard.getDistPercent, getDistColor: dashboard.getDistColor,
      showTooltip: dashboard.showTooltip, hideTooltip: dashboard.hideTooltip,
      truncateName: dashboard.truncateName, truncateQQName: dashboard.truncateQQName,
      // Component control
      componentStatus: componentCtrl.componentStatus, qrCode: componentCtrl.qrCode,
      napCatWebUiUrl: componentCtrl.napCatWebUiUrl, autoLogin: componentCtrl.autoLogin,
      isCheckingLogin: componentCtrl.isCheckingLogin,
      isStartingAstrBot: componentCtrl.isStartingAstrBot, isStoppingAstrBot: componentCtrl.isStoppingAstrBot,
      isStartingNapCat: componentCtrl.isStartingNapCat, isStoppingNapCat: componentCtrl.isStoppingNapCat,
      isStartingGptSovits: componentCtrl.isStartingGptSovits, isStoppingGptSovits: componentCtrl.isStoppingGptSovits,
      refreshQrCode: componentCtrl.refreshQrCode, openNapCatWebUI: componentCtrl.openNapCatWebUI,
      openGptSovitsWebUI: componentCtrl.openGptSovitsWebUI,
      onAutoLoginChange: componentCtrl.onAutoLoginChange,
      startAstrBot: componentCtrl.startAstrBot, stopAstrBot: componentCtrl.stopAstrBot,
      startNapCat: componentCtrl.startNapCat, stopNapCat: componentCtrl.stopNapCat,
      startGptSovits: componentCtrl.startGptSovits, stopGptSovits: componentCtrl.stopGptSovits,
      // User management
      users: userMgmt.users, userSearch: userMgmt.userSearch,
      userCurrentPage: userMgmt.userCurrentPage, userTotalElements: userMgmt.userTotalElements,
      userTotalPages: userMgmt.userTotalPages,
      onUserSearchInput: userMgmt.onUserSearchInput, goToUserPage: userMgmt.goToUserPage,
      toggleRole: userMgmt.toggleRole, toggleActive: userMgmt.toggleActive, deleteUser: userMgmt.deleteUser,
      // Media
      mediaFiles: media.mediaFiles, mediaFilesLoading: media.mediaFilesLoading,
      mediaFileFilter: media.mediaFileFilter, mediaFilePage: media.mediaFilePage,
      mediaFilesTotal: media.mediaFilesTotal, selectedMediaFileIds: media.selectedMediaFileIds,
      selectedMediaFilesCount: media.selectedMediaFilesCount, selectedMediaFilesTotalSize: media.selectedMediaFilesTotalSize,
      isPurging: media.isPurging, purgeResult: media.purgeResult,
      markMediaError: media.markMediaError, isMediaError: media.isMediaError,
      formatBytes: media.formatBytes, loadMediaFiles: media.loadMediaFiles,
      setMediaFileFilter: media.setMediaFileFilter, toggleMediaFileSelection: media.toggleMediaFileSelection,
      selectAllMediaFiles: media.selectAllMediaFiles, clearMediaFileSelection: media.clearMediaFileSelection,
      currentFilesTotalSize, totalMediaPages, confirmDeleteSelected, confirmPurgeByFilter,
      showPreviewModal: media.showPreviewModal, previewFiles: media.previewFiles,
      currentPreviewIndex: media.currentPreviewIndex, currentPreviewFile: media.currentPreviewFile,
      previewMode: media.previewMode, openPreview: media.openPreview, previewAllFiles: media.previewAllFiles,
      closePreview: media.closePreview, previewNext: media.previewNext, previewPrev: media.previewPrev,
      enterSingleView: media.enterSingleView, backToGallery: media.backToGallery, backToList: media.backToList,
      togglePreviewSelection: media.togglePreviewSelection,
      isAllPreviewSelected: media.isAllPreviewSelected, toggleSelectAllInPreview: media.toggleSelectAllInPreview,
      // Config
      configGroups: configMgmt.configGroups, configLoading: configMgmt.configLoading,
      configEditing: configMgmt.configEditing, configEditValues: configMgmt.configEditValues,
      configShowSecret: configMgmt.configShowSecret,
      startEditConfig: configMgmt.startEditConfig, cancelEditConfig: configMgmt.cancelEditConfig,
      saveConfig: configMgmt.saveConfig,
      // Maintenance
      isBackingUp: maintenance.isBackingUp, backupList: maintenance.backupList,
      isArchiving: maintenance.isArchiving, archiveDays: maintenance.archiveDays,
      triggerBackup: maintenance.triggerBackup, getDownloadUrl: maintenance.getDownloadUrl,
      triggerArchive: maintenance.triggerArchive,
      // System log
      logSubTab: systemLog.logSubTab, appLogs: systemLog.appLogs,
      appLogsLoading: systemLog.appLogsLoading, appLogsError: systemLog.appLogsError,
      appLogLevel: systemLog.appLogLevel, appLogPage: systemLog.appLogPage,
      appLogTotal: systemLog.appLogTotal, appLogTotalPages: systemLog.appLogTotalPages,
      appLogLevelOptions: systemLog.appLogLevelOptions,
      setAppLogLevel: systemLog.setAppLogLevel, goToAppLogPage: systemLog.goToAppLogPage,
      refreshAppLogs: systemLog.refreshAppLogs,
      auditLogs: systemLog.auditLogs, auditLogsLoading: systemLog.auditLogsLoading,
      auditLogsError: systemLog.auditLogsError, auditKeyword: systemLog.auditKeyword,
      auditAction: systemLog.auditAction, auditPage: systemLog.auditPage,
      auditTotal: systemLog.auditTotal, auditTotalPages: systemLog.auditTotalPages,
      auditActionOptions: systemLog.auditActionOptions,
      setAuditAction: systemLog.setAuditAction, onAuditKeywordInput: systemLog.onAuditKeywordInput,
      goToAuditPage: systemLog.goToAuditPage, refreshAuditLogs: systemLog.refreshAuditLogs,
      switchSubTab: systemLog.switchSubTab, formatTimestamp: systemLog.formatTimestamp,
      actionLabel: systemLog.actionLabel, resultBadgeClass: systemLog.resultBadgeClass,
      levelClass: systemLog.levelClass,
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
.user-search-bar {
  margin-bottom: 16px;
}

.user-search-input {
  width: 320px;
  max-width: 100%;
  padding: 8px 14px;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
  font-size: 13px;
  outline: none;
  transition: border-color 0.2s;
  box-sizing: border-box;
}

.user-search-input:focus {
  border-color: #3498db;
  box-shadow: 0 0 0 2px rgba(52, 152, 219, 0.15);
}

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

/* 分页控件 */
.user-pagination {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 16px;
  padding: 12px 0;
}

.pagination-info {
  font-size: 13px;
  color: #666;
}

.pagination-btns {
  display: flex;
  gap: 6px;
}

.btn-page {
  padding: 6px 14px;
  border: 1px solid #e0e0e0;
  background: #fff;
  border-radius: 4px;
  font-size: 12px;
  color: #555;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-page:hover:not(:disabled) {
  border-color: #3498db;
  color: #3498db;
}

.btn-page:disabled {
  background: #f5f5f5;
  color: #bbb;
  cursor: not-allowed;
  border-color: #eee;
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

/* 媒体文件管理 */
.section-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.section-card-header h4 {
  margin: 0;
}

.collapse-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid #e0e0e0;
  background: #fff;
  border-radius: 4px;
  font-size: 12px;
  color: #666;
  cursor: pointer;
  transition: all 0.2s;
}

.collapse-toggle:hover {
  border-color: #3498db;
  color: #3498db;
}

.media-manager-card {
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  padding: 16px;
}

.media-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 14px;
  font-size: 13px;
  color: #666;
}

.media-selected {
  color: #3498db;
  font-weight: 500;
}

.media-filter {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}

.media-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px;
  gap: 10px;
  font-size: 13px;
  color: #888;
}

.media-empty {
  padding: 32px;
  text-align: center;
  font-size: 13px;
  color: #999;
}

.media-table {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 14px;
  font-size: 13px;
}

.media-table th,
.media-table td {
  padding: 10px 8px;
  border-bottom: 1px solid #f0f0f0;
  text-align: left;
  color: #444;
}

.media-table th {
  font-weight: 600;
  color: #333;
  background: #fafafa;
}

.media-table tbody tr:hover {
  background: #fafafa;
}

.media-table .col-checkbox {
  width: 36px;
  text-align: center;
}

.media-table input[type="checkbox"] {
  width: 14px;
  height: 14px;
  cursor: pointer;
}

.media-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-bottom: 14px;
  font-size: 13px;
  color: #666;
}

.media-pagination button {
  padding: 6px 12px;
  border: 1px solid #e0e0e0;
  background: #fff;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  color: #666;
  transition: all 0.2s;
}

.media-pagination button:hover:not(:disabled) {
  border-color: #3498db;
  color: #3498db;
}

.media-pagination button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.purge-result {
  margin-bottom: 12px;
  padding: 10px 12px;
  background-color: #f0f9ff;
  border-radius: 6px;
  font-size: 13px;
  color: #1677ff;
  text-align: center;
}

.media-actions {
  display: flex;
  gap: 10px;
}

.media-actions button {
  flex: 1;
  padding: 10px 0;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background-color 0.2s;
}

.btn-delete:not(:disabled) {
  background-color: #e74c3c;
  color: #fff;
}

.btn-delete:not(:disabled):hover {
  background-color: #c0392b;
}

.btn-delete:disabled {
  background-color: #e0e0e0;
  color: #999;
  cursor: not-allowed;
}

.btn-purge:not(:disabled) {
  background-color: #f39c12;
  color: #fff;
}

.btn-purge:not(:disabled):hover {
  background-color: #e67e22;
}

.btn-purge:disabled {
  background-color: #e0e0e0;
  color: #999;
  cursor: not-allowed;
}

.file-name-cell {
  cursor: pointer;
  color: #3498db;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-name-cell:hover {
  text-decoration: underline;
}

.col-action {
  width: 60px;
  text-align: center;
}

.btn-preview {
  padding: 4px 10px;
  font-size: 12px;
  color: #3498db;
  background: #f0f9ff;
  border: 1px solid #b7d8f7;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-preview:hover:not(:disabled) {
  background: #e0f2ff;
  border-color: #3498db;
}

.btn-preview:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 预览弹窗 */
.preview-modal {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.75);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}

.preview-content {
  background: #fff;
  border-radius: 8px;
  max-width: 90vw;
  max-height: 90vh;
  width: 720px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}

.preview-close {
  position: absolute;
  top: 10px;
  right: 14px;
  background: none;
  border: none;
  font-size: 28px;
  color: #666;
  cursor: pointer;
  z-index: 10;
}

.preview-close:hover {
  color: #333;
}

.preview-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  gap: 12px;
}

.preview-title span {
  font-size: 15px;
  font-weight: 500;
  color: #333;
  word-break: break-all;
}

.preview-title small {
  font-size: 12px;
  color: #888;
  white-space: nowrap;
}

.preview-media {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  background: #f8f8f8;
  border-radius: 6px;
  overflow: hidden;
}

.preview-img {
  max-width: 100%;
  max-height: 60vh;
  object-fit: contain;
}

.preview-video {
  max-width: 100%;
  max-height: 60vh;
}

.preview-audio {
  width: 100%;
  padding: 20px;
}

.preview-unsupported {
  padding: 60px 20px;
  color: #888;
  font-size: 14px;
}

.preview-nav {
  display: flex;
  justify-content: space-between;
  padding: 14px 20px;
  border-top: 1px solid #f0f0f0;
  background: #fafafa;
}

.preview-nav-btn {
  padding: 8px 16px;
  border: 1px solid #e0e0e0;
  background: #fff;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  color: #555;
  transition: all 0.2s;
}

.preview-nav-btn:hover:not(:disabled) {
  border-color: #3498db;
  color: #3498db;
}

.preview-nav-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 相册网格模式 */
.preview-content.gallery-mode {
  width: 90vw;
  max-width: 1100px;
}

.preview-gallery {
  display: flex;
  flex-direction: column;
  max-height: 90vh;
}

.preview-gallery-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f0;
  background: #fafafa;
}

.preview-gallery-title {
  font-size: 16px;
  font-weight: 500;
  color: #333;
}

.preview-gallery-header small {
  font-size: 12px;
  color: #888;
}

.preview-gallery-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  background: #fff;
}

.preview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 12px;
}

.preview-grid-item {
  position: relative;
  border-radius: 8px;
  overflow: hidden;
  background: #f8f8f8;
  border: 1px solid #f0f0f0;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
}

.preview-grid-item:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.preview-select-circle {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.9);
  background: rgba(0, 0, 0, 0.25);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 2;
  transition: all 0.15s;
}

.preview-select-circle:hover {
  background: rgba(0, 0, 0, 0.45);
}

.preview-select-circle.selected {
  background: #3498db;
  border-color: #fff;
}

.preview-select-circle span {
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}

.preview-thumbnail {
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: #f0f0f0;
}

.preview-thumbnail img,
.preview-thumbnail video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.preview-thumbnail-audio,
.preview-thumbnail-file {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  font-size: 32px;
  background: #f8f8f8;
}

.preview-grid-info {
  padding: 8px;
  background: #fff;
}

.preview-grid-name {
  display: block;
  font-size: 12px;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.preview-grid-info small {
  font-size: 11px;
  color: #999;
}

.preview-empty {
  text-align: center;
  padding: 60px 20px;
  color: #888;
  font-size: 14px;
}

.preview-gallery-footer {
  padding: 14px 20px;
  border-top: 1px solid #f0f0f0;
  background: #fafafa;
  display: flex;
  justify-content: center;
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

/* 配置管理 */
.config-groups {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 16px;
}

.config-group-card {
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 8px;
  padding: 18px;
}

.config-group-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
  padding-bottom: 10px;
  border-bottom: 1px solid #f0f0f0;
}

.config-group-header h4 {
  margin: 0;
  font-size: 15px;
  color: #2c3e50;
}

.config-group-actions {
  display: flex;
  gap: 6px;
}

.config-items {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.config-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.config-item-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #888;
}

.restart-badge {
  display: inline-block;
  padding: 1px 6px;
  background: #fff3e0;
  color: #f57c00;
  border-radius: 3px;
  font-size: 10px;
  font-weight: 500;
}

.config-item-value {
  display: flex;
  align-items: center;
  gap: 6px;
}

.config-value {
  flex: 1;
  font-size: 13px;
  color: #333;
  padding: 6px 0;
  min-height: 30px;
  display: flex;
  align-items: center;
}

.config-value.secret-masked {
  cursor: pointer;
  color: #aaa;
  letter-spacing: 2px;
}

.config-edit-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
}

.config-input {
  flex: 1;
  padding: 6px 10px;
  border: 1px solid #e0e0e0;
  border-radius: 4px;
  font-size: 13px;
  color: #333;
  outline: none;
  transition: border-color 0.2s;
}

.config-input:focus {
  border-color: #3498db;
}

.btn-toggle-secret {
  padding: 4px 8px;
  border: 1px solid #e0e0e0;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  transition: border-color 0.2s;
}

.btn-toggle-secret:hover {
  border-color: #3498db;
}

/* 数据维护 */
.maintenance-section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}

.maintenance-section-header h4 {
  margin: 0;
}

.maintenance-desc {
  font-size: 13px;
  color: #888;
  margin: 0 0 14px 0;
}

.backup-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.empty-backup {
  text-align: center;
  padding: 30px;
  color: #aaa;
  font-size: 13px;
}

.backup-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  background: #f8f9fa;
  border-radius: 6px;
  transition: background 0.2s;
}

.backup-item:hover {
  background: #f0f1f3;
}

.backup-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.backup-name {
  font-size: 13px;
  font-weight: 500;
  color: #2c3e50;
}

.backup-meta {
  font-size: 11px;
  color: #999;
}

.archive-form {
  display: flex;
  align-items: flex-end;
  gap: 14px;
}

.archive-input-group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.archive-input-group label {
  font-size: 12px;
  color: #888;
}

.archive-input {
  width: 100px;
}

.archive-unit {
  font-size: 13px;
  color: #666;
  align-self: flex-end;
  margin-bottom: 6px;
}

/* ===== 系统日志 ===== */
.log-sub-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  border-bottom: 1px solid #e0e0e0;
}
.log-sub-tab {
  padding: 8px 20px;
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  color: #666;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
}
.log-sub-tab:hover {
  color: #2c3e50;
}
.log-sub-tab.active {
  color: #2c3e50;
  border-bottom-color: #2c3e50;
  font-weight: 600;
}
.log-section {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
.log-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.log-level-filter {
  display: flex;
  gap: 6px;
}
.log-level-btn {
  padding: 4px 12px;
  background: #f5f6fa;
  border: 1px solid #e0e0e0;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  color: #666;
  transition: all 0.2s;
}
.log-level-btn:hover {
  background: #e9ecef;
}
.log-level-btn.active {
  color: #fff;
  border-color: #2c3e50;
}
.log-level-btn.active.level-all {
  background: #2c3e50;
}
.log-level-btn.active.level-info {
  background: #17a2b8;
}
.log-level-btn.active.level-warn {
  background: #ffc107;
  color: #333;
}
.log-level-btn.active.level-error {
  background: #dc3545;
}
.log-level-btn.active.level-debug {
  background: #6c757d;
}
.log-viewer-wrapper {
  background: #1e1e1e;
  border-radius: 6px;
  min-height: 300px;
  max-height: 600px;
  overflow: auto;
  padding: 12px;
}
.log-viewer {
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.6;
}
.log-line {
  display: flex;
  gap: 8px;
  color: #d4d4d4;
  padding: 1px 0;
  word-break: break-all;
}
.log-line.log-error {
  color: #f48771;
}
.log-line.log-warn {
  color: #cca700;
}
.log-line.log-info {
  color: #75beff;
}
.log-line.log-debug {
  color: #888;
}
.log-level-tag {
  flex-shrink: 0;
  min-width: 50px;
  font-weight: 600;
  text-align: center;
  padding: 0 4px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.1);
  height: 18px;
  line-height: 18px;
  align-self: flex-start;
  margin-top: 1px;
}
.log-content {
  flex: 1;
  white-space: pre-wrap;
}
.log-loading,
.log-empty {
  color: #888;
  text-align: center;
  padding: 40px 0;
}
.log-error-msg {
  color: #dc3545;
  padding: 8px 12px;
  background: #fff5f5;
  border-radius: 4px;
  margin-bottom: 12px;
  font-size: 13px;
}
.log-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 12px;
}
.log-pagination .page-info {
  font-size: 13px;
  color: #666;
}
.audit-search-input,
.audit-action-select {
  padding: 6px 10px;
  border: 1px solid #e0e0e0;
  border-radius: 4px;
  font-size: 13px;
  outline: none;
}
.audit-search-input {
  flex: 1;
  min-width: 180px;
  max-width: 300px;
}
.audit-action-select {
  min-width: 140px;
  cursor: pointer;
}
.audit-table-wrapper {
  overflow-x: auto;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
}
.audit-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.audit-table thead {
  background: #f5f6fa;
}
.audit-table th {
  padding: 10px 12px;
  text-align: left;
  font-weight: 600;
  color: #333;
  border-bottom: 1px solid #e0e0e0;
  white-space: nowrap;
}
.audit-table td {
  padding: 8px 12px;
  border-bottom: 1px solid #f0f0f0;
  color: #555;
  vertical-align: top;
}
.audit-table tbody tr:hover {
  background: #fafbfc;
}
.audit-loading,
.audit-empty {
  text-align: center;
  color: #888;
  padding: 30px 0;
}
.audit-action-tag {
  display: inline-block;
  padding: 2px 8px;
  background: #e3f2fd;
  color: #1976d2;
  border-radius: 10px;
  font-size: 12px;
  white-space: nowrap;
}
.audit-target {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #666;
  max-width: 200px;
  word-break: break-all;
}
.audit-result-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 600;
}
.audit-success {
  background: #e8f5e9;
  color: #2e7d32;
}
.audit-failure {
  background: #ffebee;
  color: #c62828;
}
.audit-detail {
  max-width: 300px;
  word-break: break-all;
  color: #666;
}
</style>
