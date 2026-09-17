
<template>
  <div class="chat-interface">
    <!-- 顶部群聊选择器 -->
    <div class="chat-header">
      <div class="group-selector">
        <button @click="loadMessages" class="btn-refresh" :disabled="isLoading" title="刷新消息">
          <svg v-if="!isLoading" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"></path>
            <path d="M3 3v5h5"></path>
            <path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"></path>
            <path d="M16 21h5v-5"></path>
          </svg>
          <span v-else>...</span>
        </button>
        <button 
          @click="toggleSelectionMode" 
          :class="['btn-selection', { active: isSelectionMode }]"
          :title="isSelectionMode ? '退出选择' : '选择消息'"
        >
        </button>
      </div>
      <div v-if="currentGroupName" class="current-group">
        {{ currentGroupName }}
      </div>
    </div>
    
    <!-- 选择模式操作栏 -->
    <div v-if="isSelectionMode" class="selection-toolbar">
      <div class="selection-info">
        <span class="selected-count">已选择 {{ selectedMessages.length }} 条消息</span>
        <label class="selection-mode-label">
          <input type="checkbox" v-model="isMultiSelect" />
          多选模式
        </label>
      </div>
      <div class="selection-actions">
        <div class="quick-select">
          <input 
            type="number" 
            v-model="quickSelectCount" 
            min="1" 
            placeholder="数量" 
            class="quick-select-input"
          />
          <button @click="selectRecentMessages" class="btn-quick-select">选择最近</button>
        </div>
        <button @click="clearSelection" class="btn-clear">清空</button>
        <button
          @click="deleteSelected"
          :disabled="selectedMessages.length === 0"
          class="btn-delete"
        >
          <Icon name="trash" :size="14" /> 删除
        </button>
        <button
          @click="openAnalysisPicker"
          :disabled="selectedMessages.length === 0"
          class="btn-analyze"
        >
          <Icon name="robot" :size="14" /> AI 分析
        </button>
      </div>
    </div>

    <!-- AI 分析类型选择弹窗 -->
    <div v-if="showAnalysisPicker" class="analysis-picker-mask" @click.self="closeAnalysisPicker">
      <div class="analysis-picker">
        <div class="analysis-picker-header">
          <div class="analysis-picker-title">选择分析维度</div>
          <div class="analysis-picker-sub">
            不同维度产出对「融入圈子」有不同价值的洞察
            <span v-if="groupTypeLabel" class="analysis-picker-gt">（当前群类型：{{ groupTypeLabel }}）</span>
          </div>
          <button class="analysis-picker-close" @click="closeAnalysisPicker" title="关闭">
            <Icon name="close" :size="16" />
          </button>
        </div>
        <div class="analysis-picker-grid">
          <button
            v-for="opt in analysisTypeOptions"
            :key="opt.value"
            :class="['analysis-picker-item', { recommended: opt.recommended }]"
            :disabled="selectedMessages.length === 0"
            @click="analyzeWithType(opt.value)"
          >
            <div class="analysis-picker-icon"><Icon :name="opt.iconName" :size="24" /></div>
            <div class="analysis-picker-info">
              <div class="analysis-picker-name">
                {{ opt.label }}
                <Icon v-if="opt.recommendedBadge" name="star" :size="12" style="color:#f59e0b;margin-left:2px" />
                <span v-if="opt.recommended" class="recommended-tag">推荐</span>
              </div>
              <div class="analysis-picker-desc">{{ opt.desc }}</div>
            </div>
          </button>
        </div>
        <!-- 用户附加输入框：和转发内容一起发给 LLM -->
        <div class="analysis-picker-input-area">
          <textarea
            v-model="analysisUserPrompt"
            class="analysis-picker-input"
            placeholder="补充说明（可选）：例如『重点分析图片内容』或『我刚进群，帮我找共同话题』..."
            rows="2"
            maxlength="500"
            @keydown.enter.prevent="analyzeWithType(analysisTypeOptions[0]?.value)"
          ></textarea>
        </div>
      </div>
    </div>
    <!-- 群日报「今日速览」（AI 摘要阶段 3）：打开群聊时自动拉取该群最新一期 -->
    <div v-if="groupDigest" class="group-digest">
      <div class="digest-header">
        <span class="digest-title"><Icon name="chart" :size="14" /> 今日速览</span>
        <span class="digest-date">{{ groupDigest.digestDate || '今天' }}</span>
        <span
          v-if="digestSentimentLabel"
          class="ai-sentiment"
          :class="'st-' + (groupDigest.sentiment || '')"
        >{{ digestSentimentLabel }}</span>
        <span class="digest-actions">
          <button class="digest-btn" @click="toggleDigestExpand" :title="digestExpanded ? '收起速览' : '展开完整速览'">
            {{ digestExpanded ? '收起' : '展开' }}
          </button>
          <button
            v-if="isAdmin"
            class="digest-btn"
            :disabled="digestPushing"
            title="把这一期速览发送到该 QQ 群（手动触发）"
            @click="openPushConfirm"
          >
            <span v-if="digestPushing" class="msg-action-spinner"></span>
            {{ digestPushing ? '推送中…' : '推送到群' }}
          </button>
          <button class="digest-btn primary" :disabled="digestGenerating" @click="generateDigest">
            <span v-if="digestGenerating" class="msg-action-spinner"></span>
            {{ digestGenerating ? '生成中…' : '重新生成' }}
          </button>
        </span>
      </div>
      <div class="digest-summary" :class="{ expanded: digestExpanded }">{{ groupDigest.summary }}</div>
      <div v-if="digestTags.length" class="ai-summary-tags">
        <button v-for="tag in digestTags" :key="tag" class="ai-tag clickable" title="搜索该标签" @click="openTagSearch(tag)">#{{ tag }}</button>
      </div>
      <div v-if="digestExpanded" class="digest-meta">
        <span v-if="groupDigest.messageCount != null">共 {{ groupDigest.messageCount }} 条消息参与生成</span>
        <span v-if="groupDigest.model"> · 模型 {{ groupDigest.model }}</span>
      </div>
      <!-- 历史速览：点击切换查看某一期（只用已拉到的数据，不触发新的生成） -->
      <div v-if="digestExpanded && digestHistory.length > 1" class="digest-history">
        <span class="digest-history-label">历史速览</span>
        <button
          v-for="item in digestHistory"
          :key="item.digestDate"
          class="digest-history-chip"
          :class="{ active: item.digestDate === groupDigest.digestDate }"
          @click="viewHistoryDigest(item)"
        >{{ item.digestDate }}</button>
        <span class="digest-history-tools">
          <button class="digest-btn" @click="toggleHistoryPanel">
            {{ historyPanelOpen ? '收起列表' : '全部历史' }}
          </button>
          <button class="digest-btn" :disabled="digestExporting" @click="exportDigests">
            {{ digestExporting ? '导出中…' : '导出 Markdown' }}
          </button>
        </span>
      </div>
      <!-- 全部历史：日期 / 条数 / 一句话，点击即在该群速览卡片中查看 -->
      <div v-if="digestExpanded && historyPanelOpen" class="digest-history-panel">
        <div v-if="digestHistoryLoading" class="digest-history-loading">加载中…</div>
        <template v-else>
          <button
            v-for="item in digestHistory"
            :key="'h-' + item.digestDate"
            class="digest-history-row"
            :class="{ active: item.digestDate === groupDigest.digestDate }"
            @click="viewHistoryDigest(item)"
          >
            <span class="digest-history-row-date">{{ item.digestDate }}</span>
            <span class="digest-history-row-count">{{ item.messageCount ?? '-' }} 条</span>
            <span class="digest-history-row-summary">{{ item.summary }}</span>
          </button>
          <div class="digest-history-footer">
            <span class="digest-history-total">已加载 {{ digestHistory.length }} 期</span>
            <button
              v-if="digestHistory.length >= digestHistoryLimit && digestHistoryLimit < 200"
              class="digest-btn"
              :disabled="digestHistoryLoading"
              @click="loadMoreHistory"
            >加载更多（每页 30）</button>
            <span v-else class="digest-history-total">已到最早一期</span>
          </div>
        </template>
      </div>
    </div>
    <!-- 无日报：一行很淡的提示，点击即可生成（不占空间） -->
    <div v-else-if="groupId" class="group-digest digest-empty">
      <button class="digest-empty-btn" :disabled="digestGenerating" @click="generateDigest">
        <span v-if="digestGenerating" class="msg-action-spinner"></span>
        {{ digestGenerating ? '正在生成今日速览，请稍候…' : '今日暂无速览，点击生成' }}
      </button>
    </div>

    <div class="messages-area" ref="messagesContainer" @scroll="handleScroll">
      <div v-if="isLoadingMore" class="load-more-hint">加载更早的消息...</div>
      <div v-if="messages.length === 0 && !isLoading" class="empty-state">
        <div class="empty-icon"><Icon name="chat" :size="48" /></div>
        <p>请选择群聊开始对话</p>
      </div>
      
      <div v-else-if="isLoading" class="loading-state">
        <div class="loading-spinner"></div>
        <p>加载消息中...</p>
      </div>
      
      <div v-else class="messages-list">
        <div
          v-for="message in renderMessages"
          :key="message.id"
          :id="'msg-' + message.id"
          class="message-wrapper"
          :class="{
            'message-self': isSelfMessage(message),
            'message-other': !isSelfMessage(message),
            'message-selected': isSelected(message.id),
            'selection-mode': isSelectionMode,
            'message-highlight': highlightedMessageId === message.id
          }"
          @click="isSelectionMode && toggleMessageSelection(message.id)"
        >
          <!-- 选择框 -->
          <div v-if="isSelectionMode" class="message-checkbox" @click.stop>
            <input 
              type="checkbox" 
              :checked="isSelected(message.id)"
              @change="toggleMessageSelection(message.id)"
            />
          </div>
          
          <!-- 群消息（左对齐） -->
          <div v-if="!isSelfMessage(message)" class="message-bubble message-left">
            <img 
              :src="getAvatar(message.userQq)" 
              class="message-avatar-img"
              @error="handleAvatarError"
            />
            <div class="message-content-wrapper">
              <div class="message-header">
                <span class="message-user">{{ message.userNickname || message.userName || '未知用户' }}</span>
                <span class="message-time">{{ formatTime(message.sendTime || message.timestamp) }}</span>
              </div>
              <!-- 消息内容 -->
              <MessageContent :message="message" :qq-nickname-map="qqNicknameMap" :gallery="chatImageGallery" @navigate-to-message="handleNavigateToMessage" />
              <!-- 按需摘要按钮（悬停显示） -->
              <div class="msg-actions" @click.stop>
                <button
                  class="msg-action-btn"
                  :disabled="isSummarizing(message.id)"
                  :title="message.aiSummaryView ? '重新生成摘要' : '为这条消息生成 AI 摘要'"
                  @click="summarizeOne(message)"
                >
                  <span v-if="isSummarizing(message.id)" class="msg-action-spinner"></span>
                  <Icon v-else name="robot" :size="12" />
                  {{ isSummarizing(message.id) ? '生成中…' : (message.aiSummaryView ? '重新摘要' : 'AI 摘要') }}
                </button>
              </div>
              <!-- AI 摘要（结构化：标签 + 情感 + 一句话摘要） -->
              <div v-if="message.aiSummaryView" class="ai-summary">
                <div class="ai-summary-header">
                  <span class="ai-icon"><Icon name="robot" :size="14" /></span>
                  <span>AI 摘要</span>
                  <span v-if="message.aiSummaryView.sentimentLabel" class="ai-sentiment" :class="'st-' + message.aiSummaryView.sentiment">
                    {{ message.aiSummaryView.sentimentLabel }}
                  </span>
                </div>
                <div class="ai-summary-content">{{ message.aiSummaryView.text }}</div>
                <div v-if="message.aiSummaryView.tags.length" class="ai-summary-tags">
                  <button
                    v-for="tag in message.aiSummaryView.tags"
                    :key="tag"
                    class="ai-tag clickable"
                    title="搜索该标签"
                    @click.stop="openTagSearch(tag)"
                  >#{{ tag }}</button>
                </div>
              </div>
            </div>
          </div>
          
          <!-- 用户消息（右对齐） -->
          <div v-else class="message-bubble message-right">
            <img 
              :src="getAvatar(message.userQq)" 
              class="message-avatar-img"
              @error="handleAvatarError"
            />
            <div class="message-content-wrapper">
              <div class="message-header">
                <span class="message-time">{{ formatTime(message.sendTime || message.timestamp) }}</span>
                <span class="message-user">{{ message.userNickname || message.userName || '我' }}</span>
              </div>
              <!-- 消息内容 -->
              <MessageContent :message="message" :qq-nickname-map="qqNicknameMap" :gallery="chatImageGallery" @navigate-to-message="handleNavigateToMessage" />
              <!-- 按需摘要按钮（悬停显示） -->
              <div class="msg-actions" @click.stop>
                <button
                  class="msg-action-btn"
                  :disabled="isSummarizing(message.id)"
                  :title="message.aiSummaryView ? '重新生成摘要' : '为这条消息生成 AI 摘要'"
                  @click="summarizeOne(message)"
                >
                  <span v-if="isSummarizing(message.id)" class="msg-action-spinner"></span>
                  <Icon v-else name="robot" :size="12" />
                  {{ isSummarizing(message.id) ? '生成中…' : (message.aiSummaryView ? '重新摘要' : 'AI 摘要') }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 推送日报确认弹窗（手动触发，发送前展示原文） -->
    <div v-if="pushConfirmOpen" class="push-confirm-mask" @click.self="pushConfirmOpen = false">
      <div class="push-confirm">
        <div class="push-confirm-title">推送到 QQ 群</div>
        <div class="push-confirm-sub">将向群 <b>{{ groupId }}</b> 发送以下内容（手动触发，不可撤回）：</div>
        <pre class="push-confirm-text">{{ pushPreviewText }}</pre>
        <div class="push-confirm-actions">
          <button class="digest-btn" @click="pushConfirmOpen = false">取消</button>
          <button class="digest-btn primary" :disabled="digestPushing" @click="confirmPush">
            {{ digestPushing ? '推送中…' : '确认发送' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 标签 / 关键词搜索面板 -->
    <div v-if="searchOpen" class="tag-search-panel">
      <div class="tag-search-header">
        <span class="tag-search-title">
          <Icon name="search" :size="14" />
          {{ searchTag ? `标签：${searchTag}` : '搜索消息' }}
        </span>
        <button class="tag-search-close" @click="closeSearch">✕</button>
      </div>
      <div class="tag-search-bar">
        <input
          v-model.trim="searchKeyword"
          type="text"
          :placeholder="searchTag ? '在该标签下继续筛选关键词…' : '输入关键词搜索本群消息'"
          @keyup.enter="runSearch"
        />
        <button class="digest-btn primary" :disabled="searchLoading" @click="runSearch">
          {{ searchLoading ? '搜索中…' : '搜索' }}
        </button>
      </div>
      <div class="tag-search-body">
        <div v-if="searchFallbackNote" class="tag-search-note">{{ searchFallbackNote }}</div>
        <div v-if="searchLoading" class="tag-search-empty">搜索中…</div>
        <div v-else-if="!searchResults.length" class="tag-search-empty">
          {{ searchSearched ? '没有匹配的消息' : '点击摘要里的标签，或输入关键词开始搜索' }}
        </div>
        <button
          v-for="item in searchResults"
          :key="item.id"
          class="tag-search-item"
          @click="jumpToMessage(item)"
        >
          <div class="tag-search-item-head">
            <span class="tag-search-item-user">{{ item.userNickname || item.userQq || '未知' }}</span>
            <span class="tag-search-item-time">{{ formatTime(item.sendTime) }}</span>
          </div>
          <div class="tag-search-item-text">{{ item.contentSnippet || item.aiSummaryShort }}</div>
          <div v-if="item.aiTags" class="tag-search-item-tags">{{ String(item.aiTags).split(',').map(t => '#' + t.trim()).join(' ') }}</div>
        </button>
      </div>
    </div>

  </div>
</template>

<script>
import { ref, nextTick, watch, onMounted, onUnmounted, computed } from 'vue';
import Icon from './Icon.vue';
import { messageApi } from '../services/api';
import MessageContent from './MessageContent.vue';
import { showToast } from './Toast.vue';
import { useMessageWebSocket } from '../composables/useMessageWebSocket';
import { extractForwardXmlTitles, extractForwardMessages, extractImageUrl } from '../utils/messageParser';
import { formatMessageTime } from '../utils/formatTime';
import logger from '../utils/logger';

const PAGE_SIZE = 50;
const FALLBACK_POLL_MS = 10000;

export default {
  name: 'ChatInterface',
  components: {
    Icon,
    MessageContent
  },
  props: {
    group: {
      type: Object,
      default: null
    },
    /** 是否管理员（决定是否显示「推送到群」按钮；后端同样要求 ADMIN） */
    isAdmin: {
      type: Boolean,
      default: false
    }
  },
  emits: ['analysis-result', 'new-message-arrived'],

  setup(props, { emit }) {
    const groupId = computed(() => props.group?.groupId || '');
    const selfQq = computed(() => props.group?.ownerQq || '');
    // 兼容：父组件未传 isAdmin 时回退到登录时缓存的角色，避免按钮消失
    const isAdmin = computed(() => props.isAdmin || (localStorage.getItem('user_role') || '') === 'ADMIN');
    const messages = ref([]);
    const isLoading = ref(false);
    // 当前会话已加载消息里的全部图片（按消息顺序）→ 供图片预览左右切换
    const chatImageGallery = computed(() =>
      (messages.value || []).map(extractImageUrl).filter(Boolean)
    );

    const SENTIMENT_LABELS = { positive: '积极', neutral: '中性', negative: '消极' };

    /**
     * 结构化摘要视图模型。
     * 优先用后端解析好的字段（aiSummaryShort / aiTags / aiSentiment）；
     * 老数据只有 aiSummary 原始 JSON，这里兼容解析一次（去掉 ```json 围栏），
     * 避免页面上直接显示一坨 JSON。
     */
    const buildSummaryView = (message) => {
      const tagsOf = raw => (typeof raw === 'string' && raw ? raw.split(',').map(s => s.trim()).filter(Boolean) : []);
      const withLabel = view => ({
        ...view,
        sentimentLabel: SENTIMENT_LABELS[view.sentiment] || ''
      });

      if (message.aiSummaryShort || message.aiTags || message.aiSentiment) {
        return withLabel({
          tags: tagsOf(message.aiTags),
          sentiment: message.aiSentiment || '',
          text: message.aiSummaryShort || ''
        });
      }
      const raw = message.aiSummary;
      if (!raw) return null;
      let text = String(raw).trim().replace(/^```[a-zA-Z]*\s*/, '').replace(/\s*```$/, '');
      try {
        const obj = JSON.parse(text);
        return withLabel({
          tags: Array.isArray(obj?.tags) ? obj.tags.filter(Boolean).map(String) : [],
          sentiment: obj?.sentiment || '',
          text: obj?.summary || text
        });
      } catch (e) {
        return withLabel({ tags: [], sentiment: '', text });
      }
    };

    /** 渲染用消息列表：带上结构化摘要视图（只算一次，避免模板里反复解析） */
    const renderMessages = computed(() =>
      (messages.value || []).map(m => ({ ...m, aiSummaryView: buildSummaryView(m) }))
    );

    // ===== 阶段 1：单条按需摘要 =====
    const summarizingIds = ref(new Set());
    const isSummarizing = id => summarizingIds.value.has(id);

    const summarizeOne = async (message) => {
      if (!message || isSummarizing(message.id)) return;
      const force = !!message.aiSummaryView;   // 已有摘要时按钮含义是"重新摘要"
      const mark = (set, id) => { const n = new Set(set); n.add(id); return n; };
      const unmark = (set, id) => { const n = new Set(set); n.delete(id); return n; };
      summarizingIds.value = mark(summarizingIds.value, message.id);
      try {
        const res = await messageApi.summarize(message.id, force);
        const view = res?.summary;
        if (!view) {
          showToast('摘要返回为空，请稍后重试', 'warning');
          return;
        }
        // 就地更新，卡片会立刻出现（renderMessages 是 computed，会自动重算）
        message.aiSummaryShort = view.summary || '';
        message.aiSentiment = view.sentiment || '';
        message.aiTags = Array.isArray(view.tags) ? view.tags.join(',') : (message.aiTags || '');
        showToast(res?.cached ? '已使用已有摘要' : '摘要生成完成', 'success');
      } catch (err) {
        const msg = err?.message || '摘要生成失败';
        showToast(msg, 'error');
        logger.warn('[summarize] 失败 messageId=', message.id, msg);
      } finally {
        summarizingIds.value = unmark(summarizingIds.value, message.id);
      }
    };
    // ===== 阶段 3：群日报「今日速览」 =====
    // 后端接口：GET /api/groups/{groupId}/digest/latest、POST /api/groups/{groupId}/digest
    const groupDigest = ref(null);        // 当前群最新一期日报（后端视图：summary/tags/sentiment/digestDate/messageCount）
    const digestGenerating = ref(false);  // 生成中（按钮转圈 + 禁用）
    const digestExpanded = ref(false);    // 是否展开完整内容
    const digestHistory = ref([]);        // 历史日报（用于展开后的日期切换）

    /** 查看历史某一期：直接用已拉到的数据切换显示，不触发新的生成（不烧额度） */
    const viewHistoryDigest = (item) => {
      if (!item) return;
      groupDigest.value = item;
    };

    // ===== 历史分页浏览 + 导出 =====
    const historyPanelOpen = ref(false);
    const digestHistoryLoading = ref(false);
    const digestHistoryLimit = ref(30);      // 每页 30，最多 200（后端 limit 上限）
    const digestExporting = ref(false);

    /** 拉取历史列表（limit 递增即"加载更多"） */
    const fetchDigestHistory = async (gid, limit) => {
      if (!gid) return [];
      const res = await messageApi.getGroupDigests(gid, limit);
      const arr = Array.isArray(res) ? res : (Array.isArray(res?.data) ? res.data : []);
      return arr.filter(Boolean);
    };

    const toggleHistoryPanel = async () => {
      historyPanelOpen.value = !historyPanelOpen.value;
      if (!historyPanelOpen.value || !groupId.value) return;
      digestHistoryLoading.value = true;
      try {
        digestHistory.value = await fetchDigestHistory(groupId.value, digestHistoryLimit.value);
      } catch (e) {
        showToast(e?.message || '加载历史速览失败', 'error');
      } finally {
        digestHistoryLoading.value = false;
      }
    };

    const loadMoreHistory = async () => {
      if (!groupId.value) return;
      const next = Math.min(digestHistoryLimit.value + 30, 200);
      digestHistoryLoading.value = true;
      try {
        const list = await fetchDigestHistory(groupId.value, next);
        digestHistory.value = list;
        digestHistoryLimit.value = next;
      } catch (e) {
        showToast(e?.message || '加载更多失败', 'error');
      } finally {
        digestHistoryLoading.value = false;
      }
    };

    // ===== 手动推送日报到 QQ 群（仅管理员，发送前二次确认） =====
    const pushConfirmOpen = ref(false);
    const digestPushing = ref(false);

    /** 与后端 push 接口拼装口径保持一致，用于发送前预览 */
    const pushPreviewText = computed(() => {
      const d = groupDigest.value;
      if (!d) return '';
      const lines = [`【今日速览】${d.digestDate || ''}`, d.summary || ''];
      if (digestTags.value.length) lines.push('标签：' + digestTags.value.join('、'));
      lines.push('—— 由 AI 生成');
      return lines.join('\n');
    });

    const openPushConfirm = () => {
      if (!groupDigest.value) {
        showToast('当前没有可推送的速览', 'warning');
        return;
      }
      pushConfirmOpen.value = true;
    };

    const confirmPush = async () => {
      if (!groupId.value || !groupDigest.value) return;
      digestPushing.value = true;
      try {
        const res = await messageApi.pushGroupDigest(groupId.value, groupDigest.value.digestDate);
        pushConfirmOpen.value = false;
        showToast(res?.pushed ? '已推送到群' : '推送完成', 'success');
      } catch (e) {
        showToast(e?.message || '推送失败', 'error');
      } finally {
        digestPushing.value = false;
      }
    };

    // ===== 标签 / 关键词搜索（点击摘要标签跳转搜索） =====
    const searchOpen = ref(false);
    const searchTag = ref('');
    const searchKeyword = ref('');
    const searchResults = ref([]);
    const searchLoading = ref(false);
    const searchSearched = ref(false);
    // 兜底提示：日报标签未必存在于消息级 ai_tags，此时会退化为关键词搜索并在此说明
    const searchFallbackNote = ref('');

    const runSearch = async () => {
      if (!groupId.value) return;
      if (!searchTag.value && !searchKeyword.value) {
        showToast('请输入关键词', 'warning');
        return;
      }
      searchLoading.value = true;
      searchFallbackNote.value = '';
      try {
        let list = await messageApi.searchMessages({
          groupId: groupId.value,
          tag: searchTag.value,
          keyword: searchKeyword.value,
          limit: 50
        });
        let arr = Array.isArray(list) ? list : (Array.isArray(list?.data) ? list.data : []);
        // 兜底：日报的标签存在 group_digest.tags，消息本身可能没有 ai_tags → 标签查不到时退化为关键词搜索
        if (!arr.length && searchTag.value && !searchKeyword.value) {
          const kwList = await messageApi.searchMessages({
            groupId: groupId.value,
            tag: '',
            keyword: searchTag.value,
            limit: 50
          });
          const kwArr = Array.isArray(kwList) ? kwList : (Array.isArray(kwList?.data) ? kwList.data : []);
          if (kwArr.length) {
            arr = kwArr;
            searchFallbackNote.value = `没有带「${searchTag.value}」标签的消息，已按关键词搜索`;
          }
        }
        searchResults.value = arr;
        searchSearched.value = true;
      } catch (e) {
        showToast(e?.message || '搜索失败', 'error');
        searchResults.value = [];
      } finally {
        searchLoading.value = false;
      }
    };

    /** 点击标签：打开面板并立即按该标签搜索 */
    const openTagSearch = (tag) => {
      searchTag.value = String(tag || '').replace(/^#/, '').trim();
      searchKeyword.value = '';
      searchResults.value = [];
      searchSearched.value = false;
      searchOpen.value = true;
      if (searchTag.value) runSearch();
    };

    const closeSearch = () => {
      searchOpen.value = false;
      searchTag.value = '';
      searchKeyword.value = '';
      searchResults.value = [];
      searchSearched.value = false;
      searchFallbackNote.value = '';
    };

    /** 点击结果：若该消息已在当前加载范围内则滚动高亮，否则提示 */
    const jumpToMessage = async (item) => {
      if (!item?.id) return;
      const el = document.getElementById('msg-' + item.id);
      if (!el) {
        showToast('该消息不在当前加载范围内（可上滑加载更早消息后重试）', 'info');
        return;
      }
      highlightedMessageId.value = item.id;
      await nextTick();
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      setTimeout(() => { if (highlightedMessageId.value === item.id) highlightedMessageId.value = null; }, 2500);
    };

    /** 导出为 Markdown（纯前端拼装 + Blob 下载，不新增后端接口） */    const exportDigests = async () => {
      if (!groupId.value || digestExporting.value) return;
      digestExporting.value = true;
      try {
        const list = await fetchDigestHistory(groupId.value, 200);
        if (!list.length) {
          showToast('该群还没有速览可以导出', 'warning');
          return;
        }
        const SENT = { positive: '积极', neutral: '中性', negative: '消极' };
        const lines = [
          `# 群 ${groupId.value} 群日报`,
          '',
          `> 导出时间：${new Date().toLocaleString('zh-CN')} · 共 ${list.length} 期`,
          ''
        ];
        list.forEach(d => {
          lines.push(`## ${d.digestDate}（${d.messageCount ?? '-'} 条消息${d.sentiment ? ' · ' + (SENT[d.sentiment] || d.sentiment) : ''}）`);
          lines.push('');
          lines.push(d.summary || '');
          const tags = Array.isArray(d.tags) ? d.tags : String(d.tags || '').split(',').filter(Boolean);
          if (tags.length) {
            lines.push('');
            lines.push('标签：' + tags.map(t => String(t).trim()).filter(Boolean).join('、'));
          }
          lines.push('');
        });
        const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `群日报-${groupId.value}-${new Date().toISOString().slice(0, 10)}.md`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        showToast(`已导出 ${list.length} 期速览`, 'success');
      } catch (e) {
        showToast(e?.message || '导出失败', 'error');
      } finally {
        digestExporting.value = false;
      }
    };

    const DIGEST_SENTIMENT_LABELS = { positive: '积极', neutral: '中性', negative: '消极' };
    const digestSentimentLabel = computed(() => DIGEST_SENTIMENT_LABELS[groupDigest.value?.sentiment] || '');
    // tags 后端存的是逗号分隔字符串（新接口），这里同时兼容数组形式
    const digestTags = computed(() => {
      const raw = groupDigest.value?.tags;
      if (Array.isArray(raw)) return raw.filter(Boolean).map(String);
      return (typeof raw === 'string' && raw)
        ? raw.split(',').map(s => s.trim()).filter(Boolean)
        : [];
    });

    /** 兼容「data 里直接是日报对象」与「data 被省略（无日报）」两种响应形态 */
    const normalizeDigest = (res) => {
      if (!res || typeof res !== 'object') return null;
      if (res.summary || res.digestDate || res.tags) return res;
      const inner = res.data;
      if (inner && (inner.summary || inner.digestDate || inner.tags)) return inner;
      return null;
    };

    /** 拉取该群最新一期日报；没有日报时静默置空，不打扰用户 */
    const loadGroupDigest = async (gid) => {
      if (!gid) {
        groupDigest.value = null;
        digestHistory.value = [];
        return;
      }
      try {
        groupDigest.value = normalizeDigest(await messageApi.getLatestGroupDigest(gid));
        digestExpanded.value = false;
      } catch (error) {
        // 没有日报 / 无权限都不应影响群聊浏览
        groupDigest.value = null;
        logger.warn('[digest] 加载今日速览失败:', error);
      }
      // 历史列表（失败静默，仅用于展开后的日期切换）
      try {
        const list = await messageApi.getGroupDigests(gid, 10);
        const arr = Array.isArray(list) ? list : (Array.isArray(list?.data) ? list.data : []);
        digestHistory.value = arr.filter(Boolean);
      } catch (e) {
        digestHistory.value = [];
      }
    };

    /**
     * 生成今日速览。
     * 首次生成 → force=false（后端同群同天幂等，避免重复烧额度）；
     * 已有速览时按钮含义是「重新生成」→ force=true，真正重跑一次大模型。
     */
    const generateDigest = async () => {
      if (digestGenerating.value || !groupId.value) return;
      const hadDigest = !!groupDigest.value;
      digestGenerating.value = true;
      try {
        const view = normalizeDigest(await messageApi.generateGroupDigest(groupId.value, null, hadDigest));
        if (!view) {
          showToast('速览返回为空，请稍后重试', 'warning');
          return;
        }
        groupDigest.value = view;
        digestExpanded.value = true;  // 生成后直接展开，便于看到完整速览
        showToast(hadDigest ? '今日速览已重新生成' : '今日速览生成完成', 'success');
      } catch (error) {
        logger.warn('[digest] 生成今日速览失败:', error);
        showToast(error?.message || '生成今日速览失败', 'error');
      } finally {
        digestGenerating.value = false;
      }
    };

    const toggleDigestExpand = () => { digestExpanded.value = !digestExpanded.value; };

    const currentGroupName = ref('');
    const messagesContainer = ref(null);
    const currentPage = ref(0);
    const totalMessages = ref(0);
    const hasMore = ref(true);
    const isLoadingMore = ref(false);
    const highlightedMessageId = ref(null);
    let fallbackPollInterval = null;
    let highlightTimer = null;
    
    const currentUserId = 'current_user';
    
    // 选择模式相关状态
    const isSelectionMode = ref(false);
    const isMultiSelect = ref(false);
    const selectedMessageIds = ref(new Set());
    const quickSelectCount = ref('');
    const showAnalysisPicker = ref(false);
    const analysisUserPrompt = ref('');
    
    // 计算选中的消息数量
    const selectedMessages = computed(() => Array.from(selectedMessageIds.value));
    
    // 计算选中的消息数据
    const selectedMessagesData = computed(() => {
      return messages.value.filter(msg => selectedMessageIds.value.has(msg.id));
    });

    // 群类型标签（从 props.group.groupType 推断，用于给推荐分析类型打标）
    const GROUP_TYPE_LABELS = {
      GAME: '游戏群', STUDY: '学习群', WORK: '工作群',
      HOBBY: '兴趣群', LIFE: '生活群', SOCIAL: '社交群',
      OTHER: '其他群'
    };
    const GROUP_TYPE_RECOMMEND = {
      GAME: ['meme-dictionary', 'integration-guide', 'summary'],
      STUDY: ['summary', 'topic-trend', 'integration-guide'],
      WORK: ['summary', 'integration-guide', 'social-graph'],
      HOBBY: ['meme-dictionary', 'persona-match', 'summary'],
      LIFE: ['integration-guide', 'topic-trend', 'summary'],
      SOCIAL: ['social-graph', 'persona-match', 'integration-guide'],
      OTHER: ['summary', 'integration-guide']
    };
    const groupType = computed(() => {
      const gt = props.group?.groupType;
      return gt && GROUP_TYPE_RECOMMEND[gt] ? gt : 'OTHER';
    });
    const groupTypeLabel = computed(() => GROUP_TYPE_LABELS[groupType.value] || '其他群');

    // 6 种融入导向分析类型选项（根据群类型高亮推荐）
    const analysisTypeOptions = computed(() => {
      const recommended = new Set(GROUP_TYPE_RECOMMEND[groupType.value] || GROUP_TYPE_RECOMMEND.OTHER);
      const list = [
        { value: 'summary', label: '群聊速览', desc: '群主题 / 氛围 / 近期热点，快速了解群在聊什么', iconName: 'chart' },
        { value: 'social-graph', label: '社交图谱', desc: '活跃人物 / 意见领袖 / 话题带动者，知道谁是关键人', iconName: 'network' },
        { value: 'topic-trend', label: '话题趋势', desc: '上升 / 稳定 / 衰退话题，把握参与时机', iconName: 'trending' },
        { value: 'integration-guide', label: '融入指南', desc: '参与建议 / 共同兴趣 / 避雷提示（核心维度）', iconName: 'compass', recommendedBadge: true },
        { value: 'meme-dictionary', label: '梗词典', desc: '群内特有梗 / 缩写 / 表情含义，看懂黑话', iconName: 'book-open' },
        { value: 'persona-match', label: '人设匹配', desc: '形象定位建议 / 发言风格参考，塑造受欢迎的人设', iconName: 'theater' }
      ];
      return list.map(o => ({ ...o, recommended: recommended.has(o.value) }));
    });

    const openAnalysisPicker = () => {
      if (selectedMessages.value.length === 0) {
        showToast('请先选择要分析的消息', 'warning');
        return;
      }
      analysisUserPrompt.value = '';
      showAnalysisPicker.value = true;
    };
    const closeAnalysisPicker = () => { showAnalysisPicker.value = false; };

    // 群成员昵称映射（从后端接口加载，覆盖历史所有发送者）
    const groupMemberMap = ref(new Map());

    // 根据已加载消息 + 群成员映射构建 QQ 号 -> 昵称，用于渲染 @某人
    const qqNicknameMap = computed(() => {
      const map = new Map(groupMemberMap.value);
      messages.value.forEach(msg => {
        if (msg.userQq && msg.userNickname) {
          // 保留最新昵称（后续消息可能改名）
          map.set(String(msg.userQq), msg.userNickname);
        }
      });
      return map;
    });

    const loadGroupMembers = async (gid) => {
      try {
        const members = await messageApi.getGroupMembers(gid);
        const map = new Map();
        (members || []).forEach(m => {
          if (m.qq) {
            map.set(String(m.qq), m.nickname || m.qq);
          }
        });
        groupMemberMap.value = map;
      } catch (error) {
        logger.warn('加载群成员昵称映射失败:', error);
      }
    };

    const sortMessagesAsc = (list) => [...list].sort((a, b) => {
      return new Date(a.sendTime || a.timestamp) - new Date(b.sendTime || b.timestamp);
    });

    const mergeMessage = (incoming) => {
      // 后端广播的已删除消息不应再出现在当前视图
      if (incoming.deleted) {
        messages.value = messages.value.filter(m => m.id !== incoming.id);
        return;
      }
      const idx = messages.value.findIndex(m => m.id === incoming.id);
      if (idx >= 0) {
        messages.value[idx] = { ...messages.value[idx], ...incoming };
      } else {
        messages.value.push(incoming);
        messages.value = sortMessagesAsc(messages.value);
      }
    };

    const getLastMessageId = () => {
      if (messages.value.length === 0) return 0;
      return Math.max(...messages.value.map(m => m.id || 0));
    };

    const resolveGroup = () => {
      if (props.group?.groupId) return props.group;
      return null;
    };

    const loadMessages = async (showLoading = true, scrollToBottomFlag = true) => {
      try {
        const g = resolveGroup();
        if (!g) {
          messages.value = [];
          currentGroupName.value = '';
          groupDigest.value = null;
          return;
        }
        const gid = g.groupId;
        const sq = g.ownerQq;

        if (showLoading) isLoading.value = true;
        currentPage.value = 0;
        hasMore.value = true;

        const response = await messageApi.getMessagesByGroupIdPaged(gid, 0, PAGE_SIZE, sq);
        const list = sortMessagesAsc(response.messages || []);
        messages.value = list;

        // 同时加载该群历史成员昵称映射，用于解析 @消息
        loadGroupMembers(gid);
        // 同时加载该群最新一期日报（今日速览卡片）
        loadGroupDigest(gid);
        totalMessages.value = response.total || list.length;
        hasMore.value = list.length < totalMessages.value;
        currentGroupName.value = list.length > 0
          ? (list[0].groupName || `群聊 ${gid}`)
          : `群聊 ${gid}`;

        wsSubscribe(gid);
        wsConnect();

        if (scrollToBottomFlag) {
          nextTick(scrollToBottom);
        }
      } catch (error) {
        logger.error('加载消息失败:', error);
        if (showLoading) showToast(`加载消息失败: ${error.message}`, 'error');
      } finally {
        if (showLoading) isLoading.value = false;
      }
    };

    const loadMoreMessages = async () => {
      if (!hasMore.value || isLoadingMore.value || !groupId.value || !selfQq.value) return;
      isLoadingMore.value = true;
      const container = messagesContainer.value;
      const prevScrollHeight = container?.scrollHeight || 0;

      try {
        currentPage.value += 1;
        const response = await messageApi.getMessagesByGroupIdPaged(
          groupId.value, currentPage.value, PAGE_SIZE, selfQq.value
        );
        const older = sortMessagesAsc(response.messages || []);
        if (older.length === 0) {
          hasMore.value = false;
          return;
        }
        const existingIds = new Set(messages.value.map(m => m.id));
        const unique = older.filter(m => !existingIds.has(m.id));
        messages.value = [...unique, ...messages.value];
        hasMore.value = messages.value.length < (response.total || totalMessages.value);

        nextTick(() => {
          if (container) {
            container.scrollTop = container.scrollHeight - prevScrollHeight;
          }
        });
      } catch (error) {
        logger.error('加载更多消息失败:', error);
        currentPage.value -= 1;
      } finally {
        isLoadingMore.value = false;
      }
    };

    const fetchIncremental = async () => {
      if (!groupId.value || !selfQq.value || messages.value.length === 0) return;
      try {
        const afterId = getLastMessageId();
        const newMessages = await messageApi.getMessagesSince(groupId.value, afterId, selfQq.value);
        if (!newMessages?.length) return;
        const wasAtBottom = isAtBottom();
        newMessages.forEach(mergeMessage);
        if (wasAtBottom) nextTick(scrollToBottom);
        // 通知父组件：有新消息，触发 Sidebar 刷新，让新消息多的群移至顶层
        emit('new-message-arrived');
      } catch (error) {
        logger.warn('增量拉取失败:', error);
      }
    };

    const handleWebSocketMessage = (data) => {
      if (!data?.message || data.groupId !== groupId.value) return;
      // 如果当前选中了特定 QQ，只接收该 QQ 的消息
      if (selfQq.value && data.message?.selfQq !== selfQq.value) return;
      const wasAtBottom = isAtBottom();
      if (data.type === 'new_message') {
        mergeMessage(data.message);
        if (wasAtBottom) nextTick(scrollToBottom);
        emit('new-message-arrived');
      } else if (data.type === 'message_update') {
        mergeMessage(data.message);
      }
    };

    const { connect: wsConnect, subscribe: wsSubscribe, unsubscribe: wsUnsubscribe, disconnect: wsDisconnect, connected: wsConnected } =
      useMessageWebSocket(handleWebSocketMessage);

    const startFallbackPoll = () => {
      stopFallbackPoll();
      fallbackPollInterval = setInterval(fetchIncremental, FALLBACK_POLL_MS);
    };

    const stopFallbackPoll = () => {
      if (fallbackPollInterval) {
        clearInterval(fallbackPollInterval);
        fallbackPollInterval = null;
      }
    };

    // WS/轮询互斥：WS 连接成功时停轮询，断开时启轮询
    watch(wsConnected, (isConnected) => {
      if (isConnected) {
        stopFallbackPoll();
      } else {
        startFallbackPoll();
      }
    });

    const handleScroll = () => {
      if (!messagesContainer.value || isLoadingMore.value) return;
      if (messagesContainer.value.scrollTop < 80 && hasMore.value) {
        loadMoreMessages();
      }
    };

    const isSelfMessage = (message) => {
      // 判断是否是登录账号发送的消息
      // 通过比较 userQq 和 selfQq 是否相等来判断
      if (message.userQq && message.selfQq) {
        return message.userQq === message.selfQq;
      }
      // 备用方案：检查 isSelfMessage 字段
      if (message.isSelfMessage !== undefined) {
        return message.isSelfMessage;
      }
      // 兼容旧数据
      return message.userId === currentUserId || message.userName === '我' || message.userNickname === '我';
    };

    const getAvatar = (userQq) => {
      // 使用 QQ 头像 API
      if (userQq) {
        return `https://q.qlogo.cn/headimg_dl?dst_uin=${userQq}&spec=100`;
      }
      return 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100';
    };

    const handleAvatarError = (e) => {
      e.target.src = 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100';
    };

    // formatMessageTime 从 utils/formatTime.js 导入

    const scrollToBottom = () => {
      if (!messagesContainer.value) return;
      const container = messagesContainer.value;
      container.scrollTop = container.scrollHeight;
      // 图片/视频等异步加载后可能撑高容器，延迟再次滚动到底
      requestAnimationFrame(() => {
        setTimeout(() => {
          if (messagesContainer.value) {
            messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
          }
        }, 100);
      });
    };
    
    // 检查是否在底部（允许10px的误差）
    const isAtBottom = () => {
      if (!messagesContainer.value) return false;
      const container = messagesContainer.value;
      return container.scrollHeight - container.scrollTop - container.clientHeight <= 10;
    };

    // 点击引用消息后滚动到原消息并高亮
    const handleNavigateToMessage = (messageId) => {
      const target = messages.value.find(m => m.id === messageId || String(m.id) === String(messageId));
      if (!target) {
        showToast('原消息不在当前视图中，请加载更多消息', 'warning');
        return;
      }
      const el = document.getElementById(`msg-${target.id}`);
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'center' });
        highlightedMessageId.value = target.id;
        if (highlightTimer) clearTimeout(highlightTimer);
        highlightTimer = setTimeout(() => {
          highlightedMessageId.value = null;
        }, 3000);
      } else {
        showToast('原消息不在当前视图中，请加载更多消息', 'warning');
      }
    };

    watch(() => props.group, (newGroup, oldGroup) => {
      const oldGid = oldGroup?.groupId;
      const newGid = newGroup?.groupId;
      if (oldGid) wsUnsubscribe(oldGid);
      if (newGid) {
        groupMemberMap.value = new Map();
        loadMessages();
      } else {
        stopFallbackPoll();
        messages.value = [];
        currentGroupName.value = '';
        groupDigest.value = null;
        digestExpanded.value = false;
      }
    }, { immediate: true, deep: true });

    onMounted(() => {
      if (props.group?.groupId) {
        loadMessages();
      }
      startFallbackPoll();
    });
    
    onUnmounted(() => {
      stopFallbackPoll();
      wsDisconnect();
      if (highlightTimer) {
        clearTimeout(highlightTimer);
        highlightTimer = null;
      }
    });
    
    // 切换选择模式
    const toggleSelectionMode = () => {
      isSelectionMode.value = !isSelectionMode.value;
      if (!isSelectionMode.value) {
        // 退出选择模式时清空选择
        clearSelection();
      }
    };
    
    // 切换消息选择状态
    const toggleMessageSelection = (messageId) => {
      if (!isMultiSelect.value) {
        // 单选模式：只保留当前选中的消息
        if (selectedMessageIds.value.has(messageId)) {
          selectedMessageIds.value.clear();
        } else {
          selectedMessageIds.value.clear();
          selectedMessageIds.value.add(messageId);
        }
      } else {
        // 多选模式：切换选中状态
        if (selectedMessageIds.value.has(messageId)) {
          selectedMessageIds.value.delete(messageId);
        } else {
          selectedMessageIds.value.add(messageId);
        }
      }
    };
    
    // 检查消息是否被选中
    const isSelected = (messageId) => {
      return selectedMessageIds.value.has(messageId);
    };
    
    // 清空选择
    const clearSelection = () => {
      selectedMessageIds.value.clear();
    };
    
    // 快速选择最近n条消息
    const selectRecentMessages = () => {
      const count = parseInt(quickSelectCount.value);
      if (isNaN(count) || count <= 0) {
        showToast('请输入有效的数量', 'warning');
        return;
      }
      
      // 按时间排序消息，取最近的count条
      const sortedMessages = [...messages.value].sort((a, b) => {
        const timeA = new Date(a.sendTime || a.timestamp || 0);
        const timeB = new Date(b.sendTime || b.timestamp || 0);
        return timeB - timeA;
      });
      
      // 清空之前的选择
      selectedMessageIds.value.clear();
      
      // 选择最近的count条消息
      const recentMessages = sortedMessages.slice(0, count);
      recentMessages.forEach(msg => {
        selectedMessageIds.value.add(msg.id);
      });
      
      showToast(`已选择最近 ${recentMessages.length} 条消息`, 'success');
    };
    
    // extractForwardXmlTitles / extractForwardMessages 从 utils/messageParser.js 导入
    const cleanMessageText = (text) => {
      if (!text) return '[无内容]';
      return text
        .replace(/\[CQ:image[^\]]*\]/g, '[图片]')
        .replace(/\[CQ:video[^\]]*\]/g, '[视频]')
        .replace(/\[CQ:(?:record|voice)[^\]]*\]/g, '[语音]')
        .replace(/\[CQ:face[^\]]*\]/g, '[表情]')
        .replace(/\[CQ:file[^\]]*\]/g, '[文件]')
        .replace(/\[CQ:at,qq=([^,\]]+)\]/g, (match, qq) => {
          const nickname = qqNicknameMap.value.get(String(qq));
          return nickname ? `@${nickname}` : `@${qq}`;
        })
        .replace(/\[CQ:reply[^\]]*\]/g, '')
        .replace(/\[CQ:forward[^\]]*\]/g, '[聊天记录]')
        .replace(/\[CQ:[^\]]+\]/g, '')
        .trim() || '[无内容]';
    };
    const formatMessageForAnalysis = (msg) => {
      const type = (msg.messageType || 'TEXT').toUpperCase();
      if (type === 'FORWARD') {
        const children = extractForwardMessages(msg);
        if (children.length === 0) return '[聊天记录]';
        const lines = children.map(child => {
          const childType = (child.messageType || 'TEXT').toUpperCase();
          const childText = childType === 'FORWARD'
            ? formatMessageForAnalysis(child)
            : cleanMessageText(child.content);
          return `${child.userNickname || child.userName || '未知用户'}: ${childText}`;
        });
        return `[聊天记录]\n${lines.join('\n')}`;
      }
      if (type === 'IMAGE') return '[图片]';
      if (type === 'VIDEO') return '[视频]';
      if (type === 'VOICE' || type === 'AUDIO') return '[语音]';
      return cleanMessageText(msg.content);
    };

    // 用指定的 analysisType 启动分析（由分析类型选择器按钮调用）
    // —— 注意：不再在前端构造 prompt，LLM prompt 由后端按 messageIds 查 DB + 渲染 prompts.yml 模板生成
    const analyzeWithType = (analysisType) => {
      if (selectedMessages.value.length === 0) {
        showToast('请先选择要分析的消息', 'warning');
        return;
      }
      const selectedData = selectedMessagesData.value.map(msg => ({
        user: msg.userNickname || msg.userName || '未知用户',
        content: formatMessageForAnalysis(msg)
      }));

      closeAnalysisPicker();

      // 发送分析请求事件给父组件（最终由 AstrBotChat 调用后端 /api/astrbot/analyze-selected 接口）
      emit('analysis-result', {
        type: 'request',
        analysisType: analysisType || 'summary',
        messageIds: Array.from(selectedMessageIds.value),
        groupId: groupId.value,
        userPrompt: analysisUserPrompt.value || '',
        messages: selectedData // 用于前端展示「已选 N 条消息」的折叠卡片（不传给 LLM）
      });

      // 退出选择模式
      isSelectionMode.value = false;
      clearSelection();
    };

    // 兼容保留：原有的 analyzeSelected 仍保留给旧调用点（如果有的话），默认走 summary
    const analyzeSelected = () => analyzeWithType('summary');

    // 删除选中的消息
    const deleteSelected = async () => {
      if (selectedMessages.value.length === 0) {
        showToast('请先选择要删除的消息', 'warning');
        return;
      }

      // 判断是否包含图片/视频/语音消息，给用户"同时删除媒体文件"的选项
      const hasMedia = messages.value.some(m => {
        if (!selectedMessages.value.includes(m.id)) return false;
        const type = (m.messageType || 'TEXT').toUpperCase();
        return ['IMAGE', 'VIDEO', 'VOICE', 'AUDIO'].includes(type)
          || (m.content && /^\s*\[CQ:(image|video|record)\b/i.test(m.content))
          || (m.content && String(m.content).startsWith('/images/'));
      });

      let confirmText = `确定要删除选中的 ${selectedMessages.value.length} 条消息吗？`;
      if (hasMedia) {
        confirmText += '\n（点击"确认"仅软删除消息；点击"确认+删除媒体"同时删除图片/视频/语音文件）';
      }
      const confirmed = window.confirm(confirmText);
      if (!confirmed) return;

      // 只有当包含媒体时，再询问是否删除媒体文件（两步确认）
      let deleteMedia = false;
      if (hasMedia) {
        deleteMedia = window.confirm('是否同时删除关联的图片/视频/语音文件？\n（点击"确认"同步删除磁盘文件；"取消"仅软删除消息记录）');
      }

      try {
        const result = await messageApi.deleteMessagesBatch(selectedMessages.value, deleteMedia);
        const deletedIds = new Set(selectedMessages.value);
        messages.value = messages.value.filter(msg => !deletedIds.has(msg.id));
        showToast(`成功删除 ${result.deletedCount || selectedMessages.value.length} 条消息${deleteMedia ? '（媒体文件已清理）' : ''}`, 'success');

        clearSelection();
        isSelectionMode.value = false;
      } catch (error) {
        logger.error('删除消息失败:', error);
        showToast(`删除失败: ${error.message}`, 'error');
      }
    };

    return {
      groupId,
      messages,
      chatImageGallery,
      renderMessages,
      isSummarizing,
      summarizeOne,
      // 群日报「今日速览」
      groupDigest,
      digestTags,
      digestSentimentLabel,
      digestGenerating,
      digestExpanded,
      digestHistory,
      viewHistoryDigest,
      historyPanelOpen,
      digestHistoryLoading,
      digestHistoryLimit,
      digestExporting,
      toggleHistoryPanel,
      loadMoreHistory,
      exportDigests,
      // 手动推送 + 标签搜索
      isAdmin,
      pushConfirmOpen,
      digestPushing,
      pushPreviewText,
      openPushConfirm,
      confirmPush,
      searchOpen,
      searchTag,
      searchKeyword,
      searchResults,
      searchLoading,
      searchSearched,
      searchFallbackNote,
      runSearch,
      openTagSearch,
      closeSearch,
      jumpToMessage,
      toggleDigestExpand,
      generateDigest,
      isLoading,
      currentGroupName,
      messagesContainer,
      isLoadingMore,
      loadMessages,
      handleScroll,
      isSelfMessage,
      getAvatar,
      handleAvatarError,
      formatTime: formatMessageTime,
      highlightedMessageId,
      handleNavigateToMessage,
      qqNicknameMap,
      // 选择模式相关
      isSelectionMode,
      isMultiSelect,
      selectedMessages,
      selectedMessagesData,
      quickSelectCount,
      toggleSelectionMode,
      toggleMessageSelection,
      isSelected,
      clearSelection,
      selectRecentMessages,
      analyzeSelected,
      analyzeWithType,
      deleteSelected,
      // AI 分析类型选择弹窗
      showAnalysisPicker,
      openAnalysisPicker,
      closeAnalysisPicker,
      analysisTypeOptions,
      groupTypeLabel,
      analysisUserPrompt
    };
  }
};
</script>

<style scoped>
.chat-interface {
  background-color: var(--card-bg, white);
  border-radius: 8px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;   /* 推送确认弹窗与搜索面板以此为定位基准 */
}

.chat-header {
  padding: 8px 16px;
  border-bottom: 1px solid var(--border-color, #e0e0e0);
  background-color: var(--bg-tertiary, #f8f9fa);
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-height: 48px;
}

.group-selector {
  display: flex;
  gap: 10px;
  flex: 1;
  max-width: 400px;
}

.btn-refresh {
  width: 32px;
  height: 32px;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  transition: all 0.2s;
}

.btn-refresh:hover:not(:disabled) {
  background-color: #2980b9;
  transform: rotate(180deg);
}

.btn-refresh:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
  transform: none;
}

.current-group {
  font-weight: 500;
  color: var(--text-primary, #2c3e50);
  font-size: 14px;
}

.messages-area {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.load-more-hint {
  text-align: center;
  color: var(--text-muted, #888);
  font-size: 13px;
  padding: 8px 0 12px;
}

.empty-state, .loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-secondary, #666);
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 10px;
}

.loading-spinner {
  border: 4px solid #f3f3f3;
  border-top: 4px solid #3498db;
  border-radius: 50%;
  width: 40px;
  height: 40px;
  animation: spin 1s linear infinite;
  margin-bottom: 10px;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}

.messages-list {
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.message-wrapper {
  display: flex;
  margin-bottom: 10px;
  width: 100%;
}

.message-wrapper.message-self {
  justify-content: flex-end;
}

.message-wrapper.message-other {
  justify-content: flex-start;
}

.message-bubble {
  max-width: 70%;
  padding: 12px 16px;
  border-radius: 18px;
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.message-left {
  /* 浅色主题保持原值；暗色主题单独覆盖（原来写死 #f1f3f4，暗色下气泡是亮块） */
  background-color: #f1f3f4;
  border-bottom-left-radius: 4px;
  flex-direction: row;
  margin-right: auto;
}
.theme-dark .message-left {
  background-color: #16213e;
  color: #e0e0e0;
}

.message-right {
  background-color: #3498db;
  color: white;
  border-bottom-right-radius: 4px;
  flex-direction: row-reverse;
  margin-left: auto;
}

.message-avatar-img {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}

.message-content-wrapper {
  flex: 1;
  min-width: 0;
}

.message-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--text-secondary, #666);
}

.message-right .message-header {
  color: rgba(255, 255, 255, 0.8);
}

.message-user {
  font-weight: 500;
}

.message-time {
  color: #95a5a6;
}

.message-right .message-time {
  color: rgba(255, 255, 255, 0.6);
}

.message-text {
  line-height: 1.4;
  word-wrap: break-word;
}

.message-image {
  margin-top: 5px;
}

.message-image img {
  max-width: 100%;
  max-height: 300px;
  border-radius: 8px;
  cursor: pointer;
  transition: transform 0.2s;
}

.message-image img:hover {
  transform: scale(1.02);
}

.ai-summary {
  margin-top: 8px;
  padding: 10px 12px;
  background-color: #e3f2fd;
  border-radius: 8px;
  font-size: 14px;
}

.ai-summary-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  font-weight: 500;
  color: #1976d2;
}

.ai-summary-content {
  line-height: 1.5;
  color: var(--text-primary, #333);
}

/* 情感徽章 */
.ai-sentiment {
  margin-left: auto;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 600;
  background: rgba(25, 118, 210, 0.12);
  color: #1565c0;
}
.ai-sentiment.st-positive { background: rgba(46, 125, 50, 0.14); color: #2e7d32; }
.ai-sentiment.st-negative { background: rgba(198, 40, 40, 0.14); color: #c62828; }
.ai-sentiment.st-neutral { background: rgba(96, 125, 139, 0.16); color: #546e7a; }

/* 标签 */
.ai-summary-tags { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.ai-tag {
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11.5px;
  background: rgba(25, 118, 210, 0.1);
  color: #1565c0;
}

/* 暗色主题 */
.theme-dark .ai-summary { background-color: rgba(25, 118, 210, 0.16); }
.theme-dark .ai-summary-header { color: #90caf9; }
.theme-dark .ai-sentiment { background: rgba(144, 202, 249, 0.18); color: #90caf9; }
.theme-dark .ai-sentiment.st-positive { background: rgba(165, 214, 167, 0.18); color: #a5d6a7; }
.theme-dark .ai-sentiment.st-negative { background: rgba(239, 154, 154, 0.18); color: #ef9a9a; }
.theme-dark .ai-sentiment.st-neutral { background: rgba(176, 190, 197, 0.18); color: #b0bec5; }
.theme-dark .ai-tag { background: rgba(144, 202, 249, 0.16); color: #90caf9; }

/* ===== 群日报「今日速览」卡片（AI 摘要阶段 3）=====
   全部使用主题变量，暗色模式下由 .theme-dark 的变量自动适配，不写死浅色背景 */
.group-digest {
  flex-shrink: 0;
  margin: 10px 16px 0;
  padding: 10px 12px;
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 8px;
  background-color: var(--bg-tertiary, #f8f9fa);
  font-size: 13px;
}

.digest-header {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.digest-title {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 600;
  color: var(--accent-color, #3498db);
}

.digest-date {
  font-size: 12px;
  color: var(--text-muted, #999);
}

.digest-actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}

/* 卡片内情感徽章不需要再撑开左侧空间 */
.group-digest .ai-sentiment { margin-left: 0; }

.digest-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  border: 1px solid var(--border-color, #d8dee9);
  border-radius: 999px;
  background-color: var(--card-bg, #fff);
  color: var(--text-secondary, #666);
  font-size: 11.5px;
  cursor: pointer;
  transition: all 0.15s;
}
.digest-btn:hover:not(:disabled) {
  border-color: var(--accent-color, #3498db);
  color: var(--accent-color, #3498db);
}
.digest-btn:disabled { cursor: progress; opacity: 0.75; }
.digest-btn.primary { color: var(--accent-color, #3498db); border-color: var(--accent-color, #3498db); }

.digest-summary {
  margin-top: 6px;
  color: var(--text-primary, #333);
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
/* 折叠时最多两行；「展开」后显示完整文本 */
.digest-summary:not(.expanded) {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.digest-meta {
  margin-top: 6px;
  font-size: 11.5px;
  color: var(--text-muted, #999);
}

/* 历史速览日期切换 */
.digest-history {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--border-color, #e0e0e0);
}
.digest-history-label { font-size: 11.5px; color: var(--text-muted, #999); }
.digest-history-chip {
  padding: 2px 8px;
  border-radius: 6px;
  border: 1px solid var(--border-color, #e0e0e0);
  background: var(--card-bg, #fff);
  color: var(--text-secondary, #666);
  font-size: 11.5px;
  cursor: pointer;
  transition: all 0.15s;
}
.digest-history-chip:hover { border-color: var(--accent-color, #3498db); color: var(--accent-color, #3498db); }
.digest-history-chip.active { background: rgba(52, 152, 219, 0.12); border-color: var(--accent-color, #3498db); color: var(--accent-color, #3498db); }
.digest-history-tools { display: inline-flex; gap: 6px; margin-left: auto; }

/* 全部历史面板 */
.digest-history-panel {
  margin-top: 8px;
  max-height: 260px;
  overflow-y: auto;
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 8px;
  background: var(--card-bg, #fff);
}
.digest-history-loading { padding: 12px; text-align: center; font-size: 12px; color: var(--text-muted, #999); }
.digest-history-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  width: 100%;
  padding: 8px 12px;
  border: none;
  border-bottom: 1px solid var(--border-color, #eef1f5);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s;
}
.digest-history-row:hover { background: var(--bg-tertiary, #f8f9fa); }
.digest-history-row.active { background: rgba(52, 152, 219, 0.1); }
.digest-history-row-date { flex-shrink: 0; font-size: 12px; font-weight: 600; color: var(--text-primary, #333); }
.digest-history-row-count { flex-shrink: 0; font-size: 11.5px; color: var(--text-muted, #999); }
.digest-history-row-summary {
  font-size: 12px;
  color: var(--text-secondary, #666);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.digest-history-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 12px;
}
.digest-history-total { font-size: 11.5px; color: var(--text-muted, #999); }

/* 可点击的标签 chip */
.ai-tag.clickable {
  border: none;
  cursor: pointer;
  font-family: inherit;
  transition: all 0.15s;
}
.ai-tag.clickable:hover { background: rgba(52, 152, 219, 0.22); }

/* 推送确认弹窗 */
.push-confirm-mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 60;
}
.push-confirm {
  width: min(520px, 88%);
  background: var(--card-bg, #fff);
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 12px;
  padding: 18px 20px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.22);
}
.push-confirm-title { font-size: 15px; font-weight: 600; color: var(--text-primary, #333); margin-bottom: 6px; }
.push-confirm-sub { font-size: 12.5px; color: var(--text-secondary, #666); margin-bottom: 10px; }
.push-confirm-text {
  margin: 0 0 14px 0;
  padding: 10px 12px;
  max-height: 220px;
  overflow-y: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--text-primary, #333);
  background: var(--bg-tertiary, #f8f9fa);
  border-radius: 8px;
}
.push-confirm-actions { display: flex; justify-content: flex-end; gap: 10px; }

/* 标签 / 关键词搜索面板 */
.tag-search-panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(420px, 92%);
  background: var(--card-bg, #fff);
  border-left: 1px solid var(--border-color, #e0e0e0);
  box-shadow: -8px 0 24px rgba(0, 0, 0, 0.16);
  display: flex;
  flex-direction: column;
  z-index: 55;
}
.tag-search-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border-color, #eef1f5);
}
.tag-search-title { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; color: var(--text-primary, #333); }
.tag-search-close {
  border: none;
  background: transparent;
  color: var(--text-muted, #999);
  font-size: 15px;
  cursor: pointer;
  padding: 2px 6px;
  border-radius: 6px;
}
.tag-search-close:hover { background: var(--bg-tertiary, #f8f9fa); color: var(--text-primary, #333); }
.tag-search-bar { display: flex; gap: 8px; padding: 10px 14px; border-bottom: 1px solid var(--border-color, #eef1f5); }
.tag-search-bar input {
  flex: 1;
  min-width: 0;
  padding: 7px 10px;
  border: 1px solid var(--border-color, #d8dee9);
  border-radius: 6px;
  background: var(--card-bg, #fff);
  color: var(--text-primary, #333);
  font-size: 12.5px;
}
.tag-search-body { flex: 1; overflow-y: auto; padding: 6px 0 12px; }
.tag-search-empty { padding: 24px 16px; text-align: center; font-size: 12.5px; color: var(--text-muted, #999); }
.tag-search-note {
  margin: 8px 14px 2px;
  padding: 6px 10px;
  border-radius: 6px;
  background: rgba(52, 152, 219, 0.1);
  color: var(--accent-color, #3498db);
  font-size: 11.5px;
  line-height: 1.5;
}
.tag-search-item {
  display: block;
  width: 100%;
  padding: 10px 14px;
  border: none;
  border-bottom: 1px solid var(--border-color, #f2f4f7);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s;
}
.tag-search-item:hover { background: var(--bg-tertiary, #f8f9fa); }
.tag-search-item-head { display: flex; justify-content: space-between; gap: 8px; margin-bottom: 4px; }
.tag-search-item-user { font-size: 12px; font-weight: 600; color: var(--text-primary, #333); }
.tag-search-item-time { font-size: 11px; color: var(--text-muted, #999); }
.tag-search-item-text {
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--text-secondary, #555);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.tag-search-item-tags { margin-top: 5px; font-size: 11px; color: var(--accent-color, #3498db); }

/* 无日报时的一行淡提示（不占空间） */
.digest-empty {
  margin: 4px 16px 0;
  padding: 4px 12px;
  display: flex;
  justify-content: center;
  border: none;
  background: transparent;
}

.digest-empty-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 8px;
  border: none;
  background: transparent;
  color: var(--text-muted, #999);
  font-size: 12px;
  cursor: pointer;
  transition: color 0.15s;
}
.digest-empty-btn:hover:not(:disabled) { color: var(--accent-color, #3498db); }
.digest-empty-btn:disabled { cursor: progress; }

/* 按需摘要按钮（平时隐藏，鼠标悬停消息时出现） */
.msg-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 4px;
  opacity: 0;
  transition: opacity 0.15s;
}
.message-wrapper:hover .msg-actions,
.message-wrapper:focus-within .msg-actions { opacity: 1; }

.msg-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 9px;
  border-radius: 999px;
  border: 1px solid var(--border-color, #d8dee9);
  background: var(--card-bg, #fff);
  color: var(--text-secondary, #666);
  font-size: 11.5px;
  cursor: pointer;
  transition: all 0.15s;
}
.msg-action-btn:hover:not(:disabled) {
  border-color: var(--accent-color, #3498db);
  color: var(--accent-color, #3498db);
}
.msg-action-btn:disabled { cursor: progress; opacity: 0.75; }

.msg-action-spinner {
  width: 10px;
  height: 10px;
  border: 2px solid rgba(52, 152, 219, 0.35);
  border-top-color: #3498db;
  border-radius: 50%;
  animation: msg-action-spin 0.7s linear infinite;
}
@keyframes msg-action-spin { to { transform: rotate(360deg); } }

/* 滚动条样式 */
.messages-area::-webkit-scrollbar {
  width: 6px;
}

.messages-area::-webkit-scrollbar-track {
  background: var(--bg-tertiary, #f1f1f1);
  border-radius: 3px;
}

.messages-area::-webkit-scrollbar-thumb {
  background: #c1c1c1;
  border-radius: 3px;
}

.messages-area::-webkit-scrollbar-thumb:hover {
  background: #a8a8a8;
}

/* 选择模式按钮 */
.btn-selection {
  width: 32px;
  height: 32px;
  background-color: #9b59b6;
  color: white;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  font-size: 16px;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}

.btn-selection::after {
  content: '';
  position: absolute;
  width: 12px;
  height: 6px;
  border-left: 2px solid white;
  border-bottom: 2px solid white;
  transform: rotate(-45deg);
  top: 50%;
  left: 50%;
  margin-left: -5px;
  margin-top: -3px;
}

.btn-selection:hover {
  background-color: #8e44ad;
  transform: scale(1.1);
}

.btn-selection.active {
  background-color: #e74c3c;
}

/* 选择工具栏 */
.selection-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  background-color: var(--bg-tertiary, #f8f9fa);
  border-bottom: 1px solid var(--border-color, #e0e0e0);
  gap: 15px;
  flex-wrap: wrap;
}

.selection-info {
  display: flex;
  align-items: center;
  gap: 20px;
}

.selected-count {
  font-weight: 500;
  color: var(--text-primary, #2c3e50);
}

.selection-mode-label {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: var(--text-secondary, #666);
  font-size: 14px;
}

.selection-mode-label input[type="checkbox"] {
  width: 16px;
  height: 16px;
  cursor: pointer;
}

.selection-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.quick-select {
  display: flex;
  gap: 5px;
}

.quick-select-input {
  width: 60px;
  padding: 6px 10px;
  border: 1px solid var(--border-color, #ddd);
  border-radius: 4px;
  font-size: 14px;
  text-align: center;
}

.quick-select-input:focus {
  outline: none;
  border-color: #3498db;
}

.btn-quick-select {
  padding: 6px 12px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  transition: background-color 0.2s;
}

.btn-quick-select:hover {
  background-color: #2980b9;
}

.btn-clear {
  padding: 8px 16px;
  background-color: #95a5a6;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  transition: background-color 0.2s;
}

.btn-clear:hover {
  background-color: #7f8c8d;
}

.btn-analyze {
  padding: 8px 16px;
  background-color: #27ae60;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  gap: 6px;
}

.btn-analyze:hover:not(:disabled) {
  background-color: #219a52;
}

.btn-analyze:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
}

.btn-delete {
  padding: 8px 16px;
  background-color: #e74c3c;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  gap: 6px;
}

.btn-delete:hover:not(:disabled) {
  background-color: #c0392b;
}

.btn-delete:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
}

/* 消息选择框 */
.message-checkbox {
  display: flex;
  align-items: center;
  padding: 0 10px;
  cursor: pointer;
}

.message-checkbox input[type="checkbox"] {
  width: 20px;
  height: 20px;
  cursor: pointer;
  accent-color: #3498db;
}

/* 选择模式下的消息样式 */
.message-wrapper.selection-mode {
  cursor: pointer;
  transition: background-color 0.2s;
  border-radius: 8px;
}

.message-wrapper.selection-mode:hover {
  background-color: rgba(52, 152, 219, 0.1);
}

.message-wrapper.message-selected {
  background-color: rgba(52, 152, 219, 0.15);
  border-radius: 8px;
}

.message-wrapper.message-highlight {
  animation: highlight-pulse 3s ease;
}

@keyframes highlight-pulse {
  0% {
    background-color: rgba(255, 235, 59, 0.5);
    box-shadow: 0 0 0 2px rgba(255, 235, 59, 0.6);
  }
  100% {
    background-color: transparent;
    box-shadow: none;
  }
}

/* 响应式设计 */
@media (max-width: 768px) {
  .message-bubble {
    max-width: 85%;
  }
  
  .chat-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
  
  .group-selector {
    width: 100%;
    max-width: none;
  }
  
  .selection-toolbar {
    flex-direction: column;
    align-items: flex-start;
    gap: 10px;
  }
  
  .selection-actions {
    width: 100%;
    justify-content: flex-end;
  }
}

/* ============ AI 分析类型选择弹窗 ============ */
.analysis-picker-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  backdrop-filter: blur(2px);
}
.analysis-picker {
  width: min(680px, 92vw);
  max-height: 85vh;
  background: var(--card-bg, white);
  border-radius: 14px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.22);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: ap-pop 0.22s ease-out;
}
@keyframes ap-pop {
  from { opacity: 0; transform: translateY(8px) scale(0.98); }
  to   { opacity: 1; transform: translateY(0)   scale(1);    }
}
.analysis-picker-header {
  padding: 18px 22px 14px;
  border-bottom: 1px solid #eef0f3;
  display: flex;
  flex-direction: column;
  position: relative;
  background: linear-gradient(180deg, #f8faff 0%, #ffffff 100%);
}
.analysis-picker-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary, #1f2937);
  margin-bottom: 4px;
}
.analysis-picker-sub {
  font-size: 13px;
  color: #6b7280;
}
.analysis-picker-gt {
  color: #5b5bd6;
  font-weight: 500;
  margin-left: 4px;
}
.analysis-picker-close {
  position: absolute;
  right: 16px;
  top: 16px;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  border: none;
  background: transparent;
  color: #6b7280;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}
.analysis-picker-close:hover {
  background: var(--bg-tertiary, #f0f1f5);
  color: var(--text-primary, #1f2937);
}
.analysis-picker-grid {
  padding: 16px 18px 22px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  overflow-y: auto;
}
.analysis-picker-item {
  text-align: left;
  padding: 14px 16px;
  border-radius: 12px;
  border: 1.5px solid var(--border-color, #e5e7eb);
  background: var(--bg-tertiary, #fafbfc);
  cursor: pointer;
  display: flex;
  gap: 12px;
  align-items: flex-start;
  transition: all 0.18s ease;
  color: inherit;
  font: inherit;
}
.analysis-picker-item:hover:not(:disabled) {
  border-color: #9ca3ff;
  background: var(--bg-tertiary, #f4f5ff);
  transform: translateY(-1px);
  box-shadow: 0 6px 16px rgba(91, 91, 214, 0.12);
}
.analysis-picker-item:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.analysis-picker-item.recommended {
  border-color: #c4c7ff;
  background: var(--bg-tertiary, #eef0ff);
  position: relative;
}
.analysis-picker-item.recommended:hover:not(:disabled) {
  border-color: #5b5bd6;
  background: var(--bg-tertiary, #e6e8ff);
}
.analysis-picker-icon {
  font-size: 24px;
  line-height: 1;
  margin-top: 1px;
  flex-shrink: 0;
}
.analysis-picker-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.analysis-picker-name {
  font-size: 14.5px;
  font-weight: 600;
  color: var(--text-primary, #1f2937);
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.recommended-tag {
  font-size: 11px;
  font-weight: 500;
  padding: 1.5px 7px;
  border-radius: 999px;
  background: linear-gradient(135deg, #8a8fff 0%, #5b5bd6 100%);
  color: white;
  letter-spacing: 0.2px;
}
.analysis-picker-desc {
  font-size: 12.5px;
  color: #6b7280;
  line-height: 1.55;
}
@media (max-width: 560px) {
  .analysis-picker-grid { grid-template-columns: 1fr; }
}

.analysis-picker-input-area {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--border-color, #f0f0f0);
}
.analysis-picker-input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 8px;
  font-size: 13px;
  font-family: inherit;
  resize: none;
  outline: none;
  transition: border-color 0.2s;
  box-sizing: border-box;
}
.analysis-picker-input:focus {
  border-color: #4f46e5;
}
.analysis-picker-input::placeholder {
  color: #bbb;
}
</style>
