<template>
  <div class="user-center">
    <!-- 全局系统消息提示（z-index 高于 modal） -->
    <div v-if="systemMessage" class="system-toast" :class="systemMessageType">
      {{ systemMessage }}
    </div>
    <header class="user-center-header">
      <div class="header-brand">
        <Icon name="user" :size="24" />
        <h1>用户中心</h1>
      </div>
      <div class="header-actions">
        <div
          v-if="activeTab === 'credits' || activeTab === 'subscription'"
          class="header-credits-summary"
        >
          <span class="hcs-tier" :class="{ paid: tier !== 'FREE' }">{{ tierLabel }}</span>
          <span v-if="expiresAt" class="hcs-expire">{{ formatShortDate(expiresAt) }}到期</span>
          <span class="hcs-divider">·</span>
          <span class="hcs-balance">
            <span class="hcs-gem"><Icon name="diamond" :size="14" /></span>
            <span class="hcs-num">{{ formatCreditsNumber(balance) }}</span>
          </span>
        </div>
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
        <div v-if="activeTab === 'dashboard'" class="tab-panel">
          <div class="panel-title">
            <Icon name="dashboard" :size="20" />
            <h2>数据概览</h2>
          </div>

          <!-- 加载中 -->
          <div v-if="dashboardLoading" class="dashboard-loading">
            <div class="loading-spinner"></div>
            <span>加载中...</span>
          </div>

          <template v-else>
            <!-- 空状态：未绑定QQ（根据实际QQ绑定列表判断） -->
            <div v-if="!qqBindingsLoading && qqBindings.length === 0" class="dashboard-empty">
              <Icon name="message-circle" :size="40" />
              <p>请先绑定QQ账号以查看数据概览</p>
            </div>

            <template v-else>
              <!-- 统计卡片 -->
              <div class="stats-cards">
                <div class="stat-card">
                  <div class="stat-icon blue"><Icon name="chat" :size="28" /></div>
                  <div class="stat-info">
                    <div class="stat-value">{{ dashboardStats.totalMessages || 0 }}</div>
                    <div class="stat-label">我的消息数</div>
                    <div class="stat-sub">今日 {{ dashboardStats.todayMessages || 0 }}</div>
                  </div>
                </div>
                <div class="stat-card">
                  <div class="stat-icon purple"><Icon name="group" :size="28" /></div>
                  <div class="stat-info">
                    <div class="stat-value">{{ dashboardStats.totalGroups || 0 }}</div>
                    <div class="stat-label">我的群聊数</div>
                    <div class="stat-sub">活跃 {{ dashboardStats.activeGroups || 0 }}</div>
                  </div>
                </div>
                <div class="stat-card">
                  <div class="stat-icon orange"><Icon name="robot" :size="28" /></div>
                  <div class="stat-info">
                    <div class="stat-value">{{ dashboardStats.totalConversations || 0 }}</div>
                    <div class="stat-label">AI 对话数</div>
                  </div>
                </div>
                <div class="stat-card">
                  <div class="stat-icon teal"><Icon name="file" :size="28" /></div>
                  <div class="stat-info">
                    <div class="stat-value">{{ dashboardStats.totalFiles || 0 }}</div>
                    <div class="stat-label">我的文件数</div>
                  </div>
                </div>
                <div class="stat-card">
                  <div class="stat-icon red"><Icon name="disk" :size="28" /></div>
                  <div class="stat-info">
                    <div class="stat-value">{{ dashboardStats.filesSizeFormatted || '0 B' }}</div>
                    <div class="stat-label">文件占用</div>
                  </div>
                </div>
              </div>

              <!-- 图表区域 -->
              <div class="charts-grid">
                <div class="chart-card chart-large">
                  <div class="chart-header">
                    <h4>我的消息趋势</h4>
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
                  <h4>我的活跃群聊排行 TOP5</h4>
                  <v-chart
                    class="ranking-chart"
                    :option="groupRankingOption"
                    autoresize
                  />
                </div>

                <div class="chart-card">
                  <h4>我的消息类型分布</h4>
                  <v-chart
                    class="pie-chart-echarts"
                    :option="distributionChartOption"
                    autoresize
                  />
                </div>

                <div class="chart-card">
                  <h4>我的 AI 对话趋势</h4>
                  <v-chart
                    class="line-chart-echarts"
                    :option="aiTrendChartOption"
                    autoresize
                  />
                </div>
              </div>
            </template>
          </template>
        </div>

        <div v-if="activeTab === 'credits'" class="tab-panel">
          <div class="panel-title">
            <Icon name="star" :size="20" />
            <h2>用量管理</h2>
          </div>
          <CreditsDashboard
            :auto-focus-sign-in="autoFocusSignIn"
            :auto-related-id="autoRelatedId"
            @open-upgrade="handleCreditsOpenUpgrade"
            @switch-tab="handleCreditsSwitchTab"
          />
        </div>

        <div v-if="activeTab === 'subscription'" class="tab-panel">
          <div class="panel-title">
            <Icon name="layers" :size="20" />
            <h2>订阅管理</h2>
          </div>
          <SubscriptionDashboard
            :auto-open-upgrade="autoOpenUpgrade"
            @switch-tab="handleCreditsSwitchTab"
            @refresh-credits="handleRefreshCredits"
          />
        </div>

        <div v-if="activeTab === 'profile'" class="tab-panel">
          <div class="panel-title">
            <Icon name="user" :size="20" />
            <h2>个人信息</h2>
          </div>

          <div class="profile-cards-grid">
            <div class="section-card profile-card-new">
              <div class="profile-avatar-card">
                <div class="profile-avatar-large" @click="triggerAvatarUpload">
                  <img :src="userAvatarUrl" alt="avatar" @error="handleAvatarError" />
                  <div class="avatar-upload-overlay">
                    <Icon name="upload" :size="18" />
                    <span>更换头像</span>
                  </div>
                </div>
                <input
                  ref="avatarInput"
                  type="file"
                  accept="image/jpeg,image/png,image/gif"
                  style="display: none"
                  @change="onAvatarChange"
                />
                <div class="profile-info">
                  <h3>{{ userInfo?.nickname || userInfo?.username }}</h3>
                  <span class="role-badge" :class="{ admin: userInfo?.role === 'ADMIN' }">
                    {{ userInfo?.role === 'ADMIN' ? '管理员' : '普通用户' }}
                  </span>
                </div>
                <button class="btn-edit-profile" @click="openEditModal">
                  <Icon name="edit" :size="14" />
                  编辑资料
                </button>
              </div>
            </div>

            <div class="section-card profile-card-new">
              <h4>基本信息</h4>
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

            <div class="section-card profile-card-new">
              <div class="qq-bindings-header">
                <h4>QQ绑定</h4>
                <button class="qq-add-btn" @click="openBindModal">
                  <Icon name="add" :size="14" />
                  添加绑定
                </button>
              </div>
              <div v-if="qqBindingsLoading" class="qq-loading">
                <div class="loading-spinner"></div>
                <span>加载中...</span>
              </div>
              <div v-else-if="qqBindings.length === 0" class="qq-empty">
                <Icon name="message-circle" :size="32" />
                <div>暂无QQ绑定</div>
                <div class="qq-empty-hint">点击上方按钮绑定QQ账号</div>
              </div>
              <div v-else class="qq-bindings-list">
                <div v-for="binding in qqBindings" :key="binding.id" class="qq-binding-item">
                  <img :src="binding.avatar || '/default-avatar.svg'" :alt="binding.nickname" class="qq-binding-avatar" />
                  <div class="qq-binding-info">
                    <span class="qq-binding-nickname">{{ binding.nickname }}</span>
                    <span class="qq-binding-number">QQ: {{ binding.qqNumber }}</span>
                  </div>
                  <div class="qq-binding-actions">
                    <span v-if="binding.isDefault" class="qq-default-badge">默认</span>
                    <button v-else class="qq-action-btn" @click="handleSetDefault(binding)" title="设为默认">
                      <Icon name="star" :size="14" />
                    </button>
                    <button class="qq-action-btn danger" @click="handleUnbind(binding)" title="解绑">
                      <Icon name="delete" :size="14" />
                    </button>
                  </div>
                </div>
              </div>
            </div>

            <div class="section-card profile-card-new">
              <h4>账号安全</h4>
              <div class="password-section">
                <p class="password-desc">为了您的账号安全，建议定期更换密码</p>
                <button class="btn-change-password" @click="openPasswordModal">
                  <Icon name="lock" :size="14" />
                  修改密码
                </button>
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

          <div v-if="showPasswordModal" class="modal-overlay" @click.self="showPasswordModal = false">
            <div class="modal-content">
              <div class="modal-header">
                <h3>修改密码</h3>
                <button class="btn-close" @click="showPasswordModal = false">
                  <Icon name="x" :size="16" />
                </button>
              </div>
              <div class="modal-body">
                <div class="form-group">
                  <label>当前密码</label>
                  <input v-model="passwordForm.oldPassword" type="password" placeholder="请输入当前密码" />
                </div>
                <div class="form-group">
                  <label>新密码</label>
                  <input v-model="passwordForm.newPassword" type="password" placeholder="请输入新密码（至少6位）" />
                </div>
                <div class="form-group">
                  <label>确认新密码</label>
                  <input v-model="passwordForm.confirmPassword" type="password" placeholder="请再次输入新密码" />
                  <span v-if="passwordForm.confirmPassword && passwordForm.confirmPassword !== passwordForm.newPassword" class="error-message">两次输入的密码不一致</span>
                </div>
              </div>
              <div class="modal-footer">
                <button class="btn-cancel" @click="showPasswordModal = false">取消</button>
                <button class="btn-save" @click="changePassword" :disabled="!isPasswordFormValid || isPasswordSaving">
                  {{ isPasswordSaving ? '提交中...' : '确认修改' }}
                </button>
              </div>
            </div>
          </div>

          <div v-if="showBindModal" class="modal-overlay" @click.self="showBindModal = false">
            <div class="modal-content">
              <div class="modal-header">
                <h3>绑定QQ账号</h3>
                <button class="btn-close" @click="showBindModal = false">
                  <Icon name="x" :size="16" />
                </button>
              </div>
              <div class="modal-body">
                <div v-if="!bindCodeSent" class="form-group">
                  <label>QQ号</label>
                  <input v-model="bindForm.qqNumber" type="text" placeholder="请输入要绑定的QQ号" />
                  <span v-if="bindForm.qqNumber && !/^\d{5,11}$/.test(bindForm.qqNumber)" class="error-message">请输入正确的QQ号</span>
                </div>
                <div v-else>
                  <div class="bind-qr-section">
                    <p class="bind-tip">请使用手机QQ发送验证码到以下号码：</p>
                    <div class="bind-qq-number">{{ bindForm.qqNumber }}</div>
                    <p class="bind-tip-small">验证码有效期 5 分钟</p>
                    <p class="bind-tip-small">请查看QQ自发消息</p>
                  </div>
                  <div class="form-group">
                    <label>验证码</label>
                    <input v-model="bindForm.code" type="text" placeholder="请输入6位验证码" maxlength="6" />
                  </div>
                </div>
              </div>
              <div class="modal-footer">
                <button class="btn-cancel" @click="showBindModal = false">取消</button>
                <button v-if="!bindCodeSent" class="btn-save" @click="sendBindCode" :disabled="!isBindFormValid || isBindingSending">
                  {{ isBindingSending ? '发送中...' : '发送验证码' }}
                </button>
                <button v-else class="btn-save" @click="confirmBind" :disabled="!isBindConfirmValid || isBindingConfirm">
                  {{ isBindingConfirm ? '绑定中...' : '确认绑定' }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </main>
    </div>

    <div v-if="showPreviewModal" class="preview-modal" @click.self="closePreview">
      <div class="preview-content" :class="{ 'gallery-mode': previewMode === 'gallery' }">
        <button class="preview-close" @click="closePreview">×</button>
        <div v-if="previewMode === 'gallery'" class="preview-gallery">
          <div class="preview-gallery-header">
            <div class="preview-gallery-title-row">
              <span class="preview-gallery-title">媒体文件预览</span>
              <small>共 {{ previewTotalElements }} 个文件 · 第 {{ previewPage + 1 }} / {{ previewTotalPages }} 页</small>
            </div>
            <div class="preview-gallery-toolbar">
              <div class="preview-search">
                <input
                  type="text"
                  v-model="previewSearchQuery"
                  placeholder="搜索文件名..."
                  class="preview-search-input"
                  @keydown.enter="onPreviewSearch"
                />
                <button
                  v-if="previewSearchQuery"
                  class="preview-search-clear"
                  @click="previewSearchQuery = ''; onPreviewSearch()"
                >×</button>
              </div>
              <label class="preview-select-all">
                <input
                  type="checkbox"
                  :checked="isAllPreviewSelected"
                  @change="toggleSelectAllInPreview"
                />
                全选本页
              </label>
            </div>
          </div>
          <div class="preview-gallery-body">
            <div v-if="previewLoadingPage" class="preview-loading">加载中...</div>
            <div v-else-if="previewFiles.length === 0" class="preview-empty">暂无文件</div>
            <div v-else class="preview-grid">
              <div
                v-for="(file, index) in previewFiles"
                :key="file.id"
                class="preview-grid-item"
                :class="{ selected: selectedMediaFileIds.has(file.id) }"
              >
                <div class="preview-checkbox" @click.stop>
                  <input
                    type="checkbox"
                    :checked="selectedMediaFileIds.has(file.id)"
                    @change="togglePreviewSelection(file.id)"
                  />
                </div>
                <div class="preview-thumbnail" @click="enterSingleView(index)">
                  <img
                    v-if="file.fileType === 'IMAGE' && file.url && !isMediaError(file.id)"
                    :src="file.url"
                    :alt="file.fileName"
                    loading="lazy"
                    @error="markMediaError(file.id)"
                    @click.stop="openMediaImagePreview(file.url)"
                    style="cursor: zoom-in;"
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
                    <Icon name="audio" :size="28" />
                  </div>
                  <div v-else class="preview-thumbnail-file">
                    <Icon name="file" :size="28" />
                  </div>
                </div>
                <div class="preview-grid-info" @click="enterSingleView(index)">
                  <span class="preview-grid-name" :title="file.fileName">{{ file.fileName }}</span>
                  <small>{{ formatBytes(file.fileSize) }}</small>
                </div>
              </div>
            </div>
          </div>
          <div class="preview-gallery-footer">
            <div class="preview-pagination">
              <button
                class="preview-page-btn"
                :disabled="previewPage <= 0 || previewLoadingPage"
                @click="previewGoToPage(previewPage - 1)"
              >上一页</button>
              <span class="preview-page-info">{{ previewPage + 1 }} / {{ previewTotalPages }}</span>
              <button
                class="preview-page-btn"
                :disabled="previewPage >= previewTotalPages - 1 || previewLoadingPage"
                @click="previewGoToPage(previewPage + 1)"
              >下一页</button>
              <select
                v-if="previewTotalPages > 1"
                class="preview-page-select"
                :value="previewPage"
                @change="previewGoToPage(Number($event.target.value))"
              >
                <option v-for="p in previewTotalPages" :key="p" :value="p - 1">第 {{ p }} 页</option>
              </select>
            </div>
            <div class="preview-footer-actions">
              <span v-if="selectedMediaFileIds.size > 0" class="preview-selected-count">
                已选 {{ selectedMediaFileIds.size }} 个
              </span>
              <button
                v-if="selectedMediaFileIds.size > 0"
                class="btn-delete btn-sm"
                @click="confirmDeleteSelectedFromPreview"
              >删除选中</button>
              <button class="preview-nav-btn" @click="backToList">关闭预览</button>
            </div>
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
              @click.stop="openMediaImagePreview(currentPreviewFile.url)"
              style="cursor: zoom-in;"
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
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import Icon from '../components/Icon.vue';
import CreditsDashboard from '../components/credits/CreditsDashboard.vue';
import SubscriptionDashboard from '../components/credits/SubscriptionDashboard.vue';
import { formatFileSize, authApi, userApi } from '../services/api';
import { useComponentControl } from '../composables/useComponentControl';
import { useMediaManager } from '../composables/useMediaManager';
import { useUserDashboardData } from '../composables/useUserDashboardData';
import { useUserCreditsStore } from '../composables/useUserCreditsStore';
import { useImagePreview } from '../composables/useImagePreview';
import logger from '../utils/logger';

export default {
  name: 'UserCenter',
  components: { Icon, CreditsDashboard, SubscriptionDashboard },
  setup() {
    const router = useRouter();
    const route = useRoute();
    const activeTab = ref('dashboard');
    const autoFocusSignIn = ref(false);
    const autoOpenUpgrade = ref(false);
    const autoRelatedId = ref('');
    const dashboardLoaded = ref(false);

    const systemMessage = ref('');
    const systemMessageType = ref('success');
    const showSystemMsg = (msg, type = 'success') => {
      systemMessage.value = msg;
      systemMessageType.value = type;
      setTimeout(() => { systemMessage.value = ''; }, 5000);
    };

    const componentCtrl = useComponentControl({ showSystemMsg });
    const media = useMediaManager({ showSystemMsg });
    const dashboard = useUserDashboardData({ showSystemMsg });
    const imgPreview = useImagePreview();

    // 媒体管理网格：当前页所有图片URL（供 ← → 切换）
    const mediaGalleryUrls = computed(() =>
      (media.previewFiles.value || [])
        .filter(f => f && f.fileType === 'IMAGE' && f.url)
        .map(f => f.url)
    );
    const openMediaImagePreview = (url) => {
      if (!url) return;
      imgPreview.open(url, mediaGalleryUrls.value);
    };

    // 预览搜索
    const previewSearchQuery = ref('');
    const onPreviewSearch = () => {
      // 客户端过滤
      const query = previewSearchQuery.value.trim().toLowerCase();
      if (!query) {
        media.loadPreviewPage(media.previewPage.value);
        return;
      }
      const filtered = media.previewFiles.value.filter(f =>
        f.fileName && f.fileName.toLowerCase().includes(query)
      );
      // 客户端过滤只影响当前页显示，不改变分页
      media.previewFiles.value = filtered;
    };

    // 从预览中删除选中
    const confirmDeleteSelectedFromPreview = async () => {
      if (media.selectedMediaFileIds.value.size === 0) return;
      const ids = Array.from(media.selectedMediaFileIds.value);
      if (!confirm(`确定删除选中的 ${ids.length} 个文件吗？`)) return;
      try {
        await media.deleteSelectedMediaFiles();
        // 删除后重新加载当前页
        await media.loadPreviewPage(media.previewPage.value);
        if (previewSearchQuery.value) onPreviewSearch();
      } catch (e) {
        // deleteSelectedMediaFiles 已处理错误
      }
    };

    // 全局共享积分状态（与 Sidebar / UserMenuPopover 同步，签到后 Header 自动更新）
    const { balance, tier, tierLabel, expiresAt, reload: reloadCredits } = useUserCreditsStore();

    const formatShortDate = (dateStr) => {
      if (!dateStr) return '-';
      const d = new Date(dateStr);
      if (isNaN(d.getTime())) return dateStr;
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    };

    const formatCreditsNumber = (n) => {
      if (n === null || n === undefined) return '0';
      return Number(n).toLocaleString('zh-CN');
    };

    const navItems = [
      { key: 'dashboard', icon: 'dashboard', label: '数据概览' },
      { key: 'profile', icon: 'user', label: '个人信息' },
      { key: 'credits', icon: 'star', label: '用量管理' },
      { key: 'subscription', icon: 'layers', label: '订阅管理' },
    ];

    const handleNavClick = (key) => {
      activeTab.value = key;
      if (key === 'dashboard') {
        if (!dashboardLoaded.value) {
          dashboardLoaded.value = true;
          dashboard.loadAll();
        }
        nextTick(() => {
          if (!qqBindingsLoading.value && qqBindings.length === 0) {
            loadQqBindings();
          }
        });
      } else if (key === 'profile') {
        nextTick(() => {
          loadQqBindings();
        });
      }
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

    const avatarInput = ref(null);
    const isUploadingAvatar = ref(false);

    const triggerAvatarUpload = () => {
      avatarInput.value?.click();
    };

    const onAvatarChange = async (event) => {
      const file = event.target.files?.[0];
      if (!file) return;

      const validTypes = ['image/jpeg', 'image/png', 'image/gif'];
      if (!validTypes.includes(file.type)) {
        showSystemMsg('仅支持 JPG、PNG、GIF 格式', 'error');
        return;
      }
      if (file.size > 2 * 1024 * 1024) {
        showSystemMsg('图片大小不能超过 2MB', 'error');
        return;
      }

      isUploadingAvatar.value = true;
      try {
        const result = await userApi.uploadAvatar(file, 'user');
        if (result) {
          const avatarUrl = result.avatarUrl || result.url || result.avatar || (typeof result === 'string' ? result : null);
          if (avatarUrl) {
            const newInfo = { ...userInfo.value, avatar: avatarUrl };
            localStorage.setItem('user_info', JSON.stringify(newInfo));
            userInfo.value = newInfo;
            showSystemMsg('头像上传成功');
          } else {
            showSystemMsg('头像上传失败：返回数据异常', 'error');
          }
        }
      } catch (error) {
        showSystemMsg('头像上传失败：' + error.message, 'error');
      } finally {
        isUploadingAvatar.value = false;
        if (avatarInput.value) avatarInput.value.value = '';
      }
    };

    const showPasswordModal = ref(false);
    const passwordForm = ref({
      oldPassword: '',
      newPassword: '',
      confirmPassword: ''
    });
    const isPasswordSaving = ref(false);

    const isPasswordFormValid = computed(() => {
      const { oldPassword, newPassword, confirmPassword } = passwordForm.value;
      if (!oldPassword || !newPassword || !confirmPassword) return false;
      if (newPassword.length < 6) return false;
      if (newPassword !== confirmPassword) return false;
      return true;
    });

    const openPasswordModal = () => {
      passwordForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' };
      showPasswordModal.value = true;
    };

    const changePassword = async () => {
      if (!isPasswordFormValid.value || isPasswordSaving.value) return;
      isPasswordSaving.value = true;
      try {
        await authApi.changePassword(passwordForm.value.oldPassword, passwordForm.value.newPassword);
        showPasswordModal.value = false;
        showSystemMsg('密码修改成功');
      } catch (error) {
        showSystemMsg('密码修改失败：' + error.message, 'error');
      } finally {
        isPasswordSaving.value = false;
      }
    };

    const qqBindings = ref([]);
    const qqBindingsLoading = ref(false);

    const loadQqBindings = async () => {
      qqBindingsLoading.value = true;
      try {
        const data = await userApi.getQqBindings();
        qqBindings.value = data || [];
      } catch (error) {
        showSystemMsg('加载QQ绑定失败：' + error.message, 'error');
      } finally {
        qqBindingsLoading.value = false;
      }
    };

    const showBindModal = ref(false);
    const bindCodeSent = ref(false);
    const bindForm = ref({ qqNumber: '', code: '' });
    const isBindingSending = ref(false);
    const isBindingConfirm = ref(false);

    const isBindFormValid = computed(() => {
      return /^\d{5,11}$/.test(bindForm.value.qqNumber);
    });

    const isBindConfirmValid = computed(() => {
      return bindForm.value.code && bindForm.value.code.length === 6;
    });

    const openBindModal = () => {
      bindForm.value = { qqNumber: '', code: '' };
      bindCodeSent.value = false;
      showBindModal.value = true;
    };

    const sendBindCode = async () => {
      if (!isBindFormValid.value || isBindingSending.value) return;
      isBindingSending.value = true;
      try {
        await userApi.sendQqBindingCode(bindForm.value.qqNumber);
        bindCodeSent.value = true;
        showSystemMsg('验证码已发送');
      } catch (error) {
        showSystemMsg('发送失败：' + error.message, 'error');
      } finally {
        isBindingSending.value = false;
      }
    };

    const confirmBind = async () => {
      if (!isBindConfirmValid.value || isBindingConfirm.value) return;
      isBindingConfirm.value = true;
      try {
        await userApi.bindQq({
          qqNumber: bindForm.value.qqNumber,
          verificationCode: bindForm.value.code
        });
        showBindModal.value = false;
        showSystemMsg('QQ绑定成功');
        await loadQqBindings();
      } catch (error) {
        showSystemMsg('绑定失败：' + error.message, 'error');
      } finally {
        isBindingConfirm.value = false;
      }
    };

    const handleSetDefault = async (binding) => {
      try {
        await userApi.setDefaultQq(binding.id);
        showSystemMsg('已设为默认');
        await loadQqBindings();
      } catch (error) {
        showSystemMsg('设置失败：' + error.message, 'error');
      }
    };

    const handleUnbind = async (binding) => {
      if (!window.confirm(`确定解绑QQ ${binding.qqNumber} 吗？`)) return;
      try {
        await userApi.unbindQq(binding.id);
        showSystemMsg('解绑成功');
        await loadQqBindings();
      } catch (error) {
        showSystemMsg('解绑失败：' + error.message, 'error');
      }
    };

    const formatDate = (dateStr) => {
      if (!dateStr) return '-';
      return new Date(dateStr).toLocaleString('zh-CN');
    };

    const goHome = () => router.push('/');
    const handleAvatarError = (e) => {
      e.target.src = '/default-avatar.svg';
    };

    const handleCreditsOpenUpgrade = () => {
      // 切换到订阅管理 Tab，并触发升级权益弹窗自动打开
      activeTab.value = 'subscription';
      autoOpenUpgrade.value = false;
      nextTick(() => {
        autoOpenUpgrade.value = true;
      });
    };

    const handleCreditsSwitchTab = (tab) => {
      if (tab && navItems.some(n => n.key === tab)) {
        activeTab.value = tab;
      }
    };

    // 订阅页购买/退款成功后，通知用量管理页刷新余额
    const handleRefreshCredits = () => {
      window.dispatchEvent(new CustomEvent('credits:refresh'));
    };

    const parseInitialUrlParams = () => {
      try {
        const tab = route.query?.tab;
        const focus = route.query?.focus;
        const openUpgrade = route.query?.openUpgrade;
        const relatedId = route.query?.relatedId;
        if (tab && navItems.some(n => n.key === tab)) {
          activeTab.value = tab;
        }
        if (tab === 'credits' && focus === 'signIn') {
          autoFocusSignIn.value = true;
        }
        if (tab === 'subscription' && (openUpgrade === '1' || openUpgrade === 1 || openUpgrade === 'true')) {
          autoOpenUpgrade.value = true;
        }
        if (tab === 'credits' && relatedId) {
          autoRelatedId.value = String(relatedId);
        }
      } catch (e) {
        logger.warn('解析 URL query 失败:', e);
      }
    };

    // 监听路由 query 变化（从 AstrBotChat 跳转过来时，UserCenter 可能已挂载）
    watch(() => route.query, (newQuery) => {
      if (!newQuery) return;
      const tab = newQuery.tab;
      if (tab && navItems.some(n => n.key === tab) && activeTab.value !== tab) {
        activeTab.value = tab;
      }
      if (tab === 'credits' && newQuery.focus === 'signIn') {
        autoFocusSignIn.value = false;
        nextTick(() => { autoFocusSignIn.value = true; });
      }
      if (tab === 'subscription' && (newQuery.openUpgrade === '1' || newQuery.openUpgrade === 1)) {
        autoOpenUpgrade.value = false;
        nextTick(() => { autoOpenUpgrade.value = true; });
      }
      if (tab === 'credits' && newQuery.relatedId) {
        autoRelatedId.value = '';
        nextTick(() => { autoRelatedId.value = String(newQuery.relatedId); });
      }
    }, { deep: true });

    onMounted(() => {
      parseInitialUrlParams();

      componentCtrl.startPolling();
      componentCtrl.loadNapCatWebUiUrl();
      componentCtrl.refreshQrCode();
      if (componentCtrl.autoLogin.value) componentCtrl.checkNapCatLogin();
      window.addEventListener('keydown', media.onPreviewKeydown);

      loadUserInfo();
      reloadCredits();

      // 数据概览为默认标签页，进入页面即加载数据
      if (activeTab.value === 'dashboard') {
        dashboardLoaded.value = true;
        dashboard.loadAll();
      }
      // 加载QQ绑定列表（用于空状态判断）
      nextTick(() => {
        loadQqBindings();
      });

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
      activeTab, navItems,
      formatDate, formatBytes, goHome, handleAvatarError,
      handleNavClick,
      systemMessage, systemMessageType,
      autoFocusSignIn, autoOpenUpgrade, autoRelatedId,
      handleCreditsOpenUpgrade, handleCreditsSwitchTab, handleRefreshCredits,
      userInfo, userAvatarUrl,
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
      selectedMediaFileIds: media.selectedMediaFileIds,
      openPreview: media.openPreview,
      closePreview: media.closePreview,
      previewMode: media.previewMode,
      previewFiles: media.previewFiles,
      currentPreviewFile: media.currentPreviewFile,
      currentPreviewIndex: media.currentPreviewIndex,
      enterSingleView: media.enterSingleView,
      backToGallery: media.backToGallery,
      backToList: media.backToList,
      previewPrev: media.previewPrev,
      previewNext: media.previewNext,
      previewPage: media.previewPage,
      previewTotalPages: media.previewTotalPages,
      previewTotalElements: media.previewTotalElements,
      previewLoadingPage: media.previewLoadingPage,
      previewGoToPage: media.previewGoToPage,
      togglePreviewSelection: media.togglePreviewSelection,
      isAllPreviewSelected: media.isAllPreviewSelected,
      toggleSelectAllInPreview: media.toggleSelectAllInPreview,
      isMediaError: media.isMediaError,
      markMediaError: media.markMediaError,
      showPreviewModal: media.showPreviewModal,
      openMediaImagePreview,
      previewSearchQuery,
      onPreviewSearch,
      confirmDeleteSelectedFromPreview,
      // Dashboard
      dashboardStats: dashboard.stats,
      dashboardLoading: dashboard.isLoading,
      trendChartOption: dashboard.trendChartOption,
      groupRankingOption: dashboard.groupRankingOption,
      distributionChartOption: dashboard.distributionChartOption,
      aiTrendChartOption: dashboard.aiTrendChartOption,
      trendDays: dashboard.trendDays,
      trendInterval: dashboard.trendInterval,
      setTrendDays: dashboard.setTrendDays,
      loadDashboard: dashboard.loadAll,
      balance, tier, tierLabel, expiresAt,
      formatShortDate, formatCreditsNumber,
      showEditModal,
      isSaving,
      editForm,
      openEditModal,
      isValidEmail,
      isEditFormValid,
      saveProfile,
      avatarInput,
      isUploadingAvatar,
      triggerAvatarUpload,
      onAvatarChange,
      showPasswordModal,
      passwordForm,
      isPasswordSaving,
      isPasswordFormValid,
      openPasswordModal,
      changePassword,
      qqBindings,
      qqBindingsLoading,
      loadQqBindings,
      showBindModal,
      bindForm,
      bindCodeSent,
      isBindingSending,
      isBindingConfirm,
      isBindFormValid,
      isBindConfirmValid,
      openBindModal,
      sendBindCode,
      confirmBind,
      handleSetDefault,
      handleUnbind,
    };
  }
};
</script>

<style scoped>
.user-center {
  height: 100vh;
  background-color: var(--bg-primary, #f5f7fa);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.user-center-header {
  flex-shrink: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 24px;
  background-color: var(--card-bg, white);
  border-bottom: 1px solid var(--border-color, #e8e8e8);
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
  z-index: 10;
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
  color: var(--text-primary, #333);
}

.header-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

/* Header 积分摘要 */
.header-credits-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  background: linear-gradient(135deg, #f4f8ff, #faf4ff);
  border: 1px solid #e0e8f5;
  border-radius: 8px;
  font-size: 13px;
}
.hcs-tier {
  padding: 2px 10px;
  border-radius: 10px;
  background: var(--bg-tertiary, #ecf0f1);
  color: #7f8c8d;
  font-weight: 600;
  font-size: 12px;
}
.hcs-tier.paid {
  background: linear-gradient(135deg, #9b59b6, #8e44ad);
  color: #fff;
}
.hcs-expire {
  color: #95a5a6;
  font-size: 12px;
}
.hcs-divider {
  color: #ccc;
}
.hcs-balance {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-weight: 600;
  color: var(--text-primary, #2c3e50);
}
.hcs-gem {
  font-size: 13px;
}
.hcs-num {
  color: #3498db;
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
  color: var(--text-primary, #333);
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
  background: var(--card-bg, white);
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
  color: var(--text-secondary, #555);
  line-height: 1.6;
}

.intro-content ul {
  margin: 0 0 15px 0;
  padding-left: 0;
  color: var(--text-secondary, #555);
  line-height: 1.8;
  list-style: none;
}

.intro-content li {
  padding: 4px 0;
}

.intro-content li strong {
  color: var(--text-primary, #333);
}

.intro-tip {
  color: var(--text-muted, #888);
  font-size: 13px;
  margin-top: 10px;
  padding: 10px;
  background: var(--bg-primary, #f5f7fa);
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
  background: var(--card-bg, white);
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
  color: var(--text-primary, #333);
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
  background: var(--card-bg, white);
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}

.section-card h4 {
  margin: 0 0 15px 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary, #333);
}

.section-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
}

.collapse-toggle {
  padding: 4px 8px;
  background-color: var(--bg-tertiary, #ecf0f1);
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
  border: 2px solid var(--border-color, #eee);
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

.chart-btn {
  padding: 4px 12px;
  background-color: var(--card-bg, white);
  border: 1px solid var(--border-color, #ddd);
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}

.chart-btn.active {
  background-color: #3498db;
  color: white;
  border-color: #3498db;
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

.profile-card {
  max-width: 600px;
}

.profile-avatar-section {
  display: flex;
  align-items: center;
  gap: 20px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--border-color, #eee);
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
  color: var(--text-primary, #333);
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
  border-bottom: 1px solid var(--border-color, #f0f0f0);
}

.detail-row label {
  font-weight: 500;
  color: var(--text-secondary, #666);
}

.detail-row span {
  color: var(--text-primary, #333);
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
  background-color: var(--card-bg, white);
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
  border-bottom: 1px solid var(--border-color, #eee);
}

.modal-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary, #333);
}

.btn-close {
  background: none;
  border: none;
  color: var(--text-muted, #999);
  cursor: pointer;
  padding: 4px;
}

.btn-close:hover {
  color: var(--text-primary, #333);
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
  color: var(--text-secondary, #666);
  font-size: 14px;
}

.form-group input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--border-color, #ddd);
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
  border-top: 1px solid var(--border-color, #eee);
}

.btn-cancel {
  padding: 8px 20px;
  background-color: var(--bg-tertiary, #f5f5f5);
  color: var(--text-secondary, #666);
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

/* 全局系统消息提示（高于 modal 层级） */
.system-toast {
  position: fixed;
  top: 24px;
  left: 50%;
  transform: translateX(-50%);
  padding: 12px 24px;
  border-radius: 8px;
  color: #fff;
  font-size: 14px;
  z-index: 9999;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.2);
  animation: toast-slide-in 0.3s ease;
}
.system-toast.success { background-color: #07c160; }
.system-toast.error { background-color: #e74c3c; }
@keyframes toast-slide-in {
  from { opacity: 0; transform: translate(-50%, -16px); }
  to { opacity: 1; transform: translate(-50%, 0); }
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
  background: var(--card-bg, white);
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
  position: relative;
  padding: 8px;
  border-radius: 8px;
  border: 2px solid transparent;
  transition: border-color 0.2s, background-color 0.2s;
}

.preview-grid-item:hover {
  background-color: var(--bg-tertiary, #f0f7ff);
}

.preview-grid-item.selected {
  border-color: #3498db;
  background-color: #e8f4fd;
}

.preview-checkbox {
  position: absolute;
  top: 4px;
  left: 4px;
  z-index: 2;
  background: rgba(255,255,255,0.9);
  border-radius: 3px;
  padding: 2px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.preview-checkbox input {
  margin: 0;
  width: 14px;
  height: 14px;
  cursor: pointer;
}

.preview-gallery-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 8px;
}

.preview-search {
  position: relative;
  flex: 1;
  min-width: 200px;
  max-width: 320px;
}

.preview-search-input {
  width: 100%;
  padding: 6px 32px 6px 12px;
  border: 1px solid var(--border-color, #ddd);
  border-radius: 16px;
  font-size: 13px;
  outline: none;
  transition: border-color 0.2s;
}

.preview-search-input:focus {
  border-color: #3498db;
}

.preview-search-clear {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  background: none;
  border: none;
  color: var(--text-muted, #999);
  cursor: pointer;
  font-size: 16px;
  padding: 0 4px;
  line-height: 1;
}

.preview-search-clear:hover {
  color: var(--text-secondary, #666);
}

.preview-select-all {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: var(--text-secondary, #555);
  cursor: pointer;
  user-select: none;
}

.preview-select-all input {
  cursor: pointer;
}

.preview-footer-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.preview-selected-count {
  font-size: 13px;
  color: var(--text-secondary, #666);
  padding: 4px 10px;
  background-color: #e8f4fd;
  border-radius: 4px;
}

.btn-delete.btn-sm {
  padding: 5px 12px;
  font-size: 12px;
  background-color: #e74c3c;
  color: #fff;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.btn-delete.btn-sm:hover {
  background-color: #c0392b;
}

.preview-gallery-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.preview-gallery-header {
  margin-bottom: 12px;
}

.preview-thumbnail {
  aspect-ratio: 1;
  background-color: var(--bg-tertiary, #f5f5f5);
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
  color: var(--text-primary, #333);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.preview-gallery-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding-top: 15px;
  border-top: 1px solid var(--border-color, #eee);
}

.preview-pagination {
  display: flex;
  align-items: center;
  gap: 8px;
}

.preview-page-btn {
  padding: 5px 12px;
  background-color: var(--card-bg, #fff);
  color: #3498db;
  border: 1px solid #3498db;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  transition: all 0.2s;
}

.preview-page-btn:hover:not(:disabled) {
  background-color: #3498db;
  color: #fff;
}

.preview-page-btn:disabled {
  border-color: #ccc;
  color: #ccc;
  cursor: not-allowed;
  background-color: var(--bg-tertiary, #f5f5f5);
}

.preview-page-info {
  font-size: 13px;
  color: var(--text-secondary, #666);
  min-width: 60px;
  text-align: center;
}

.preview-page-select {
  padding: 4px 8px;
  border: 1px solid var(--border-color, #ddd);
  border-radius: 4px;
  font-size: 12px;
  background-color: var(--card-bg, #fff);
  cursor: pointer;
}

.preview-page-select:focus {
  outline: none;
  border-color: #3498db;
}

.preview-loading {
  text-align: center;
  padding: 60px 20px;
  color: var(--text-muted, #999);
  font-size: 14px;
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
  background-color: var(--border-color, #f0f0f0);
}

.profile-cards-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 20px;
}

.profile-card-new {
  height: 100%;
}

.profile-card-new h4 {
  margin: 0 0 15px 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary, #333);
}

.profile-avatar-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}

.profile-avatar-large {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  overflow: hidden;
  border: 3px solid #3498db;
  position: relative;
  cursor: pointer;
  transition: border-color 0.2s;
}

.profile-avatar-large:hover {
  border-color: #2980b9;
}

.profile-avatar-large img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-upload-overlay {
  position: absolute;
  inset: 0;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 12px;
  gap: 4px;
  opacity: 0;
  transition: opacity 0.2s;
}

.profile-avatar-large:hover .avatar-upload-overlay {
  opacity: 1;
}

.profile-info {
  text-align: center;
}

.profile-info h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--text-primary, #333);
}

.role-badge {
  display: inline-block;
  margin-top: 6px;
  padding: 2px 10px;
  font-size: 12px;
  border-radius: 12px;
  background-color: var(--bg-tertiary, #ecf0f1);
  color: #7f8c8d;
}

.role-badge.admin {
  background-color: #fff3cd;
  color: #856404;
}

.password-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 10px 0;
}

.password-desc {
  margin: 0;
  color: #7f8c8d;
  font-size: 13px;
  text-align: center;
}

.btn-change-password {
  padding: 8px 18px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.btn-change-password:hover {
  background-color: #2980b9;
}

.qq-loading {
  text-align: center;
  padding: 20px;
  color: #7f8c8d;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.qq-default-badge {
  padding: 2px 8px;
  background-color: #27ae60;
  color: white;
  font-size: 11px;
  border-radius: 10px;
  font-weight: 500;
}

.qq-bindings-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.qq-bindings-header h4 {
  margin: 0;
  font-size: 15px;
  color: var(--text-primary, #2c3e50);
}

.qq-add-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 12px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.qq-add-btn:hover {
  background-color: #2980b9;
}

.qq-binding-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.qq-action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--border-color, #ddd);
  background-color: var(--card-bg, white);
  border-radius: 4px;
  color: var(--text-secondary, #666);
  cursor: pointer;
  transition: all 0.2s;
  padding: 0;
}

.qq-action-btn:hover {
  border-color: #3498db;
  color: #3498db;
}

.qq-action-btn.danger:hover {
  border-color: #e74c3c;
  color: #e74c3c;
}

.qq-empty {
  text-align: center;
  padding: 30px 20px;
  color: #7f8c8d;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.qq-empty svg {
  color: #95a5a6;
}

.qq-empty-hint {
  font-size: 12px;
  color: #95a5a6;
}

.qq-binding-number {
  color: #7f8c8d;
  font-size: 12px;
}

.qq-bindings-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.qq-binding-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  background-color: var(--bg-tertiary, #f8f9fa);
  border-radius: 6px;
  transition: background-color 0.2s;
}

.qq-binding-item:hover {
  background-color: var(--bg-tertiary, #eef2f5);
}

.qq-binding-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid var(--border-color, #ddd);
}

.qq-binding-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.qq-binding-nickname {
  font-weight: 500;
  color: var(--text-primary, #333);
  font-size: 14px;
}

.bind-qr-section {
  text-align: center;
  padding: 16px;
  background-color: var(--bg-tertiary, #f8f9fa);
  border-radius: 8px;
  margin-bottom: 16px;
}

.bind-tip {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--text-secondary, #555);
}

.bind-qq-number {
  font-size: 24px;
  font-weight: bold;
  color: var(--text-primary, #2c3e50);
  margin: 8px 0;
  letter-spacing: 2px;
}

.bind-tip-small {
  margin: 0;
  font-size: 12px;
  color: #95a5a6;
}

/* ===== 数据概览 ===== */
.dashboard-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 60px 20px;
  color: #7f8c8d;
  font-size: 13px;
  background: var(--card-bg, white);
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}

.dashboard-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 60px 20px;
  color: #95a5a6;
  background: var(--card-bg, white);
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}

.dashboard-empty p {
  margin: 0;
  font-size: 14px;
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
  background: var(--card-bg, white);
  border-radius: 8px;
  border: 1px solid var(--border-color, #e8e8e8);
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}

.stat-icon {
  width: 52px;
  height: 52px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.stat-icon.blue { background: #e3f2fd; color: #1976d2; }
.stat-icon.purple { background: #f3e5f5; color: #7b1fa2; }
.stat-icon.green { background: #e8f5e9; color: #388e3c; }
.stat-icon.orange { background: #fff3e0; color: #f57c00; }
.stat-icon.teal { background: #e0f2f1; color: #00796b; }
.stat-icon.red { background: #ffebee; color: #c62828; }
.stat-icon.amber { background: #fff8e1; color: #ff8f00; }

.stat-info {
  min-width: 0;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary, #333);
  line-height: 1.2;
}

.stat-label {
  font-size: 13px;
  color: var(--text-secondary, #666);
  margin-top: 2px;
}

.stat-sub {
  font-size: 12px;
  color: #3498db;
  margin-top: 2px;
}

/* 图表 */
.charts-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 20px;
}

.chart-card {
  background: var(--card-bg, white);
  border-radius: 8px;
  border: 1px solid var(--border-color, #e8e8e8);
  padding: 18px;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}

.chart-card h4 {
  margin: 0 0 16px 0;
  font-size: 14px;
  color: var(--text-primary, #333);
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

@media (max-width: 768px) {
  .charts-grid {
    grid-template-columns: 1fr;
  }
  .stats-cards {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
