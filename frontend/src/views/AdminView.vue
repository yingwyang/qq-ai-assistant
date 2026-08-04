<template>
  <div class="admin-view" :class="'theme-' + (currentTheme || 'light')">
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
          <template v-for="item in navItems" :key="item.key">
            <div v-if="item.isGroup" class="nav-group-title">{{ item.label }}</div>
            <div
              v-else
              class="nav-item"
              :class="{ active: activeTab === item.key }"
              @click="setActiveTab(item.key)"
            >
              <Icon :name="item.icon" :size="18" />
              <span>{{ item.label }}</span>
            </div>
          </template>
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
              <div class="stat-icon green"><Icon name="user" :size="28" /></div>
              <div class="stat-info">
                <div class="stat-value">{{ stats.totalUsers }}</div>
                <div class="stat-label">用户总数</div>
                <div class="stat-today">今日活跃 {{ stats.todayActiveUsers || 0 }}</div>
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
            <div class="stat-card">
              <div class="stat-icon amber"><Icon name="sparkles" :size="28" /></div>
              <div class="stat-info">
                <div class="stat-value">{{ stats.totalAiMessages || 0 }}</div>
                <div class="stat-label">AI消息总数</div>
                <div class="stat-today">近7天 {{ stats.todayAiConversations || 0 }}</div>
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
              <v-chart
                class="line-chart-echarts"
                :option="trendChartOption"
                autoresize
              />
            </div>

            <div class="chart-card">
              <h4>活跃群聊排行 TOP5</h4>
              <v-chart
                class="ranking-chart"
                :option="groupRankingOption"
                autoresize
              />
            </div>

            <div class="chart-card">
              <h4>活跃QQ账号排行 TOP5</h4>
              <v-chart
                class="ranking-chart"
                :option="qqRankingOption"
                autoresize
              />
            </div>
          </div>

          <!-- 消息类型分布 -->
          <div class="chart-card distribution-card">
            <h4>消息类型分布</h4>
            <v-chart
              class="pie-chart-echarts"
              :option="distributionChartOption"
              autoresize
            />
          </div>

          <!-- 新增图表区域：时段分布 + AI趋势 -->
          <div class="charts-row">
            <div class="chart-card">
              <h4>今日消息时段分布</h4>
              <v-chart
                class="bar-chart-echarts"
                :option="hourlyChartOption"
                autoresize
              />
            </div>

            <div class="chart-card">
              <h4>近7天 AI 对话趋势</h4>
              <v-chart
                class="line-chart-echarts"
                :option="aiTrendChartOption"
                autoresize
              />
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

        <!-- ===== 积分管理：规则配置 ===== -->
        <div v-if="activeTab === 'credit-rule'" class="tab-panel">
          <div class="panel-title">
            <Icon name="settings" :size="20" />
            <h2>积分规则配置</h2>
          </div>

          <div v-if="ruleLoading" class="loading-box">加载规则中...</div>

          <div v-else class="credit-rule-wrap">
            <div class="section-card">
              <h4>基础奖励</h4>
              <div class="rule-form-grid">
                <div class="rule-item">
                  <label>新人奖励</label>
                  <input type="number" v-model.number="ruleForm.newUserBonus" min="0" class="config-input" />
                  <small>注册即送积分</small>
                </div>
                <div class="rule-item">
                  <label>每日签到积分</label>
                  <input type="number" v-model.number="ruleForm.signInPoints" min="0" class="config-input" />
                  <small>每日签到奖励</small>
                </div>
              </div>
            </div>

            <div class="section-card">
              <h4>AI 费率配置</h4>
              <div class="rule-form-grid">
                <div class="rule-item">
                  <label>tokenUnit（每多少 token 计费）</label>
                  <input type="number" v-model.number="ruleForm.tokenUnit" min="1" class="config-input" />
                </div>
                <div class="rule-item">
                  <label>promptRate（输入倍率）</label>
                  <input type="number" v-model.number="ruleForm.promptRate" min="0" class="config-input" />
                </div>
                <div class="rule-item">
                  <label>completionRate（输出倍率）</label>
                  <input type="number" v-model.number="ruleForm.completionRate" min="0" class="config-input" />
                </div>
                <div class="rule-item">
                  <label>minCost（单次最小消耗）</label>
                  <input type="number" v-model.number="ruleForm.minCost" min="0" class="config-input" />
                </div>
                <div class="rule-item">
                  <label>defaultCostPerMsg（默认每条消耗）</label>
                  <input type="number" v-model.number="ruleForm.defaultCostPerMsg" min="0" class="config-input" />
                </div>
                <div class="rule-item toggle-item">
                  <label>管理员免费（adminFree）</label>
                  <label class="rule-switch">
                    <input type="checkbox" v-model="ruleForm.adminFree" />
                    <span>{{ ruleForm.adminFree ? '已开启' : '已关闭' }}</span>
                  </label>
                </div>
                <div class="rule-item toggle-item">
                  <label>允许透支（allowOverdraft）</label>
                  <label class="rule-switch">
                    <input type="checkbox" v-model="ruleForm.allowOverdraft" />
                    <span>{{ ruleForm.allowOverdraft ? '已开启' : '已关闭' }}</span>
                  </label>
                  <small>注：后端实体暂未持久化此字段</small>
                </div>
              </div>
            </div>

            <div class="section-card">
              <h4>套餐配置（4 档）</h4>
              <div class="plans-edit-grid">
                <div v-for="plan in planMeta" :key="plan.key" class="plan-edit-card">
                  <div class="plan-edit-name">{{ plan.name }}</div>
                  <div class="plan-edit-row">
                    <label>价格（元）</label>
                    <input type="number" v-model.number="ruleForm[plan.priceField]" min="0" step="0.01" class="config-input" />
                  </div>
                  <div class="plan-edit-row">
                    <label>赠送积分</label>
                    <input type="number" v-model.number="ruleForm[plan.creditField]" min="0" class="config-input" />
                  </div>
                </div>
              </div>
              <div class="plan-duration-row">
                <label>套餐时长（天，所有档位共享）</label>
                <input type="number" v-model.number="ruleForm.planDurationDays" min="1" class="config-input plan-duration-input" />
              </div>
            </div>

            <div class="rule-actions">
              <button class="btn-action promote rule-save-btn" :disabled="ruleSaving" @click="saveRule">
                {{ ruleSaving ? '保存中...' : '保存并立即生效' }}
              </button>
            </div>
          </div>
        </div>

        <!-- ===== 积分管理：用户积分 ===== -->
        <div v-if="activeTab === 'credit-users'" class="tab-panel">
          <div class="panel-title">
            <Icon name="user" :size="20" />
            <h2>用户积分</h2>
          </div>

          <div class="user-search-bar">
            <input
              v-model="ucKeyword"
              type="text"
              placeholder="搜索用户名或昵称..."
              class="user-search-input"
              @input="onUcSearchInput"
            />
          </div>

          <div class="user-table-wrapper">
            <table class="user-table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>用户名</th>
                  <th>昵称</th>
                  <th>角色</th>
                  <th>余额</th>
                  <th>订阅等级</th>
                  <th>订阅到期</th>
                  <th>累计收入</th>
                  <th>累计支出</th>
                  <th>消费封禁</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="ucLoading">
                  <td colspan="11" class="audit-loading">加载中...</td>
                </tr>
                <tr v-else-if="ucList.length === 0">
                  <td colspan="11" class="audit-empty">暂无用户数据</td>
                </tr>
                <tr v-for="row in ucList" :key="row.userId">
                  <td>{{ row.userId }}</td>
                  <td>{{ row.username }}</td>
                  <td>{{ row.nickname || '-' }}</td>
                  <td>
                    <span class="role-badge" :class="(row.role || 'USER').toLowerCase()">
                      {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
                    </span>
                  </td>
                  <td class="amount-cell">{{ row.balance }}</td>
                  <td>
                    <span class="tier-badge" :class="('tier-' + (row.subscriptionTier || 'FREE')).toLowerCase()">
                      {{ row.subscriptionTier || 'FREE' }}
                    </span>
                  </td>
                  <td>{{ formatDate(row.subscriptionExpiresAt) }}</td>
                  <td class="credits-cell">{{ row.totalEarned }}</td>
                  <td class="spent-cell">{{ row.totalSpent }}</td>
                  <td>
                    <span class="status-badge" :class="row.consumptionBanned ? 'inactive' : 'active'">
                      {{ row.consumptionBanned ? '已封禁' : '正常' }}
                    </span>
                  </td>
                  <td>
                    <div class="action-btns">
                      <button class="btn-action promote" @click="openAdjustModal(row)">调整积分</button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="user-pagination">
            <span class="pagination-info">
              共 {{ ucTotalElements }} 条，第 {{ ucPage + 1 }} / {{ Math.max(1, ucTotalPages) }} 页
            </span>
            <div class="pagination-btns">
              <button class="btn-page" :disabled="ucPage === 0" @click="goToUcPage(0)">首页</button>
              <button class="btn-page" :disabled="ucPage === 0" @click="goToUcPage(ucPage - 1)">上一页</button>
              <button class="btn-page" :disabled="ucPage >= ucTotalPages - 1" @click="goToUcPage(ucPage + 1)">下一页</button>
              <button class="btn-page" :disabled="ucPage >= ucTotalPages - 1" @click="goToUcPage(ucTotalPages - 1)">末页</button>
            </div>
          </div>
        </div>

        <!-- ===== 积分管理：积分流水 ===== -->
        <div v-if="activeTab === 'credit-transactions'" class="tab-panel">
          <div class="panel-title">
            <Icon name="file" :size="20" />
            <h2>积分流水</h2>
          </div>

          <!-- 顶栏汇总 -->
          <div class="tx-summary-bar">
            <div class="tx-summary-card earned">
              <div class="tx-summary-label">收入合计（本页）</div>
              <div class="tx-summary-value">+{{ txSummary.earned }}</div>
            </div>
            <div class="tx-summary-card spent">
              <div class="tx-summary-label">支出合计（本页）</div>
              <div class="tx-summary-value">-{{ txSummary.spent }}</div>
            </div>
            <div class="tx-summary-card net">
              <div class="tx-summary-label">净额（本页）</div>
              <div class="tx-summary-value">{{ txSummary.net > 0 ? '+' : '' }}{{ txSummary.net }}</div>
            </div>
            <div class="tx-summary-card count">
              <div class="tx-summary-label">本页条数</div>
              <div class="tx-summary-value">{{ txSummary.count }}</div>
            </div>
          </div>

          <!-- 筛选区 -->
          <div class="tx-filters">
            <div class="tx-filter-row">
              <input v-model="txFilters.userId" type="text" placeholder="用户 ID" class="tx-filter-input" />
              <select v-model="txFilters.type" class="audit-action-select">
                <option v-for="opt in txTypeOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
              <select v-model="txFilters.direction" class="audit-action-select">
                <option v-for="opt in txDirectionOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
              <input v-model="txFilters.relatedId" type="text" placeholder="关联 ID" class="tx-filter-input" />
            </div>
            <div class="tx-filter-row">
              <input v-model="txFilters.start" type="date" class="tx-filter-input" title="开始日期" />
              <input v-model="txFilters.end" type="date" class="tx-filter-input" title="结束日期" />
              <input v-model="txFilters.min" type="number" placeholder="最小金额" class="tx-filter-input" />
              <input v-model="txFilters.max" type="number" placeholder="最大金额" class="tx-filter-input" />
              <button class="btn-action promote" @click="searchTransactions">搜索</button>
              <button class="btn-action" @click="resetTxFilters">重置</button>
              <button class="btn-action promote" :disabled="txExporting" @click="exportTransactions">
                {{ txExporting ? '导出中...' : '导出 JSON' }}
              </button>
            </div>
          </div>

          <div class="user-table-wrapper">
            <table class="user-table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>用户 ID</th>
                  <th>类型</th>
                  <th>方向</th>
                  <th>金额</th>
                  <th>余额</th>
                  <th>备注</th>
                  <th>关联 ID</th>
                  <th>时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="txLoading">
                  <td colspan="9" class="audit-loading">加载中...</td>
                </tr>
                <tr v-else-if="txList.length === 0">
                  <td colspan="9" class="audit-empty">暂无流水数据</td>
                </tr>
                <tr v-for="tx in txList" :key="tx.id">
                  <td>{{ tx.id }}</td>
                  <td>{{ tx.userId }}</td>
                  <td>{{ txTypeTextLabel(tx.type) }}</td>
                  <td>
                    <span class="dir-badge" :class="(tx.direction || '').toLowerCase()">
                      {{ tx.direction === 'IN' ? '收入' : '支出' }}
                    </span>
                  </td>
                  <td :class="tx.direction === 'IN' ? 'credits-cell' : 'spent-cell'">
                    {{ tx.direction === 'IN' ? '+' : '-' }}{{ Math.abs(tx.amount) }}
                  </td>
                  <td>{{ tx.balanceAfter }}</td>
                  <td class="audit-detail">{{ tx.remark || '-' }}</td>
                  <td class="audit-target">{{ tx.relatedId || '-' }}</td>
                  <td>{{ formatDate(tx.createdAt) }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="user-pagination">
            <span class="pagination-info">
              共 {{ txTotalElements }} 条，第 {{ txPage + 1 }} / {{ Math.max(1, txTotalPages) }} 页
            </span>
            <div class="pagination-btns">
              <button class="btn-page" :disabled="txPage === 0" @click="goToTxPage(0)">首页</button>
              <button class="btn-page" :disabled="txPage === 0" @click="goToTxPage(txPage - 1)">上一页</button>
              <button class="btn-page" :disabled="txPage >= txTotalPages - 1" @click="goToTxPage(txPage + 1)">下一页</button>
              <button class="btn-page" :disabled="txPage >= txTotalPages - 1" @click="goToTxPage(txTotalPages - 1)">末页</button>
            </div>
          </div>
        </div>

        <!-- ===== 积分管理：订单管理 ===== -->
        <div v-if="activeTab === 'credit-orders'" class="tab-panel">
          <div class="panel-title">
            <Icon name="file" :size="20" />
            <h2>订单管理</h2>
          </div>

          <!-- 筛选区 -->
          <div class="tx-filters">
            <div class="tx-filter-row">
              <input v-model="orderFilters.orderNo" type="text" placeholder="订单号精确搜索" class="tx-filter-input" />
              <input v-model="orderFilters.keyword" type="text" placeholder="用户关键字" class="tx-filter-input" />
              <div class="status-multi-select">
                <span class="status-multi-label">状态：</span>
                <label v-for="opt in orderStatusOptions" :key="opt.value" class="status-chip" :class="{ active: orderFilters.statusSelected.includes(opt.value) }">
                  <input type="checkbox" :value="opt.value" v-model="orderFilters.statusSelected" />
                  {{ opt.label }}
                </label>
              </div>
            </div>
            <div class="tx-filter-row">
              <input v-model="orderFilters.start" type="date" class="tx-filter-input" title="开始日期" />
              <input v-model="orderFilters.end" type="date" class="tx-filter-input" title="结束日期" />
              <input v-model="orderFilters.minPrice" type="number" step="0.01" placeholder="最小金额" class="tx-filter-input" />
              <input v-model="orderFilters.maxPrice" type="number" step="0.01" placeholder="最大金额" class="tx-filter-input" />
              <button class="btn-action promote" @click="searchOrders">搜索</button>
              <button class="btn-action" @click="resetOrderFilters">重置</button>
              <button class="btn-action promote" :disabled="ordersExporting" @click="exportOrders">
                {{ ordersExporting ? '导出中...' : '导出 JSON' }}
              </button>
              <button class="btn-action promote" @click="openManualModal">+ 补单</button>
            </div>
          </div>

          <div class="user-table-wrapper">
            <table class="user-table">
              <thead>
                <tr>
                  <th>订单号</th>
                  <th>用户 ID</th>
                  <th>套餐</th>
                  <th>金额</th>
                  <th>积分</th>
                  <th>状态</th>
                  <th>支付时间</th>
                  <th>到期</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="ordersLoading">
                  <td colspan="9" class="audit-loading">加载中...</td>
                </tr>
                <tr v-else-if="orders.length === 0">
                  <td colspan="9" class="audit-empty">暂无订单数据</td>
                </tr>
                <tr v-for="o in orders" :key="o.orderNo">
                  <td class="order-no-cell" @click="openOrderDetail(o.orderNo)" :title="o.orderNo">{{ o.orderNo }}</td>
                  <td>{{ o.userId }}</td>
                  <td>{{ planTierText(o.planTier) }}</td>
                  <td class="amount-cell">¥{{ Number(o.price || 0).toFixed(2) }}</td>
                  <td class="credits-cell">{{ o.creditAmount }}</td>
                  <td><span class="status-tag" :class="'status-' + o.status">{{ orderStatusText(o.status) }}</span></td>
                  <td>{{ formatDate(o.paidAt) }}</td>
                  <td>{{ formatDate(o.expiresAt) }}</td>
                  <td class="action-cell">
                    <button class="btn-link" @click="openOrderDetail(o.orderNo)">详情</button>
                    <button v-if="o.status === 'PENDING'" class="btn-link btn-danger" @click="openCancelModal(o.orderNo)">作废</button>
                    <button v-if="o.status === 'PAID'" class="btn-link btn-warn" @click="openRefundModal(o.orderNo)">退款</button>
                    <button class="btn-link" @click="exportSingleOrder(o.orderNo)">导出</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="user-pagination">
            <span class="pagination-info">
              共 {{ ordersTotalElements }} 条，第 {{ ordersPage + 1 }} / {{ Math.max(1, ordersTotalPages) }} 页
            </span>
            <div class="pagination-btns">
              <button class="btn-page" :disabled="ordersPage === 0" @click="goToOrdersPage(0)">首页</button>
              <button class="btn-page" :disabled="ordersPage === 0" @click="goToOrdersPage(ordersPage - 1)">上一页</button>
              <button class="btn-page" :disabled="ordersPage >= ordersTotalPages - 1" @click="goToOrdersPage(ordersPage + 1)">下一页</button>
              <button class="btn-page" :disabled="ordersPage >= ordersTotalPages - 1" @click="goToOrdersPage(ordersTotalPages - 1)">末页</button>
            </div>
          </div>
        </div>
      </main>
    </div>

    <!-- ===== 调整积分弹窗 ===== -->
    <div v-if="adjustModal.visible" class="modal-overlay" @click.self="closeAdjustModal">
      <div class="modal-content small-modal credit-modal">
        <div class="modal-header">
          <h3>调整积分</h3>
          <button class="btn-close" @click="closeAdjustModal">×</button>
        </div>
        <div class="modal-body">
          <div class="refund-order-info">
            <div><label>用户：</label><span>{{ adjustModal.username }}（{{ adjustModal.nickname || '-' }}）</span></div>
            <div><label>当前余额：</label><span>{{ adjustModal.balance }}</span></div>
          </div>
          <div class="form-group">
            <label>调整金额（正数增加、负数扣减）<span style="color:red">*</span></label>
            <input type="number" v-model.number="adjustModal.amount" placeholder="例如 100 或 -50" />
          </div>
          <div class="form-group">
            <label>调整原因 <span style="color:red">*</span></label>
            <textarea v-model="adjustModal.reason" rows="3" placeholder="请输入调账原因（审计日志可见）"></textarea>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="closeAdjustModal">取消</button>
          <button class="btn-save" :disabled="adjustModal.submitting" @click="submitAdjust">
            {{ adjustModal.submitting ? '提交中...' : '确认调整' }}
          </button>
        </div>
      </div>
    </div>

    <!-- ===== 补单弹窗 ===== -->
    <div v-if="manualModal.visible" class="modal-overlay" @click.self="closeManualModal">
      <div class="modal-content credit-modal manual-modal">
        <div class="modal-header">
          <h3>管理员补单</h3>
          <button class="btn-close" @click="closeManualModal">×</button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label>用户 ID <span style="color:red">*</span></label>
            <input type="number" v-model.number="manualModal.userId" placeholder="目标用户 ID" />
          </div>
          <div class="form-group">
            <label>套餐 <span style="color:red">*</span></label>
            <select v-model="manualModal.planCode" class="audit-action-select" style="width:100%">
              <option v-for="opt in orderPlanOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
          </div>
          <div class="form-grid-2">
            <div class="form-group">
              <label>自定义价格（分，可选）</label>
              <input type="number" v-model.number="manualModal.priceCents" placeholder="留空用套餐默认" />
            </div>
            <div class="form-group">
              <label>自定义积分（可选）</label>
              <input type="number" v-model.number="manualModal.pointsGranted" placeholder="留空用套餐默认" />
            </div>
          </div>
          <div class="form-grid-2">
            <div class="form-group">
              <label>时长（天，可选）</label>
              <input type="number" v-model.number="manualModal.durationDays" placeholder="留空用套餐默认" />
            </div>
            <div class="form-group">
              <label>自定义订单号（可选）</label>
              <input type="text" v-model="manualModal.orderNo" placeholder="留空自动生成" />
            </div>
          </div>
          <div class="form-group">
            <label>备注</label>
            <textarea v-model="manualModal.remark" rows="2" placeholder="可选"></textarea>
          </div>
          <p class="modal-tip">提交后将直接创建为 PAID 状态订单并发放积分。</p>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="closeManualModal">取消</button>
          <button class="btn-save" :disabled="manualModal.submitting" @click="submitManualCreate">
            {{ manualModal.submitting ? '提交中...' : '确认补单' }}
          </button>
        </div>
      </div>
    </div>

    <!-- ===== 作废弹窗 ===== -->
    <div v-if="cancelModal.visible" class="modal-overlay" @click.self="closeCancelModal">
      <div class="modal-content small-modal credit-modal">
        <div class="modal-header">
          <h3>作废订单</h3>
          <button class="btn-close" @click="closeCancelModal">×</button>
        </div>
        <div class="modal-body">
          <div class="refund-order-info">
            <div><label>订单号：</label><span>{{ cancelModal.orderNo }}</span></div>
          </div>
          <div class="form-group">
            <label>作废原因</label>
            <textarea v-model="cancelModal.reason" rows="3" placeholder="可选"></textarea>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="closeCancelModal">取消</button>
          <button class="btn-save btn-warn" :disabled="cancelModal.submitting" @click="submitCancel">
            {{ cancelModal.submitting ? '提交中...' : '确认作废' }}
          </button>
        </div>
      </div>
    </div>

    <!-- ===== 退款弹窗 ===== -->
    <div v-if="refundModal.visible" class="modal-overlay" @click.self="closeRefundModal">
      <div class="modal-content small-modal credit-modal">
        <div class="modal-header">
          <h3>订单退款</h3>
          <button class="btn-close" @click="closeRefundModal">×</button>
        </div>
        <div class="modal-body">
          <div class="refund-order-info">
            <div><label>订单号：</label><span>{{ refundModal.orderNo }}</span></div>
          </div>
          <div class="form-group">
            <label>退款比例（0~1，默认 1.0 全额）<span style="color:red">*</span></label>
            <input type="number" v-model.number="refundModal.refundRatio" step="0.01" min="0.01" max="1" />
          </div>
          <div class="form-group">
            <label>退款原因 <span style="color:red">*</span></label>
            <textarea v-model="refundModal.reason" rows="3" placeholder="请输入退款原因"></textarea>
          </div>
          <p class="modal-tip">将按比例扣减已发放积分。</p>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="closeRefundModal">取消</button>
          <button class="btn-save btn-warn" :disabled="refundModal.submitting" @click="submitRefund">
            {{ refundModal.submitting ? '提交中...' : '确认退款' }}
          </button>
        </div>
      </div>
    </div>

    <!-- ===== 订单详情抽屉 ===== -->
    <Transition name="drawer">
      <div v-if="detailDrawer.visible" class="drawer-overlay" @click.self="closeOrderDetail">
        <div class="drawer-container admin-drawer">
          <div class="drawer-header">
            <div class="drawer-title">
              <span>订单详情</span>
              <span class="drawer-order-no">{{ detailDrawer.orderNo }}</span>
              <span v-if="detailDrawer.data" class="status-tag" :class="'status-' + detailDrawer.data.status">
                {{ orderStatusText(detailDrawer.data.status) }}
              </span>
            </div>
            <button class="drawer-close" @click="closeOrderDetail">×</button>
          </div>
          <div class="drawer-body">
            <div v-if="detailDrawer.loading" class="audit-loading">加载中...</div>
            <template v-else-if="detailDrawer.data">
              <div class="detail-section">
                <h5>订单基础信息</h5>
                <div class="detail-grid">
                  <div class="detail-item"><label>订单号</label><span>{{ detailDrawer.data.orderNo }}</span></div>
                  <div class="detail-item"><label>用户 ID</label><span>{{ detailDrawer.data.userId }}</span></div>
                  <div class="detail-item"><label>套餐</label><span>{{ planTierText(detailDrawer.data.planTier) }}</span></div>
                  <div class="detail-item"><label>状态</label><span>{{ orderStatusText(detailDrawer.data.status) }}</span></div>
                  <div class="detail-item"><label>创建时间</label><span>{{ formatDate(detailDrawer.data.createdAt) }}</span></div>
                  <div class="detail-item"><label>更新时间</label><span>{{ formatDate(detailDrawer.data.updatedAt) }}</span></div>
                </div>
              </div>
              <div class="detail-section">
                <h5>金额与权益</h5>
                <div class="detail-grid">
                  <div class="detail-item"><label>订单金额</label><span class="amount-cell">¥{{ Number(detailDrawer.data.price || 0).toFixed(2) }}</span></div>
                  <div class="detail-item"><label>获得积分</label><span class="credits-cell">{{ detailDrawer.data.creditAmount }}</span></div>
                  <div class="detail-item"><label>时长</label><span>{{ detailDrawer.data.durationDays }} 天</span></div>
                  <div class="detail-item"><label>支付方式</label><span>{{ detailDrawer.data.paymentMethod || '-' }}</span></div>
                  <div class="detail-item"><label>支付时间</label><span>{{ formatDate(detailDrawer.data.paidAt) }}</span></div>
                  <div class="detail-item"><label>到期时间</label><span>{{ formatDate(detailDrawer.data.expiresAt) }}</span></div>
                </div>
              </div>
              <div v-if="detailDrawer.data.status === 'REFUNDED' || detailDrawer.data.refundReason || detailDrawer.data.refundedAt" class="detail-section">
                <h5>退款信息</h5>
                <div class="detail-grid">
                  <div class="detail-item"><label>退款时间</label><span>{{ formatDate(detailDrawer.data.refundedAt) }}</span></div>
                  <div class="detail-item"><label>退款金额</label><span class="amount-cell">¥{{ Number(detailDrawer.data.refundAmount || 0).toFixed(2) }}</span></div>
                  <div class="detail-item"><label>退款原因</label><span>{{ detailDrawer.data.refundReason || '-' }}</span></div>
                  <div class="detail-item"><label>退款管理员 ID</label><span>{{ detailDrawer.data.refundAdminUserId || '-' }}</span></div>
                </div>
              </div>
              <div class="detail-section">
                <h5>订单时间轴</h5>
                <div class="timeline">
                  <div v-for="(evt, i) in orderTimeline" :key="i" class="timeline-item" :class="{ done: evt.done, last: i === orderTimeline.length - 1 }">
                    <div class="timeline-dot"></div>
                    <div class="timeline-content">
                      <div class="timeline-title">{{ evt.title }} <span class="timeline-op">（{{ evt.operator }}）</span></div>
                      <div class="timeline-time">{{ formatDate(evt.time) }}</div>
                    </div>
                  </div>
                </div>
              </div>
              <div v-if="detailDrawer.relatedTransactions.length > 0" class="detail-section">
                <h5>关联流水</h5>
                <div class="related-list">
                  <div v-for="tx in detailDrawer.relatedTransactions" :key="tx.id" class="related-item">
                    <div class="related-left">
                      <span class="related-type" :class="tx.type">{{ txTypeTextLabel(tx.type) }}</span>
                      <span class="related-desc">{{ tx.remark || '-' }}</span>
                    </div>
                    <div class="related-right">
                      <span class="related-amount" :class="tx.direction === 'IN' ? 'add' : 'sub'">
                        {{ tx.direction === 'IN' ? '+' : '-' }}{{ Math.abs(tx.amount) }}
                      </span>
                      <span class="related-time">{{ formatDate(tx.createdAt) }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </template>
          </div>
          <div v-if="detailDrawer.data" class="drawer-footer">
            <button v-if="detailDrawer.data.status === 'PAID'" class="btn-save btn-warn" style="width:100%" @click="openRefundModal(detailDrawer.data.orderNo)">退款</button>
          </div>
        </div>
      </div>
    </Transition>

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
import { formatFileSize, logout as apiLogout } from '../services/api';
import { useTheme } from '../composables/useTheme';
import { useDashboardData } from '../composables/useDashboardData';
import { useComponentControl } from '../composables/useComponentControl';
import { useUserManagement } from '../composables/useUserManagement';
import { useMediaManager } from '../composables/useMediaManager';
import { useSystemLog } from '../composables/useSystemLog';
import { useConfigManagement } from '../composables/useConfigManagement';
import { useMaintenance } from '../composables/useMaintenance';
import { useAdminCreditsRule } from '../composables/useAdminCreditsRule';
import { useAdminUserCredits } from '../composables/useAdminUserCredits';
import {
  useAdminTransactions,
  TX_TYPE_OPTIONS as TX_TYPE_OPTS,
  TX_DIRECTION_OPTIONS as TX_DIR_OPTS,
  txTypeText as txTypeTextLabel,
} from '../composables/useAdminTransactions';
import {
  useAdminOrders,
  ORDER_STATUS_OPTIONS as ORDER_STATUS_OPTS,
  ORDER_PLAN_OPTIONS as ORDER_PLAN_OPTS,
  orderStatusText as orderStatusLabel,
  planTierText as planTierLabel,
} from '../composables/useAdminOrders';

export default {
  name: 'AdminView',
  components: { Icon },
  setup() {
    const router = useRouter();
    const { theme: currentTheme } = useTheme();
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
    // 积分管理 4 个子模块
    const adminRule = useAdminCreditsRule({ showSystemMsg });
    const adminUserCredits = useAdminUserCredits({ showSystemMsg });
    const adminTx = useAdminTransactions({ showSystemMsg });
    const adminOrders = useAdminOrders({ showSystemMsg });

    const navItems = [
      { key: 'dashboard', icon: 'dashboard', label: '数据概览' },
      { key: 'users', icon: 'group', label: '用户管理' },
      { key: 'components', icon: 'settings', label: '组件控制' },
      { key: 'media', icon: 'image', label: '媒体管理' },
      { key: 'log', icon: 'file', label: '系统日志' },
      { key: 'config', icon: 'config', label: '配置管理' },
      { key: 'maintenance', icon: 'backup', label: '数据维护' },
      // 积分管理分组
      { key: 'group-credits', label: '积分管理', isGroup: true },
      { key: 'credit-rule', icon: 'settings', label: '规则配置' },
      { key: 'credit-users', icon: 'user', label: '用户积分' },
      { key: 'credit-transactions', icon: 'file', label: '积分流水' },
      { key: 'credit-orders', icon: 'file', label: '订单管理' },
    ];

    // 切换 Tab 时按需懒加载积分管理数据
    const setActiveTab = (key) => {
      activeTab.value = key;
      if (key === 'credit-rule') {
        adminRule.loadRule();
      } else if (key === 'credit-users') {
        adminUserCredits.loadUserCredits(0);
      } else if (key === 'credit-transactions') {
        adminTx.loadTransactions(0);
      } else if (key === 'credit-orders') {
        adminOrders.loadOrders(0);
      }
    };

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
    const logout = async () => {
      // 通知后端停止插件 + 注销 JWT
      try {
        await apiLogout();
      } catch (e) {
        console.warn('后端登出失败（忽略）:', e);
      }
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
      window.addEventListener('keydown', media.onPreviewKeydown);
    });
    onUnmounted(() => {
      componentCtrl.stopPolling();
      userMgmt.cleanup();
      adminUserCredits.cleanup();
      window.removeEventListener('keydown', media.onPreviewKeydown);
    });

    return {
      currentTheme, activeTab, navItems, systemMessage, systemMessageType,
      isMediaCollapsed, formatDate, formatFileSize, goHome, logout, setActiveTab,
      // Dashboard
      stats: dashboard.stats, messageTrend: dashboard.messageTrend,
      trendChartOption: dashboard.trendChartOption,
      groupRankingOption: dashboard.groupRankingOption,
      qqRankingOption: dashboard.qqRankingOption,
      distributionChartOption: dashboard.distributionChartOption,
      hourlyChartOption: dashboard.hourlyChartOption,
      aiTrendChartOption: dashboard.aiTrendChartOption,
      trendDays: dashboard.trendDays, trendInterval: dashboard.trendInterval,
      groupRanking: dashboard.groupRanking, qqRanking: dashboard.qqRanking,
      messageTypeDistribution: dashboard.messageTypeDistribution,
      diskUsage: dashboard.diskUsage,
      setTrendDays: dashboard.setTrendDays,
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
      // ===== 积分管理：规则配置 =====
      ruleLoading: adminRule.ruleLoading, ruleSaving: adminRule.ruleSaving,
      ruleForm: adminRule.form, planMeta: adminRule.planMeta,
      loadRule: adminRule.loadRule, saveRule: adminRule.saveRule,
      // ===== 积分管理：用户积分 =====
      ucList: adminUserCredits.ucList, ucLoading: adminUserCredits.ucLoading,
      ucKeyword: adminUserCredits.ucKeyword, ucPage: adminUserCredits.ucPage,
      ucSize: adminUserCredits.ucSize, ucTotalElements: adminUserCredits.ucTotalElements,
      ucTotalPages: adminUserCredits.ucTotalPages,
      adjustModal: adminUserCredits.adjustModal,
      loadUserCredits: adminUserCredits.loadUserCredits, onUcSearchInput: adminUserCredits.onUcSearchInput,
      goToUcPage: adminUserCredits.goToUcPage,
      openAdjustModal: adminUserCredits.openAdjustModal, closeAdjustModal: adminUserCredits.closeAdjustModal,
      submitAdjust: adminUserCredits.submitAdjust,
      // ===== 积分管理：积分流水 =====
      txList: adminTx.txList, txLoading: adminTx.txLoading,
      txPage: adminTx.txPage, txSize: adminTx.txSize,
      txTotalElements: adminTx.txTotalElements, txTotalPages: adminTx.txTotalPages,
      txExporting: adminTx.txExporting,
      txFilters: adminTx.filters, txSummary: adminTx.summary,
      txTypeOptions: TX_TYPE_OPTS, txDirectionOptions: TX_DIR_OPTS,
      txTypeTextLabel: txTypeTextLabel,
      loadTransactions: adminTx.loadTransactions, searchTransactions: adminTx.searchTransactions,
      resetTxFilters: adminTx.resetFilters, goToTxPage: adminTx.goToTxPage,
      exportTransactions: adminTx.exportTransactions,
      // ===== 积分管理：订单管理 =====
      orders: adminOrders.orders, ordersLoading: adminOrders.ordersLoading,
      ordersPage: adminOrders.ordersPage, ordersSize: adminOrders.ordersSize,
      ordersTotalElements: adminOrders.ordersTotalElements, ordersTotalPages: adminOrders.ordersTotalPages,
      ordersExporting: adminOrders.ordersExporting,
      orderFilters: adminOrders.filters,
      orderStatusOptions: ORDER_STATUS_OPTS, orderPlanOptions: ORDER_PLAN_OPTS,
      orderStatusText: orderStatusLabel, planTierText: planTierLabel,
      manualModal: adminOrders.manualModal, refundModal: adminOrders.refundModal,
      cancelModal: adminOrders.cancelModal,
      detailDrawer: adminOrders.detailDrawer, orderTimeline: adminOrders.timelineEvents,
      loadOrders: adminOrders.loadOrders, searchOrders: adminOrders.searchOrders,
      resetOrderFilters: adminOrders.resetOrderFilters, goToOrdersPage: adminOrders.goToOrdersPage,
      openManualModal: adminOrders.openManualModal, closeManualModal: adminOrders.closeManualModal,
      submitManualCreate: adminOrders.submitManualCreate,
      openCancelModal: adminOrders.openCancelModal, closeCancelModal: adminOrders.closeCancelModal,
      submitCancel: adminOrders.submitCancel,
      openRefundModal: adminOrders.openRefundModal, closeRefundModal: adminOrders.closeRefundModal,
      submitRefund: adminOrders.submitRefund,
      exportSingleOrder: adminOrders.exportSingleOrder, exportOrders: adminOrders.exportOrders,
      openOrderDetail: adminOrders.openOrderDetail, closeOrderDetail: adminOrders.closeOrderDetail,
    };
  },
};
</script>

<style scoped>
.admin-view {
  height: 100vh;
  background: var(--bg-primary, #f5f5f5);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.admin-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 24px;
  height: 56px;
  background: var(--sidebar-bg, #2c3e50);
  color: #fff;
  border-bottom: 1px solid var(--border-color, #34495e);
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
  min-height: 0;
  overflow: hidden;
}

.admin-sidebar {
  width: 200px;
  height: 100%;
  background: var(--sidebar-bg, #2c3e50);
  border-right: 1px solid var(--border-color, #34495e);
  padding: 16px 0;
  overflow-y: auto;
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
  color: var(--sidebar-text, #ecf0f1);
  transition: all 0.2s;
}

.nav-item:hover {
  background: rgba(255,255,255,0.1);
  color: #fff;
}

.nav-item.active {
  background: var(--accent-color, #3498db);
  color: #fff;
  font-weight: 600;
}

.nav-group-title {
  padding: 8px 14px 4px;
  font-size: 18px;
  font-weight: 600;
  color: #090f16;
}

.admin-main {
  flex: 1;
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
  background-color: var(--bg-primary, #f5f5f5);
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
  color: var(--text-primary, #333);
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
  background: var(--card-bg, #fff);
  border-radius: 8px;
  border: 1px solid var(--border-color, #e8e8e8);
  box-shadow: 0 2px 8px var(--card-shadow, rgba(0,0,0,0.08));
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
.stat-icon.amber { background: #fff8e1; color: #ff8f00; }

.bar-chart-echarts {
  height: 240px;
  width: 100%;
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

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary, #333);
}

.stat-label {
  font-size: 13px;
  color: var(--text-secondary, #666);
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
  background: var(--card-bg, #fff);
  border-radius: 8px;
  border: 1px solid var(--border-color, #e8e8e8);
  padding: 18px;
  box-shadow: 0 2px 8px var(--card-shadow, rgba(0,0,0,0.08));
}

.chart-card h4 {
  margin: 0 0 16px 0;
  font-size: 14px;
  color: var(--text-primary, #333);
}

.chart-large {
  grid-column: 1 / -1;
}

.line-chart-echarts {
  height: 280px;
  width: 100%;
}

.ranking-chart {
  height: 220px;
  width: 100%;
}

.pie-chart-echarts {
  height: 320px;
  width: 100%;
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
  border: 1px solid var(--border-color, #e0e0e0);
  background: var(--card-bg, #fff);
  border-radius: 4px;
  font-size: 12px;
  color: var(--text-secondary, #666);
  cursor: pointer;
  transition: all 0.2s;
}

.chart-btn:hover {
  border-color: var(--accent-color, #3498db);
  color: var(--accent-color, #3498db);
}

.chart-btn.active {
  background: var(--accent-color, #3498db);
  color: #fff;
  border-color: var(--accent-color, #3498db);
}

/* 分布图卡片 */
.distribution-card {
  margin-bottom: 20px;
}

/* 用户表格 */
.user-search-bar {
  margin-bottom: 16px;
}

.user-search-input {
  width: 320px;
  max-width: 100%;
  padding: 8px 14px;
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 6px;
  font-size: 13px;
  outline: none;
  transition: border-color 0.2s;
  box-sizing: border-box;
  background: var(--input-bg, #fff);
}

.user-search-input:focus {
  border-color: var(--accent-color, #3498db);
  box-shadow: 0 0 0 2px rgba(52, 152, 219, 0.15);
}

.user-table-wrapper {
  background: var(--card-bg, #fff);
  border-radius: 8px;
  border: 1px solid var(--border-color, #e8e8e8);
  overflow-x: auto;
}

.user-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.user-table th {
  background: var(--bg-tertiary, #f8f9fa);
  padding: 12px 16px;
  text-align: left;
  font-weight: 600;
  color: var(--text-secondary, #666);
  border-bottom: 1px solid var(--border-color, #e8e8e8);
  white-space: nowrap;
}

.user-table td {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-color, #f0f0f0);
  color: var(--text-primary, #333);
}

.user-table tbody tr:hover {
  background: var(--bg-tertiary, #f8f9fa);
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
  color: var(--text-secondary, #666);
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
  border: 1px solid var(--border-color, #e0e0e0);
  background: var(--card-bg, #fff);
  border-radius: 4px;
  font-size: 12px;
  color: var(--text-primary, #333);
  cursor: pointer;
  transition: all 0.2s;
}

.btn-page:hover:not(:disabled) {
  border-color: var(--accent-color, #3498db);
  color: var(--accent-color, #3498db);
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
  background: var(--card-bg, #fff);
  border: 1px solid var(--border-color, #e8e8e8);
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
  color: var(--text-primary, #333);
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
  background: var(--card-bg, #fff);
  border-radius: 8px;
  border: 1px solid var(--border-color, #e8e8e8);
  padding: 18px;
}

.section-card h4 {
  margin: 0 0 16px 0;
  font-size: 14px;
  color: var(--text-primary, #333);
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
  border: 1px solid var(--border-color, #e0e0e0);
  background: var(--card-bg, #fff);
  border-radius: 4px;
  font-size: 12px;
  color: var(--text-secondary, #666);
  cursor: pointer;
  transition: all 0.2s;
}

.collapse-toggle:hover {
  border-color: var(--accent-color, #3498db);
  color: var(--accent-color, #3498db);
}

.media-manager-card {
  background: var(--card-bg, #fff);
  border: 1px solid var(--border-color, #e8e8e8);
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
  border-bottom: 1px solid var(--border-color, #f0f0f0);
  text-align: left;
  color: var(--text-primary, #333);
}

.media-table th {
  font-weight: 600;
  color: var(--text-primary, #333);
  background: var(--bg-tertiary, #fafafa);
}

.media-table tbody tr:hover {
  background: var(--bg-tertiary, #fafafa);
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
  border: 1px solid var(--border-color, #e0e0e0);
  background: var(--card-bg, #fff);
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  color: var(--text-secondary, #666);
  transition: all 0.2s;
}

.media-pagination button:hover:not(:disabled) {
  border-color: var(--accent-color, #3498db);
  color: var(--accent-color, #3498db);
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
  color: var(--accent-color, #3498db);
  background: var(--bg-tertiary, #f0f9ff);
  border: 1px solid var(--border-color, #b7d8f7);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-preview:hover:not(:disabled) {
  background: var(--bg-tertiary, #e0f2ff);
  border-color: var(--accent-color, #3498db);
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
  background: var(--card-bg, #fff);
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
  border: 1px solid var(--border-color, #e0e0e0);
  background: var(--card-bg, #fff);
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  color: var(--text-primary, #333);
  transition: all 0.2s;
}

.preview-nav-btn:hover:not(:disabled) {
  border-color: var(--accent-color, #3498db);
  color: var(--accent-color, #3498db);
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
  background: var(--card-bg, #fff);
  border: 1px solid var(--border-color, #e8e8e8);
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
  color: var(--text-primary, #333);
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
  color: var(--text-primary, #333);
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
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 4px;
  font-size: 13px;
  color: var(--text-primary, #333);
  outline: none;
  transition: border-color 0.2s;
}

.config-input:focus {
  border-color: var(--accent-color, #3498db);
}

.btn-toggle-secret {
  padding: 4px 8px;
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 4px;
  background: var(--card-bg, #fff);
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  transition: border-color 0.2s;
}

.btn-toggle-secret:hover {
  border-color: var(--accent-color, #3498db);
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
  background: var(--bg-tertiary, #f8f9fa);
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
  color: var(--text-primary, #333);
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
  color: var(--text-secondary, #666);
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
}
.log-sub-tab:hover {
  color: var(--text-primary, #333);
}
.log-sub-tab.active {
  color: var(--text-primary, #333);
  border-bottom-color: var(--accent-color, #3498db);
  font-weight: 600;
}
.log-section {
  background: var(--card-bg, #fff);
  border-radius: 8px;
  padding: 16px;
  box-shadow: 0 1px 3px var(--card-shadow, rgba(0, 0, 0, 0.06));
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
  background: var(--bg-tertiary, #f5f6fa);
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  color: var(--text-secondary, #666);
  transition: all 0.2s;
}
.log-level-btn:hover {
  background: var(--bg-tertiary, #e9ecef);
}
.log-level-btn.active {
  color: #fff;
  border-color: var(--accent-color, #3498db);
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
  border: 1px solid var(--border-color, #e0e0e0);
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
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 6px;
}
.audit-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.audit-table thead {
  background: var(--bg-tertiary, #f5f6fa);
}
.audit-table th {
  padding: 10px 12px;
  text-align: left;
  font-weight: 600;
  color: var(--text-primary, #333);
  border-bottom: 1px solid var(--border-color, #e0e0e0);
  white-space: nowrap;
}
.audit-table td {
  padding: 8px 12px;
  border-bottom: 1px solid var(--border-color, #f0f0f0);
  color: var(--text-primary, #333);
  vertical-align: top;
}
.audit-table tbody tr:hover {
  background: var(--bg-tertiary, #fafbfc);
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

/* ===== 积分管理：规则配置 ===== */
.credit-rule-wrap {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.rule-form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
  margin-top: 12px;
}
.rule-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.rule-item label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary, #333);
}
.rule-item small {
  font-size: 11px;
  color: #999;
}
.rule-item.toggle-item {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  background: var(--bg-tertiary, #fafbfc);
  border-radius: 6px;
}
.rule-item.toggle-item label:first-child {
  flex: 1;
}
.rule-switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-size: 12px;
  color: #666;
}
.rule-switch input[type='checkbox'] {
  width: 16px;
  height: 16px;
  cursor: pointer;
}
.plans-edit-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
  margin-top: 12px;
}
.plan-edit-card {
  padding: 14px;
  background: var(--bg-tertiary, #fafbfc);
  border: 1px solid var(--border-color, #e8e8e8);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.plan-edit-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #333);
}
.plan-edit-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.plan-edit-row label {
  font-size: 12px;
  color: #666;
}
.plan-duration-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
}
.plan-duration-row label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary, #333);
}
.plan-duration-input {
  width: 120px;
}
.rule-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 8px;
}
.rule-save-btn {
  min-width: 160px;
}

/* ===== 积分管理：流水汇总 + 筛选 ===== */
.tx-summary-bar {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.tx-summary-card {
  padding: 14px 16px;
  border-radius: 8px;
  background: var(--card-bg, #fff);
  border: 1px solid var(--border-color, #e8e8e8);
}
.tx-summary-card.earned { border-left: 3px solid #4caf50; }
.tx-summary-card.spent { border-left: 3px solid #f44336; }
.tx-summary-card.net { border-left: 3px solid #2196f3; }
.tx-summary-card.count { border-left: 3px solid #ff9800; }
.tx-summary-label {
  font-size: 12px;
  color: #888;
  margin-bottom: 4px;
}
.tx-summary-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary, #333);
}
.tx-filters {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  background: var(--card-bg, #fff);
  border: 1px solid var(--border-color, #e8e8e8);
  border-radius: 8px;
  margin-bottom: 16px;
}
.tx-filter-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.tx-filter-input {
  padding: 6px 10px;
  border: 1px solid var(--border-color, #d9d9d9);
  border-radius: 4px;
  font-size: 13px;
  min-width: 140px;
  background: var(--card-bg, #fff);
  color: var(--text-primary, #333);
}
.tx-filter-input:focus {
  outline: none;
  border-color: var(--accent-color, #3498db);
}
.status-multi-select {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.status-multi-label {
  font-size: 13px;
  color: #666;
}
.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border: 1px solid var(--border-color, #d9d9d9);
  border-radius: 12px;
  font-size: 12px;
  cursor: pointer;
  color: #666;
  user-select: none;
  transition: all 0.2s;
}
.status-chip input[type='checkbox'] {
  width: 13px;
  height: 13px;
  cursor: pointer;
}
.status-chip.active {
  background: var(--accent-color, #3498db);
  color: #fff;
  border-color: var(--accent-color, #3498db);
}

/* ===== 积分管理：徽章 + 单元格 ===== */
.tier-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
}
.tier-badge.tier-free { background: #eceff1; color: #607d8b; }
.tier-badge.tier-lite { background: #e3f2fd; color: #1976d2; }
.tier-badge.tier-pro { background: #f3e5f5; color: #7b1fa2; }
.tier-badge.tier-proplus { background: #fff3e0; color: #f57c00; }
.tier-badge.tier-ultra { background: #ffebee; color: #c62828; }
.dir-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
}
.dir-badge.in { background: #e8f5e9; color: #2e7d32; }
.dir-badge.out { background: #ffebee; color: #c62828; }
.credits-cell {
  color: #2e7d32;
  font-weight: 600;
}
.spent-cell {
  color: #c62828;
  font-weight: 600;
}
.amount-cell {
  color: #1976d2;
  font-weight: 600;
}
.order-no-cell {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: var(--accent-color, #3498db);
  cursor: pointer;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.order-no-cell:hover {
  text-decoration: underline;
}
.action-cell {
  white-space: nowrap;
}
.status-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
}
.status-tag.status-PENDING { background: #fff8e1; color: #ff8f00; }
.status-tag.status-PAID { background: #e8f5e9; color: #2e7d32; }
.status-tag.status-REFUNDED { background: #f3e5f5; color: #7b1fa2; }
.status-tag.status-CANCELLED { background: #eceff1; color: #607d8b; }
.status-tag.status-EXPIRED { background: #ffebee; color: #c62828; }

/* ===== 通用链接按钮 ===== */
.btn-link {
  background: none;
  border: none;
  color: var(--accent-color, #3498db);
  cursor: pointer;
  font-size: 12px;
  padding: 2px 6px;
  border-radius: 3px;
  transition: background 0.2s;
}
.btn-link:hover {
  background: rgba(52, 152, 219, 0.1);
}
.btn-link.btn-danger { color: #c62828; }
.btn-link.btn-danger:hover { background: rgba(198, 40, 40, 0.1); }
.btn-link.btn-warn { color: #ef6c00; }
.btn-link.btn-warn:hover { background: rgba(239, 108, 0, 0.1); }

/* ===== 模态框（积分管理复用） ===== */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.modal-content {
  background: var(--card-bg, #fff);
  border-radius: 8px;
  width: 520px;
  max-width: 92vw;
  max-height: 88vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
}
.modal-content.small-modal {
  width: 420px;
}
.modal-content.credit-modal {
  width: 560px;
}
.modal-content.manual-modal {
  width: 620px;
}
.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 20px;
  border-bottom: 1px solid var(--border-color, #e8e8e8);
}
.modal-header h3 {
  margin: 0;
  font-size: 16px;
  color: var(--text-primary, #333);
}
.btn-close {
  background: none;
  border: none;
  font-size: 22px;
  line-height: 1;
  color: #999;
  cursor: pointer;
  padding: 0 4px;
}
.btn-close:hover {
  color: #333;
}
.modal-body {
  padding: 20px;
  overflow-y: auto;
  flex: 1;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid var(--border-color, #e8e8e8);
}
.btn-cancel {
  padding: 8px 18px;
  border: 1px solid var(--border-color, #d9d9d9);
  background: var(--card-bg, #fff);
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  color: var(--text-primary, #333);
}
.btn-cancel:hover {
  background: var(--bg-tertiary, #fafbfc);
}
.btn-save {
  padding: 8px 18px;
  border: none;
  background: var(--accent-color, #3498db);
  color: #fff;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}
.btn-save:hover:not(:disabled) {
  opacity: 0.9;
}
.btn-save:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-save.btn-warn {
  background: #ef6c00;
}
.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
}
.form-group label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary, #333);
}
.form-group input,
.form-group textarea,
.form-group select {
  padding: 8px 10px;
  border: 1px solid var(--border-color, #d9d9d9);
  border-radius: 4px;
  font-size: 13px;
  background: var(--card-bg, #fff);
  color: var(--text-primary, #333);
}
.form-group input:focus,
.form-group textarea:focus,
.form-group select:focus {
  outline: none;
  border-color: var(--accent-color, #3498db);
}
.form-grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.modal-tip {
  margin: 8px 0 0;
  padding: 8px 12px;
  background: #fff8e1;
  border-radius: 4px;
  font-size: 12px;
  color: #ff8f00;
}
.refund-order-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px;
  background: var(--bg-tertiary, #fafbfc);
  border-radius: 6px;
  margin-bottom: 14px;
}
.refund-order-info label {
  font-weight: 600;
  color: #666;
  margin-right: 6px;
}
.refund-order-info span {
  color: var(--text-primary, #333);
}

/* ===== 订单详情抽屉 ===== */
.drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  justify-content: flex-end;
  z-index: 1000;
}
.drawer-container {
  width: 560px;
  max-width: 92vw;
  height: 100%;
  background: var(--card-bg, #fff);
  display: flex;
  flex-direction: column;
  box-shadow: -4px 0 16px rgba(0, 0, 0, 0.15);
}
.drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 20px;
  border-bottom: 1px solid var(--border-color, #e8e8e8);
}
.drawer-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary, #333);
}
.drawer-order-no {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #888;
  font-weight: 400;
}
.drawer-close {
  background: none;
  border: none;
  font-size: 22px;
  color: #999;
  cursor: pointer;
}
.drawer-close:hover {
  color: #333;
}
.drawer-body {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}
.detail-section {
  margin-bottom: 22px;
}
.detail-section h5 {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #333);
  padding-bottom: 6px;
  border-bottom: 1px solid var(--border-color, #f0f0f0);
}
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 16px;
}
.detail-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.detail-item label {
  font-size: 11px;
  color: #999;
}
.detail-item span {
  font-size: 13px;
  color: var(--text-primary, #333);
}

/* ===== 时间轴 ===== */
.timeline {
  position: relative;
  padding-left: 18px;
}
.timeline::before {
  content: '';
  position: absolute;
  left: 5px;
  top: 4px;
  bottom: 4px;
  width: 2px;
  background: var(--border-color, #e0e0e0);
}
.timeline-item {
  position: relative;
  padding-bottom: 16px;
}
.timeline-item.last {
  padding-bottom: 0;
}
.timeline-dot {
  position: absolute;
  left: -16px;
  top: 4px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #bbb;
  border: 2px solid var(--card-bg, #fff);
}
.timeline-item.done .timeline-dot {
  background: var(--accent-color, #3498db);
}
.timeline-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary, #333);
}
.timeline-op {
  font-size: 11px;
  color: #999;
  font-weight: 400;
}
.timeline-time {
  font-size: 11px;
  color: #aaa;
  margin-top: 2px;
}

/* ===== 关联流水 ===== */
.related-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.related-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  background: var(--bg-tertiary, #fafbfc);
  border-radius: 6px;
}
.related-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}
.related-type {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
  background: #e3f2fd;
  color: #1976d2;
  white-space: nowrap;
}
.related-desc {
  font-size: 12px;
  color: #666;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.related-right {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  white-space: nowrap;
}
.related-amount {
  font-weight: 600;
}
.related-amount.add { color: #2e7d32; }
.related-amount.sub { color: #c62828; }
.related-time {
  color: #aaa;
  font-size: 11px;
}
.drawer-footer {
  padding: 14px 20px;
  border-top: 1px solid var(--border-color, #e8e8e8);
}

/* 抽屉过渡动画 */
.drawer-enter-active,
.drawer-leave-active {
  transition: opacity 0.25s ease;
}
.drawer-enter-active .drawer-container,
.drawer-leave-active .drawer-container {
  transition: transform 0.25s ease;
}
.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
}
.drawer-enter-from .drawer-container,
.drawer-leave-to .drawer-container {
  transform: translateX(100%);
}
</style>
