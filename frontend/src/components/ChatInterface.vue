
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
          @click="analyzeSelected"
          :disabled="selectedMessages.length === 0"
          class="btn-analyze"
        >
          <Icon name="robot" :size="14" /> AI 分析
        </button>
      </div>
    </div>
    
    <!-- 消息列表区域 -->
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
          v-for="message in messages"
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
              <MessageContent :message="message" :qq-nickname-map="qqNicknameMap" @navigate-to-message="handleNavigateToMessage" />
              <!-- AI 总结（左对齐） -->
              <div v-if="message.aiSummary" class="ai-summary">
                <div class="ai-summary-header">
                  <span class="ai-icon"><Icon name="robot" :size="14" /></span>
                  <span>AI 总结</span>
                </div>
                <div class="ai-summary-content">{{ message.aiSummary }}</div>
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
              <MessageContent :message="message" :qq-nickname-map="qqNicknameMap" @navigate-to-message="handleNavigateToMessage" />
            </div>
          </div>
        </div>
      </div>
    </div>
    
    <!-- 图片预览弹窗 -->
    <div v-if="previewImage" class="image-preview" @click="closeImagePreview">
      <img :src="previewImage" />
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

const PAGE_SIZE = 50;
const FALLBACK_POLL_MS = 10000;

export default {
  name: 'ChatInterface',
  components: {
    Icon,
    MessageContent
  },
  props: {
    groupId: {
      type: String,
      default: ''
    }
  },
  emits: ['analysis-result', 'new-message-arrived'],

  setup(props, { emit }) {
    const groupId = ref(props.groupId);
    const messages = ref([]);
    const isLoading = ref(false);
    const currentGroupName = ref('');
    const messagesContainer = ref(null);
    const previewImage = ref(null);
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
    
    // 计算选中的消息数量
    const selectedMessages = computed(() => Array.from(selectedMessageIds.value));
    
    // 计算选中的消息数据
    const selectedMessagesData = computed(() => {
      return messages.value.filter(msg => selectedMessageIds.value.has(msg.id));
    });

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
        console.warn('加载群成员昵称映射失败:', error);
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

    const resolveGroupId = async () => {
      if (groupId.value) return groupId.value;
      const recentGroups = await messageApi.getRecentGroups();
      if (recentGroups?.length > 0) {
        groupId.value = String(recentGroups[0].groupId || recentGroups[0].id || '');
      }
      return groupId.value;
    };

    const loadMessages = async (showLoading = true, scrollToBottomFlag = true) => {
      try {
        const gid = await resolveGroupId();
        if (!gid) {
          showToast('暂无最近群聊，请选择群聊', 'warning');
          return;
        }

        if (showLoading) isLoading.value = true;
        currentPage.value = 0;
        hasMore.value = true;

        const response = await messageApi.getMessagesByGroupIdPaged(gid, 0, PAGE_SIZE);
        const list = sortMessagesAsc(response.messages || []);
        messages.value = list;

        // 同时加载该群历史成员昵称映射，用于解析 @消息
        loadGroupMembers(gid);
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
        console.error('加载消息失败:', error);
        if (showLoading) showToast(`加载消息失败: ${error.message}`, 'error');
      } finally {
        if (showLoading) isLoading.value = false;
      }
    };

    const loadMoreMessages = async () => {
      if (!hasMore.value || isLoadingMore.value || !groupId.value) return;
      isLoadingMore.value = true;
      const container = messagesContainer.value;
      const prevScrollHeight = container?.scrollHeight || 0;

      try {
        currentPage.value += 1;
        const response = await messageApi.getMessagesByGroupIdPaged(
          groupId.value, currentPage.value, PAGE_SIZE
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
        console.error('加载更多消息失败:', error);
        currentPage.value -= 1;
      } finally {
        isLoadingMore.value = false;
      }
    };

    const fetchIncremental = async () => {
      if (!groupId.value || messages.value.length === 0) return;
      try {
        const afterId = getLastMessageId();
        const newMessages = await messageApi.getMessagesSince(groupId.value, afterId);
        if (!newMessages?.length) return;
        const wasAtBottom = isAtBottom();
        newMessages.forEach(mergeMessage);
        if (wasAtBottom) nextTick(scrollToBottom);
        // 通知父组件：有新消息，触发 Sidebar 刷新，让新消息多的群移至顶层
        emit('new-message-arrived');
      } catch (error) {
        console.warn('增量拉取失败:', error);
      }
    };

    const handleWebSocketMessage = (data) => {
      if (!data?.message || data.groupId !== groupId.value) return;
      const wasAtBottom = isAtBottom();
      if (data.type === 'new_message') {
        mergeMessage(data.message);
        if (wasAtBottom) nextTick(scrollToBottom);
        emit('new-message-arrived');
      } else if (data.type === 'message_update') {
        mergeMessage(data.message);
      }
    };

    const { connect: wsConnect, subscribe: wsSubscribe, unsubscribe: wsUnsubscribe, disconnect: wsDisconnect } =
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

    const openImage = (url) => {
      previewImage.value = url;
    };

    const closeImagePreview = () => {
      previewImage.value = null;
    };

    const formatTime = (timestamp) => {
      if (!timestamp) return '';
      const date = new Date(timestamp);
      if (isNaN(date.getTime())) return '';

      const now = new Date();
      const pad = (n) => String(n).padStart(2, '0');
      const timeStr = `${pad(date.getHours())}:${pad(date.getMinutes())}`;

      const isSameDay = (d1, d2) =>
        d1.getFullYear() === d2.getFullYear() &&
        d1.getMonth() === d2.getMonth() &&
        d1.getDate() === d2.getDate();

      const diffMs = now - date;
      const diffMin = Math.floor(diffMs / 60000);
      const diffHour = Math.floor(diffMs / 3600000);

      // 1 分钟内
      if (diffMin < 1) return '刚刚';
      // 1 分钟 ~ 1 小时
      if (diffHour < 1) return `${diffMin}分钟前`;
      // 当天内超过 1 小时
      if (isSameDay(date, now)) return timeStr;

      // 昨天
      const yesterday = new Date(now);
      yesterday.setDate(yesterday.getDate() - 1);
      if (isSameDay(date, yesterday)) return `昨天 ${timeStr}`;

      // 同一年显示 MM-DD HH:mm
      if (date.getFullYear() === now.getFullYear()) {
        return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${timeStr}`;
      }

      // 跨年份显示 YYYY-MM-DD HH:mm
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${timeStr}`;
    };

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

    watch(() => props.groupId, (newGroupId, oldGroupId) => {
      if (oldGroupId) wsUnsubscribe(oldGroupId);
      if (newGroupId) {
        groupId.value = newGroupId;
        groupMemberMap.value = new Map();
        loadMessages();
        startFallbackPoll();
      } else {
        stopFallbackPoll();
        messages.value = [];
      }
    }, { immediate: true });
    
    onMounted(() => {
      if (props.groupId) {
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
    
    // 分析选中的消息
    const analyzeSelected = async () => {
      if (selectedMessages.value.length === 0) {
        showToast('请先选择要分析的消息', 'warning');
        return;
      }
      
      // 从 XML 中提取聊天记录 title
      const extractForwardXmlTitles = (xml) => {
        if (!xml || typeof xml !== 'string') return [];
        try {
          const parser = new DOMParser();
          const doc = parser.parseFromString(xml, 'text/xml');
          return Array.from(doc.querySelectorAll('item title')).map(t => t.textContent || '');
        } catch (e) {
          return [];
        }
      };

      // 解析转发消息中的子消息列表
      const extractForwardMessages = (msg) => {
        if (Array.isArray(msg.forwardMessages)) {
          return msg.forwardMessages;
        }
        const c = msg.content || '';
        if (!c.trim()) return [];
        try {
          const jsonMatch = c.match(/(\{[\s\S]*\}|\[[\s\S]*\])/);
          if (jsonMatch) {
            const parsed = JSON.parse(jsonMatch[1]);
            if (Array.isArray(parsed)) return parsed;
            if (parsed.messages && Array.isArray(parsed.messages)) return parsed.messages;
            if (parsed.content && Array.isArray(parsed.content)) return parsed.content;
            if (parsed.xmlContent && typeof parsed.xmlContent === 'string') {
              const titles = extractForwardXmlTitles(parsed.xmlContent);
              return titles.slice(1).map((text, index) => ({
                id: `forward-${msg.id}-${index}`,
                userNickname: '',
                content: text,
                messageType: 'TEXT'
              }));
            }
          }
        } catch (e) {
          // 解析失败则忽略
        }
        if (c.includes('<msg') && c.includes('</msg>')) {
          const titles = extractForwardXmlTitles(c);
          return titles.slice(1).map((text, index) => ({
            id: `forward-${msg.id}-${index}`,
            userNickname: '',
            content: text,
            messageType: 'TEXT'
          }));
        }
        return [];
      };

      // 清理消息中的 CQ 码
      const cleanMessageText = (text) => {
        if (!text) return '[无内容]';
        return text
          .replace(/\[CQ:image[^\]]*\]/g, '[图片]')
          .replace(/\[CQ:video[^\]]*\]/g, '[视频]')
          .replace(/\[CQ:record[^\]]*\]/g, '[语音]')
          .replace(/\[CQ:voice[^\]]*\]/g, '[语音]')
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

      // 格式化单条消息内容：媒体/转发等折叠为占位，清理 CQ 码
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

      // 构建选中的消息数据
      const selectedData = selectedMessagesData.value.map(msg => ({
        user: msg.userNickname || msg.userName || '未知用户',
        content: formatMessageForAnalysis(msg)
      }));

      // 构建消息内容
      const messageContents = selectedData.map(msg => `${msg.user}: ${msg.content}`).join('\n');

      // 发送分析请求事件给父组件
      emit('analysis-result', {
        type: 'request',
        messages: selectedData,
        prompt: `请分析以下群聊消息，总结主要内容、讨论话题和关键信息：\n\n${messageContents}`
      });
      
      // 退出选择模式
      isSelectionMode.value = false;
      clearSelection();
    };

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
        console.error('删除消息失败:', error);
        showToast(`删除失败: ${error.message}`, 'error');
      }
    };

    return {
      groupId,
      messages,
      isLoading,
      currentGroupName,
      messagesContainer,
      previewImage,
      isLoadingMore,
      loadMessages,
      handleScroll,
      isSelfMessage,
      getAvatar,
      handleAvatarError,
      openImage,
      closeImagePreview,
      formatTime,
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
      deleteSelected
    };
  }
};
</script>

<style scoped>
.chat-interface {
  background-color: white;
  border-radius: 8px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-header {
  padding: 8px 16px;
  border-bottom: 1px solid #e0e0e0;
  background-color: #f8f9fa;
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
  color: #2c3e50;
  font-size: 14px;
}

.messages-area {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.load-more-hint {
  text-align: center;
  color: #888;
  font-size: 13px;
  padding: 8px 0 12px;
}

.empty-state, .loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #666;
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
  background-color: #f1f3f4;
  border-bottom-left-radius: 4px;
  flex-direction: row;
  margin-right: auto;
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
  color: #666;
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
  padding: 10px;
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
  line-height: 1.4;
  color: #333;
}

.image-preview {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.9);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  cursor: pointer;
}

.image-preview img {
  max-width: 90%;
  max-height: 90%;
  object-fit: contain;
  border-radius: 8px;
}

/* 滚动条样式 */
.messages-area::-webkit-scrollbar {
  width: 6px;
}

.messages-area::-webkit-scrollbar-track {
  background: #f1f1f1;
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
  background-color: #f8f9fa;
  border-bottom: 1px solid #e0e0e0;
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
  color: #2c3e50;
}

.selection-mode-label {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: #666;
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
  border: 1px solid #ddd;
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
</style>
