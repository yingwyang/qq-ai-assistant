<template>
  <div class="admin-view" :class="'theme-' + (currentTheme || 'light')">
    <!-- 顶部导航栏 -->
    <header class="admin-header">
      <div class="header-brand">
        <button class="sidebar-toggle" @click="sidebarOpen = !sidebarOpen" aria-label="切换侧边栏">
          <Icon name="menu" :size="20" />
        </button>
        <Icon name="admin" :size="24" />
        <h1>系统管理中心</h1>
      </div>
      <div class="header-actions">
        <button
          class="health-pill"
          :class="'health-' + healthLevel"
          :title="`AstrBot ${health.astrbot ? '在线' : '离线'} · NapCat ${health.napcat ? '在线' : '离线'} · GPT-SoVITS ${health.gptsovits ? '在线' : '离线'}`"
          @click="setActiveTab('components')"
        >
          <span class="health-dot"></span>
          <span>{{ healthText }}</span>
        </button>
        <button class="btn-home" @click="toggleTheme">
          <Icon :name="currentTheme === 'dark' ? 'sun' : 'moon'" :size="16" />
          {{ currentTheme === 'dark' ? '浅色' : '暗色' }}
        </button>
        <button class="btn-home" @click="goHome">
          <Icon name="home" :size="16" /> 返回首页
        </button>
        <button class="btn-logout" @click="logout">
          <Icon name="logout" :size="16" /> 退出登录
        </button>
      </div>
    </header>

    <div class="admin-layout">
      <!-- 移动端遮罩 -->
      <div v-if="sidebarOpen" class="sidebar-overlay" @click="sidebarOpen = false"></div>

      <!-- 左侧导航 -->
      <aside class="admin-sidebar" :class="{ open: sidebarOpen }">
        <nav class="admin-nav">
          <template v-for="item in navItems" :key="item.key">
            <div v-if="item.isGroup" class="nav-group-title">{{ item.label }}</div>
            <div
              v-else
              class="nav-item"
              :class="{ active: activeTab === item.key }"
              @click="setActiveTab(item.key); sidebarOpen = false"
            >
              <Icon :name="item.icon" :size="18" />
              <span>{{ item.label }}</span>
            </div>
          </template>
        </nav>
      </aside>

      <!-- 主内容区：动态切换子组件 -->
      <main class="admin-main">
        <component :is="currentComponent" />
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

    <!-- ===== 纠纷处理弹窗 ===== -->
    <div v-if="disputeModal.visible" class="modal-overlay" @click.self="closeDisputeModal">
      <div class="modal-content small-modal credit-modal">
        <div class="modal-header">
          <h3>处理纠纷</h3>
          <button class="btn-close" @click="closeDisputeModal">×</button>
        </div>
        <div class="modal-body">
          <div class="refund-order-info">
            <div><label>订单号：</label><span>{{ disputeModal.orderNo }}</span></div>
          </div>
          <div class="form-group">
            <label>处理方式 <span style="color:red">*</span></label>
            <select v-model="disputeModal.agree" class="audit-action-select" style="width:100%">
              <option :value="true">同意退款（全额退款）</option>
              <option :value="false">驳回纠纷（恢复已支付状态）</option>
            </select>
          </div>
          <div class="form-group">
            <label>处理说明 <span style="color:red">*</span></label>
            <textarea v-model="disputeModal.reason" rows="3" placeholder="请输入处理说明"></textarea>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="closeDisputeModal">取消</button>
          <button class="btn-save btn-warn" :disabled="disputeModal.submitting" @click="submitResolveDispute">
            {{ disputeModal.submitting ? '提交中...' : '确认处理' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 退款审批弹窗（同意 / 驳回复用） -->
    <div v-if="refundApproveModal.visible" class="modal-overlay" @click.self="closeRefundApproveModal">
      <div class="modal-content small-modal credit-modal">
        <div class="modal-header">
          <h3>{{ refundApproveModal.mode === 'approve' ? '同意退款' : '驳回退款' }}</h3>
          <button class="btn-close" @click="closeRefundApproveModal">×</button>
        </div>
        <div class="modal-body">
          <div class="refund-order-info">
            <div><label>订单号：</label><span>{{ refundApproveModal.orderNo }}</span></div>
          </div>
          <div class="form-group">
            <label>{{ refundApproveModal.mode === 'approve' ? '审批原因' : '驳回原因' }}</label>
            <textarea v-model="refundApproveModal.reason" rows="3" placeholder="请输入原因（可选）"></textarea>
          </div>
          <p class="modal-tip">
            {{ refundApproveModal.mode === 'approve' ? '同意后将执行退款并按比例扣减已发放积分。' : '驳回后订单将恢复至退款申请前的状态。' }}
          </p>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="closeRefundApproveModal">取消</button>
          <button
            class="btn-save"
            :class="refundApproveModal.mode === 'approve' ? 'btn-warn' : 'btn-danger'"
            :disabled="refundApproveModal.submitting"
            @click="submitRefundApprove"
          >
            {{ refundApproveModal.submitting ? '提交中...' : (refundApproveModal.mode === 'approve' ? '确认同意' : '确认驳回') }}
          </button>
        </div>
      </div>
    </div>

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
            <StatePanel v-if="detailDrawer.loading" state="loading" />
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
            <button v-if="detailDrawer.data.status === 'DISPUTED'" class="btn-save btn-warn" style="width:100%" @click="openDisputeModal(detailDrawer.data.orderNo)">处理纠纷</button>
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
            <small>
              共 {{ previewTotalElements }} 个文件 · 第 {{ previewPage + 1 }} / {{ previewTotalPages }} 页（本页 {{ previewFiles.length }} 个） · 已选 {{ selectedMediaFileIds.size }} 个
            </small>
          </div>

          <div class="preview-gallery-body">
            <div v-if="previewLoadingPage" class="preview-empty">加载中...</div>
            <div v-else-if="previewFiles.length === 0" class="preview-empty">暂无文件</div>
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
                  <img
                    v-else-if="file.fileType === 'VIDEO'"
                    src="/deleted-video.svg"
                    :alt="file.fileName + '（已删除）'"
                    class="preview-thumbnail-placeholder"
                    style="width: 64px; height: 64px; object-fit: contain;"
                  />
                  <div v-else-if="file.fileType === 'AUDIO'" class="preview-thumbnail-audio">
                    <Icon name="audio" :size="28" />
                  </div>
                  <div v-else class="preview-thumbnail-file">
                    <Icon name="file" :size="28" />
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
              {{ isAllPreviewSelected ? '取消全选' : '全选本页' }}
            </button>

            <!-- 相册分页：一页 40 张，不翻页就永远只能看到第一页 -->
            <div class="preview-pager">
              <button class="preview-nav-btn" :disabled="previewPage <= 0 || previewLoadingPage" @click="previewGoToPage(0)">首页</button>
              <button class="preview-nav-btn" :disabled="previewPage <= 0 || previewLoadingPage" @click="previewGoToPage(previewPage - 1)">上一页</button>
              <span class="preview-pager-info">第 {{ previewPage + 1 }} / {{ previewTotalPages }} 页</span>
              <button class="preview-nav-btn" :disabled="previewPage >= previewTotalPages - 1 || previewLoadingPage" @click="previewGoToPage(previewPage + 1)">下一页</button>
              <button class="preview-nav-btn" :disabled="previewPage >= previewTotalPages - 1 || previewLoadingPage" @click="previewGoToPage(previewTotalPages - 1)">末页</button>
              <label class="preview-page-size">
                每页
                <select :value="String(previewPageSize)" @change="setPreviewPageSize($event.target.value)">
                  <option value="20">20</option>
                  <option value="40">40</option>
                  <option value="80">80</option>
                </select>
              </label>
            </div>

            <button class="preview-nav-btn" @click="backToList">关闭预览</button>
          </div>
        </div>

        <!-- 单张查看模式 -->
        <div v-else-if="currentPreviewFile" class="preview-single">
          <div class="preview-title">
            <span>{{ currentPreviewFile.fileName }}</span>
            <small>
              {{ formatBytes(currentPreviewFile.fileSize) }} ·
              <template v-if="previewSource === 'all'">
                第 {{ previewPage * previewPageSize + currentPreviewIndex + 1 }} / {{ previewTotalElements }} 个（本页 {{ currentPreviewIndex + 1 }} / {{ previewFiles.length }}）
              </template>
              <template v-else>
                {{ currentPreviewIndex + 1 }} / {{ previewFiles.length }}（已勾选）
              </template>
            </small>
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
              :disabled="previewPrevDisabled"
              @click="previewPrev"
            >
              ← 上一个
            </button>
            <button class="preview-nav-btn" @click="backToGallery">
              返回相册
            </button>
            <button
              class="preview-nav-btn"
              :disabled="previewNextDisabled"
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

    <!-- 全局确认框：admin 域内所有 showConfirm() 调用都靠它渲染（原先只在 HomeView 挂载） -->
    <ConfirmDialog />
  </div>
</template>

<script>
import './admin/admin-shared.css';
import { ref, reactive, computed, onMounted, onUnmounted, provide, defineAsyncComponent, shallowRef, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import Icon from '../components/Icon.vue';
import ConfirmDialog, { showConfirm } from '../components/ConfirmDialog.vue';
import { formatFileSize, logout as apiLogout, adminOrdersApi, systemApi } from '../services/api';
import { useTheme } from '../composables/useTheme';
import { useAdminNav, adminTabMeta, isAdminTabKey } from '../composables/useAdminNav';
import { useDashboardData } from '../composables/useDashboardData';
import { useComponentControl } from '../composables/useComponentControl';
import { useUserManagement } from '../composables/useUserManagement';
import { useMediaManager } from '../composables/useMediaManager';
import { useSystemLog } from '../composables/useSystemLog';
import { useConfigManagement } from '../composables/useConfigManagement';
import { useMaintenance } from '../composables/useMaintenance';
import { useAdminCreditsRule } from '../composables/useAdminCreditsRule';
import { useAdminUserCredits } from '../composables/useAdminUserCredits';
import { useImagePreview } from '../composables/useImagePreview';
import {
  useAdminTransactions,
  TX_TYPE_OPTIONS as TX_TYPE_OPTS,
  CASH_CATEGORY_OPTIONS,
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
import logger from '../utils/logger';

export default {
  name: 'AdminView',
  components: { Icon, ConfirmDialog },
  setup() {
    const router = useRouter();
    const route = useRoute();
    const { theme: currentTheme, toggleTheme } = useTheme();
    const sidebarOpen = ref(false);
    const currentComponent = shallowRef(null);

    const systemMessage = ref('');
    const systemMessageType = ref('');
    const showSystemMsg = (msg, type = 'success') => {
      systemMessage.value = msg;
      systemMessageType.value = type;
      setTimeout(() => { systemMessage.value = ''; }, 5000);
    };

    // 创建所有模块 composables（单例，通过 provide 共享给子组件）
    const dashboard = useDashboardData();
    const componentCtrl = useComponentControl({ showSystemMsg });
    const userMgmt = useUserManagement({ showSystemMsg });
    const isMediaCollapsed = ref(false);
    const media = useMediaManager({ showSystemMsg, loadDiskUsage: dashboard.loadDiskUsage });
    const imgPreview = useImagePreview();
    const systemLog = useSystemLog({ showSystemMsg });
    const configMgmt = useConfigManagement({ showSystemMsg });
    const maintenance = useMaintenance({ showSystemMsg });
    const adminRule = useAdminCreditsRule({ showSystemMsg });
    const adminUserCredits = useAdminUserCredits({ showSystemMsg });
    const adminTx = useAdminTransactions({ showSystemMsg });
    const adminOrders = useAdminOrders({ showSystemMsg });

    // ===== 退款审批 / 纠纷处理：待处理数量徽章 =====
    const pendingRefundCount = ref(0);
    const pendingDisputeCount = ref(0);
    const loadPendingCount = async () => {
      try {
        const data = await adminOrdersApi.getPendingCount();
        pendingRefundCount.value =
          data?.pendingRefundCount ?? data?.pendingRefund ?? data?.refundCount ?? 0;
        pendingDisputeCount.value =
          data?.pendingDisputeCount ?? data?.pendingDispute ?? data?.disputeCount ?? 0;
      } catch (e) {
        // 静默失败，不影响主流程
      }
    };

    // ===== 导航（tab 持久化 / URL 深链接 / 按需加载） =====
    const badgeCounts = computed(() => ({
      pendingRefund: pendingRefundCount.value,
      pendingDispute: pendingDisputeCount.value,
    }));

    const { activeTab, setActiveTab, onTabChange, navItems, currentTabMeta } = useAdminNav({
      route,
      router,
      badgeCounts,
    });

    // 已创建过的异步子组件缓存：切回同一 tab 不重新构造组件
    const componentCache = new Map();
    const componentFor = (key) => {
      if (!componentCache.has(key)) {
        componentCache.set(key, defineAsyncComponent(adminTabMeta(key).loader));
      }
      return componentCache.get(key);
    };

    // 订单管理 / 退款审批 / 纠纷处理共用同一份 adminOrders.filters：
    // 后两者进入时会把 statusSelected 改成 PENDING_REFUND / DISPUTED，若切回订单管理时不还原，
    // 就会带着这两个状态过滤去查订单 → 显示"暂无订单数据"（旧 bug）。
    // 这里按 tab 分别存快照，三者互不污染。
    const ORDER_TABS = ['credit-orders', 'credit-refund-approve', 'credit-dispute'];
    const tabFilterSnapshots = {};
    const snapshotOrderFilters = (key) => {
      tabFilterSnapshots[key] = {
        ...adminOrders.filters,
        statusSelected: [...adminOrders.filters.statusSelected],
      };
    };
    const restoreOrderFilters = (key) => {
      const snap = tabFilterSnapshots[key];
      if (!snap) return;
      Object.assign(adminOrders.filters, snap);
      adminOrders.filters.statusSelected = [...(snap.statusSelected || [])];
    };

    // 首次进入某 tab 时才请求它需要的数据（不再 onMounted 一把梭）
    const loadedTabs = new Set();
    const loadTabData = (key, { force = false } = {}) => {
      if (loadedTabs.has(key) && !force) return;
      loadedTabs.add(key);
      if (key === 'dashboard') {
        dashboard.loadAll();
      } else if (key === 'users') {
        userMgmt.loadUsers();
      } else if (key === 'media') {
        media.loadMediaFiles();
      } else if (key === 'config') {
        configMgmt.loadConfig();
      } else if (key === 'maintenance') {
        maintenance.loadBackupList();
      } else if (key === 'credit-rule') {
        adminRule.loadRule();
      } else if (key === 'credit-users') {
        adminUserCredits.loadUserCredits(0);
      } else if (key === 'credit-transactions') {
        adminTx.loadTransactions(0);
      } else if (key === 'credit-orders') {
        restoreOrderFilters(key);
        adminOrders.loadOrders(0);
      } else if (key === 'credit-refund-approve') {
        adminOrders.filters.orderNo = '';
        adminOrders.filters.keyword = '';
        adminOrders.filters.statusSelected = ['PENDING_REFUND'];
        adminOrders.loadOrders(0);
        loadPendingCount();
      } else if (key === 'credit-dispute') {
        adminOrders.filters.orderNo = '';
        adminOrders.filters.keyword = '';
        adminOrders.filters.statusSelected = ['DISPUTED'];
        adminOrders.loadOrders(0);
        loadPendingCount();
      }
    };

    // tab 变化时：换组件 + 按需加载 + 轮询开关
    const applyTab = (key, prevKey) => {
      if (ORDER_TABS.includes(prevKey) && prevKey !== key) {
        snapshotOrderFilters(prevKey);
      }
      currentComponent.value = componentFor(key);
      if (key === 'components') {
        componentCtrl.startPolling();
        componentCtrl.loadNapCatWebUiUrl();
        componentCtrl.refreshQrCode();
        if (componentCtrl.autoLogin.value) componentCtrl.checkNapCatLogin();
      } else if (prevKey === 'components') {
        componentCtrl.stopPolling();
      }
      // 系统日志的自动刷新只在日志页生效
      if (prevKey === 'log' && key !== 'log') {
        systemLog.stopAutoRefresh();
      }
      // 审批队列每次进入都要拿最新数据，其余页面只加载一次
      loadTabData(key, { force: ORDER_TABS.includes(key) });
    };
    onTabChange(applyTab);

    // 媒体管理：当前页所有图片URL（供 ← → 切换）
    const mediaGalleryUrls = computed(() =>
      (media.previewFiles.value || [])
        .filter(f => f && f.fileType === 'IMAGE' && f.url)
        .map(f => f.url)
    );
    const openMediaImagePreview = (url) => {
      if (!url) return;
      imgPreview.open(url, mediaGalleryUrls.value);
    };

    // 预览导航可用性：勾选预览时只在本屏内移动；「全部媒体」模式下可跨页继续
    const previewPrevDisabled = computed(() => {
      if (media.currentPreviewIndex.value > 0) return false;
      return !(media.previewSource.value === 'all' && media.previewPage.value > 0);
    });
    const previewNextDisabled = computed(() => {
      if (media.currentPreviewIndex.value < media.previewFiles.value.length - 1) return false;
      return !(media.previewSource.value === 'all'
        && media.previewPage.value < media.previewTotalPages.value - 1);
    });

    // ===== 退款审批弹窗（同意 / 驳回复用） =====
    const refundApproveModal = reactive({
      visible: false,
      orderNo: '',
      mode: 'approve',
      reason: '',
      submitting: false,
    });
    const openApproveRefundModal = (orderNo) => {
      refundApproveModal.visible = true;
      refundApproveModal.orderNo = orderNo;
      refundApproveModal.mode = 'approve';
      refundApproveModal.reason = '';
      refundApproveModal.submitting = false;
    };
    const openRejectRefundModal = (orderNo) => {
      refundApproveModal.visible = true;
      refundApproveModal.orderNo = orderNo;
      refundApproveModal.mode = 'reject';
      refundApproveModal.reason = '';
      refundApproveModal.submitting = false;
    };
    const closeRefundApproveModal = () => {
      refundApproveModal.visible = false;
    };
    const submitRefundApprove = async () => {
      if (!refundApproveModal.orderNo) return;
      refundApproveModal.submitting = true;
      try {
        const isApprove = refundApproveModal.mode === 'approve';
        const fn = isApprove ? adminOrdersApi.approveRefund : adminOrdersApi.rejectRefund;
        await fn(refundApproveModal.orderNo, refundApproveModal.reason.trim());
        if (showSystemMsg) {
          showSystemMsg(`订单 ${refundApproveModal.orderNo} ${isApprove ? '退款已同意' : '退款已驳回'}`);
        }
        closeRefundApproveModal();
        await adminOrders.loadOrders(adminOrders.ordersPage.value);
        loadPendingCount();
      } catch (error) {
        if (showSystemMsg) showSystemMsg('操作失败: ' + error.message, 'error');
      } finally {
        refundApproveModal.submitting = false;
      }
    };

    // ===== 纠纷处理：按按钮预设 agree 值 =====
    const openDisputeAgree = (orderNo) => {
      adminOrders.openDisputeModal(orderNo);
      adminOrders.disputeModal.agree = true;
    };
    const openDisputeReject = (orderNo) => {
      adminOrders.openDisputeModal(orderNo);
      adminOrders.disputeModal.agree = false;
    };
    const submitResolveDispute = async () => {
      await adminOrders.submitResolveDispute();
      loadPendingCount();
    };

    // ===== 状态映射 =====
    const orderStatusText = (s) => {
      if (s === 'PENDING_REFUND') return '退款审批中';
      return orderStatusLabel(s);
    };

    const currentFilesTotalSize = computed(() =>
      media.mediaFiles.value.reduce((sum, f) => sum + (f.fileSize || 0), 0)
    );
    const totalMediaPages = computed(() =>
      Math.ceil(media.mediaFilesTotal.value / media.mediaFileSize.value) || 1
    );
    const confirmDeleteSelected = async () => {
      const count = media.selectedMediaFileIds.value.size;
      const ok = await showConfirm({
        title: '删除媒体文件',
        message: `确定删除选中的 ${count} 个文件吗？删除后关联消息会被软删除，文件不可恢复。`,
        type: 'warning',
        confirmText: '删除',
      });
      if (!ok) return;
      media.deleteSelectedMediaFiles();
    };
    const confirmPurgeByFilter = async () => {
      const filter = media.mediaFileFilter.value;
      const labels = { ALL: '全部', IMAGE: '图片', VIDEO: '视频', AUDIO: '音频' };
      const ok = await showConfirm({
        title: '清理媒体文件',
        message: `确定清理${labels[filter] || ''}媒体文件吗？清理后不可恢复。`,
        type: 'warning',
        confirmText: '立即清理',
      });
      if (!ok) return;
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
      try {
        await apiLogout();
      } catch (e) {
        logger.warn('后端登出失败（忽略）:', e);
      }
      localStorage.removeItem('user_role');
      localStorage.removeItem('user_info');
      localStorage.removeItem('isLoggedIn');
      router.push('/login');
    };

    // ===== 顶部健康指示 + 待办角标轮询 =====
    const health = reactive({ astrbot: false, napcat: false, gptsovits: false, known: false });
    const healthText = computed(() => {
      if (!health.known) return '状态未知';
      const running = [health.astrbot, health.napcat, health.gptsovits].filter(Boolean).length;
      return `组件 ${running}/3 在线`;
    });
    const healthLevel = computed(() => {
      if (!health.known) return 'unknown';
      const running = [health.astrbot, health.napcat, health.gptsovits].filter(Boolean).length;
      if (running === 3) return 'ok';
      return running === 0 ? 'down' : 'partial';
    });
    const loadHealth = async () => {
      try {
        const res = await systemApi.getComponentStatus();
        health.astrbot = !!res?.astrbot?.running;
        health.napcat = !!res?.napcat?.running;
        health.gptsovits = !!res?.gptsovits?.running;
        health.known = true;
      } catch (e) {
        health.known = false;
      }
    };

    let healthTimer = null;
    let pendingTimer = null;
    const startShellPolling = () => {
      stopShellPolling();
      loadHealth();
      pendingTimer = setInterval(() => {
        if (document.hidden) return;
        loadPendingCount();
      }, 60000);
      healthTimer = setInterval(() => {
        if (document.hidden) return;
        loadHealth();
      }, 60000);
    };
    const stopShellPolling = () => {
      if (healthTimer) { clearInterval(healthTimer); healthTimer = null; }
      if (pendingTimer) { clearInterval(pendingTimer); pendingTimer = null; }
    };

    onMounted(() => {
      // 只加载当前 tab 需要的数据（其余页面首次打开时再请求）
      const initialTab = isAdminTabKey(activeTab.value) ? activeTab.value : 'dashboard';
      applyTab(initialTab, null);
      loadPendingCount();
      startShellPolling();
      window.addEventListener('keydown', media.onPreviewKeydown);
    });
    onUnmounted(() => {
      componentCtrl.stopPolling();
      stopShellPolling();
      userMgmt.cleanup();
      adminUserCredits.cleanup();
      systemLog.cleanup();
      window.removeEventListener('keydown', media.onPreviewKeydown);
      document.title = 'QQ AI 助手';
    });

    // 浏览器标题跟随当前页面
    watch(
      () => currentTabMeta.value,
      (meta) => {
        if (meta) document.title = `${meta.title} · 系统管理中心`;
      },
      { immediate: true }
    );

    // ===== provide 给所有子组件 =====
    provide('adminShowMsg', showSystemMsg);
    provide('adminDashboard', dashboard);
    provide('adminComponentCtrl', componentCtrl);
    provide('adminUserMgmt', userMgmt);
    provide('adminIsMediaCollapsed', isMediaCollapsed);
    provide('adminMedia', media);
    provide('adminSystemLog', systemLog);
    provide('adminConfig', configMgmt);
    provide('adminMaintenance', maintenance);
    provide('adminCreditsRule', adminRule);
    provide('adminUserCredits', adminUserCredits);
    provide('adminTx', adminTx);
    provide('adminTxTypeOptions', TX_TYPE_OPTS);
    provide('adminCashCategories', CASH_CATEGORY_OPTIONS);
    provide('adminTxDirectionOptions', TX_DIR_OPTS);
    provide('adminTxTypeTextLabel', txTypeTextLabel);
    provide('adminOrders', adminOrders);
    provide('adminOrderStatusOptions', ORDER_STATUS_OPTS);
    provide('adminOrderPlanOptions', ORDER_PLAN_OPTS);
    provide('adminOrderStatusText', orderStatusText);
    provide('adminPlanTierText', planTierLabel);
    provide('adminPendingRefundCount', pendingRefundCount);
    provide('adminPendingDisputeCount', pendingDisputeCount);
    provide('adminLoadPendingCount', loadPendingCount);
    provide('adminRefundApproveModal', refundApproveModal);
    provide('adminOpenApproveRefundModal', openApproveRefundModal);
    provide('adminOpenRejectRefundModal', openRejectRefundModal);
    provide('adminCloseRefundApproveModal', closeRefundApproveModal);
    provide('adminSubmitRefundApprove', submitRefundApprove);
    provide('adminOpenDisputeAgree', openDisputeAgree);
    provide('adminOpenDisputeReject', openDisputeReject);
    provide('adminSubmitResolveDispute', submitResolveDispute);
    provide('adminSubmitApprovePayment', adminOrders.submitApprovePayment);
    provide('adminFormatDate', formatDate);
    provide('adminFormatFileSize', formatFileSize);
    provide('adminOpenMediaImagePreview', openMediaImagePreview);
    provide('adminCurrentTheme', currentTheme);
    provide('adminActiveTab', activeTab);
    // 子页面之间互相跳转（如组件控制 → AI 摘要 / 配置管理）
    provide('adminSetActiveTab', setActiveTab);
    // 子页面需要带上 query 做跨页筛选（如用户积分 → 资金流水 ?userId=）
    provide('adminRouter', router);
    provide('adminRoute', route);

    return {
      currentTheme, activeTab, sidebarOpen, currentComponent, navItems, systemMessage, systemMessageType,
      formatDate, formatFileSize, goHome, logout, setActiveTab,
      toggleTheme, health, healthText, healthLevel, loadHealth,
      // Shared dialogs (exposed to template)
      adjustModal: adminUserCredits.adjustModal,
      closeAdjustModal: adminUserCredits.closeAdjustModal,
      submitAdjust: adminUserCredits.submitAdjust,
      manualModal: adminOrders.manualModal,
      orderPlanOptions: ORDER_PLAN_OPTS,
      closeManualModal: adminOrders.closeManualModal,
      submitManualCreate: adminOrders.submitManualCreate,
      cancelModal: adminOrders.cancelModal,
      closeCancelModal: adminOrders.closeCancelModal,
      submitCancel: adminOrders.submitCancel,
      refundModal: adminOrders.refundModal,
      closeRefundModal: adminOrders.closeRefundModal,
      submitRefund: adminOrders.submitRefund,
      disputeModal: adminOrders.disputeModal,
      closeDisputeModal: adminOrders.closeDisputeModal,
      openDisputeModal: adminOrders.openDisputeModal,
      submitResolveDispute,
      refundApproveModal, openApproveRefundModal, openRejectRefundModal,
      closeRefundApproveModal, submitRefundApprove,
      detailDrawer: adminOrders.detailDrawer,
      orderTimeline: adminOrders.timelineEvents,
      orderStatusText, planTierText: planTierLabel,
      txTypeTextLabel,
      openRefundModal: adminOrders.openRefundModal,
      openOrderDetail: adminOrders.openOrderDetail,
      closeOrderDetail: adminOrders.closeOrderDetail,
      // Media preview
      showPreviewModal: media.showPreviewModal,
      previewFiles: media.previewFiles,
      currentPreviewIndex: media.currentPreviewIndex,
      currentPreviewFile: media.currentPreviewFile,
      previewMode: media.previewMode,
      previewPage: media.previewPage,
      previewPageSize: media.previewPageSize,
      previewTotalPages: media.previewTotalPages,
      previewTotalElements: media.previewTotalElements,
      previewLoadingPage: media.previewLoadingPage,
      previewSource: media.previewSource,
      previewGoToPage: media.previewGoToPage,
      setPreviewPageSize: media.setPreviewPageSize,
      previewPrevDisabled,
      previewNextDisabled,
      closePreview: media.closePreview,
      previewNext: media.previewNext,
      previewPrev: media.previewPrev,
      enterSingleView: media.enterSingleView,
      backToGallery: media.backToGallery,
      backToList: media.backToList,
      togglePreviewSelection: media.togglePreviewSelection,
      isAllPreviewSelected: media.isAllPreviewSelected,
      toggleSelectAllInPreview: media.toggleSelectAllInPreview,
      selectedMediaFileIds: media.selectedMediaFileIds,
      isMediaError: media.isMediaError,
      markMediaError: media.markMediaError,
      formatBytes: media.formatBytes,
      openMediaImagePreview,
      currentFilesTotalSize, totalMediaPages, confirmDeleteSelected, confirmPurgeByFilter,
      isMediaCollapsed,
    };
  },
};
</script>

<style scoped>
/* ===== 壳布局 ===== */
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

/* 顶部健康指示：绿=全部在线，橙=部分在线，红=全部离线，灰=未知 */
.health-pill {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 6px 12px;
  border: none;
  border-radius: 14px;
  cursor: pointer;
  font-size: 12.5px;
  color: #fff;
  background: rgba(255,255,255,0.1);
  transition: background 0.2s;
}

.health-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #95a5a6;
  box-shadow: 0 0 0 3px rgba(149, 165, 166, 0.25);
}

.health-ok .health-dot { background: #2ecc71; box-shadow: 0 0 0 3px rgba(46, 204, 113, 0.25); }
.health-partial .health-dot { background: #f39c12; box-shadow: 0 0 0 3px rgba(243, 156, 18, 0.25); }
.health-down .health-dot { background: #e74c3c; box-shadow: 0 0 0 3px rgba(231, 76, 60, 0.25); }
.health-unknown .health-dot { background: #7f8c8d; box-shadow: 0 0 0 3px rgba(127, 140, 141, 0.25); }

.admin-layout {
  display: flex;
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.admin-sidebar {
  width: 200px;
  flex-shrink: 0;
  height: 100%;
  background: var(--sidebar-bg, #2c3e50);
  border-right: 1px solid var(--border-color, #34495e);
  padding: 16px 0;
  overflow-y: auto;
  transition: transform 0.25s ease;
}

.sidebar-overlay {
  display: none;
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 199;
}

.sidebar-toggle {
  display: none;
  background: none;
  border: none;
  color: inherit;
  cursor: pointer;
  padding: 4px;
  margin-right: 4px;
  border-radius: 4px;
  transition: background 0.2s;
}

.sidebar-toggle:hover {
  background: rgba(255, 255, 255, 0.1);
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
  /* 侧栏在两种主题下都是深色底，这里必须用侧栏文字色；
     原先写死 #090f16（近黑），两种主题下都看不清 */
  color: var(--sidebar-text, #ecf0f1);
  opacity: 0.75;
}

.admin-main {
  flex: 1;
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
  background-color: var(--bg-primary, #f5f5f5);
}

/* ===== 系统消息 ===== */
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

/* ===== 模态框通用 ===== */
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

.modal-content.small-modal { width: 420px; }

.modal-content.credit-modal { width: 560px; }

.modal-content.manual-modal { width: 620px; }

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
  color: var(--text-muted, #999);
  cursor: pointer;
  padding: 0 4px;
}

.btn-close:hover { color: var(--text-primary, #333); }

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

.btn-cancel:hover { background: var(--bg-tertiary, #fafbfc); }

.btn-save {
  padding: 8px 18px;
  border: none;
  background: var(--accent-color, #3498db);
  color: #fff;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}

.btn-save:hover:not(:disabled) { opacity: 0.9; }

.btn-save:disabled { opacity: 0.5; cursor: not-allowed; }

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

.refund-order-info label { font-weight: 600; color: var(--text-secondary, #666); margin-right: 6px; }

.refund-order-info span { color: var(--text-primary, #333); }

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
  color: var(--text-muted, #888);
  font-weight: 400;
}

.drawer-close {
  background: none;
  border: none;
  font-size: 22px;
  color: var(--text-muted, #999);
  cursor: pointer;
}

.drawer-close:hover { color: var(--text-primary, #333); }

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

.detail-item label { font-size: 11px; color: var(--text-muted, #999); }

.detail-item span { font-size: 13px; color: var(--text-primary, #333); }

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

.timeline-item.last { padding-bottom: 0; }

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

.timeline-item.done .timeline-dot { background: var(--accent-color, #3498db); }

.timeline-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary, #333);
}

.timeline-op {
  font-size: 11px;
  color: var(--text-muted, #999);
  font-weight: 400;
}

.timeline-time {
  font-size: 11px;
  color: var(--text-muted, #aaa);
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
  color: var(--text-secondary, #666);
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

.related-amount { font-weight: 600; }

.related-amount.add { color: #2e7d32; }

.related-amount.sub { color: #c62828; }

.related-time {
  color: var(--text-muted, #aaa);
  font-size: 11px;
}

.drawer-footer {
  padding: 14px 20px;
  border-top: 1px solid var(--border-color, #e8e8e8);
}

/* ===== 抽屉过渡动画 ===== */
.drawer-enter-active,
.drawer-leave-active {
  transition: opacity 0.25s ease;
}

.drawer-enter-active .drawer-container,
.drawer-leave-active .drawer-container {
  transition: transform 0.25s ease;
}

.drawer-enter-from,
.drawer-leave-to { opacity: 0; }

.drawer-enter-from .drawer-container,
.drawer-leave-to .drawer-container {
  transform: translateX(100%);
}

/* ===== 预览弹窗 ===== */
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
  color: var(--text-secondary, #666);
  cursor: pointer;
  z-index: 10;
}

.preview-close:hover { color: var(--text-primary, #333); }

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
  color: var(--text-primary, #333);
  word-break: break-all;
}

.preview-title small { font-size: 12px; color: var(--text-muted, #888); white-space: nowrap; }

.preview-media {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  background: #f8f8f8;
  border-radius: 6px;
  overflow: hidden;
}

.preview-img { max-width: 100%; max-height: 60vh; object-fit: contain; }

.preview-video { max-width: 100%; max-height: 60vh; }

.preview-audio { width: 100%; padding: 20px; }

.preview-unsupported { padding: 60px 20px; color: var(--text-muted, #888); font-size: 14px; }

.preview-nav {
  display: flex;
  justify-content: space-between;
  padding: 14px 20px;
  border-top: 1px solid var(--border-color, #f0f0f0);
  background: var(--bg-tertiary, #fafafa);
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

.preview-nav-btn:disabled { opacity: 0.5; cursor: not-allowed; }

.preview-content.gallery-mode { width: 90vw; max-width: 1100px; }

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
  border-bottom: 1px solid var(--border-color, #f0f0f0);
  background: var(--bg-tertiary, #fafafa);
}

.preview-gallery-title { font-size: 16px; font-weight: 500; color: var(--text-primary, #333); }

.preview-gallery-header small { font-size: 12px; color: var(--text-muted, #888); }

.preview-gallery-body { flex: 1; overflow-y: auto; padding: 16px 20px; background: var(--card-bg, #fff); }

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
  border: 1px solid var(--border-color, #f0f0f0);
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

.preview-select-circle:hover { background: rgba(0, 0, 0, 0.45); }

.preview-select-circle.selected { background: #3498db; border-color: #fff; }

.preview-select-circle span { color: #fff; font-size: 12px; font-weight: 700; }

.preview-thumbnail {
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--border-color, #f0f0f0);
}

.preview-thumbnail img,
.preview-thumbnail video { width: 100%; height: 100%; object-fit: cover; }

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

.preview-grid-info { padding: 8px; background: var(--card-bg, #fff); }

.preview-grid-name {
  display: block;
  font-size: 12px;
  color: var(--text-primary, #333);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.preview-grid-info small { font-size: 11px; color: var(--text-muted, #999); }

.preview-empty { text-align: center; padding: 60px 20px; color: var(--text-muted, #888); font-size: 14px; }

.preview-gallery-footer {
  padding: 14px 20px;
  border-top: 1px solid var(--border-color, #f0f0f0);
  background: var(--bg-tertiary, #fafafa);
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.preview-pager {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.preview-pager-info {
  font-size: 12.5px;
  color: var(--text-secondary, #666);
  padding: 0 4px;
}

.preview-page-size {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12.5px;
  color: var(--text-secondary, #666);
  margin-left: 6px;
}

.preview-page-size select {
  padding: 5px 7px;
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 4px;
  background: var(--input-bg, #fff);
  color: var(--text-primary, #333);
  font-size: 12.5px;
}

/* ===== 审计日志复用 ===== */
.audit-search-input,
.audit-action-select {
  padding: 6px 10px;
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 4px;
  font-size: 13px;
  outline: none;
}

.audit-search-input { flex: 1; min-width: 180px; max-width: 300px; }

.audit-table-wrapper {
  overflow-x: auto;
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 6px;
}

.audit-table { width: 100%; border-collapse: collapse; font-size: 13px; }

.audit-table thead { background: var(--bg-tertiary, #f5f6fa); }

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

.audit-table tbody tr:hover { background: var(--bg-tertiary, #fafbfc); }

.audit-action-tag {
  display: inline-block;
  padding: 2px 8px;
  background: #e3f2fd;
  color: #1976d2;
  border-radius: 10px;
  font-size: 12px;
  white-space: nowrap;
}

.audit-result-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 600;
}

.audit-success { background: #e8f5e9; color: #2e7d32; }

.audit-failure { background: #ffebee; color: #c62828; }

/* ===== 响应式 ===== */
@media (max-width: 1024px) {
  .admin-sidebar {
    width: 180px;
  }
  .admin-main {
    padding: 16px;
  }
  .nav-item {
    padding: 8px 10px;
    font-size: 13px;
  }
}

@media (max-width: 768px) {
  .sidebar-toggle {
    display: flex;
    align-items: center;
  }
  .sidebar-overlay {
    display: block;
  }
  .admin-sidebar {
    position: fixed;
    top: 0;
    left: 0;
    bottom: 0;
    width: 200px;
    z-index: 200;
    transform: translateX(-100%);
    box-shadow: 2px 0 8px rgba(0, 0, 0, 0.15);
  }
  .admin-sidebar.open {
    transform: translateX(0);
  }
  .admin-main {
    padding: 12px;
    width: 100%;
  }
  .admin-header {
    padding: 0 12px;
  }
  .admin-header h1 {
    font-size: 15px;
  }
  .btn-home span,
  .btn-logout span {
    display: none;
  }
}
</style>