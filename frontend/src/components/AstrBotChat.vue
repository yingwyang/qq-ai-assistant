<template>
  <div class="astrbot-chat">
    <div class="chat-header">
      <div class="header-info">
        <h3>{{ botName }}</h3>
        <span class="status" :class="{ 'online': isOnline, 'offline': !isOnline }">
          {{ isOnline ? '在线' : '离线' }}
        </span>
        <svg
          v-if="isLoading"
          class="header-loading-icon"
          width="16"
          height="16"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"></path>
          <path d="M3 3v5h5"></path>
          <path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"></path>
          <path d="M16 21h5v-5"></path>
        </svg>
      </div>
      <div class="header-actions">
        <!-- TTS 角色选择器 -->
        <div v-if="ttsCharacters.length > 0" class="tts-character-selector">
          <select
            v-model="selectedTtsCharacter"
            @change="onTtsCharacterChange"
            class="tts-character-select"
            title="语音合成角色"
          >
            <option v-for="c in ttsCharacters" :key="c.name" :value="c.name">
              {{ c.label || c.name }}
            </option>
          </select>
        </div>
        <button class="action-btn" @click="toggleConversationList" title="对话历史">
          <Icon name="list" :size="16" />
        </button>
        <button class="action-btn" @click="createNewConversation" title="新对话">
          <Icon name="add" :size="16" />
        </button>
      </div>
    </div>

    <!-- 对话列表面板 -->
    <div v-if="showConversationList" class="conversation-panel">
      <div class="panel-header">
        <h4>对话历史 ({{ conversations.length }})</h4>
        <div class="panel-header-actions">
          <button
            class="toggle-group-btn"
            :class="{ active: showGrouping }"
            @click="toggleGrouping"
            :title="showGrouping ? '取消分组' : '按群分组'"
          >
            <Icon name="group" :size="14" />
          </button>
          <button class="close-btn" @click="showConversationList = false"><Icon name="close" :size="16" /></button>
        </div>
      </div>
      <div class="conversation-list">
        <!-- 分组模式 -->
        <template v-if="showGrouping">
          <div
            v-for="group in groupedConversations"
            :key="group.key"
            class="conv-group"
          >
            <div class="conv-group-header" @click="toggleGroup(group.key)">
              <Icon :name="collapsedGroups[group.key] ? 'arrow-right' : 'expand'" :size="14" />
              <span class="conv-group-title">{{ group.label }}</span>
              <span class="conv-group-count">{{ group.conversations.length }}</span>
            </div>
            <div v-show="!collapsedGroups[group.key]" class="conv-group-items">
              <div
                v-for="conv in group.conversations"
                :key="conv.conversationId"
                class="conversation-item"
                :class="{ 'active': currentConversationId === conv.conversationId }"
              >
                <div class="conv-content" @click="loadConversation(conv.conversationId)">
                  <div class="conv-title">{{ conv.title || '新对话' }}</div>
                  <div class="conv-meta">
                    <span>{{ conv.messageCount || 0 }} 条消息</span>
                    <span>{{ formatDate(conv.timeUpdated) }}</span>
                  </div>
                </div>
                <button
                  class="delete-btn"
                  @click="(e) => { e.preventDefault(); e.stopPropagation(); showDeleteConfirm(conv.conversationId); }"
                  title="删除"
                >
                  <Icon name="delete" :size="14" />
                </button>
              </div>
            </div>
          </div>
        </template>
        <!-- 扁平模式 -->
        <template v-else>
          <div
            v-for="conv in conversations"
            :key="conv.conversationId"
            class="conversation-item"
            :class="{ 'active': currentConversationId === conv.conversationId }"
          >
            <div class="conv-content" @click="loadConversation(conv.conversationId)">
              <div class="conv-title">{{ conv.title || '新对话' }}</div>
              <div class="conv-meta">
                <span>{{ conv.messageCount || 0 }} 条消息</span>
                <span>{{ formatDate(conv.timeUpdated) }}</span>
              </div>
            </div>
            <button
              class="delete-btn"
              @click="(e) => { e.preventDefault(); e.stopPropagation(); showDeleteConfirm(conv.conversationId); }"
              title="删除"
            >
              <Icon name="delete" :size="14" />
            </button>
          </div>
        </template>
        <div v-if="conversations.length === 0" class="empty-conversations">
          暂无对话历史
        </div>
      </div>
    </div>

    <!-- 删除确认弹窗 -->
    <div v-if="showConfirmDialog" class="confirm-dialog-overlay" @click="cancelDelete">
      <div class="confirm-dialog" @click.stop>
        <div class="confirm-dialog-header">
          <span class="confirm-icon"><Icon name="warning" :size="20" /></span>
          <h3>确认删除</h3>
        </div>
        <div class="confirm-dialog-body">
          <p>确定要删除这个对话吗？</p>
          <p class="confirm-hint">此操作不可恢复，删除后将无法找回该对话记录。</p>
        </div>
        <div class="confirm-dialog-footer">
          <button class="btn-cancel" @click="cancelDelete">取消</button>
          <button class="btn-confirm" @click="confirmDelete">确定删除</button>
        </div>
      </div>
    </div>

    <!-- 设置弹窗 -->
    <div v-if="showSettings" class="settings-dialog-overlay" @click="closeSettings">
      <div class="settings-dialog" @click.stop>
        <div class="settings-dialog-header">
          <span class="settings-icon"><Icon name="settings" :size="22" /></span>
          <h3>AstrBot 设置</h3>
          <button class="close-btn" @click="closeSettings"><Icon name="close" :size="16" /></button>
        </div>
        <!-- 设置面板内容区域 -->
        <div class="settings-panel-body">
          <!-- 左侧提供商列表 -->
          <div class="settings-sidebar">
            <div class="sidebar-header">
              <span class="sidebar-title">提供商</span>
              <button class="btn-add-provider" @click="showAddProvider = !showAddProvider" title="新增提供商">
                <Icon name="add" :size="14" />
              </button>
            </div>
            <div v-if="showAddProvider" class="provider-add-form">
              <input v-model="newProviderName" type="text" placeholder="输入提供商名称" @keyup.enter="addProvider">
              <button class="btn-provider-confirm" @click="addProvider">确定</button>
              <button class="btn-provider-cancel" @click="showAddProvider = false; newProviderName = ''">取消</button>
            </div>
            <div class="provider-list">
              <div 
                v-for="(provider, index) in providers" 
                :key="index" 
                class="provider-item"
                :class="{ active: currentProviderIndex === index }"
                @click="currentProviderIndex = index"
              >
                <div class="provider-icon">
                  <Icon name="bot" :size="14" />
                </div>
                <div class="provider-info">
                  <span class="provider-name">{{ provider.name }}</span>
                  <span class="provider-url">{{ provider.baseUrl || '未配置' }}</span>
                </div>
                <button class="provider-delete" @click.stop="removeProvider(index)" title="删除">
                  <Icon name="close" :size="12" />
                </button>
              </div>
            </div>
          </div>

          <!-- 右侧配置详情 -->
          <div class="settings-content">
            <div v-if="currentProvider" class="provider-config">
              <!-- 提供商头部 -->
              <div class="provider-header">
                <div class="provider-title-row">
                  <div class="provider-icon-large">
                    <Icon name="bot" :size="20" />
                  </div>
                  <div>
                    <h3 class="provider-display-name">{{ currentProvider.name }}</h3>
                    <span class="provider-url-text">{{ currentProvider.baseUrl }}</span>
                  </div>
                </div>
                <button class="btn-save-config" @click="saveSettings">
                  <Icon name="check" :size="14" /> 保存配置
                </button>
              </div>

              <!-- 配置表单 -->
              <div class="config-form">
                <div class="form-section">
                  <div class="form-item">
                    <label class="form-label">ID</label>
                    <span class="form-hint">提供商唯一 ID</span>
                    <input v-model="currentProvider.name" type="text" class="form-input" placeholder="输入提供商 ID">
                  </div>

                  <div class="form-item">
                    <label class="form-label">API Key</label>
                    <span class="form-hint">API 密钥</span>
                    <div class="password-field">
                      <input v-model="currentProvider.apiKey" :type="currentProvider.showApiKey ? 'text' : 'password'" class="form-input" placeholder="输入 API Key">
                      <button class="toggle-key-btn" @click="currentProvider.showApiKey = !currentProvider.showApiKey">
                        {{ currentProvider.showApiKey ? '隐藏' : '显示' }}
                      </button>
                    </div>
                  </div>

                  <div class="form-item">
                    <label class="form-label">API Base URL</label>
                    <span class="form-hint">自定义 API 端点 URL</span>
                    <input v-model="currentProvider.baseUrl" type="text" class="form-input" placeholder="输入 API 地址，例如 https://api.openai.com/v1">
                  </div>

                  <!-- 助手名称 -->
                  <div class="form-item">
                    <label class="form-label">助手名称</label>
                    <span class="form-hint">对话中显示的助手名称</span>
                    <input v-model="botName" type="text" class="form-input" placeholder="输入助手显示名称">
                  </div>

                  <!-- AstrBot API Key -->
                  <div class="form-item">
                    <label class="form-label">AstrBot API Key</label>
                    <span class="form-hint">AstrBot 服务 API 密钥</span>
                    <div class="password-field">
                      <input v-model="astrbotApiKey" :type="showAstrbotKey ? 'text' : 'password'" class="form-input" placeholder="输入 AstrBot API Key">
                      <button class="toggle-key-btn" @click="showAstrbotKey = !showAstrbotKey">
                        {{ showAstrbotKey ? '隐藏' : '显示' }}
                      </button>
                    </div>
                  </div>
                </div>

                <!-- 模型配置 -->
                <div class="model-section">
                  <div class="section-header">
                    <h4 class="section-title">已配置的模型</h4>
                    <div class="section-actions">
                      <input v-model="modelSearch" type="text" class="search-input" placeholder="搜索模型或ID">
                      <button class="btn-get-models" @click="fetchModels">
                        <Icon name="download" :size="14" /> 获取模型列表
                      </button>
                      <button class="btn-custom-model" @click="showCustomModel = !showCustomModel">
                        <Icon name="plus" :size="14" /> 自定义模型
                      </button>
                    </div>
                  </div>

                  <div v-if="showCustomModel" class="custom-model-form">
                    <input v-model="newModelName" type="text" class="form-input" placeholder="输入模型名称，如 gpt-4o" @keyup.enter="addModel">
                    <button class="btn-model-confirm" @click="addModel">确定</button>
                    <button class="btn-model-cancel" @click="showCustomModel = false; newModelName = ''">取消</button>
                  </div>

                  <div class="model-config-list">
                    <div 
                      v-for="(model, idx) in filteredModels" 
                      :key="idx" 
                      class="model-config-item"
                    >
                      <div class="model-info">
                        <span class="model-name" :title="model.name">{{ model.name }}</span>
                        <span class="model-id">{{ model.id || model.name }}</span>
                      </div>
                      <div class="model-actions">
                        <label class="model-switch">
                          <input type="checkbox" v-model="model.enabled" class="switch-input">
                          <span class="switch-track"></span>
                        </label>
                        <button class="model-action-btn" @click="copyModelName(model.name)" title="复制">
                          <Icon name="copy" :size="12" />
                        </button>
                        <button class="model-action-btn" @click="setAsCurrentModel(model.name)" title="设为当前模型">
                          <Icon name="check-circle" :size="12" />
                        </button>
                        <button class="model-action-btn delete-btn" @click="removeModel(model.name)" title="删除">
                          <Icon name="trash" :size="12" />
                        </button>
                      </div>
                    </div>
                    <div v-if="filteredModels.length === 0" class="empty-models">
                      <Icon name="inbox" :size="32" />
                      <p>暂无模型，请添加或获取模型列表</p>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            <!-- 智能体/人格管理 -->
            <div v-else class="persona-config">
              <PersonaManager :embedded="true" />
            </div>
          </div>
        </div>
        <div class="settings-dialog-footer">
          <button class="btn-cancel" @click="closeSettings">取消</button>
          <button class="btn-confirm" style="background: #3498db;" @click="saveSettings">保存设置</button>
        </div>
      </div>
    </div>

    <div class="chat-messages" ref="messagesContainer">
      <div v-if="messages.length === 0" class="empty-chat">
        <div class="empty-icon"><Icon name="chat" :size="48" /></div>
        <p>开始与 AstrBot 对话</p>
        <p class="empty-hint">对话将自动保存，您可以随时查看历史记录</p>
      </div>

      <div
        v-for="(message, index) in messages"
        :key="index"
        class="message-wrapper"
        :class="{ 
          'message-self': message.isSelf,
          'message-system': message.isSystem 
        }"
      >
        <div 
          class="message-bubble" 
          :class="[
            message.isSelf ? 'message-right' : 'message-left',
            { 'message-system-bubble': message.isSystem }
          ]"
        >
          <div class="message-avatar" v-if="!message.isSystem">
            <img :src="message.isSelf ? userAvatar : botAvatar" alt="avatar" />
          </div>
          <div class="message-content">
            <div class="message-header" v-if="!message.isSystem">
              <span class="message-sender">{{ message.sender }}</span>
              <span class="message-time">{{ formatTime(message.time) }}</span>
            </div>
            <RichTextRenderer
              class="message-text"
              :class="{ 'message-system-text': message.isSystem }"
              :content="message.text"
              :section-mode="!message.isSelf && !message.isSystem"
              :scroll-container="messagesContainer"
            />
            <!-- AI 回复操作按钮 -->
            <div v-if="!message.isSelf && !message.isSystem" class="message-actions">
              <button
                class="msg-action-btn copy-btn"
                title="复制内容"
                @click="copyMessageText(message.text)"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                  <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                </svg>
                <span>复制</span>
              </button>
              <button
                class="msg-action-btn voice-btn"
                :class="{ generating: message.voiceGenerating, playing: message.audioUrl }"
                :title="message.audioUrl ? '播放/隐藏语音' : '生成语音'"
                @click="handleVoiceAction(message)"
                :disabled="message.voiceGenerating"
              >
                <svg v-if="message.voiceGenerating" class="spin-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"></path>
                  <path d="M3 3v5h5"></path>
                  <path d="M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16"></path>
                  <path d="M16 21h5v-5"></path>
                </svg>
                <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"></path>
                  <path d="M19 10v2a7 7 0 0 1-14 0v-2"></path>
                  <line x1="12" y1="19" x2="12" y2="23"></line>
                  <line x1="8" y1="23" x2="16" y2="23"></line>
                </svg>
                <span>{{ message.voiceGenerating ? '生成中...' : (message.audioUrl ? '播放语音' : '语音生成') }}</span>
              </button>
            </div>
            <!-- 语音播放器 -->
            <div v-if="message.audioUrl && message.showAudioPlayer" class="voice-player">
              <audio controls :src="message.audioUrl" @ended="message.showAudioPlayer = false" autoplay></audio>
            </div>
          </div>
        </div>
      </div>

      <div v-if="isLoading" class="loading-indicator">
        <span class="loading-dots">AstrBot 正在思考</span>
      </div>
    </div>

    <!-- 积分不足提示条 -->
    <div v-if="showInsufficientCredits" class="credits-banner insufficient-banner">
      <div class="credits-banner-icon">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10"></circle>
          <line x1="12" y1="8" x2="12" y2="12"></line>
          <line x1="12" y1="16" x2="12.01" y2="16"></line>
        </svg>
      </div>
      <div class="credits-banner-body">
        <div class="credits-banner-title">积分不足（需 {{ insufficientNeed }} 积分，当前 {{ insufficientBalance }}），请升级权益或完成签到</div>
        <div class="credits-banner-actions">
          <button class="banner-btn banner-btn-primary" @click="goToSignIn">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M9 11l3 3L22 4"></path>
              <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"></path>
            </svg>
            去签到
          </button>
          <button class="banner-btn banner-btn-upgrade" @click="goToUpgrade">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"></polygon>
            </svg>
            升级权益
          </button>
          <button class="banner-btn banner-btn-close" @click="showInsufficientCredits = false" title="关闭">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <line x1="18" y1="6" x2="6" y2="18"></line>
              <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
          </button>
        </div>
      </div>
    </div>

    <!-- 扣费成功轻量提示 -->
    <Transition name="credit-hint">
      <div v-if="showCreditHint" class="credit-hint-toast">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"></path>
        </svg>
        <span>本次消耗 {{ lastCost }} 积分，剩余 {{ lastBalance }}</span>
      </div>
    </Transition>

    <div class="chat-input-area">
      <div class="model-selector-bar">
        <div class="model-selector">
          <Icon name="bot" :size="14" />
          <select v-model="currentModel" @change="onCurrentModelChange" :disabled="isLoading || modelLoading">
            <option value="">默认模型</option>
            <option v-for="m in availableModels" :key="m.id" :value="m.id">{{ m.label }}</option>
          </select>
          <span v-if="modelLoading" class="model-loading">加载中...</span>
          <span v-else-if="modelError" class="model-error" :title="modelError">⚠️</span>
          <button v-if="!modelLoading" class="model-refresh-btn" @click="refreshAvailableModels" title="刷新模型列表">
            <Icon name="refresh" :size="12" />
          </button>
        </div>
        <div class="model-tip" v-if="currentModel">
          当前会话使用：<b>{{ currentModelLabel }}</b>
        </div>
        <div class="model-tip model-empty-tip" v-else-if="!modelLoading && availableModels.length === 0">
          <span v-if="modelError">模型加载失败，请检查 AstrBot 或点击 🔄 重试</span>
          <span v-else>暂无可用模型，请在 AstrBot 中配置后刷新</span>
        </div>
      </div>
      <div class="input-wrapper">
        <input
          ref="inputRef"
          v-model="inputMessage"
          type="text"
          placeholder="输入消息..."
          @keyup.enter="sendMessage"
          :disabled="isLoading"
        />
        <button class="send-btn" @click="sendMessage" :disabled="!inputMessage.trim() || isLoading">
          {{ isLoading ? '发送中...' : '发送' }}
        </button>
      </div>
      <div v-if="currentConversationId" class="conversation-info">
        当前对话: {{ currentConversationTitle || '新对话' }}
      </div>
    </div>
  </div>
</template>

<script>
import { ref, computed, onMounted, onUnmounted, nextTick, watch, getCurrentInstance } from 'vue';
import Icon from './Icon.vue';
import RichTextRenderer from './RichTextRenderer.vue';
import { astrBotApi, userApi, systemApi } from '../services/api';
import { filterToolJson, processAstrBotResponse } from '../utils/messageFilter';
import { showToast } from './Toast.vue';
import PersonaManager from './PersonaManager.vue';
export default {
  name: 'AstrBotChat',
  components: { Icon, RichTextRenderer, PersonaManager },
  props: {
    groupId: { type: String, default: null },
    groupName: { type: String, default: null },
    userId: { type: String, default: null },
    userNickname: { type: String, default: null }
  },
  setup(props) {
    const messages = ref([]);
    const inputMessage = ref('');
    const messagesContainer = ref(null);
    const inputRef = ref(null);
    const isLoading = ref(false);
    const isOnline = ref(true);
    const botAvatarInput = ref(null);

    // 积分提示相关状态
    const showInsufficientCredits = ref(false);
    const insufficientNeed = ref(0);
    const insufficientBalance = ref(0);
    const showCreditHint = ref(false);
    const lastCost = ref(0);
    const lastBalance = ref(0);
    let _creditHintTimer = null;

    // 路由跳转
    const instance = getCurrentInstance();
    const router = instance?.proxy?.$router;

    const goToSignIn = () => {
      showInsufficientCredits.value = false;
      if (router) {
        router.push({ path: '/user-center', query: { tab: 'credits', focus: 'signIn' } });
      } else {
        window.location.href = '/user-center?tab=credits&focus=signIn';
      }
    };

    const goToUpgrade = () => {
      showInsufficientCredits.value = false;
      if (router) {
        router.push({ path: '/user-center', query: { tab: 'subscription', openUpgrade: '1' } });
      } else {
        window.location.href = '/user-center?tab=subscription&openUpgrade=1';
      }
    };

    const showCreditHintBriefly = (cost, balance) => {
      lastCost.value = cost;
      lastBalance.value = balance;
      showCreditHint.value = true;
      if (_creditHintTimer) clearTimeout(_creditHintTimer);
      _creditHintTimer = setTimeout(() => {
        showCreditHint.value = false;
      }, 3000);
    };

    // 使用独立的过滤模块 - 导入自 ../utils/messageFilter

    // 对话管理
    const conversations = ref([]);
    const currentConversationId = ref(null);
    const currentConversationTitle = ref('');
    const showConversationList = ref(false);

    // 分组相关状态
    const showGrouping = ref(true);
    const collapsedGroups = ref({});

    // 分组计算属性
    const groupedConversations = computed(() => {
      const groups = {};
      for (const conv of conversations.value) {
        const key = conv.groupId || '__nogroup__';
        if (!groups[key]) {
          // 从 title 中提取群名（格式："类型 - 群名"）
          let label = '';
          if (key === '__nogroup__') {
            label = '💬 通用对话';
          } else {
            // 如果标题包含 "- "，提取群名部分
            if (conv.title && conv.title.includes(' - ')) {
              label = '📁 ' + conv.title.split(' - ').slice(1).join(' - ');
            } else {
              label = '👥 群 ' + key;
            }
          }
          groups[key] = {
            key,
            label,
            conversations: []
          };
        }
        groups[key].conversations.push(conv);
      }
      // 按更新时间排序
      for (const key in groups) {
        groups[key].conversations.sort((a, b) => {
          const ta = a.timeUpdated ? new Date(a.timeUpdated).getTime() : 0;
          const tb = b.timeUpdated ? new Date(b.timeUpdated).getTime() : 0;
          return tb - ta;
        });
      }
      // 分组排序：通用对话放最后
      const result = Object.values(groups);
      result.sort((a, b) => {
        if (a.key === '__nogroup__') return 1;
        if (b.key === '__nogroup__') return -1;
        return b.conversations.length - a.conversations.length;
      });
      return result;
    });

    const toggleGrouping = () => {
      showGrouping.value = !showGrouping.value;
    };

    const toggleGroup = (key) => {
      collapsedGroups.value[key] = !collapsedGroups.value[key];
    };

    // 切换对话列表面板，每次打开都重新加载列表
    const toggleConversationList = () => {
      showConversationList.value = !showConversationList.value;
      if (showConversationList.value) {
        loadConversations();
      }
    };

    // 删除确认弹窗
    const showConfirmDialog = ref(false);
    const conversationToDelete = ref(null);


    const userAvatar = ref('https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100');
    const botAvatar = ref('https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100');
    
    // 名称设置
    const botName = ref('AstrBot 助手');

    // API Key 配置
    const astrbotApiKey = ref('');
    const llmModel = ref('');
    const llmModels = ref([]);
    const showAstrbotKey = ref(false);
    const newModelName = ref('');
    const showCustomModel = ref(false);
    const modelSearch = ref('');

    // 聊天界面模型选择器（本次会话覆盖用）
    const currentModel = ref('');
    const availableModels = ref([]); // 数组元素: { id: '完整ID', label: '短名显示' }
    const modelLoading = ref(false);
    const modelError = ref(''); // 加载错误信息

    // 根据当前选中的 model id 找到显示用的短名
    const currentModelLabel = computed(() => {
      if (!currentModel.value) return '';
      const found = availableModels.value.find(m => m.id === currentModel.value);
      if (found) return found.label;
      // 找不到时截取最后一段
      if (currentModel.value.includes('/')) {
        const parts = currentModel.value.split('/');
        return parts[parts.length - 1];
      }
      return currentModel.value;
    });

    // 设置弹窗
    const showSettings = ref(false);

    // TTS 角色选择
    const ttsCharacters = ref([]);
    const selectedTtsCharacter = ref(localStorage.getItem('tts_character') || '');
    
    // 提供商配置
    const providers = ref([{
      name: 'siliconflow',
      apiKey: '',
      baseUrl: 'https://api.siliconflow.cn/v1',
      showApiKey: false
    }]);
    const currentProviderIndex = ref(0);
    const showAddProvider = ref(false);
    const newProviderName = ref('');
    
    // 当前选中的提供商
    const currentProvider = computed(() => {
      return providers.value[currentProviderIndex.value] || null;
    });

    // 过滤后的模型列表
    const filteredModels = computed(() => {
      if (!modelSearch.value) {
        return llmModels.value.map(name => ({ name, id: name, enabled: llmModel.value === name }));
      }
      const search = modelSearch.value.toLowerCase();
      return llmModels.value
        .filter(name => name.toLowerCase().includes(search))
        .map(name => ({ name, id: name, enabled: llmModel.value === name }));
    });

    // 添加提供商
    const addProvider = () => {
      const name = newProviderName.value.trim();
      if (!name) return;
      providers.value.push({
        name,
        apiKey: '',
        baseUrl: '',
        showApiKey: false
      });
      currentProviderIndex.value = providers.value.length - 1;
      newProviderName.value = '';
      showAddProvider.value = false;
    };

    // 删除提供商
    const removeProvider = (index) => {
      if (providers.value.length <= 1) {
        showToast('至少保留一个提供商', 'error');
        return;
      }
      providers.value.splice(index, 1);
      if (currentProviderIndex.value >= providers.value.length) {
        currentProviderIndex.value = providers.value.length - 1;
      }
    };

    // 合并 AstrBot API 和用户设置面板中已配置的模型
    const mergeModels = (apiModels, configuredModels) => {
      const set = new Set();
      for (const m of apiModels) {
        if (m) set.add(m);
      }
      for (const m of configuredModels) {
        if (m) set.add(m);
      }
      return Array.from(set);
    };

    // 从后端 /api/astrbot/models 获取模型列表（后端优先读 cmd_config.json）
    const loadAvailableModels = async () => {
      modelLoading.value = true;
      modelError.value = '';
      try {
        // 后端返回: { status, models, count, source?, message? }
        //   models 元素: { id(完整provider_id), name(友好显示名), enabled, provider, modalities?, maxContextTokens? }
        let astrbotModels = [];
        try {
          const res = await astrBotApi.getModels();
          if (res && res.status === 'error') {
            const msg = res.message || 'AstrBot 返回错误';
            throw new Error(msg);
          }
          const models = res && Array.isArray(res.models) ? res.models : [];

          // 只取 enabled=true 的模型，和 AstrBot WebUI 表现一致
          const enabledModels = models.filter(m => m.enabled !== false);

          // 转成前端下拉框需要的 { id, label, modalities, maxContextTokens } 格式
          astrbotModels = enabledModels.map(m => {
            if (!m && !m.id) return null;
            const label = m.name || m.id;
            return {
              id: m.id,
              label,
              fullName: m.fullName || m.id,
              modalities: m.modalities || [],
              maxContextTokens: m.maxContextTokens || 0,
              provider: m.provider || ''
            };
          }).filter(Boolean);

          if (astrbotModels.length === 0) {
            modelError.value = 'AstrBot 中未启用任何模型，请先在 AstrBot WebUI 开启模型';
          } else {
            // ========== 默认选中策略 ==========
            // 优先级：1) 已恢复的 currentModel（必须在可用列表中才有效）
            //         2) 用户已保存的 llmModel（必须在可用列表中才有效）
            //         3) 第一个已启用的模型

            const currentInList = currentModel.value && astrbotModels.find(m => m.id === currentModel.value);

            if (currentInList) {
              // 情况1：已恢复的模型在可用列表中 → 保持不变
              // 无需操作
            } else {
              // 情况2：没有恢复的选择，或恢复的模型已被禁用/删除
              // → 优先用 llmModel（如果它还在列表中），否则取第一个启用模型
              const preferred = (llmModel.value && astrbotModels.find(m => m.id === llmModel.value))
                ? llmModel.value
                : (astrbotModels[0] ? astrbotModels[0].id : '');
              if (preferred && currentModel.value !== preferred) {
                console.info('模型回退:', currentModel.value || '(空', '→', preferred);
                currentModel.value = preferred;
                // 同步更新 localStorage，避免下次又恢复到已禁用的模型
                localStorage.setItem('astrbot_current_model', preferred);
                if (currentConversationId.value) {
                  localStorage.setItem(`astrbot_current_model_${currentConversationId.value}`, preferred);
                }
              }
            }
          }
        } catch (e) {
          console.warn('AstrBot 模型接口请求失败:', e.message);
          const raw = (e.message || '未知错误').toString();
          let tip = raw;
          if (/401|403|未授权|Unauthorized/i.test(raw)) {
            tip = 'AstrBot API Key 无效，请在「设置」→「AstrBot API Key」填写正确的 Key';
          } else if (/Connection refused|ECONNREFUSED|无法连接|Not Found|404/i.test(raw)) {
            tip = 'AstrBot 未启动或端口 6185 不可达，请先启动 AstrBot';
          } else if (/timeout|超时/i.test(raw)) {
            tip = 'AstrBot 请求超时，请检查 AstrBot 是否正常运行';
          } else {
            tip = raw.replace(/^获取模型列表失败:\s*/, '');
          }
          modelError.value = tip;
        }

        availableModels.value = astrbotModels;
      } finally {
        modelLoading.value = false;
      }
    };

    // 刷新模型：仅重新调 AstrBot API
    const refreshAvailableModels = async () => {
      await loadAvailableModels();
    };

    // 切换当前模型：同步保存到后端作为默认模型（持久化）
    const onCurrentModelChange = async () => {
      const m = currentModel.value;
      try {
        // 保存当前选择到 localStorage（带当前会话 ID 作为作用域，避免跨会话污染）
        const scopeKey = `astrbot_current_model_${currentConversationId.value || 'global'}`;
        if (m) {
          localStorage.setItem(scopeKey, m);
          localStorage.setItem('astrbot_current_model', m);
        } else {
          localStorage.removeItem(scopeKey);
          localStorage.removeItem('astrbot_current_model');
        }

        // 仅当非空且不是"默认模型"时，同步到后端作为用户默认模型
        if (m) {
          await astrBotApi.setModel(m);
          llmModel.value = m;
          localStorage.setItem('llm_model', m);
        }
      } catch (e) {
        console.warn('保存模型选择失败:', e);
      }
    };

    // 加载模型列表：仅显示在设置面板里，不再持久化到 llmModels
    const fetchModels = async () => {
      const provider = currentProvider.value;
      if (!provider || !provider.apiKey || !provider.baseUrl) {
        showToast('请先配置 API Key 和 Base URL', 'error');
        return;
      }
      showToast('正在获取模型列表...', 'info');
      try {
        const response = await fetch(`${provider.baseUrl}/models`, {
          headers: { 'Authorization': `Bearer ${provider.apiKey}` }
        });
        if (response.ok) {
          const data = await response.json();
          const newModels = (data.data || []).map(m => m.id || m.name);
          // 仅写入 llmModels 变量用于设置面板展示，不再保存到后端
          llmModels.value = newModels;
          // 同步到聊天界面的下拉框（{id, label} 格式）
          const toModelObj = (fullId) => {
            if (!fullId) return null;
            let label = fullId;
            if (fullId.includes('/')) {
              const parts = fullId.split('/');
              label = parts[parts.length - 1];
            }
            return { id: fullId, label };
          };
          const fromProvider = newModels.map(toModelObj).filter(Boolean);
          const seen = new Set(availableModels.value.map(m => m.id));
          for (const m of fromProvider) {
            if (!seen.has(m.id)) {
              availableModels.value.push(m);
              seen.add(m.id);
            }
          }
          // 清空本地缓存中 llmModels 字段
          localStorage.removeItem('llm_models');
          showToast(`成功获取 ${newModels.length} 个模型（未持久化）`, 'success');
        } else {
          showToast('获取模型列表失败', 'error');
        }
      } catch (error) {
        console.error('获取模型列表失败:', error);
        showToast('获取模型列表失败', 'error');
      }
    };

    // 复制模型名称
    const copyModelName = async (name) => {
      try {
        await navigator.clipboard.writeText(name);
        showToast('已复制到剪贴板', 'success');
      } catch (error) {
        showToast('复制失败', 'error');
      }
    };

    // 设置为当前模型
    const setAsCurrentModel = (name) => {
      llmModel.value = name;
      showToast(`已设为当前模型: ${name}`, 'success');
    };

    // 加载设置 - 优先从后端获取
    const loadSettings = async () => {
      // 先从 localStorage 加载默认值
      const savedBotName = localStorage.getItem('astrbot_bot_name');
      const savedAstrbotApiKey = localStorage.getItem('astrbot_api_key');
      const savedProviders = localStorage.getItem('providers');
      const savedLlmModel = localStorage.getItem('llm_model');
      const savedLlmModels = localStorage.getItem('llm_models');

      if (savedBotName) botName.value = savedBotName;
      if (savedAstrbotApiKey) astrbotApiKey.value = savedAstrbotApiKey;
      if (savedProviders) {
        try { providers.value = JSON.parse(savedProviders); } catch (e) {}
      }
      if (savedLlmModel) llmModel.value = savedLlmModel;
      // 废弃 llmModels：不再从 localStorage 加载
      llmModels.value = [];
      localStorage.removeItem('llm_models');
      // 同步到聊天界面的默认模型选择
      if (llmModel.value && !currentModel.value) {
        currentModel.value = llmModel.value;
      }

      // 确保至少有一个提供商
      if (providers.value.length === 0) {
        providers.value = [{ name: 'default', apiKey: '', baseUrl: '', showApiKey: false }];
      }

      // 优先从后端获取最新设置
      try {
        const response = await userApi.getSettings(props.userId);
        if (response) {
          // 后端返回格式: { status, data: { ... } }
          // parseResponse 对非标格式返回整个对象，所以需要取 .data
          const data = response.data || response;
          botName.value = data.botName || botName.value;
          astrbotApiKey.value = data.astrbotApiKey || astrbotApiKey.value;
          llmModel.value = data.llmModel || llmModel.value;
          // 不再从后端读取 llmModels（唯一数据源改为 AstrBot API），同时清空数据库旧值
          if (data.llmModels && Array.isArray(data.llmModels) && data.llmModels.length > 0) {
            llmModels.value = [];
          }
          // 从后端恢复 providers
          if (data.providers && Array.isArray(data.providers) && data.providers.length > 0) {
            providers.value = data.providers.map(p => ({
              name: p.name || 'default',
              apiKey: p.apiKey || '',
              baseUrl: p.baseUrl || '',
              showApiKey: false
            }));
          }

          // 同步到 localStorage
          localStorage.setItem('astrbot_bot_name', botName.value);
          localStorage.setItem('astrbot_api_key', astrbotApiKey.value);
          localStorage.setItem('providers', JSON.stringify(providers.value));
          localStorage.setItem('llm_model', llmModel.value);
          // 废弃 llmModels：清空本地缓存
          localStorage.removeItem('llm_models');
        }
      } catch (error) {
        console.error('加载用户设置失败:', error);
      }
    };
    
    // 保存设置 - 保存到后端
    const saveSettings = async () => {
      const provider = currentProvider.value;
      // 如果当前模型已选中，同步到 llmModel
      if (currentModel.value) {
        llmModel.value = currentModel.value;
      }
      // 保存到 localStorage（llmModels 不保存）
      localStorage.setItem('astrbot_bot_name', botName.value);
      localStorage.setItem('astrbot_api_key', astrbotApiKey.value);
      localStorage.setItem('providers', JSON.stringify(providers.value));
      localStorage.setItem('llm_model', llmModel.value);
      localStorage.removeItem('llm_models');

      // 保存到后端（无论是否有 userId）
      try {
        await userApi.saveSettings({
          userId: props.userId,
          botName: botName.value,
          astrbotApiKey: astrbotApiKey.value,
          llmApiKey: provider?.apiKey || '',
          llmBaseUrl: provider?.baseUrl || '',
          llmModel: llmModel.value,
          llmModels: [], // 废弃 llmModels 存储，清空数据库
          providers: providers.value.map(p => ({
            name: p.name,
            apiKey: p.apiKey,
            baseUrl: p.baseUrl
          }))
        });
        showToast('设置已保存', 'success');
      } catch (error) {
        console.error('保存用户设置到后端失败:', error);
        showToast('设置保存失败', 'error');
      }

      showSettings.value = false;
    };

    // 添加模型
    const addModel = () => {
      const name = newModelName.value.trim();
      if (!name) return;
      if (llmModels.value.includes(name)) {
        showToast('该模型已存在', 'error');
        return;
      }
      llmModels.value.push(name);
      newModelName.value = '';
      showCustomModel.value = false;
    };

    // 删除模型
    const removeModel = (model) => {
      const index = llmModels.value.indexOf(model);
      if (index > -1) {
        llmModels.value.splice(index, 1);
        if (llmModel.value === model) {
          llmModel.value = llmModels.value[0] || '';
        }
      }
    };
    
    // 关闭设置
    const closeSettings = () => {
      // 恢复原来的值
      loadSettings();
      showSettings.value = false;
    };
    
    // 头像加载错误处理
    const handleBotAvatarError = () => {
      botAvatar.value = 'https://q.qlogo.cn/headimg_dl?dst_uin=0&spec=100';
    };
    
    // 头像文件上传处理
    const handleBotAvatarUpload = async (event) => {
      const file = event.target.files[0];
      if (file) {
        const formData = new FormData();
        formData.append('file', file);
        formData.append('type', 'bot');
        
        console.log('准备上传文件:', file.name, '大小:', file.size);
        console.log('FormData内容:', [...formData.entries()]);
        
        try {
          const response = await userApi.uploadAvatar(formData);
          console.log('上传响应:', response);
          if (response && response.url) {
            botAvatar.value = response.url;
            console.log('头像URL:', botAvatar.value);
          } else {
            console.error('头像上传失败:', response?.message || '未知错误');
            // 降级为本地预览
            const reader = new FileReader();
            reader.onload = (e) => {
              botAvatar.value = e.target.result;
            };
            reader.readAsDataURL(file);
          }
        } catch (error) {
          console.error('头像上传失败:', error);
          // 降级为本地预览
          const reader = new FileReader();
          reader.onload = (e) => {
            botAvatar.value = e.target.result;
          };
          reader.readAsDataURL(file);
        }
      }
    };

    // 加载对话列表
    const loadConversations = async () => {
      try {
        const response = await astrBotApi.getConversations({});
        // 兼容多种响应结构：直接数组、{ data: [...] }、{ status: 'ok', data: [...] }
        const list = response?.data !== undefined ? response.data : (response || []);
        conversations.value = Array.isArray(list) ? list : [];
      } catch (error) {
        console.error('加载对话列表失败:', error);
        conversations.value = [];
      }
    };

    // 加载特定对话的消息
    const loadConversation = async (conversationId) => {
      if (!conversationId || conversationId === 'null' || conversationId === 'undefined') {
        currentConversationId.value = null;
        currentConversationTitle.value = '';
        messages.value = [];
        localStorage.removeItem('astrbot_current_conversation');
        showToast('请选择或新建一个对话', 'warning');
        return;
      }
      try {
        isLoading.value = true;

        const convData = await astrBotApi.getConversation(conversationId);
        const conv = convData && convData.status === 'ok' ? convData.data : convData;
        if (conv) {
          currentConversationTitle.value = conv.title || '新对话';
        }

        const msgData = await astrBotApi.getConversationMessages(conversationId);
        const msgList = msgData && msgData.status === 'ok'
          ? msgData.data
          : (Array.isArray(msgData) ? msgData : []);
        messages.value = msgList.map(msg => ({
          text: msg.content,
          sender: msg.role === 'USER' ? (props.userNickname || '我') : 'AstrBot',
          isSelf: msg.role === 'USER',
          time: new Date(msg.timeCreated)
        }));

        currentConversationId.value = conversationId;
        localStorage.setItem('astrbot_current_conversation', conversationId);
        showConversationList.value = false;

        // ========== 会话级模型恢复 ==========
        // 切换会话时，恢复该会话上次使用的模型
        const sessionSavedModel = localStorage.getItem(`astrbot_current_model_${conversationId}`);
        const globalSavedModel = localStorage.getItem('astrbot_current_model');
        const candidateModel = sessionSavedModel || globalSavedModel || localStorage.getItem('llm_model');

        if (candidateModel) {
          // 检查候选模型是否在可用列表中（避免恢复到已禁用的模型）
          const inList = availableModels.value.length > 0
            ? availableModels.value.find(m => m.id === candidateModel)
            : true; // 列表还没加载完，先恢复，等 loadAvailableModels 再校验
          if (inList || availableModels.value.length === 0) {
            currentModel.value = candidateModel;
          } else {
            // 模型已被禁用，回退到第一个可用模型
            const fallback = availableModels.value[0];
            if (fallback) {
              currentModel.value = fallback.id;
              localStorage.setItem(`astrbot_current_model_${conversationId}`, fallback.id);
              localStorage.setItem('astrbot_current_model', fallback.id);
              console.info('会话模型已禁用，回退:', candidateModel, '→', fallback.id);
            }
          }
        }

        nextTick(() => scrollToBottom());
      } catch (error) {
        const msg = error?.message || '';
        if (msg.includes('对话不存在') || msg.includes('不存在')) {
          // 对话已失效，清空当前对话并提示用户新建
          console.warn('当前对话已失效:', conversationId);
          currentConversationId.value = null;
          currentConversationTitle.value = '';
          messages.value = [];
          localStorage.removeItem('astrbot_current_conversation');
          showToast('当前对话已失效，请新建对话', 'warning');
        } else {
          console.error('加载对话失败:', error);
        }
      } finally {
        isLoading.value = false;
      }
    };

    // 创建新对话（可选传入自定义标题，用于 AI 分析场景带群名）
    const createNewConversation = async (titleOverride = null) => {
      try {
        const request = {};
        if (props.groupId) request.groupId = props.groupId;
        if (props.userId) request.userId = props.userId;
        if (props.userNickname) request.userNickname = props.userNickname;
        if (titleOverride) request.title = titleOverride;

        const response = await astrBotApi.createConversation(request);
        const newConv = response && response.status === 'ok' ? response.data : response;
        if (newConv) {
          conversations.value.unshift(newConv);
          currentConversationId.value = newConv.conversationId;
          currentConversationTitle.value = newConv.title || (titleOverride || '新对话');
          messages.value = [];
          localStorage.setItem('astrbot_current_conversation', newConv.conversationId);
        }
      } catch (error) {
        console.error('创建新对话失败:', error);
      }
    };

    // 显示删除确认弹窗
    const showDeleteConfirm = (conversationId) => {
      console.log('🗑️ 显示删除确认弹窗，conversationId:', conversationId);
      conversationToDelete.value = conversationId;
      showConfirmDialog.value = true;
    };

    // 取消删除
    const cancelDelete = () => {
      console.log('❌ 用户取消删除');
      showConfirmDialog.value = false;
      conversationToDelete.value = null;
    };

    // 确认删除
    const confirmDelete = async () => {
      const conversationId = conversationToDelete.value;
      if (!conversationId) return;
      
      console.log('✅ 用户确认删除，开始执行删除操作');
      showConfirmDialog.value = false;
      isLoading.value = true;
      
      try {
        console.log('📡 调用 API 删除对话...');
        const response = await astrBotApi.deleteConversation(conversationId);
        console.log('API 响应:', response);
        
        if (!response || response.status === 'ok' || response.deleted) {
          // 从列表中移除
          const index = conversations.value.findIndex(c => c.conversationId === conversationId);
          console.log('找到对话在列表中的索引:', index);
          if (index > -1) {
            conversations.value.splice(index, 1);
            console.log('✅ 对话已从列表中移除');
          }
          // 如果删除的是当前对话，清空当前对话
          if (currentConversationId.value === conversationId) {
            currentConversationId.value = null;
            currentConversationTitle.value = '';
            messages.value = [];
            localStorage.removeItem('astrbot_current_conversation');
          }
        } else {
          console.error('❌ API 返回错误:', response);
          showToast('删除失败: ' + (response?.message || '未知错误'), 'error');
        }
      } catch (error) {
        console.error('❌ 删除对话失败:', error);
        showToast('删除对话失败: ' + error.message, 'error');
      } finally {
        isLoading.value = false;
        conversationToDelete.value = null;
      }
    };

    // 发送消息
    const sendMessage = async () => {
      if (!inputMessage.value.trim() || isLoading.value) return;
      const userMessage = inputMessage.value.trim();

      messages.value.push({
        text: userMessage,
        sender: props.userNickname || '我',
        isSelf: true,
        time: new Date()
      });

      inputMessage.value = '';
      isLoading.value = true;
      nextTick(() => scrollToBottom());

      try {
        const request = { message: userMessage };
        if (currentConversationId.value) request.conversationId = currentConversationId.value;
        if (props.groupId) request.groupId = props.groupId;
        if (props.userId) request.userId = props.userId;
        if (props.userNickname) request.userNickname = props.userNickname;
        // 模型选择：本次会话显式选择 -> 使用该模型；否则传 'default' 由后端按用户默认模型处理
        request.model = currentModel.value || 'default';

        const response = await astrBotApi.sendMessage(request);
        if (response) {
          if (response.conversationId) {
            currentConversationId.value = response.conversationId;
            localStorage.setItem('astrbot_current_conversation', response.conversationId);
          }
          // 使用独立的过滤模块处理响应
          const replyText = processAstrBotResponse(response);
          messages.value.push({
            text: replyText,
            sender: 'AstrBot',
            isSelf: false,
            time: new Date()
          });
          // 扣费成功：轻量提示
          if (typeof response.cost === 'number' && typeof response.balance === 'number') {
            showCreditHintBriefly(response.cost, response.balance);
          }
        }
      } catch (error) {
        console.error('发送消息失败:', error);
        // 积分不足：自定义提示条
        if (error && error.errorCode === 'INSUFFICIENT_CREDITS') {
          const need = (error.details && error.details.need) || 0;
          const balance = (error.details && error.details.balance) || 0;
          insufficientNeed.value = need;
          insufficientBalance.value = balance;
          showInsufficientCredits.value = true;
          // 替换最后一条用户消息为系统说明（保留用户消息，增加系统提示）
          messages.value.push({
            text: `⚠️ 消息发送失败：${error.message || '积分不足'}`,
            sender: '系统',
            isSelf: false,
            isSystem: true,
            time: new Date()
          });
        } else {
          showToast(error?.message || '发送失败，请稍后重试', 'error');
          messages.value.push({
            text: '抱歉，网络错误，请稍后重试。',
            sender: 'AstrBot',
            isSelf: false,
            time: new Date()
          });
        }
      } finally {
        isLoading.value = false;
        nextTick(() => scrollToBottom());
      }
    };

    // 从本地存储恢复对话
    const restoreConversation = async () => {
      const savedConvId = localStorage.getItem('astrbot_current_conversation');
      if (savedConvId) {
        await loadConversation(savedConvId);
      }
    };

    // 检查 AstrBot 状态
    const checkStatus = async () => {
      try {
        const response = await astrBotApi.getStatus();
        isOnline.value = response && response.status === 'online';
      } catch (error) {
        isOnline.value = false;
      }
    };

    const scrollToBottom = () => {
      if (messagesContainer.value) {
        messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight;
      }
    };

    // 将 HTML 内容转为纯文本（用于复制）
    const htmlToPlainText = (html) => {
      if (!html) return '';
      const temp = document.createElement('div');
      temp.innerHTML = html;
      // 保留 details/summary 结构可读性：summary 后换行
      temp.querySelectorAll('summary').forEach(el => {
        el.insertAdjacentText('afterend', '\n');
      });
      temp.querySelectorAll('br, p, div, li').forEach(el => {
        el.insertAdjacentText('afterend', '\n');
      });
      return temp.innerText || temp.textContent || '';
    };

    // 复制 AI 回复内容
    const copyMessageText = async (text) => {
      const plainText = htmlToPlainText(text);
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          await navigator.clipboard.writeText(plainText);
        } else {
          const textarea = document.createElement('textarea');
          textarea.value = plainText;
          textarea.style.position = 'fixed';
          textarea.style.opacity = '0';
          document.body.appendChild(textarea);
          textarea.select();
          document.execCommand('copy');
          document.body.removeChild(textarea);
        }
        showToast('已复制到剪贴板', 'success');
      } catch (error) {
        console.error('复制失败:', error);
        showToast('复制失败', 'error');
      }
    };

    // 将 HTML/Markdown 内容转换为纯文本（用于语音合成）
    const contentToPlainText = (html) => {
      if (!html) return '';
      // 先通过 DOM 解析 HTML 内容
      const temp = document.createElement('div');
      temp.innerHTML = html;
      // 处理 summary/br/p/div/li，保留换行
      temp.querySelectorAll('summary').forEach(el => {
        el.insertAdjacentText('afterend', '\n');
      });
      temp.querySelectorAll('br, p, div, li').forEach(el => {
        el.insertAdjacentText('afterend', '\n');
      });
      let text = temp.innerText || temp.textContent || '';
      // 移除 Markdown 图片语法
      text = text.replace(/!\[[^\]]*\]\([^)]*\)/g, ' ');
      // 移除 Markdown 链接 [text](url) -> text
      text = text.replace(/\[([^\]]+)\]\([^)]*\)/g, '$1');
      // 移除 Markdown 格式符
      text = text.replace(/[#>*_~`]/g, '');
      // 合并连续的空白
      text = text.replace(/\s+/g, ' ').trim();
      return text;
    };

    // 处理语音生成/播放
    const handleVoiceAction = async (message) => {
      if (message.voiceGenerating) return;

      // 如果已有音频，切换播放器显示
      if (message.audioUrl) {
        message.showAudioPlayer = !message.showAudioPlayer;
        return;
      }

      message.voiceGenerating = true;
      try {
        const plainText = contentToPlainText(message.text);
        if (!plainText.trim()) {
          showToast('没有可合成的文本内容', 'warning');
          return;
        }
        const result = await systemApi.generateVoice(plainText, selectedTtsCharacter.value || null);
        if (result && result.audioUrl) {
          message.audioUrl = result.audioUrl;
          message.showAudioPlayer = true;
          showToast('语音生成成功', 'success');
        } else {
          showToast(result?.message || '语音生成失败', 'error');
        }
      } catch (error) {
        console.error('语音生成失败:', error);
        showToast('语音生成失败: ' + (error.message || '未知错误'), 'error');
      } finally {
        message.voiceGenerating = false;
      }
    };

    const formatTime = (time) => {
      if (!time) return '';
      const date = new Date(time);
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

      if (diffMin < 1) return '刚刚';
      if (diffHour < 1) return `${diffMin}分钟前`;
      if (isSameDay(date, now)) return timeStr;

      const yesterday = new Date(now);
      yesterday.setDate(yesterday.getDate() - 1);
      if (isSameDay(date, yesterday)) return `昨天 ${timeStr}`;

      if (date.getFullYear() === now.getFullYear()) {
        return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${timeStr}`;
      }

      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${timeStr}`;
    };

    const formatDate = (time) => {
      if (!time) return '';
      const date = new Date(time);
      const now = new Date();
      const diff = now - date;
      if (diff < 3600000) {
        const minutes = Math.floor(diff / 60000);
        return minutes < 1 ? '刚刚' : `${minutes}分钟前`;
      }
      if (diff < 86400000) {
        return `${Math.floor(diff / 3600000)}小时前`;
      }
      return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
    };

    // 处理分析请求
    // — 新链路（有 messageIds）：调用 /api/astrbot/analyze-selected 接口（后端按 messageIds 查 DB + 渲染 prompts.yml 模板）
    // — 旧链路（无 messageIds 但有 prompt）：fallback 到 sendMessage，保持向后兼容
    const handleAnalysisRequest = async (data) => {
      // 简单的 HTML 转义，防止消息内容污染渲染
      const escapeHtml = (text) => {
        if (text == null) return '';
        return String(text)
          .replace(/&/g, '&amp;')
          .replace(/</g, '&lt;')
          .replace(/>/g, '&gt;')
          .replace(/"/g, '&quot;')
          .replace(/'/g, '&#039;');
      };

      // 为 @账号名 添加高亮样式（来自 ChatInterface 的 content 已被转换为 @昵称）
      const formatAtMention = (text) => {
        return text.replace(/@(\S+)/g, '<span class="at-mention">@$1</span>');
      };

      const analysisTypeLabel = ({
        'summary': '群聊速览',
        'social-graph': '社交图谱',
        'topic-trend': '话题趋势',
        'integration-guide': '融入指南',
        'meme-dictionary': '梗词典',
        'persona-match': '人设匹配'
      })[data.analysisType] || 'AI分析';

      // 创建新对话用于分析（标题带上群名称，便于在会话列表中区分来源）
      const analysisTitle = props.groupName
        ? `${analysisTypeLabel} - ${props.groupName}`
        : `${analysisTypeLabel} - 群聊消息`;
      await createNewConversation(analysisTitle);

      // 添加系统提示消息，将转发的聊天记录折叠在 <details> 中
      const msgCount = Array.isArray(data.messages) ? data.messages.length : 0;
      const detailsHtml = msgCount > 0
        ? `<details><summary>已选择 ${msgCount} 条消息（点击展开）</summary>\n\n${data.messages.map(m => {
            const user = escapeHtml(m.user || '未知用户');
            const content = formatAtMention(escapeHtml(m.content || '[无内容]'));
            return `<p><strong>${user}:</strong> ${content}</p>`;
          }).join('\n')}\n</details>`
        : '';

      messages.value.push({
        text: `[${analysisTypeLabel}] 已转发 ${msgCount} 条聊天记录\n\n${detailsHtml}`,
        sender: '系统',
        isSelf: false,
        time: new Date(),
        isSystem: true
      });
      
      // 显示分析中提示
      isLoading.value = true;
      nextTick(() => scrollToBottom());
      
      try {
        let result = null;

        // ============ 新链路：传了 messageIds 就走 analyze-selected 接口 ============
        if (Array.isArray(data.messageIds) && data.messageIds.length > 0) {
          const params = {
            messageIds: data.messageIds,
            analysisType: data.analysisType || 'summary',
            model: currentModel.value || 'default',
            userPrompt: data.userPrompt || ''
          };
          // groupId 优先用请求传的（ChatInterface 已经从 props 带了），fallback 到组件 props
          if (data.groupId || props.groupId) {
            params.groupId = data.groupId || props.groupId;
          }
          // 传递 conversationId 以便后端保存消息
          if (currentConversationId.value) {
            params.conversationId = currentConversationId.value;
          }

          console.log('[analyze-selected] 请求参数:', params);
          const raw = await astrBotApi.analyzeSelected(params);
          // 兼容两种返回包装：{status, analysis, ...} 或直接返回对象
          result = (raw && raw.status === 'ok') ? raw : (raw || {});
          console.log('[analyze-selected] 响应:', result);

        // ============ 旧链路：没传 messageIds（老版本 ChatInterface）时 fallback 走 sendMessage ============
        } else if (data.prompt) {
          const request = { 
            message: data.prompt,
            type: 'analysis'
          };
          if (currentConversationId.value) request.conversationId = currentConversationId.value;
          if (props.groupId) request.groupId = props.groupId;
          if (props.userId) request.userId = props.userId;
          if (props.userNickname) request.userNickname = props.userNickname;
          console.log('[fallback sendMessage] 分析请求:', request);
          const response = await astrBotApi.sendMessage(request);
          result = response || {};
          if (response && response.conversationId) {
            currentConversationId.value = response.conversationId;
            localStorage.setItem('astrbot_current_conversation', response.conversationId);
          }
        } else {
          throw new Error('缺少分析参数（messageIds 或 prompt 至少一个）');
        }

        // 从结果中提取回复文本（新接口在 analysis 字段，sendMessage 走 processAstrBotResponse 过滤）
        let replyText = '';
        if (result && typeof result.analysis === 'string' && result.analysis.trim()) {
          replyText = result.analysis;
        } else {
          replyText = processAstrBotResponse(result);
        }

        if (!replyText || !replyText.trim()) {
          replyText = '抱歉，未收到分析结果。';
        }

        // 将后端返回的推荐分析类型/群类型以小字形式追加到 reply 上方（系统提示）
        let extraHint = '';
        if (result && Array.isArray(result.recommendedAnalysisTypes) && result.recommendedAnalysisTypes.length > 0) {
          const LABELS = {
            'summary': '群聊速览',
            'social-graph': '社交图谱',
            'topic-trend': '话题趋势',
            'integration-guide': '融入指南',
            'meme-dictionary': '梗词典',
            'persona-match': '人设匹配'
          };
          const labels = result.recommendedAnalysisTypes
            .filter(t => t !== data.analysisType)
            .map(t => LABELS[t] || t)
            .filter(Boolean)
            .slice(0, 3);
          if (labels.length > 0) {
            const gtLabel = result.groupTypeLabel ? `（当前群：${result.groupTypeLabel}）` : '';
            extraHint = `<div class="analysis-extra-hint">💡 同类群还推荐尝试：<b>${labels.join(' / ')}</b> ${gtLabel}</div>\n\n`;
          }
        }

        messages.value.push({
          text: extraHint + replyText,
          sender: 'AstrBot',
          isSelf: false,
          time: new Date()
        });

        // 扣费成功：轻量提示
        const cost = typeof result.cost === 'number' ? result.cost : null;
        const balance = typeof result.balance === 'number' ? result.balance : null;
        if (cost !== null && balance !== null) {
          showCreditHintBriefly(cost, balance);
        }
      } catch (error) {
        console.error('分析失败:', error);
        if (error && error.errorCode === 'INSUFFICIENT_CREDITS') {
          const need = (error.details && error.details.need) || 0;
          const balance = (error.details && error.details.balance) || 0;
          insufficientNeed.value = need;
          insufficientBalance.value = balance;
          showInsufficientCredits.value = true;
          messages.value.push({
            text: `⚠️ 分析失败：${error.message || '积分不足'}`,
            sender: '系统',
            isSelf: false,
            isSystem: true,
            time: new Date()
          });
        } else {
          showToast(error?.message || '分析失败，请稍后重试', 'error');
          messages.value.push({
            text: '抱歉，分析过程中出现错误，请稍后重试。',
            sender: 'AstrBot',
            isSelf: false,
            time: new Date()
          });
        }
      } finally {
        isLoading.value = false;
        nextTick(() => scrollToBottom());
      }
    };

    let _statusInterval = null;

    onMounted(async () => {
      await loadSettings();
      checkStatus();
      loadConversations();
      restoreConversation();
      _statusInterval = setInterval(checkStatus, 30000);

      // ========== 先从 localStorage 恢复当前会话的模型选择 ==========
      // 优先读取会话级别的选择，其次是全局选择
      const sessionScopedModel = currentConversationId.value
        ? localStorage.getItem(`astrbot_current_model_${currentConversationId.value}`)
        : null;
      const globalSavedModel = localStorage.getItem('astrbot_current_model');
      const savedModel = sessionScopedModel || globalSavedModel || localStorage.getItem('llm_model');
      if (savedModel) {
        currentModel.value = savedModel;
      }

      // 加载用户已配置的模型 + AstrBot 模型
      await loadAvailableModels();

      // 加载 TTS 角色列表
      try {
        const charData = await systemApi.getTtsCharacters();
        if (charData && charData.characters) {
          ttsCharacters.value = charData.characters;
          // 恢复选择：localStorage > 后端当前角色 > 第一个角色
          if (charData.current) {
            selectedTtsCharacter.value = charData.current;
            localStorage.setItem('tts_character', charData.current);
          } else if (!selectedTtsCharacter.value && charData.characters.length > 0) {
            selectedTtsCharacter.value = charData.characters[0].name;
          }
        }
      } catch (e) {
        console.warn('加载 TTS 角色列表失败:', e);
      }
    });

    // TTS 角色切换
    const onTtsCharacterChange = async () => {
      try {
        await systemApi.switchTtsCharacter(selectedTtsCharacter.value);
        localStorage.setItem('tts_character', selectedTtsCharacter.value);
        showToast(`已切换到 ${selectedTtsCharacter.value}`, 'success');
      } catch (e) {
        console.error('切换 TTS 角色失败:', e);
        showToast('切换角色失败: ' + (e.message || '未知错误'), 'error');
      }
    };

    onUnmounted(() => {
      // 清理定时器，防止组件卸载后继续发 API 请求导致 401 闪屏
      if (_statusInterval) {
        clearInterval(_statusInterval);
        _statusInterval = null;
      }
      if (_creditHintTimer) {
        clearTimeout(_creditHintTimer);
        _creditHintTimer = null;
      }
    });

    // llmModels 已废弃（唯一模型数据源为 AstrBot API），不再 watch 同步到聊天选择器

    // 监听 userId 变化，重新加载设置和会话（用户切换账号时）
    watch(() => props.userId, async (newUserId, oldUserId) => {
      if (newUserId && newUserId !== oldUserId) {
        // 清除旧用户的会话数据
        localStorage.removeItem('astrbot_current_conversation');
        currentConversationId.value = null;
        currentConversationTitle.value = '';
        messages.value = [];
        conversations.value = [];
        // 重新加载当前用户的数据
        loadSettings();
        await loadConversations();
      }
    });

    return {
      messages,
      inputMessage,
      messagesContainer,
      inputRef,
      botAvatarInput,
      isLoading,
      isOnline,
      conversations,
      currentConversationId,
      currentConversationTitle,
      showConversationList,
      showGrouping,
      groupedConversations,
      collapsedGroups,
      toggleGrouping,
      toggleGroup,
      toggleConversationList,
      showConfirmDialog,
      userAvatar,
      botAvatar,
      botName,
      astrbotApiKey,
      llmModel,
      llmModels,
      showAstrbotKey,
      showSettings,
      newModelName,
      showCustomModel,
      modelSearch,
      providers,
      currentProviderIndex,
      showAddProvider,
      newProviderName,
      currentProvider,
      filteredModels,
      showInsufficientCredits,
      insufficientNeed,
      insufficientBalance,
      showCreditHint,
      lastCost,
      lastBalance,
      goToSignIn,
      goToUpgrade,
      sendMessage,
      formatTime,
      formatDate,
      loadConversation,
      createNewConversation,
      showDeleteConfirm,
      cancelDelete,
      confirmDelete,
      handleAnalysisRequest,
      saveSettings,
      closeSettings,
      handleBotAvatarError,
      handleBotAvatarUpload,
      copyMessageText,
      handleVoiceAction,
      ttsCharacters,
      selectedTtsCharacter,
      onTtsCharacterChange,
      addModel,
      removeModel,
      addProvider,
      removeProvider,
      fetchModels,
      copyModelName,
      setAsCurrentModel,
      currentModel,
      currentModelLabel,
      availableModels,
      modelLoading,
      modelError,
      loadAvailableModels,
      refreshAvailableModels,
      onCurrentModelChange
    };
  }
};
</script>

<style scoped>
.astrbot-chat {
  display: flex;
  flex-direction: column;
  height: 100%;
  background-color: #f5f5f5;
  position: relative;
}

.chat-header {
  display: flex;
  align-items: center;
  padding: 15px 20px;
  background-color: white;
  border-bottom: 1px solid #e0e0e0;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.header-avatar {
  width: 40px;
  height: 40px;
  margin-right: 12px;
  border-radius: 50%;
  overflow: hidden;
}

.header-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.header-info {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-info h3 {
  margin: 0;
  font-size: 16px;
  color: #2c3e50;
}

.header-info .status {
  font-size: 12px;
}

.header-info .status.online {
  color: #27ae60;
}

.header-info .status.offline {
  color: #e74c3c;
}

.header-loading-icon {
  color: #3498db;
  animation: astrbot-spin 1s linear infinite;
}

@keyframes astrbot-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.tts-character-selector {
  margin-right: 4px;
}
.tts-character-select {
  padding: 4px 8px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 12px;
  background: #fff;
  color: #333;
  cursor: pointer;
  outline: none;
  transition: border-color 0.2s;
  max-width: 140px;
}
.tts-character-select:hover {
  border-color: #4f46e5;
}
.tts-character-select:focus {
  border-color: #4f46e5;
}

.action-btn {
  background: none;
  border: none;
  font-size: 20px;
  cursor: pointer;
  padding: 5px;
  border-radius: 4px;
  transition: background-color 0.2s;
}

.action-btn:hover {
  background-color: #f0f0f0;
}

.conversation-panel {
  position: absolute;
  top: 70px;
  right: 10px;
  width: 280px;
  max-height: 400px;
  background: white;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  z-index: 100;
  display: flex;
  flex-direction: column;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #e0e0e0;
}

.panel-header h4 {
  margin: 0;
  font-size: 14px;
  color: #2c3e50;
}

.close-btn {
  background: none;
  border: none;
  font-size: 16px;
  cursor: pointer;
  color: #7f8c8d;
}

.conversation-list {
  overflow-y: auto;
  max-height: 350px;
  padding: 8px;
}

.conversation-item {
  display: flex;
  align-items: center;
  padding: 10px 12px;
  border-radius: 6px;
  transition: background-color 0.2s;
  margin-bottom: 4px;
}

.conversation-item:hover {
  background-color: #f5f5f5;
}

.conversation-item.active {
  background-color: #e3f2fd;
}

.conv-content {
  flex: 1;
  cursor: pointer;
  min-width: 0;
}

.delete-btn {
  background: none;
  border: none;
  font-size: 14px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  opacity: 0.6;
  transition: opacity 0.2s, background-color 0.2s;
}

.delete-btn:hover {
  opacity: 1;
  background-color: #ffebee;
}

/* 删除确认弹窗样式 */
.confirm-dialog-overlay {
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

.confirm-dialog {
  background: white;
  border-radius: 12px;
  width: 90%;
  max-width: 400px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
  animation: dialogSlideIn 0.2s ease-out;
}

@keyframes dialogSlideIn {
  from {
    opacity: 0;
    transform: translateY(-20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.confirm-dialog-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 20px 10px;
  border-bottom: 1px solid #f0f0f0;
}

.confirm-icon {
  font-size: 24px;
}

.confirm-dialog-header h3 {
  margin: 0;
  font-size: 18px;
  color: #2c3e50;
}

.confirm-dialog-body {
  padding: 20px;
}

.confirm-dialog-body p {
  margin: 0 0 8px;
  font-size: 15px;
  color: #2c3e50;
}

.confirm-hint {
  font-size: 13px !important;
  color: #7f8c8d !important;
}

.confirm-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 0 20px 20px;
}

.btn-cancel {
  padding: 10px 20px;
  border: 1px solid #ddd;
  background: white;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  color: #666;
  transition: all 0.2s;
}

.btn-cancel:hover {
  background: #f5f5f5;
}

.btn-confirm {
  padding: 10px 20px;
  border: none;
  background: #e74c3c;
  color: white;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
}

.btn-confirm:hover {
  background: #c0392b;
}

/* 设置弹窗样式 - 新版配置面板 */
.settings-dialog-overlay {
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

.settings-dialog {
  background: white;
  border-radius: 12px;
  width: 95%;
  max-width: 1000px;
  max-height: 85vh;
  overflow: hidden;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
  animation: dialogSlideIn 0.2s ease-out;
  display: flex;
  flex-direction: column;
}

.settings-dialog-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f0;
  background: white;
  flex-shrink: 0;
}

.settings-icon {
  font-size: 20px;
}

.settings-dialog-header h3 {
  margin: 0;
  flex: 1;
  font-size: 16px;
  color: #2c3e50;
}

/* 设置面板主体 - 左右布局 */
.settings-panel-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

/* 左侧提供商列表 */
.settings-sidebar {
  width: 260px;
  background: #f8f9fa;
  border-right: 1px solid #e0e0e0;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid #e0e0e0;
}

.sidebar-title {
  font-size: 14px;
  font-weight: 600;
  color: #2c3e50;
}

.btn-add-provider {
  width: 28px;
  height: 28px;
  border: 1px dashed #ccc;
  border-radius: 6px;
  background: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #666;
  transition: all 0.2s;
}

.btn-add-provider:hover {
  border-color: #3498db;
  color: #3498db;
  background: #f0f7ff;
}

.provider-add-form {
  padding: 12px;
  border-bottom: 1px solid #e0e0e0;
  display: flex;
  gap: 6px;
}

.provider-add-form input {
  flex: 1;
  padding: 6px 10px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 13px;
}

.btn-provider-confirm {
  padding: 6px 12px;
  background: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
}

.btn-provider-cancel {
  padding: 6px 12px;
  background: #f5f5f5;
  color: #666;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
}

.provider-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px;
}

.provider-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  margin-bottom: 2px;
}

.provider-item:hover {
  background: #e8f4fc;
}

.provider-item.active {
  background: #3498db;
  color: white;
}

.provider-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: #e0e0e0;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.provider-item.active .provider-icon {
  background: rgba(255, 255, 255, 0.2);
}

.provider-info {
  flex: 1;
  min-width: 0;
}

.provider-name {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: #2c3e50;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.provider-item.active .provider-name {
  color: white;
}

.provider-url {
  display: block;
  font-size: 11px;
  color: #95a5a6;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.provider-item.active .provider-url {
  color: rgba(255, 255, 255, 0.7);
}

.provider-delete {
  width: 24px;
  height: 24px;
  border: none;
  background: transparent;
  border-radius: 4px;
  cursor: pointer;
  color: #95a5a6;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: all 0.2s;
}

.provider-item:hover .provider-delete {
  opacity: 1;
}

.provider-item.active .provider-delete {
  color: rgba(255, 255, 255, 0.7);
}

.provider-delete:hover {
  background: rgba(0, 0, 0, 0.1);
}

/* 右侧配置详情 */
.settings-content {
  flex: 1;
  overflow-y: auto;
}

/* 提供商配置 */
.provider-config {
  padding: 0;
}

.provider-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px;
  border-bottom: 1px solid #f0f0f0;
  background: #fafafa;
}

.provider-title-row {
  display: flex;
  align-items: center;
  gap: 14px;
}

.provider-icon-large {
  width: 48px;
  height: 48px;
  border-radius: 10px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
}

.provider-display-name {
  margin: 0;
  font-size: 18px;
  color: #2c3e50;
}

.provider-url-text {
  display: block;
  font-size: 13px;
  color: #7f8c8d;
  margin-top: 2px;
}

.btn-save-config {
  padding: 8px 16px;
  background: #27ae60;
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  transition: background 0.2s;
}

.btn-save-config:hover {
  background: #2ecc71;
}

/* 配置表单 */
.config-form {
  padding: 20px;
}

.form-section {
  background: white;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
  border: 1px solid #e0e0e0;
}

.form-item {
  margin-bottom: 16px;
}

.form-item:last-child {
  margin-bottom: 0;
}

.form-label {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: #2c3e50;
  margin-bottom: 4px;
}

.form-hint {
  display: block;
  font-size: 12px;
  color: #95a5a6;
  margin-bottom: 8px;
}

.form-input {
  width: 100%;
  padding: 10px 14px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
  transition: all 0.2s;
  box-sizing: border-box;
}

.form-input:focus {
  outline: none;
  border-color: #3498db;
  box-shadow: 0 0 0 3px rgba(52, 152, 219, 0.1);
}

.password-field {
  display: flex;
  gap: 8px;
}

.password-field .form-input {
  flex: 1;
}

.toggle-key-btn {
  padding: 10px 14px;
  background: #f5f5f5;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 13px;
  color: #666;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.2s;
}

.toggle-key-btn:hover {
  background: #e8e8e8;
  border-color: #3498db;
  color: #3498db;
}

/* 模型配置区域 */
.model-section {
  background: white;
  border-radius: 8px;
  padding: 16px;
  border: 1px solid #e0e0e0;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.section-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #2c3e50;
}

.section-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.search-input {
  padding: 6px 12px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 13px;
  width: 160px;
}

.btn-get-models, .btn-custom-model {
  padding: 6px 12px;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
  transition: all 0.2s;
}

.btn-get-models {
  background: #f0f7ff;
  color: #3498db;
  border-color: #d0e3ff;
}

.btn-get-models:hover {
  background: #e0f0ff;
}

.btn-custom-model {
  background: #f0fff4;
  color: #27ae60;
  border-color: #d0f0dc;
}

.btn-custom-model:hover {
  background: #e0ffe8;
}

.custom-model-form {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  padding: 12px;
  background: #f8f9fa;
  border-radius: 6px;
}

.custom-model-form .form-input {
  flex: 1;
  padding: 8px 12px;
}

.btn-model-confirm {
  padding: 8px 14px;
  background: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
}

.btn-model-cancel {
  padding: 8px 14px;
  background: #f5f5f5;
  color: #666;
  border: 1px solid #ddd;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
}

/* 模型配置列表 */
.model-config-list {
  max-height: 300px;
  overflow-y: auto;
}

.model-config-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-radius: 6px;
  border: 1px solid #e9ecef;
  margin-bottom: 6px;
  transition: all 0.2s;
}

.model-config-item:hover {
  background: #f8f9fa;
}

.model-info {
  flex: 1;
  min-width: 0;
}

.model-name {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: #2c3e50;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-id {
  display: block;
  font-size: 12px;
  color: #95a5a6;
  margin-top: 2px;
}

.model-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.model-switch {
  position: relative;
  display: inline-block;
  width: 40px;
  height: 22px;
  margin-right: 4px;
}

.switch-input {
  opacity: 0;
  width: 0;
  height: 0;
}

.switch-track {
  position: absolute;
  cursor: pointer;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: #ccc;
  border-radius: 22px;
  transition: 0.3s;
}

.switch-track:before {
  position: absolute;
  content: "";
  height: 18px;
  width: 18px;
  left: 2px;
  bottom: 2px;
  background-color: white;
  border-radius: 50%;
  transition: 0.3s;
}

.switch-input:checked + .switch-track {
  background-color: #3498db;
}

.switch-input:checked + .switch-track:before {
  transform: translateX(18px);
}

.model-action-btn {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  border-radius: 4px;
  cursor: pointer;
  color: #7f8c8d;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.model-action-btn:hover {
  background: #f0f0f0;
  color: #3498db;
}

.model-action-btn.delete-btn:hover {
  background: #ffebee;
  color: #e74c3c;
}

.empty-models {
  text-align: center;
  padding: 40px;
  color: #95a5a6;
}

.empty-models p {
  margin-top: 8px;
  font-size: 14px;
}

/* 智能体配置区域 */
.persona-config {
  padding: 20px;
}

/* 设置弹窗底部 */
.settings-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 20px;
  border-top: 1px solid #f0f0f0;
  background: white;
  flex-shrink: 0;
}

.conv-title {
  font-size: 14px;
  color: #2c3e50;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conv-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #7f8c8d;
  margin-top: 4px;
}

.empty-conversations {
  text-align: center;
  padding: 20px;
  color: #95a5a6;
  font-size: 14px;
}

/* 对话分组样式 */
.panel-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toggle-group-btn {
  background: transparent;
  border: 1px solid #ddd;
  border-radius: 6px;
  padding: 4px 8px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 4px;
  color: #666;
  font-size: 12px;
  transition: all 0.2s;
}

.toggle-group-btn:hover {
  background: #f0f0f0;
  color: #333;
}

.toggle-group-btn.active {
  background: #5b5bd6;
  border-color: #5b5bd6;
  color: white;
}

.conv-group {
  margin-bottom: 4px;
}

.conv-group-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  background: #f8f9fa;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  color: #333;
  transition: background 0.2s;
}

.conv-group-header:hover {
  background: #e9ecef;
}

.conv-group-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-group-count {
  background: #e0e0e0;
  color: #666;
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 500;
}

.conv-group-items {
  padding-left: 8px;
  border-left: 2px solid #e0e0e0;
  margin-left: 12px;
}

.conv-group-items .conversation-item {
  border-radius: 6px;
}

.conv-group-items .conversation-item:hover {
  background-color: #f0f4ff;
}

.conv-group-items .conv-content {
  padding: 8px 10px;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  background-color: #f5f5f5;
}

.empty-chat {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #95a5a6;
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 10px;
}

.empty-hint {
  font-size: 12px;
  margin-top: 8px;
  color: #bdc3c7;
}

.message-wrapper {
  display: flex;
  margin-bottom: 10px;
}

.message-wrapper.message-self {
  justify-content: flex-end;
}

.message-bubble {
  max-width: 70%;
  padding: 10px 14px;
  border-radius: 18px;
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.message-left {
  background-color: white;
  border-bottom-left-radius: 4px;
  flex-direction: row;
  margin-right: auto;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.1);
}

.message-right {
  background-color: #3498db;
  color: white;
  border-bottom-right-radius: 4px;
  flex-direction: row-reverse;
  margin-left: auto;
}

.message-avatar img {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
}

.message-content {
  display: flex;
  flex-direction: column;
}

.message-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 12px;
}

.message-left .message-header {
  color: #7f8c8d;
}

.message-right .message-header {
  color: rgba(255, 255, 255, 0.8);
}

.message-sender {
  font-weight: 500;
}

.message-text {
  word-break: break-word;
  line-height: 1.35;
  font-size: 14px;
  white-space: pre-wrap;
}

.message-text p {
  margin: 4px 0;
}

.message-text ul,
.message-text ol {
  margin: 4px 0;
  padding-left: 20px;
}

.message-text li {
  margin: 2px 0;
}

.message-text h1,
.message-text h2,
.message-text h3,
.message-text h4 {
  margin: 6px 0 4px;
  font-size: 1.1em;
}

.message-actions {
  display: flex;
  gap: 8px;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}

.msg-action-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: none;
  background: transparent;
  color: #7f8c8d;
  font-size: 12px;
  cursor: pointer;
  border-radius: 4px;
  transition: all 0.2s;
}

.msg-action-btn:hover {
  background-color: rgba(52, 152, 219, 0.1);
  color: #3498db;
}

.msg-action-btn svg {
  flex-shrink: 0;
}

.msg-action-btn.voice-btn.generating {
  color: #f39c12;
  cursor: not-allowed;
  opacity: 0.9;
}

.msg-action-btn.voice-btn.playing {
  color: #27ae60;
}

.msg-action-btn .spin-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.voice-player {
  margin-top: 10px;
  padding: 8px 12px;
  background: linear-gradient(135deg, #e8f5e9 0%, #f1f8e9 100%);
  border-radius: 8px;
  border: 1px solid #c8e6c9;
}

.voice-player audio {
  width: 100%;
  max-width: 400px;
}

/* 系统消息样式 */
.message-system {
  justify-content: center;
}

.message-system-bubble {
  background-color: #f0f7ff;
  border: 1px solid #d0e3ff;
  border-radius: 12px;
  max-width: 90%;
  margin: 10px auto;
  padding: 12px 16px;
}

.message-system-text {
  color: #4a6fa5;
  font-size: 13px;
  line-height: 1.6;
}

.loading-indicator {
  text-align: center;
  padding: 10px;
  color: #7f8c8d;
}

.loading-dots::after {
  content: '...';
  animation: dots 1.5s steps(4, end) infinite;
}

@keyframes dots {
  0%, 20% { content: ''; }
  40% { content: '.'; }
  60% { content: '..'; }
  80%, 100% { content: '...'; }
}

.chat-input-area {
  padding: 12px 20px 15px;
  background-color: white;
  border-top: 1px solid #e0e0e0;
}

.model-selector-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  padding: 8px 12px;
  background: linear-gradient(135deg, #f8fbff 0%, #f1f5fb 100%);
  border: 1px solid #e3ebf5;
  border-radius: 10px;
}

.model-selector {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #4a5568;
  font-size: 13px;
}

.model-selector select {
  padding: 5px 10px 5px 28px;
  border: 1px solid #cbd5e0;
  border-radius: 6px;
  background-color: white;
  font-size: 13px;
  color: #2d3748;
  cursor: pointer;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s;
  max-width: 220px;
}

.model-selector select:hover:not(:disabled) {
  border-color: #3498db;
}

.model-selector select:focus {
  border-color: #3498db;
  box-shadow: 0 0 0 2px rgba(52, 152, 219, 0.15);
}

.model-selector select:disabled {
  background-color: #f5f5f5;
  cursor: not-allowed;
}

.model-selector svg {
  color: #7f8c8d;
  flex-shrink: 0;
}

.model-loading {
  font-size: 12px;
  color: #95a5a6;
  font-style: italic;
}

.model-error {
  font-size: 14px;
  color: #e74c3c;
  cursor: help;
  padding: 0 4px;
}

.model-empty-tip {
  color: #e67e22 !important;
  background: rgba(230, 126, 34, 0.08) !important;
  border-color: rgba(230, 126, 34, 0.25) !important;
  font-size: 12px !important;
}

.model-refresh-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: 1px solid #cbd5e0;
  background-color: white;
  border-radius: 6px;
  color: #7f8c8d;
  cursor: pointer;
  transition: all 0.2s;
}

.model-refresh-btn:hover {
  border-color: #3498db;
  color: #3498db;
  background-color: #f0f7ff;
}

.model-tip {
  font-size: 12px;
  color: #5b5bd6;
  background: rgba(91, 91, 214, 0.08);
  padding: 3px 10px;
  border-radius: 10px;
  white-space: nowrap;
  max-width: 50%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.model-tip b {
  font-weight: 600;
}

.input-wrapper {
  display: flex;
  gap: 10px;
}

.input-wrapper input {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #ddd;
  border-radius: 24px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}

.input-wrapper input:focus {
  border-color: #3498db;
}

.input-wrapper input:disabled {
  background-color: #f5f5f5;
  cursor: not-allowed;
}

.send-btn {
  padding: 12px 24px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 24px;
  font-size: 14px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.send-btn:hover:not(:disabled) {
  background-color: #2980b9;
}

.send-btn:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
}

.conversation-info {
  font-size: 12px;
  color: #7f8c8d;
  margin-top: 8px;
  text-align: center;
}

/* 积分不足提示条 */
.credits-banner {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 18px;
  margin: 0 16px 12px;
  border-radius: 10px;
  background: linear-gradient(135deg, #fff8e6 0%, #fff3cd 100%);
  border: 1px solid #ffc107;
  box-shadow: 0 2px 8px rgba(255, 193, 7, 0.15);
  animation: bannerIn 0.3s ease;
}

@keyframes bannerIn {
  from { opacity: 0; transform: translateY(-8px); }
  to { opacity: 1; transform: translateY(0); }
}

.credits-banner-icon {
  flex-shrink: 0;
  color: #f39c12;
  margin-top: 1px;
}

.credits-banner-body {
  flex: 1;
  min-width: 0;
}

.credits-banner-title {
  font-size: 14px;
  color: #856404;
  font-weight: 600;
  margin-bottom: 10px;
  line-height: 1.5;
}

.credits-banner-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.banner-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border-radius: 20px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border: none;
  transition: all 0.2s;
  white-space: nowrap;
}

.banner-btn-primary {
  background: linear-gradient(135deg, #27ae60 0%, #2ecc71 100%);
  color: white;
  box-shadow: 0 2px 6px rgba(39, 174, 96, 0.25);
}

.banner-btn-primary:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 10px rgba(39, 174, 96, 0.35);
}

.banner-btn-upgrade {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  box-shadow: 0 2px 6px rgba(102, 126, 234, 0.25);
}

.banner-btn-upgrade:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 10px rgba(102, 126, 234, 0.35);
}

.banner-btn-close {
  background: rgba(0,0,0,0.05);
  color: #856404;
  padding: 6px 8px;
}

.banner-btn-close:hover {
  background: rgba(0,0,0,0.1);
}

/* 扣费成功轻量提示 */
.credit-hint-toast {
  position: absolute;
  bottom: 88px;
  left: 50%;
  transform: translateX(-50%);
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: rgba(39, 174, 96, 0.95);
  color: white;
  font-size: 13px;
  font-weight: 500;
  border-radius: 20px;
  box-shadow: 0 4px 12px rgba(39, 174, 96, 0.3);
  z-index: 50;
  backdrop-filter: blur(6px);
  white-space: nowrap;
}

.credit-hint-enter-active,
.credit-hint-leave-active {
  transition: all 0.35s ease;
}

.credit-hint-enter-from,
.credit-hint-leave-to {
  opacity: 0;
  transform: translate(-50%, 12px);
}

/* AI 分析结果的推荐提示 */
.analysis-extra-hint {
  padding: 8px 12px;
  margin-bottom: 10px;
  border-radius: 8px;
  background: linear-gradient(135deg, #f0f1ff 0%, #eef6ff 100%);
  color: #4b4fa3;
  font-size: 12.5px;
  line-height: 1.55;
  border: 1px solid #d9dcff;
}
.analysis-extra-hint b {
  color: #5b5bd6;
  font-weight: 600;
}
</style>
