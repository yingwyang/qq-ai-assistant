<template>
  <div class="user-center">
    <header class="user-center-header">
      <div class="header-brand">
        <Icon name="user" :size="24" />
        <h1>用户中心</h1>
      </div>
      <div class="header-actions">
        <button class="btn-home" @click="goHome">
          <Icon name="home" :size="16" /> 返回首页
        </button>
      </div>
    </header>

    <div class="user-center-layout">
      <aside class="user-center-sidebar">
        <nav class="user-center-nav">
          <div
            v-for="(item, index) in navItems"
            :key="`nav-${item.key}-${index}`"
            class="nav-item"
            :class="{ active: activeTab === item.key }"
            @click="handleNavClick(item.key)"
            @mousedown.stop
          >
            <Icon :name="item.icon" :size="18" />
            <span>{{ item.label }}</span>
          </div>
        </nav>
      </aside>

      <main class="user-center-main">
        <div v-if="activeTab === 'napcat'" class="tab-panel">
          <div class="panel-title">
            <Icon name="settings" :size="20" />
            <h2>NapCat 管理</h2>
          </div>

          <div class="napcat-layout">
            <div class="napcat-intro-card">
              <h4>本系统 NapCat 功能</h4>
              <div class="intro-content">
                <p>本系统通过 NapCat 与 QQ 打通，实现以下能力：</p>
                <ul>
                  <li><strong>消息接入</strong> - 接收群聊和私聊消息，存入消息库供查看与管理</li>
                  <li><strong>AI 回复</strong> - 对接 AstrBot，对消息进行智能分析与自动回复</li>
                  <li><strong>多媒体管理</strong> - 接收的图片、语音、视频等文件自动归档，支持在线预览</li>
                  <li><strong>Webhook 推送</strong> - 支持将消息实时推送到外部系统</li>
                </ul>
                <p class="intro-tip">请先启动 NapCat，再使用 QQ 扫码登录，即可开始接收消息。</p>
              </div>
            </div>

            <div class="napcat-right">
              <div class="section-card napcat-login-card">
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

              <div class="component-card napcat-control-card">
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
            </div>
          </div>
        </div>

        <div v-if="activeTab === 'media'" class="tab-panel">
          <div class="panel-title">
            <Icon name="image" :size="20" />
            <h2>媒体文件管理</h2>
          </div>
          <div class="section-card">
            <div class="section-card-header">
              <h4>我的媒体文件</h4>
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
                </div>
              </div>
            </div>
          </div>
        </div>

        <div v-if="activeTab === 'profile'" class="tab-panel">
          <div class="panel-title">
            <Icon name="user" :size="20" />
            <h2>个人信息</h2>
            <button class="btn-edit-profile" @click="openEditModal">
              <Icon name="edit" :size="14" />
              编辑资料
            </button>
          </div>
          <div class="section-card profile-card">
            <div class="profile-avatar-section">
              <div class="profile-avatar">
                <img :src="userAvatarUrl" alt="avatar" @error="handleAvatarError" />
              </div>
              <div class="profile-info">
                <h3>{{ userInfo?.nickname || userInfo?.username }}</h3>
                <p class="profile-role">{{ userInfo?.role === 'ADMIN' ? '管理员' : '普通用户' }}</p>
              </div>
            </div>
            <div class="profile-details">
              <div class="detail-row">
                <label>用户名</label>
                <span>{{ userInfo?.username }}</span>
              </div>
              <div class="detail-row">
                <label>邮箱</label>
                <span>{{ userInfo?.email || '-' }}</span>
              </div>
              <div class="detail-row">
                <label>注册时间</label>
                <span>{{ formatDate(userInfo?.createdAt) }}</span>
              </div>
              <div class="detail-row">
                <label>最后登录</label>
                <span>{{ formatDate(userInfo?.lastLoginTime) }}</span>
              </div>
            </div>
          </div>

          <div v-if="showEditModal" class="modal-overlay" @click.self="showEditModal = false">
            <div class="modal-content">
              <div class="modal-header">
                <h3>编辑资料</h3>
                <button class="btn-close" @click="showEditModal = false">
                  <Icon name="x" :size="16" />
                </button>
              </div>
              <div class="modal-body">
                <div class="form-group">
                  <label>昵称</label>
                  <input v-model="editForm.nickname" type="text" placeholder="请输入昵称" />
                </div>
                <div class="form-group">
                  <label>邮箱</label>
                  <input v-model="editForm.email" type="email" placeholder="请输入邮箱" />
                  <span v-if="editForm.email && !isValidEmail(editForm.email)" class="error-message">邮箱格式不正确</span>
                </div>
              </div>
              <div class="modal-footer">
                <button class="btn-cancel" @click="showEditModal = false">取消</button>
                <button class="btn-save" @click="saveProfile" :disabled="!isEditFormValid || isSaving">
                  {{ isSaving ? '保存中...' : '保存' }}
                </button>
              </div>
            </div>
          </div>
        </div>

        <div v-if="activeTab === 'manual'" class="tab-panel manual-panel">
          <div class="panel-title">
            <Icon name="book" :size="20" />
            <h2>用户手册</h2>
          </div>
          <div class="manual-layout">
            <aside class="manual-sidebar">
              <div class="manual-sidebar-header">
                <Icon name="list" :size="16" />
                <span>目录</span>
              </div>
              <nav class="manual-nav">
                <div
                  v-for="(section, index) in manualSections"
                  :key="section.id"
                  class="manual-nav-item"
                  :class="{ active: activeSection === section.id }"
                  @click="scrollToSection(section.id)"
                >
                  <span class="manual-nav-num">{{ index + 1 }}</span>
                  <span class="manual-nav-label">{{ section.title }}</span>
                </div>
              </nav>
            </aside>
            <main class="manual-main">
              <div class="manual-content">
                <div id="section-overview" class="manual-section" :class="{ active: activeSection === 'overview' }">
                  <div class="manual-section-header">
                    <div class="manual-section-icon">
                      <Icon name="app" :size="24" />
                    </div>
                    <div class="manual-section-title">
                      <h3>系统概述</h3>
                      <p>了解本系统的核心功能与架构</p>
                    </div>
                  </div>
                  <div class="manual-body">
                    <p>本系统是一个基于 QQ 的 AI 助手管理平台，通过 <strong>NapCat</strong> 接入 QQ 消息，结合 <strong>AstrBot</strong> 实现智能回复，同时提供消息管理、媒体文件管理等完整功能。</p>
                    <div class="feature-grid">
                      <div class="feature-card">
                        <Icon name="message-circle" :size="24" />
                        <h5>消息接入</h5>
                        <p>通过 NapCat 接收群聊和私聊消息，实时同步到系统</p>
                      </div>
                      <div class="feature-card">
                        <Icon name="robot" :size="24" />
                        <h5>AI 助手</h5>
                        <p>AstrBot 提供智能对话和群聊分析能力，支持多轮对话</p>
                      </div>
                      <div class="feature-card">
                        <Icon name="image" :size="24" />
                        <h5>媒体管理</h5>
                        <p>自动归档接收的图片、语音、视频等文件，支持在线预览</p>
                      </div>
                      <div class="feature-card">
                        <Icon name="database" :size="24" />
                        <h5>消息存储</h5>
                        <p>所有消息和对话历史自动保存到数据库，支持检索和导出</p>
                      </div>
                    </div>
                    <p>系统架构采用前后端分离设计，前端基于 Vue.js 构建，后端采用 Spring Boot，数据库使用 SQLite，确保系统轻量高效。</p>
                  </div>
                </div>

                <div id="section-login" class="manual-section" :class="{ active: activeSection === 'login' }">
                  <div class="manual-section-header">
                    <div class="manual-section-icon">
                      <Icon name="lock" :size="24" />
                    </div>
                    <div class="manual-section-title">
                      <h3>登录与权限</h3>
                      <p>系统账号登录与角色权限说明</p>
                    </div>
                  </div>
                  <div class="manual-body">
                    <h5>账号登录</h5>
                    <p>点击页面右上角「登录」按钮进入登录页面，输入用户名和密码进行登录。首次使用可点击「立即注册」创建新账号。</p>
                    <div class="step-list">
                      <div class="step-item">
                        <span class="step-num">1</span>
                        <span>点击页面右上角「登录」按钮</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">2</span>
                        <span>在登录弹窗中输入用户名和密码</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">3</span>
                        <span>点击「登录」按钮完成登录</span>
                      </div>
                    </div>
                    <h5>角色权限</h5>
                    <div class="permission-grid">
                      <div class="permission-card">
                        <Icon name="user" :size="20" />
                        <h6>普通用户</h6>
                        <ul>
                          <li>查看个人消息和群聊记录</li>
                          <li>使用 AstrBot AI 助手</li>
                          <li>管理个人媒体文件</li>
                          <li>查看个人信息和资料</li>
                        </ul>
                      </div>
                      <div class="permission-card admin">
                        <Icon name="admin" :size="20" />
                        <h6>管理员</h6>
                        <ul>
                          <li>普通用户所有权限</li>
                          <li>管理系统用户</li>
                          <li>查看数据统计</li>
                          <li>控制服务启停</li>
                          <li>查看系统日志</li>
                        </ul>
                      </div>
                    </div>
                    <h5>退出登录</h5>
                    <p>点击侧边栏头像旁的「退出」按钮即可安全退出系统。退出后需要重新登录才能访问系统功能。</p>
                  </div>
                </div>

                <div id="section-layout" class="manual-section" :class="{ active: activeSection === 'layout' }">
                  <div class="manual-section-header">
                    <div class="manual-section-icon">
                      <Icon name="layout" :size="24" />
                    </div>
                    <div class="manual-section-title">
                      <h3>首页布局</h3>
                      <p>页面布局与各区域功能介绍</p>
                    </div>
                  </div>
                  <div class="manual-body">
                    <h5>三栏布局（桌面端）</h5>
                    <div class="layout-grid">
                      <div class="layout-card">
                        <h6>左侧导航栏</h6>
                        <p>群聊列表、好友列表、系统按钮、头像菜单</p>
                        <ul>
                          <li>群聊列表：显示已接入的所有群聊</li>
                          <li>好友列表：显示私聊好友</li>
                          <li>系统按钮：管理员进入管理中心，普通用户进入用户中心</li>
                          <li>头像菜单：个人资料、用户手册、退出登录</li>
                        </ul>
                      </div>
                      <div class="layout-card">
                        <h6>中间消息区</h6>
                        <p>当前选中群聊的消息列表，支持搜索和筛选</p>
                        <ul>
                          <li>消息列表：按时间顺序显示群聊消息</li>
                          <li>搜索框：支持关键词搜索</li>
                          <li>时间筛选：按时间范围过滤消息</li>
                          <li>AI 分析：对群聊消息进行智能分析</li>
                        </ul>
                      </div>
                      <div class="layout-card">
                        <h6>右侧 AI 区</h6>
                        <p>AstrBot 助手对话界面，可拖动调整宽度</p>
                        <ul>
                          <li>对话窗口：与 AI 助手实时对话</li>
                          <li>对话历史：切换查看历史对话</li>
                          <li>模型选择：切换 AI 模型</li>
                          <li>人格管理：自定义 AI 角色</li>
                        </ul>
                      </div>
                    </div>
                    <h5>响应式适配</h5>
                    <div class="responsive-info">
                      <div class="responsive-item">
                        <Icon name="tablet" :size="20" />
                        <span><strong>平板端</strong>：双栏布局，可切换查看群聊或 AI 助手</span>
                      </div>
                      <div class="responsive-item">
                        <Icon name="smartphone" :size="20" />
                        <span><strong>移动端</strong>：底部标签页切换群聊和 AI 助手</span>
                      </div>
                    </div>
                  </div>
                </div>

                <div id="section-napcat" class="manual-section" :class="{ active: activeSection === 'napcat' }">
                  <div class="manual-section-header">
                    <div class="manual-section-icon">
                      <Icon name="settings" :size="24" />
                    </div>
                    <div class="manual-section-title">
                      <h3>NapCat 管理</h3>
                      <p>NapCat 服务启动、登录与功能说明</p>
                    </div>
                  </div>
                  <div class="manual-body">
                    <h5>启动 NapCat</h5>
                    <div class="step-list">
                      <div class="step-item">
                        <span class="step-num">1</span>
                        <span>进入用户中心，选择「NapCat 管理」标签</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">2</span>
                        <span>点击「启动」按钮启动 NapCat 服务</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">3</span>
                        <span>等待状态显示为「运行中」</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">4</span>
                        <span>使用手机 QQ 扫描二维码完成登录</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">5</span>
                        <span>勾选「下次自动登录」可避免重复扫码</span>
                      </div>
                    </div>
                    <h5>停止 NapCat</h5>
                    <p>点击「停止」按钮即可停止服务。停止后不再接收新消息，但已接收的消息仍可查看。如需重新接收消息，需再次启动并登录。</p>
                    <h5>WebUI 访问</h5>
                    <p>点击「打开 NapCat WebUI」可进入 NapCat 自带的管理界面，在 WebUI 中可查看更多 NapCat 详细信息和配置。</p>
                    <h5>功能说明</h5>
                    <div class="function-list">
                      <div class="function-item">
                        <Icon name="message-circle" :size="18" />
                        <span><strong>消息接入</strong>：接收群聊和私聊消息，存入消息库供查看与管理</span>
                      </div>
                      <div class="function-item">
                        <Icon name="robot" :size="18" />
                        <span><strong>AI 回复</strong>：对接 AstrBot，对消息进行智能分析与自动回复</span>
                      </div>
                      <div class="function-item">
                        <Icon name="image" :size="18" />
                        <span><strong>多媒体管理</strong>：接收的图片、语音、视频等文件自动归档，支持在线预览</span>
                      </div>
                      <div class="function-item">
                        <Icon name="send" :size="18" />
                        <span><strong>Webhook 推送</strong>：支持将消息实时推送到外部系统</span>
                      </div>
                    </div>
                  </div>
                </div>

                <div id="section-astrbot" class="manual-section" :class="{ active: activeSection === 'astrbot' }">
                  <div class="manual-section-header">
                    <div class="manual-section-icon">
                      <Icon name="robot" :size="24" />
                    </div>
                    <div class="manual-section-title">
                      <h3>AstrBot 助手</h3>
                      <p>AI 助手对话与群聊分析功能</p>
                    </div>
                  </div>
                  <div class="manual-body">
                    <h5>打开对话</h5>
                    <p>首页右侧面板即为 AstrBot 助手对话界面。若右侧面板收起，点击「AI」按钮展开。输入消息后按回车或点击发送按钮即可与 AI 对话。</p>
                    <h5>对话功能</h5>
                    <div class="feature-list">
                      <div class="feature-item">
                        <span class="feature-tag">多轮对话</span>
                        <span>支持连续多轮对话，上下文自动关联，AI 能够理解对话历史</span>
                      </div>
                      <div class="feature-item">
                        <span class="feature-tag">对话历史</span>
                        <span>对话历史会自动保存，可随时切换查看，支持跨会话继续</span>
                      </div>
                      <div class="feature-item">
                        <span class="feature-tag">快捷指令</span>
                        <span>支持通过快捷指令快速调用特定功能，提升使用效率</span>
                      </div>
                      <div class="feature-item">
                        <span class="feature-tag">人格管理</span>
                        <span>可在侧边栏打开人格管理，自定义 AI 角色和说话风格</span>
                      </div>
                    </div>
                    <h5>群聊分析</h5>
                    <div class="step-list">
                      <div class="step-item">
                        <span class="step-num">1</span>
                        <span>在首页中间消息区选择群聊后，点击「AI 分析」按钮</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">2</span>
                        <span>可指定分析的消息数量（默认最近 100 条）</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">3</span>
                        <span>选择分析类型（总结、情感分析等）</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">4</span>
                        <span>分析结果会展示在消息列表中</span>
                      </div>
                    </div>
                  </div>
                </div>

                <div id="section-messages" class="manual-section" :class="{ active: activeSection === 'messages' }">
                  <div class="manual-section-header">
                    <div class="manual-section-icon">
                      <Icon name="message-square" :size="24" />
                    </div>
                    <div class="manual-section-title">
                      <h3>消息管理</h3>
                      <p>消息查看、搜索与操作</p>
                    </div>
                  </div>
                  <div class="manual-body">
                    <h5>查看消息</h5>
                    <p>在左侧导航栏选择群聊或好友，中间区域显示消息列表。消息按时间顺序排列，最新消息在底部。支持图片、语音、视频、文件等多种消息类型。</p>
                    <h5>搜索消息</h5>
                    <div class="step-list">
                      <div class="step-item">
                        <span class="step-num">1</span>
                        <span>在消息区顶部搜索框输入关键词</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">2</span>
                        <span>按回车或点击搜索按钮进行搜索</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">3</span>
                        <span>搜索结果会高亮显示匹配内容</span>
                      </div>
                    </div>
                    <h5>时间筛选</h5>
                    <p>点击时间筛选按钮选择时间范围，支持按今天、本周、本月、自定义时间筛选。筛选后只显示指定时间范围内的消息。</p>
                    <h5>消息操作</h5>
                    <div class="action-list">
                      <div class="action-item">
                        <Icon name="copy" :size="16" />
                        <span><strong>复制</strong>：右键点击消息选择「复制」</span>
                      </div>
                      <div class="action-item">
                        <Icon name="download" :size="16" />
                        <span><strong>导出</strong>：点击导出按钮可导出消息记录</span>
                      </div>
                      <div class="action-item">
                        <Icon name="trash" :size="16" />
                        <span><strong>删除</strong>：管理员可删除消息</span>
                      </div>
                    </div>
                  </div>
                </div>

                <div id="section-media" class="manual-section" :class="{ active: activeSection === 'media' }">
                  <div class="manual-section-header">
                    <div class="manual-section-icon">
                      <Icon name="image" :size="24" />
                    </div>
                    <div class="manual-section-title">
                      <h3>媒体文件管理</h3>
                      <p>文件查看、筛选与删除操作</p>
                    </div>
                  </div>
                  <div class="manual-body">
                    <h5>查看文件</h5>
                    <p>进入用户中心，选择「媒体管理」标签。支持按类型筛选：全部、图片、视频、音频。点击文件名或「预览」按钮可查看单个文件。点击「预览全部」可进入相册模式浏览所有图片。</p>
                    <h5>文件筛选</h5>
                    <div class="filter-info">
                      <div class="filter-item">
                        <span class="filter-tag">类型筛选</span>
                        <span>使用顶部筛选按钮按文件类型过滤</span>
                      </div>
                      <div class="filter-item">
                        <span class="filter-tag">分页浏览</span>
                        <span>支持分页浏览，每页显示固定数量文件</span>
                      </div>
                      <div class="filter-item">
                        <span class="filter-tag">文件信息</span>
                        <span>显示文件大小、类型、上传时间等信息</span>
                      </div>
                    </div>
                    <h5>选择与删除</h5>
                    <div class="step-list">
                      <div class="step-item">
                        <span class="step-num">1</span>
                        <span>点击文件列表左侧的复选框进行多选</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">2</span>
                        <span>点击表头复选框可全选/取消全选当前页文件</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">3</span>
                        <span>选中文件后点击「删除选中」按钮删除</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">4</span>
                        <span>删除前会有二次确认提示，防止误删</span>
                      </div>
                    </div>
                  </div>
                </div>

                <div id="section-profile" class="manual-section" :class="{ active: activeSection === 'profile' }">
                  <div class="manual-section-header">
                    <div class="manual-section-icon">
                      <Icon name="user" :size="24" />
                    </div>
                    <div class="manual-section-title">
                      <h3>个人信息</h3>
                      <p>查看与修改个人资料</p>
                    </div>
                  </div>
                  <div class="manual-body">
                    <h5>查看信息</h5>
                    <p>进入用户中心，选择「个人信息」标签。可查看头像、昵称、用户名、邮箱等信息，显示注册时间和最后登录时间。</p>
                    <h5>修改资料</h5>
                    <div class="step-list">
                      <div class="step-item">
                        <span class="step-num">1</span>
                        <span>点击侧边栏头像或「个人资料」按钮</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">2</span>
                        <span>在个人资料弹窗中修改信息</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">3</span>
                        <span>可修改昵称、邮箱等个人信息</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">4</span>
                        <span>可上传新头像图片</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">5</span>
                        <span>点击「保存」按钮完成修改</span>
                      </div>
                    </div>
                  </div>
                </div>

                <div id="section-qqbind" class="manual-section" :class="{ active: activeSection === 'qqbind' }">
                  <div class="manual-section-header">
                    <div class="manual-section-icon">
                      <Icon name="user" :size="24" />
                    </div>
                    <div class="manual-section-title">
                      <h3>QQ账号绑定</h3>
                      <p>绑定与验证QQ账号，确保账号安全</p>
                    </div>
                  </div>
                  <div class="manual-body">
                    <h5>为什么需要绑定QQ号？</h5>
                    <p>本系统通过 NapCat 接入 QQ 消息，绑定QQ号后，系统会将该QQ号接收的所有消息（群聊、私聊）同步到您的账号中。只有绑定了QQ号，才能查看和管理相关消息。</p>
                    <h5>绑定流程</h5>
                    <div class="step-list">
                      <div class="step-item">
                        <span class="step-num">1</span>
                        <span>进入用户中心，选择「个人信息」标签</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">2</span>
                        <span>在「QQ账号绑定」区域输入要绑定的QQ号</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">3</span>
                        <span>点击「获取验证码」按钮，系统会通过 NapCat 向目标QQ发送验证码私信</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">4</span>
                        <span>在手机QQ上查看收到的验证码，输入到系统中</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">5</span>
                        <span>点击「确认绑定」完成绑定操作</span>
                      </div>
                    </div>
                    <h5>安全机制</h5>
                    <div class="security-list">
                      <div class="security-item">
                        <Icon name="shield" :size="18" />
                        <span><strong>身份验证</strong>：只有收到验证码并正确输入的人才能完成绑定，确保QQ账号主人操作</span>
                      </div>
                      <div class="security-item">
                        <Icon name="lock" :size="18" />
                        <span><strong>全局唯一</strong>：每个QQ号只能被一个用户绑定，防止多人绑定同一QQ号</span>
                      </div>
                      <div class="security-item">
                        <Icon name="clock" :size="18" />
                        <span><strong>验证码有效期</strong>：验证码5分钟内有效，过期需重新获取</span>
                      </div>
                      <div class="security-item">
                        <Icon name="alert-circle" :size="18" />
                        <span><strong>发送限制</strong>：每个QQ号每天最多发送10次验证码，防止滥用</span>
                      </div>
                    </div>
                    <h5>解绑操作</h5>
                    <div class="step-list">
                      <div class="step-item">
                        <span class="step-num">1</span>
                        <span>在个人信息页面的绑定列表中找到要解绑的QQ号</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">2</span>
                        <span>点击「解绑」按钮</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">3</span>
                        <span>确认解绑操作</span>
                      </div>
                      <div class="step-item">
                        <span class="step-num">4</span>
                        <span>解绑后该QQ号可被其他用户绑定</span>
                      </div>
                    </div>
                    <h5>常见问题</h5>
                    <ul>
                      <li><strong>提示"该QQ号已被其他用户绑定"？</strong>请确认该QQ号是否已被其他用户绑定，联系管理员查询绑定记录</li>
                      <li><strong>未收到验证码？</strong>请检查 NapCat 是否正常运行，目标QQ号是否在线且能接收私信</li>
                      <li><strong>验证码显示乱码？</strong>此问题已修复，更新代码后重新部署即可</li>
                    </ul>
                  </div>
                </div>

                <div id="section-admin" class="manual-section" :class="{ active: activeSection === 'admin' }">
                  <div class="manual-section-header">
                    <div class="manual-section-icon">
                      <Icon name="admin" :size="24" />
                    </div>
                    <div class="manual-section-title">
                      <h3>管理员功能</h3>
                      <p>系统管理中心各项功能说明</p>
                    </div>
                  </div>
                  <div class="manual-body">
                    <h5>系统管理中心</h5>
                    <p>管理员登录后点击侧边栏「系统」按钮进入管理中心，包含数据概览、用户管理、群聊管理、系统控制等模块。</p>
                    <h5>数据概览</h5>
                    <div class="stat-list">
                      <div class="stat-item">
                        <span class="stat-icon"><Icon name="message-circle" :size="18" /></span>
                        <span><strong>总消息数</strong>：系统接收到的所有消息数量</span>
                      </div>
                      <div class="stat-item">
                        <span class="stat-icon"><Icon name="group" :size="18" /></span>
                        <span><strong>群聊总数</strong>：已接入的群聊数量</span>
                      </div>
                      <div class="stat-item">
                        <span class="stat-icon"><Icon name="user" :size="18" /></span>
                        <span><strong>用户总数</strong>：系统注册用户数量</span>
                      </div>
                      <div class="stat-item">
                        <span class="stat-icon"><Icon name="robot" :size="18" /></span>
                        <span><strong>AI 对话数</strong>：与 AstrBot 的对话数量</span>
                      </div>
                      <div class="stat-item">
                        <span class="stat-icon"><Icon name="file" :size="18" /></span>
                        <span><strong>文件总数</strong>：媒体文件数量</span>
                      </div>
                      <div class="stat-item">
                        <span class="stat-icon"><Icon name="disk" :size="18" /></span>
                        <span><strong>磁盘使用</strong>：文件存储占用空间</span>
                      </div>
                    </div>
                    <h5>用户管理</h5>
                    <ul>
                      <li>查看所有系统用户列表</li>
                      <li>支持搜索、分页浏览</li>
                      <li>可编辑用户信息、修改角色、删除用户</li>
                      <li>支持批量操作</li>
                    </ul>
                    <h5>群聊管理</h5>
                    <ul>
                      <li>查看所有已接入的群聊</li>
                      <li>可查看群聊详细信息</li>
                      <li>支持禁用/启用群聊消息接收</li>
                    </ul>
                    <h5>系统控制</h5>
                    <ul>
                      <li>AstrBot 服务启动/停止</li>
                      <li>GPT-SoVITS 服务启动/停止（语音合成）</li>
                      <li>TTS 服务配置</li>
                      <li>系统日志查看</li>
                    </ul>
                  </div>
                </div>

                <div id="section-faq" class="manual-section" :class="{ active: activeSection === 'faq' }">
                  <div class="manual-section-header">
                    <div class="manual-section-icon">
                      <Icon name="help-circle" :size="24" />
                    </div>
                    <div class="manual-section-title">
                      <h3>常见问题</h3>
                      <p>使用过程中常见问题解答</p>
                    </div>
                  </div>
                  <div class="manual-body">
                    <div class="faq-list">
                      <div class="faq-item">
                        <div class="faq-question">
                          <Icon name="help-circle" :size="16" />
                          <span>二维码无法显示？</span>
                        </div>
                        <div class="faq-answer">
                          <ul>
                            <li>请先启动 NapCat 服务</li>
                            <li>检查网络连接是否正常</li>
                            <li>点击「刷新二维码」重试</li>
                            <li>确认 NapCat 配置文件中的端口设置正确</li>
                          </ul>
                        </div>
                      </div>
                      <div class="faq-item">
                        <div class="faq-question">
                          <Icon name="help-circle" :size="16" />
                          <span>消息接收延迟？</span>
                        </div>
                        <div class="faq-answer">
                          <ul>
                            <li>检查 NapCat 是否正常运行</li>
                            <li>检查 QQ 是否在线</li>
                            <li>检查网络连接稳定性</li>
                            <li>重启 NapCat 服务尝试恢复</li>
                          </ul>
                        </div>
                      </div>
                      <div class="faq-item">
                        <div class="faq-question">
                          <Icon name="help-circle" :size="16" />
                          <span>AI 助手无响应？</span>
                        </div>
                        <div class="faq-answer">
                          <ul>
                            <li>检查 AstrBot 服务是否启动</li>
                            <li>检查网络连接是否正常</li>
                            <li>查看后端日志排查错误</li>
                            <li>尝试重新发起对话</li>
                          </ul>
                        </div>
                      </div>
                      <div class="faq-item">
                        <div class="faq-question">
                          <Icon name="help-circle" :size="16" />
                          <span>文件预览失败？</span>
                        </div>
                        <div class="faq-answer">
                          <ul>
                            <li>文件可能已被删除或移动</li>
                            <li>尝试刷新页面重新加载</li>
                            <li>检查文件权限是否正常</li>
                            <li>确认文件格式是否被支持</li>
                          </ul>
                        </div>
                      </div>
                      <div class="faq-item">
                        <div class="faq-question">
                          <Icon name="help-circle" :size="16" />
                          <span>验证码显示乱码？</span>
                        </div>
                        <div class="faq-answer">
                          <ul>
                            <li>原因：发送验证码私信时未指定UTF-8编码导致中文乱码</li>
                            <li>解决方案：此问题已在版本更新中修复，更新代码并重新部署</li>
                            <li>修复位置：NapCatService.java 中 StringEntity 添加 UTF-8 编码</li>
                          </ul>
                        </div>
                      </div>
                      <div class="faq-item">
                        <div class="faq-question">
                          <Icon name="help-circle" :size="16" />
                          <span>QQ绑定提示"已被其他用户绑定"？</span>
                        </div>
                        <div class="faq-answer">
                          <ul>
                            <li>原因：同一个QQ号在系统中只能被一个用户绑定（全局唯一约束）</li>
                            <li>解决方案：确认该QQ号是否已被其他用户绑定，联系管理员查询绑定记录</li>
                            <li>检查数据库中是否存在残留的active=true的绑定记录</li>
                          </ul>
                        </div>
                      </div>
                      <div class="faq-item">
                        <div class="faq-question">
                          <Icon name="help-circle" :size="16" />
                          <span>登录失败？</span>
                        </div>
                        <div class="faq-answer">
                          <ul>
                            <li>确认用户名和密码是否正确</li>
                            <li>检查网络连接是否正常</li>
                            <li>联系管理员确认账号状态</li>
                          </ul>
                        </div>
                      </div>
                      <div class="faq-item">
                        <div class="faq-question">
                          <Icon name="help-circle" :size="16" />
                          <span>页面显示异常？</span>
                        </div>
                        <div class="faq-answer">
                          <ul>
                            <li>清除浏览器缓存后重新加载</li>
                            <li>尝试使用其他浏览器</li>
                            <li>确认浏览器版本是否支持</li>
                          </ul>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>

                <div id="section-support" class="manual-section" :class="{ active: activeSection === 'support' }">
                  <div class="manual-section-header">
                    <div class="manual-section-icon">
                      <Icon name="headphones" :size="24" />
                    </div>
                    <div class="manual-section-title">
                      <h3>技术支持</h3>
                      <p>获取帮助与服务维护</p>
                    </div>
                  </div>
                  <div class="manual-body">
                    <p>如遇问题，请优先查看本手册中的「常见问题」部分。若问题仍未解决，请联系系统管理员。</p>
                    <div class="support-list">
                      <div class="support-item">
                        <Icon name="activity" :size="20" />
                        <div>
                          <h6>服务状态</h6>
                          <p>定期检查 NapCat、AstrBot 服务是否正常运行，确保系统稳定</p>
                        </div>
                      </div>
                      <div class="support-item">
                        <Icon name="file-text" :size="20" />
                        <div>
                          <h6>日志查看</h6>
                          <p>管理员可在系统管理中心查看详细日志，排查问题</p>
                        </div>
                      </div>
                      <div class="support-item">
                        <Icon name="backup" :size="20" />
                        <div>
                          <h6>数据备份</h6>
                          <p>定期备份数据库，防止数据丢失</p>
                        </div>
                      </div>
                    </div>
                    <div class="contact-info">
                      <p><strong>联系管理员</strong>：如需技术支持，请联系系统管理员获取帮助。</p>
                    </div>
                  </div>
                </div>
              </div>
            </main>
          </div>
        </div>
      </main>
    </div>

    <div v-if="showPreviewModal" class="preview-modal" @click.self="closePreview">
      <div class="preview-content" :class="{ 'gallery-mode': previewMode === 'gallery' }">
        <button class="preview-close" @click="closePreview">×</button>
        <div v-if="previewMode === 'gallery'" class="preview-gallery">
          <div class="preview-gallery-header">
            <span class="preview-gallery-title">媒体文件预览</span>
            <small>{{ previewFiles.length }} 个文件</small>
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
            <button class="preview-nav-btn" @click="backToList">关闭预览</button>
          </div>
        </div>

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
            <audio
              v-else-if="currentPreviewFile.fileType === 'AUDIO'"
              :src="currentPreviewFile.url"
              controls
              class="preview-audio"
            ></audio>
            <div v-else class="preview-unsupported">暂不支持预览该类型文件</div>
          </div>
          <div class="preview-nav">
            <button
              class="preview-nav-btn"
              :disabled="currentPreviewIndex <= 0"
              @click="previewPrev"
            >
              ← 上一个
            </button>
            <button class="preview-nav-btn" @click="backToGallery">返回相册</button>
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
  </div>
</template>

<script>
import { ref, computed, onMounted, onUnmounted } from 'vue';
import { useRouter } from 'vue-router';
import Icon from '../components/Icon.vue';
import { formatFileSize, authApi } from '../services/api';
import { useComponentControl } from '../composables/useComponentControl';
import { useMediaManager } from '../composables/useMediaManager';

export default {
  name: 'UserCenter',
  components: { Icon },
  setup() {
    const router = useRouter();
    const activeTab = ref('napcat');
    const isMediaCollapsed = ref(false);

    const systemMessage = ref('');
    const showSystemMsg = (msg, type = 'success') => {
      systemMessage.value = msg;
      setTimeout(() => { systemMessage.value = ''; }, 5000);
    };

    const componentCtrl = useComponentControl({ showSystemMsg });
    const media = useMediaManager({ showSystemMsg });

    const navItems = [
      { key: 'napcat', icon: 'settings', label: 'NapCat 管理' },
      { key: 'media', icon: 'image', label: '媒体管理' },
      { key: 'profile', icon: 'user', label: '个人信息' },
      { key: 'manual', icon: 'book', label: '用户手册' },
    ];

    const handleNavClick = (key) => {
      console.log('nav click:', key);
      activeTab.value = key;
    };

    const manualSections = [
      { id: 'overview', title: '系统概述' },
      { id: 'login', title: '登录与权限' },
      { id: 'layout', title: '首页布局' },
      { id: 'napcat', title: 'NapCat 管理' },
      { id: 'astrbot', title: 'AstrBot 助手' },
      { id: 'messages', title: '消息管理' },
      { id: 'media', title: '媒体文件管理' },
      { id: 'profile', title: '个人信息' },
      { id: 'qqbind', title: 'QQ账号绑定' },
      { id: 'admin', title: '管理员功能' },
      { id: 'faq', title: '常见问题' },
      { id: 'support', title: '技术支持' },
    ];

    const activeSection = ref('overview');

    const scrollToSection = (sectionId) => {
      activeSection.value = sectionId;
      setTimeout(() => {
        const element = document.getElementById(`section-${sectionId}`);
        if (element) {
          element.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
      }, 100);
    };

    const userInfo = ref(null);

    const loadUserInfo = () => {
      const info = localStorage.getItem('user_info');
      userInfo.value = info ? JSON.parse(info) : null;
    };

    const showEditModal = ref(false);
    const isSaving = ref(false);
    const editForm = ref({
      nickname: '',
      email: ''
    });

    const openEditModal = () => {
      editForm.value = {
        nickname: userInfo.value?.nickname || '',
        email: userInfo.value?.email || ''
      };
      showEditModal.value = true;
    };

    const isValidEmail = (email) => {
      const re = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
      return re.test(email);
    };

    const isEditFormValid = computed(() => {
      if (!editForm.value.nickname && !editForm.value.email) return false;
      if (editForm.value.email && !isValidEmail(editForm.value.email)) return false;
      return true;
    });

    const saveProfile = async () => {
      if (!isEditFormValid.value || isSaving.value) return;
      isSaving.value = true;
      try {
        const data = await authApi.updateProfile(editForm.value.nickname || undefined, editForm.value.email || undefined);
        if (data) {
          localStorage.setItem('user_info', JSON.stringify(data));
          userInfo.value = data;
          showEditModal.value = false;
          showSystemMsg('资料更新成功');
        }
      } catch (error) {
        showSystemMsg('更新失败：' + error.message, 'error');
      } finally {
        isSaving.value = false;
      }
    };

    const userAvatarUrl = computed(() => {
      if (!userInfo.value || !userInfo.value.avatar) return '/default-avatar.svg';
      const avatar = userInfo.value.avatar;
      if (avatar.startsWith('http')) return avatar;
      const normalized = avatar.startsWith('/') ? avatar : '/' + avatar;
      return `http://localhost:8081${normalized}`;
    });

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

    const formatDate = (dateStr) => {
      if (!dateStr) return '-';
      return new Date(dateStr).toLocaleString('zh-CN');
    };

    const goHome = () => router.push('/');
    const handleAvatarError = (e) => {
      e.target.src = '/default-avatar.svg';
    };

    onMounted(() => {
      componentCtrl.startPolling();
      componentCtrl.loadNapCatWebUiUrl();
      componentCtrl.refreshQrCode();
      if (componentCtrl.autoLogin.value) componentCtrl.checkNapCatLogin();
      media.loadMediaFiles();
      window.addEventListener('keydown', media.onPreviewKeydown);

      loadUserInfo();

      authApi.getCurrentUser().then(data => {
        if (data) {
          const currentInfo = JSON.parse(localStorage.getItem('user_info') || '{}');
          const newInfo = {
            ...currentInfo,
            username: data.username,
            nickname: data.nickname,
            role: data.role,
            avatar: data.avatar,
            email: data.email,
            createdAt: data.createdAt,
            lastLoginTime: data.lastLoginTime
          };
          localStorage.setItem('user_info', JSON.stringify(newInfo));
          userInfo.value = newInfo;
        }
      }).catch(() => {});
    });

    onUnmounted(() => {
      componentCtrl.stopPolling();
      window.removeEventListener('keydown', media.onPreviewKeydown);
    });

    const formatBytes = (bytes) => formatFileSize(bytes);

    return {
      activeTab, navItems, isMediaCollapsed,
      formatDate, formatBytes, goHome, handleAvatarError,
      handleNavClick,
      userInfo, userAvatarUrl,
      manualSections, activeSection, scrollToSection,
      componentStatus: componentCtrl.componentStatus,
      qrCode: componentCtrl.qrCode,
      napCatWebUiUrl: componentCtrl.napCatWebUiUrl,
      autoLogin: componentCtrl.autoLogin,
      isStartingNapCat: componentCtrl.isStartingNapCat,
      isStoppingNapCat: componentCtrl.isStoppingNapCat,
      refreshQrCode: componentCtrl.refreshQrCode,
      onAutoLoginChange: componentCtrl.onAutoLoginChange,
      startNapCat: componentCtrl.startNapCat,
      stopNapCat: componentCtrl.stopNapCat,
      openNapCatWebUI: componentCtrl.openNapCatWebUI,
      mediaFiles: media.mediaFiles,
      mediaFilesTotal: media.mediaFilesTotal,
      mediaFileFilter: media.mediaFileFilter,
      mediaFilePage: media.mediaFilePage,
      mediaFileSize: media.mediaFileSize,
      mediaFilesLoading: media.isLoading,
      isPurging: media.isPurging,
      purgeResult: media.purgeResult,
      selectedMediaFileIds: media.selectedMediaFileIds,
      selectedMediaFilesCount: media.selectedMediaFilesCount,
      selectedMediaFilesTotalSize: media.selectedMediaFilesTotalSize,
      setMediaFileFilter: media.setMediaFileFilter,
      loadMediaFiles: media.loadMediaFiles,
      toggleMediaFileSelection: media.toggleMediaFileSelection,
      selectAllMediaFiles: media.selectAllMediaFiles,
      clearMediaFileSelection: media.clearMediaFileSelection,
      openPreview: media.openPreview,
      closePreview: media.closePreview,
      previewAllFiles: media.previewAllFiles,
      previewMode: media.previewMode,
      previewFiles: media.previewFiles,
      currentPreviewFile: media.currentPreviewFile,
      currentPreviewIndex: media.currentPreviewIndex,
      enterSingleView: media.enterSingleView,
      backToGallery: media.backToGallery,
      backToList: media.backToList,
      previewPrev: media.previewPrev,
      previewNext: media.previewNext,
      isMediaError: media.isMediaError,
      markMediaError: media.markMediaError,
      showPreviewModal: media.showPreviewModal,
      confirmDeleteSelected,
      currentFilesTotalSize,
      totalMediaPages,
      showEditModal,
      isSaving,
      editForm,
      openEditModal,
      isValidEmail,
      isEditFormValid,
      saveProfile,
    };
  }
};
</script>

<style scoped>
.user-center {
  min-height: 100vh;
  background-color: #f5f7fa;
  display: flex;
  flex-direction: column;
}

.user-center-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  background-color: white;
  border-bottom: 1px solid #e8e8e8;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
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
  color: #333;
}

.header-actions {
  display: flex;
  gap: 10px;
}

.btn-home {
  padding: 6px 12px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.btn-home:hover {
  background-color: #2980b9;
}

.user-center-layout {
  flex: 1;
  display: flex;
  min-height: 0;
  overflow: hidden;
}

.user-center-sidebar {
  width: 200px;
  background-color: #2c3e50;
  padding: 20px 0;
  overflow-y: auto;
}

.user-center-nav {
  display: flex;
  flex-direction: column;
}

.user-center-nav .nav-item {
  display: flex;
  align-items: center;
  padding: 10px 20px;
  color: #ecf0f1;
  cursor: pointer;
  gap: 10px;
  transition: background-color 0.2s;
  position: relative;
  z-index: 1;
  pointer-events: auto;
}

.user-center-nav .nav-item:hover {
  background-color: #34495e;
}

.user-center-nav .nav-item.active {
  background-color: #3498db;
  border-right: 3px solid #ecf0f1;
}

.user-center-main {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
  min-height: 0;
}

.tab-panel {
  animation: fadeIn 0.3s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
}

.panel-title h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.component-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 20px;
  margin-bottom: 20px;
}

.napcat-layout {
  display: flex;
  gap: 20px;
  align-items: flex-start;
  margin-bottom: 20px;
}

.napcat-intro-card {
  flex: 1;
  background: white;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
  min-width: 0;
}

.napcat-intro-card h4 {
  margin: 0 0 15px 0;
  font-size: 15px;
  font-weight: 600;
}

.intro-content p {
  margin: 0 0 12px 0;
  color: #555;
  line-height: 1.6;
}

.intro-content ul {
  margin: 0 0 15px 0;
  padding-left: 0;
  color: #555;
  line-height: 1.8;
  list-style: none;
}

.intro-content li {
  padding: 4px 0;
}

.intro-content li strong {
  color: #333;
}

.intro-tip {
  color: #888;
  font-size: 13px;
  margin-top: 10px;
  padding: 10px;
  background: #f5f7fa;
  border-radius: 4px;
}

.napcat-right {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.component-card {
  background: white;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}

.component-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 15px;
}

.component-status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background-color: #e74c3c;
}

.component-status-dot.active {
  background-color: #27ae60;
}

.component-title {
  font-weight: 600;
  color: #333;
}

.component-status-text {
  margin-left: auto;
  font-size: 13px;
  color: #7f8c8d;
}

.component-actions {
  display: flex;
  gap: 10px;
  margin-bottom: 15px;
}

.btn-start, .btn-stop {
  flex: 1;
  padding: 8px 16px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
}

.btn-start {
  background-color: #27ae60;
  color: white;
}

.btn-start:hover:not(:disabled) {
  background-color: #229954;
}

.btn-start:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
}

.btn-stop {
  background-color: #e74c3c;
  color: white;
}

.btn-stop:hover:not(:disabled) {
  background-color: #c0392b;
}

.btn-stop:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
}

.webui-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #3498db;
  text-decoration: none;
  font-size: 13px;
}

.webui-link:hover {
  text-decoration: underline;
}

.section-card {
  background: white;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}

.section-card h4 {
  margin: 0 0 15px 0;
  font-size: 15px;
  font-weight: 600;
  color: #333;
}

.section-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
}

.collapse-toggle {
  padding: 4px 8px;
  background-color: #ecf0f1;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.login-area {
  display: flex;
  justify-content: center;
  padding: 20px;
}

.qrcode-box {
  text-align: center;
}

.qrcode-box img {
  max-width: 200px;
  border: 2px solid #eee;
  border-radius: 8px;
}

.qrcode-box p {
  margin: 15px 0;
  color: #7f8c8d;
  font-size: 14px;
}

.btn-refresh {
  padding: 6px 16px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  margin-bottom: 10px;
}

.btn-refresh:hover {
  background-color: #2980b9;
}

.auto-login-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #7f8c8d;
  cursor: pointer;
}

.loading-box {
  text-align: center;
  padding: 40px;
  color: #7f8c8d;
}

.media-manager-body {
  animation: fadeIn 0.2s ease;
}

.media-manager-card {
  background: #fafafa;
  border-radius: 6px;
  padding: 16px;
}

.media-summary {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
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
  margin-bottom: 16px;
}

.chart-btn {
  padding: 4px 12px;
  background-color: white;
  border: 1px solid #ddd;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}

.chart-btn.active {
  background-color: #3498db;
  color: white;
  border-color: #3498db;
}

.media-loading {
  text-align: center;
  padding: 40px;
  color: #7f8c8d;
}

.loading-spinner {
  width: 24px;
  height: 24px;
  border: 3px solid #f3f3f3;
  border-top: 3px solid #3498db;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin: 0 auto 10px;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

.media-empty {
  text-align: center;
  padding: 40px;
  color: #7f8c8d;
}

.media-table {
  width: 100%;
  border-collapse: collapse;
  background: white;
  border-radius: 6px;
  overflow: hidden;
}

.media-table th, .media-table td {
  padding: 10px 12px;
  text-align: left;
  border-bottom: 1px solid #eee;
}

.media-table th {
  background-color: #f8f9fa;
  font-weight: 600;
  font-size: 12px;
  color: #666;
}

.col-checkbox {
  width: 40px;
}

.col-action {
  width: 80px;
}

.file-name-cell {
  cursor: pointer;
  color: #3498db;
}

.file-name-cell:hover {
  text-decoration: underline;
}

.btn-preview {
  padding: 4px 10px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 3px;
  cursor: pointer;
  font-size: 12px;
}

.btn-preview:hover {
  background-color: #2980b9;
}

.btn-delete {
  padding: 4px 10px;
  background-color: #e74c3c;
  color: white;
  border: none;
  border-radius: 3px;
  cursor: pointer;
  font-size: 12px;
}

.btn-delete:hover:not(:disabled) {
  background-color: #c0392b;
}

.btn-delete:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
}

.media-pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 15px;
  margin-top: 15px;
  font-size: 13px;
  color: #666;
}

.media-pagination button {
  padding: 4px 12px;
  background-color: white;
  border: 1px solid #ddd;
  border-radius: 4px;
  cursor: pointer;
}

.media-pagination button:hover:not(:disabled) {
  background-color: #f0f0f0;
}

.media-pagination button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.purge-result {
  text-align: center;
  padding: 10px;
  background-color: #d4edda;
  color: #155724;
  border-radius: 4px;
  margin-bottom: 15px;
  font-size: 13px;
}

.media-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 15px;
}

.profile-card {
  max-width: 600px;
}

.profile-avatar-section {
  display: flex;
  align-items: center;
  gap: 20px;
  padding-bottom: 20px;
  border-bottom: 1px solid #eee;
}

.profile-avatar {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  overflow: hidden;
  border: 3px solid #3498db;
}

.profile-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.profile-info h3 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #333;
}

.profile-role {
  margin: 5px 0 0 0;
  font-size: 14px;
  color: #7f8c8d;
}

.profile-details {
  padding-top: 20px;
}

.detail-row {
  display: flex;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid #f0f0f0;
}

.detail-row label {
  font-weight: 500;
  color: #666;
}

.detail-row span {
  color: #333;
}

.btn-edit-profile {
  margin-left: auto;
  padding: 6px 14px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.btn-edit-profile:hover {
  background-color: #2980b9;
}

.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.modal-content {
  background-color: white;
  border-radius: 8px;
  width: 90%;
  max-width: 450px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.2);
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid #eee;
}

.modal-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.btn-close {
  background: none;
  border: none;
  color: #999;
  cursor: pointer;
  padding: 4px;
}

.btn-close:hover {
  color: #333;
}

.modal-body {
  padding: 20px;
}

.form-group {
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  margin-bottom: 6px;
  font-weight: 500;
  color: #666;
  font-size: 14px;
}

.form-group input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 14px;
  box-sizing: border-box;
}

.form-group input:focus {
  outline: none;
  border-color: #3498db;
}

.error-message {
  display: block;
  margin-top: 6px;
  color: #e74c3c;
  font-size: 12px;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid #eee;
}

.btn-cancel {
  padding: 8px 20px;
  background-color: #f5f5f5;
  color: #666;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}

.btn-cancel:hover {
  background-color: #eee;
}

.btn-save {
  padding: 8px 20px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}

.btn-save:hover:not(:disabled) {
  background-color: #2980b9;
}

.btn-save:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
}

.manual-panel {
  padding: 0;
}

.manual-layout {
  display: flex;
  flex: 1;
  min-height: 0;
}

.manual-sidebar {
  width: 160px;
  background: #2c3e50;
  padding: 20px 0;
  overflow-y: auto;
  flex-shrink: 0;
  position: sticky;
  top: 0;
  align-self: flex-start;
  max-height: 100vh;
}

.manual-sidebar-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 20px 15px;
  color: #ecf0f1;
  font-size: 14px;
  font-weight: 600;
  border-bottom: 1px solid #34495e;
  margin-bottom: 10px;
}

.manual-nav {
  display: flex;
  flex-direction: column;
}

.manual-nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 20px;
  color: #bdc3c7;
  cursor: pointer;
  transition: all 0.2s;
  font-size: 13px;
}

.manual-nav-item:hover {
  background-color: #34495e;
  color: #ecf0f1;
}

.manual-nav-item.active {
  background-color: #3498db;
  color: white;
}

.manual-nav-num {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: rgba(255,255,255,0.2);
  border-radius: 4px;
  font-size: 12px;
  flex-shrink: 0;
}

.manual-nav-item.active .manual-nav-num {
  background-color: rgba(255,255,255,0.3);
}

.manual-main {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
  background-color: #f5f7fa;
}

.manual-content {
  max-width: 900px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.manual-section {
  background: white;
  border-radius: 12px;
  padding: 0;
  box-shadow: 0 2px 12px rgba(0,0,0,0.08);
  overflow: hidden;
}

.manual-section-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px 24px;
  background: linear-gradient(135deg, #3498db 0%, #2980b9 100%);
}

.manual-section-icon {
  width: 48px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: rgba(255,255,255,0.2);
  border-radius: 10px;
  color: white;
}

.manual-section-title h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: white;
}

.manual-section-title p {
  margin: 4px 0 0 0;
  font-size: 13px;
  color: rgba(255,255,255,0.8);
}

.manual-body {
  padding: 24px;
}

.manual-body h5 {
  margin: 20px 0 10px 0;
  font-size: 15px;
  font-weight: 600;
  color: #2c3e50;
}

.manual-body p {
  margin: 12px 0;
  color: #333;
  font-size: 14px;
  line-height: 1.7;
}

.manual-body ul {
  margin: 0 0 14px 0;
  padding-left: 0;
  color: #555;
  line-height: 1.9;
  font-size: 13px;
  list-style: none;
}

.manual-body li {
  position: relative;
  padding-left: 20px;
}

.manual-body li::before {
  content: '▸';
  position: absolute;
  left: 0;
  color: #3498db;
  font-size: 14px;
}

.manual-body strong {
  color: #2c3e50;
  font-weight: 600;
}

.feature-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin: 16px 0;
}

.feature-card {
  background-color: #f8f9fa;
  border-radius: 8px;
  padding: 16px;
  text-align: center;
}

.feature-card h5 {
  margin: 10px 0 6px 0;
  font-size: 14px;
  font-weight: 600;
  color: #2c3e50;
}

.feature-card p {
  margin: 0;
  font-size: 12px;
  color: #666;
  line-height: 1.5;
}

.step-list {
  background-color: #f8f9fa;
  border-radius: 8px;
  padding: 16px;
  margin: 12px 0;
}

.step-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 8px 0;
}

.step-num {
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #3498db;
  color: white;
  border-radius: 50%;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}

.step-item span:last-child {
  font-size: 13px;
  color: #555;
  line-height: 1.6;
}

.permission-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 16px;
  margin: 16px 0;
}

.permission-card {
  background-color: #f8f9fa;
  border-radius: 8px;
  padding: 16px;
  border-left: 4px solid #3498db;
}

.permission-card.admin {
  border-left-color: #e67e22;
}

.permission-card h6 {
  margin: 0 0 10px 0;
  font-size: 14px;
  font-weight: 600;
  color: #2c3e50;
}

.permission-card ul {
  margin: 0;
}

.layout-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 16px;
  margin: 16px 0;
}

.layout-card {
  background-color: #f8f9fa;
  border-radius: 8px;
  padding: 16px;
}

.layout-card h6 {
  margin: 0 0 6px 0;
  font-size: 14px;
  font-weight: 600;
  color: #2c3e50;
}

.layout-card p {
  margin: 0 0 10px 0;
  font-size: 12px;
  color: #666;
}

.responsive-info {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 12px 0;
}

.responsive-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: #555;
}

.function-list, .feature-list, .action-list, .stat-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 12px 0;
}

.function-item, .feature-item, .action-item, .stat-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background-color: #f8f9fa;
  border-radius: 6px;
  font-size: 13px;
  color: #555;
}

.feature-tag {
  background-color: #3498db;
  color: white;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.filter-info {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 12px 0;
}

.filter-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-tag {
  background-color: #67c23a;
  color: white;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.faq-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.faq-item {
  background-color: #f8f9fa;
  border-radius: 8px;
  overflow: hidden;
}

.faq-question {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  background-color: #fff;
  font-weight: 500;
  color: #2c3e50;
  font-size: 14px;
  cursor: pointer;
}

.faq-answer {
  padding: 0 16px 14px;
}

.faq-answer ul {
  margin: 0;
}

.support-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 16px;
  margin: 16px 0;
}

.support-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  background-color: #f8f9fa;
  border-radius: 8px;
  padding: 16px;
}

.support-item h6 {
  margin: 0 0 6px 0;
  font-size: 14px;
  font-weight: 600;
  color: #2c3e50;
}

.support-item p {
  margin: 0;
  font-size: 12px;
  color: #666;
  line-height: 1.5;
}

.contact-info {
  background-color: #e3f2fd;
  border-left: 4px solid #2196f3;
  border-radius: 0 8px 8px 0;
  padding: 14px 16px;
  margin-top: 16px;
}

.preview-modal {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0,0,0,0.8);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.preview-content {
  background: white;
  border-radius: 8px;
  max-width: 90vw;
  max-height: 90vh;
  overflow: hidden;
}

.preview-close {
  position: absolute;
  top: 15px;
  right: 15px;
  width: 35px;
  height: 35px;
  background-color: rgba(0,0,0,0.5);
  color: white;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  font-size: 20px;
  z-index: 10;
}

.preview-close:hover {
  background-color: rgba(0,0,0,0.7);
}

.preview-gallery {
  padding: 20px;
  max-height: 80vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.preview-gallery-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
}

.preview-gallery-title {
  font-weight: 600;
}

.preview-gallery-body {
  flex: 1;
  overflow-y: auto;
}

.preview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 15px;
}

.preview-grid-item {
  cursor: pointer;
}

.preview-thumbnail {
  aspect-ratio: 1;
  background-color: #f5f5f5;
  border-radius: 6px;
  overflow: hidden;
  margin-bottom: 8px;
}

.preview-thumbnail img, .preview-thumbnail video {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.preview-thumbnail-audio, .preview-thumbnail-file {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32px;
}

.preview-grid-info {
  text-align: center;
}

.preview-grid-name {
  display: block;
  font-size: 12px;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.preview-gallery-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 15px;
  border-top: 1px solid #eee;
}

.preview-nav-btn {
  padding: 6px 16px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}

.preview-nav-btn:hover:not(:disabled) {
  background-color: #2980b9;
}

.preview-nav-btn:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
}

.preview-single {
  padding: 20px;
}

.preview-title {
  text-align: center;
  margin-bottom: 15px;
}

.preview-title span {
  font-weight: 600;
}

.preview-title small {
  color: #7f8c8d;
  margin-left: 10px;
}

.preview-media {
  display: flex;
  align-items: center;
  justify-content: center;
  max-height: 60vh;
  padding: 20px;
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
  max-width: 500px;
}

.preview-unsupported {
  color: #7f8c8d;
  font-size: 16px;
}

.preview-nav {
  display: flex;
  justify-content: center;
  gap: 20px;
  margin-top: 20px;
}

.preview-thumbnail-placeholder {
  background-color: #f0f0f0;
}
</style>
