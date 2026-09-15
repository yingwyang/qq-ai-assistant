<template>
  <div class="message-content">
    <!-- 已删除消息：统一显示占位，不再尝试加载任何媒体 -->
    <div v-if="message.deleted" class="media-placeholder">
      <svg viewBox="0 0 64 64" width="48" height="48" stroke="#999" fill="none" stroke-width="2">
        <rect x="8" y="8" width="48" height="48" rx="4"/>
        <line x1="18" y1="18" x2="46" y2="46" stroke-linecap="round"/>
        <line x1="46" y1="18" x2="18" y2="46" stroke-linecap="round"/>
      </svg>
      <span class="placeholder-label">消息已删除</span>
    </div>

    <!-- 媒体加载中（mediaPending=true，后端异步下载/转码中） -->
    <div v-else-if="message.mediaPending" class="media-loading">
      <div class="loading-spinner"></div>
      <span class="loading-label">{{ mediaLoadingLabel }}</span>
    </div>

    <!-- 媒体下载/转码失败（DLQ 消费者回填的失败占位符） -->
    <div v-else-if="isMediaFailed" class="media-placeholder">
      <svg viewBox="0 0 64 64" width="48" height="48" stroke="#e74c3c" fill="none" stroke-width="2">
        <circle cx="32" cy="32" r="28"/>
        <line x1="32" y1="20" x2="32" y2="38" stroke-linecap="round"/>
        <circle cx="32" cy="46" r="2" fill="#e74c3c" stroke="none"/>
      </svg>
      <span class="placeholder-label">{{ mediaFailLabel }}</span>
    </div>

    <!-- 回复/引用消息 -->
    <div v-else-if="isReplyMessage" class="message-reply-wrapper">
      <div class="message-reply" @click="onReplyClick">
        <div class="reply-bar"></div>
        <div class="reply-content">
          <div class="reply-sender">{{ replyTarget }}</div>
          <div class="reply-preview">
            <img
              v-if="replyPreviewType === 'image' && replyMediaUrl && !replyImageError"
              :src="replyMediaUrl"
              class="reply-preview-image"
              @click.stop="openImage(replyMediaUrl)"
              @error="onReplyImageError"
            />
            <span v-else-if="replyPreviewType === 'image' && replyImageError" class="reply-preview-placeholder">[图片已删除]</span>
            <span v-else>{{ replyPreview }}</span>
          </div>
        </div>
      </div>
      <div v-if="replyText" class="reply-text">{{ replyText }}</div>
    </div>

    <!-- 聊天记录/合并转发消息 -->
    <div v-else-if="isForwardMessage" class="message-forward" :class="{ 'is-empty': forwardMessages.length === 0 }" @click="toggleForwardExpanded">
      <div class="forward-header">
        <div class="forward-title">[聊天记录]</div>
        <div class="forward-summary">{{ forwardSummary }}</div>
      </div>
      <div v-if="isForwardExpanded && forwardMessages.length > 0" class="forward-list" @click.stop>
        <div
          v-for="(childMsg, index) in forwardMessages"
          :key="childMsg.id || index"
          class="forward-item"
        >
          <div class="forward-item-header">
            <span class="forward-item-sender">{{ childMsg.userNickname || childMsg.userName || '未知用户' }}</span>
            <span v-if="childMsg.sendTime || childMsg.timestamp" class="forward-item-time">{{ formatForwardTime(childMsg.sendTime || childMsg.timestamp) }}</span>
          </div>
          <MessageContent
            :message="childMsg"
            :qq-nickname-map="qqNicknameMap"
            @navigate-to-message="$emit('navigate-to-message', $event)"
          />
        </div>
      </div>
      <div v-if="isForwardExpanded && forwardMessages.length === 0" class="forward-empty" @click.stop>
        <div v-if="forwardFallbackText" class="forward-fallback">{{ forwardFallbackText }}</div>
        <div v-else>暂无聊天记录详情</div>
        <div class="forward-hint">合并转发消息详情需 NapCat 在线时同步拉取</div>
      </div>
      <button
        v-if="forwardMessages.length > 0"
        class="forward-toggle-btn"
        @click.stop="toggleForwardExpanded"
      >
        {{ isForwardExpanded ? '收起' : '展开' }}
      </button>
    </div>

    <!-- 图片消息 -->
    <div v-else-if="isImageMessage" class="message-image">
      <img
        v-if="imageUrl && !imageError"
        :src="imageUrl"
        @error="onImageError"
        @click="openImage(imageUrl)"
      />
      <div v-else class="media-placeholder">
        <svg viewBox="0 0 64 64" width="64" height="64" stroke="#999" fill="none" stroke-width="2">
          <rect x="4" y="12" width="56" height="40" rx="4"/>
          <circle cx="20" cy="28" r="4"/>
          <path d="M52 50 L36 34 L24 46 L8 30 L8 52 L52 52 Z"/>
        </svg>
        <span class="placeholder-label">图片已删除</span>
      </div>
    </div>
    
    <!-- 语音消息 - QQ样式 -->
    <div v-else-if="isVoiceMessage" class="message-voice-qq" @click="toggleVoicePlay">
      <div class="voice-wave" :class="{ 'is-playing': isPlaying }">
        <span class="wave-dot"></span>
        <span class="wave-dot"></span>
        <span class="wave-dot"></span>
      </div>
      <span class="voice-duration">{{ formatDuration(voiceDuration) }}"</span>
    </div>
    
    <!-- 视频消息 -->
    <div v-else-if="isVideoMessage" class="message-video">
      <!-- 原视频未被 QQ 缓存的场景:后端落盘的是 QQ 缩略图,直接展示预览图 -->
      <div v-if="videoUrl && videoPreviewImage" class="video-preview-wrap">
        <img
          :src="videoUrl"
          class="video-preview-img"
          alt="视频预览"
          @click="openImage(videoUrl)"
        />
        <span class="video-preview-label">原视频未缓存，仅预览</span>
      </div>
      <video
        v-else-if="videoUrl && !videoError"
        :src="videoUrl"
        controls
        class="video-player"
        @error="onVideoError"
      ></video>
      <div v-else class="media-placeholder">
        <svg viewBox="0 0 64 64" width="64" height="64" stroke="#999" fill="none" stroke-width="2">
          <rect x="6" y="12" width="36" height="40" rx="4"/>
          <polygon points="42,20 58,12 58,52 42,44" fill="none"/>
        </svg>
        <span class="placeholder-label">视频已删除</span>
      </div>
    </div>
    
    <!-- 表情消息 -->
    <div v-else-if="isFaceMessage" class="message-face">
      <span class="face-text">[表情 {{ faceId }}]</span>
    </div>
    
    <!-- 小程序分享消息 -->
    <div v-else-if="isMiniAppMessage" class="message-mini-app">
      <div class="mini-app-card" @click="openMiniAppUrl">
        <div class="mini-app-header">
          <div class="mini-app-icon">
            <img v-if="miniAppData.icon" :src="miniAppData.icon" @error="onMiniAppIconError" />
            <div v-else class="mini-app-icon-placeholder">
              <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="#666" stroke-width="2">
                <rect x="3" y="3" width="18" height="18" rx="2"/>
                <path d="M8 8h8M8 16h8M8 12h8"/>
              </svg>
            </div>
          </div>
          <div class="mini-app-info">
            <div class="mini-app-title">{{ miniAppData.title || '[小程序]' }}</div>
            <div class="mini-app-desc">{{ miniAppData.desc || miniAppData.description }}</div>
            <div class="mini-app-source">{{ miniAppData.appName || miniAppData.source }}</div>
          </div>
          <div class="mini-app-arrow">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="#999" stroke-width="2">
              <path d="M5 12h14M12 5l7 7-7 7"/>
            </svg>
          </div>
        </div>
        <div v-if="miniAppData.preview" class="mini-app-preview">
          <img :src="miniAppData.preview" @error="onMiniAppPreviewError" />
        </div>
      </div>
    </div>
    
    <!-- 文本消息 -->
    <RichTextRenderer v-else class="message-text" :content="displayContent" />
  </div>
</template>

<script>
import { computed, ref } from 'vue';
import { showToast } from './Toast.vue';
import RichTextRenderer from './RichTextRenderer.vue';
import { systemApi, formatFileSize } from '../services/api';
import { useImagePreview } from '../composables/useImagePreview';
import { extractForwardXmlTitles, extractForwardMessages } from '../utils/messageParser';
import logger from '../utils/logger';

export default {
  name: 'MessageContent',
  components: { RichTextRenderer },
  props: {
    message: {
      type: Object,
      required: true
    },
    qqNicknameMap: {
      type: Map,
      default: () => new Map()
    }
  },
  emits: ['navigate-to-message'],
  setup(props, { emit }) {
    const { open: openImage } = useImagePreview();
    const isPlaying = ref(false);
    const voiceDuration = ref(0);
    const decodeHtmlEntities = (text) => {
      if (!text) return text;
      return text
        .replace(/&#91;/g, '[')
        .replace(/&#93;/g, ']')
        .replace(/&#44;/g, ',')
        .replace(/&amp;/g, '&')
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"')
        .replace(/&#39;/g, "'")
        .replace(/&#34;/g, '"')
        .replace(/&#10;/g, '\n')
        .replace(/&#13;/g, '\r');
    };
    const content = computed(() => decodeHtmlEntities(props.message.content || '').trim());

    // 媒体下载/转码失败检测（DLQ 消费者回填的占位符）
    const FAILURE_PLACEHOLDERS = ['[图片下载失败]', '[视频下载失败]', '[语音转码失败]', '[媒体下载失败]'];
    const isMediaFailed = computed(() => FAILURE_PLACEHOLDERS.includes(content.value));
    const mediaFailLabel = computed(() => content.value);

    // 媒体加载中提示文案（根据消息类型）
    const mediaLoadingLabel = computed(() => {
      const t = props.message.messageType;
      if (t === 'VOICE' || t === 'RECORD') return '语音转码中...';
      if (t === 'VIDEO') return '视频下载中...';
      return '图片下载中...';
    });
    const imageError = ref(false);
    const videoError = ref(false);
    const isForwardExpanded = ref(false);
    let currentAudio = null;

    // 图片加载失败 -> 显示"图片已删除"
    const onImageError = () => {
      if (imageError.value) return;
      imageError.value = true;
    };

    // 视频加载失败 -> 显示"视频已删除"占位
    const onVideoError = () => {
      if (videoError.value) return;
      videoError.value = true;
    };

    // 判断是否是回复/引用消息
    const isReplyMessage = computed(() => {
      if (props.message.messageType === 'REPLY') return true;
      if (content.value.includes('[CQ:reply')) return true;
      return false;
    });

    // 判断是否是聊天记录/合并转发消息
    const isForwardMessage = computed(() => {
      if (props.message.messageType === 'FORWARD') return true;
      if (content.value.includes('[CQ:forward')) return true;
      return false;
    });

    // 回复目标文本
    const replyTarget = computed(() => {
      if (props.message.replyToNickname) {
        return `回复 ${props.message.replyToNickname}`;
      }
      if (props.message.replyToMessageId) {
        return `回复消息 #${props.message.replyToMessageId}`;
      }
      return '回复消息';
    });

    // 将 CQ:at,qq=xxx 渲染为 @昵称（优先从当前群消息中查找昵称，找不到则显示 @QQ号）
    const escapeHtml = (text) => {
      if (!text) return text;
      return text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
    };
    const formatAtCqCode = (text, html = false) => {
      if (!text) return text;
      return text.replace(/\[CQ:at,qq=([^,\]]+)\]/g, (match, qq) => {
        const nickname = props.qqNicknameMap.get(String(qq));
        const name = nickname || qq;
        if (html) {
          return `<span class="at-mention">@${escapeHtml(name)}</span>`;
        }
        return `@${name}`;
      });
    };

    // 回复预览（被引用消息摘要）
    const replyPreview = computed(() => {
      let text = props.message.replyToContent || '';
      if (!text) {
        // 从当前消息内容中移除 CQ:reply 码后作为预览兜底
        text = content.value;
      }
      // 先清理 CQ:reply 码，再把 CQ:at 渲染为 @昵称，最后移除其它 CQ 码
      text = text.replace(/\[CQ:reply[^\]]*\]/g, '').trim();
      text = formatAtCqCode(text);
      text = text.replace(/\[CQ:[^\]]+\]/g, '').trim();
      if (!text) return '[查看原消息]';
      return text.length > 60 ? text.slice(0, 60) + '...' : text;
    });

    // 回复文本（用户实际输入的内容）
    const replyText = computed(() => {
      let text = content.value.replace(/\[CQ:reply[^\]]*\]/g, '').trim();
      text = formatAtCqCode(text);
      // 把媒体/表情 CQ 码显示为占位，避免暴露原始 CQ 字符串
      text = text
        .replace(/\[CQ:image[^\]]*\]/g, '[图片]')
        .replace(/\[CQ:video[^\]]*\]/g, '[视频]')
        .replace(/\[CQ:record[^\]]*\]/g, '[语音]')
        .replace(/\[CQ:voice[^\]]*\]/g, '[语音]')
        .replace(/\[CQ:face[^\]]*\]/g, '[表情]')
        .replace(/\[CQ:[^\]]+\]/g, '');
      return text;
    });

    // 被引用消息的媒体 URL（支持本地路径与 CQ 码）
    const replyMediaUrl = computed(() => extractMediaUrl(props.message.replyToContent || ''));

    // 被引用消息预览类型（根据解析后的媒体 URL 判断图片/视频/音频/文本）
    const replyPreviewType = computed(() => {
      const url = replyMediaUrl.value;
      if (/\.(jpg|jpeg|png|gif|webp|bmp)(\?.*)?$/i.test(url)) return 'image';
      if (/\.(mp4|webm|mov)(\?.*)?$/i.test(url)) return 'video';
      if (/\.(mp3|wav|amr|ogg)(\?.*)?$/i.test(url)) return 'audio';
      return 'text';
    });

    const replyImageError = ref(false);
    const onReplyImageError = () => {
      replyImageError.value = true;
    };

    // 点击引用条，通知父组件滚动到原消息
    const onReplyClick = () => {
      if (props.message.replyToMessageId) {
        emit('navigate-to-message', props.message.replyToMessageId);
      }
    };

    // extractForwardXmlTitles / extractForwardMessages 从 utils/messageParser.js 导入

    const forwardMessages = computed(() => extractForwardMessages(props.message));

    // 聊天记录摘要
    const forwardSummary = computed(() => {
      const c = content.value;
      // 优先从 XML 第一个 title 提取大标题
      const titles = extractForwardXmlTitles(c);
      if (titles.length > 0 && titles[0]) {
        return titles[0];
      }
      const nameMatch = c.match(/name=([^,\]]+)/);
      if (nameMatch && nameMatch[1]) {
        return `${nameMatch[1].replace(/&amp;/g, '&')} 的聊天记录`;
      }
      if (forwardMessages.value.length > 0) {
        const first = forwardMessages.value[0];
        return `${first.userNickname || first.userName || '某人'} 的聊天记录`;
      }
      return '合并转发消息';
    });

    // 合并转发内容不可用时的回退文本（显示 forward ID）
    const forwardFallbackText = computed(() => {
      const c = content.value;
      const idMatch = c.match(/\[CQ:forward,id=([^\]]+)\]/);
      if (idMatch && idMatch[1]) {
        return `合并转发消息 (ID: ${idMatch[1]})`;
      }
      return '';
    });

    // 切换聊天记录展开/收起
    const toggleForwardExpanded = () => {
      isForwardExpanded.value = !isForwardExpanded.value;
    };

    // 格式化嵌套消息时间
    const formatForwardTime = (timestamp) => {
      if (!timestamp) return '';
      const date = new Date(timestamp);
      if (isNaN(date.getTime())) return '';
      return date.toLocaleTimeString('zh-CN', {
        hour: '2-digit',
        minute: '2-digit',
        hour12: false
      });
    };
    
    // 判断是否是图片消息
    const isImageMessage = computed(() => {
      if (props.message.messageType === 'IMAGE') return true;
      if (content.value.includes('[CQ:image')) return true;
      // 兼容后端已下载到本地的图片路径
      if (/^\/images\/images\//.test(content.value)) return true;
      if (/^\/images\/[^/]+\.(jpg|jpeg|png|gif|webp|bmp)(\?.*)?$/i.test(content.value)) return true;
      if (/^\/uploads\/.*\.(jpg|jpeg|png|gif|webp|bmp)(\?.*)?$/i.test(content.value)) return true;
      return false;
    });

    // 判断是否是语音消息
    const isVoiceMessage = computed(() => {
      if (props.message.messageType === 'VOICE' || props.message.messageType === 'RECORD') return true;
      if (content.value.includes('[CQ:record')) return true;
      if (content.value.includes('[CQ:voice')) return true;
      // 检查是否是本地语音/音频文件路径
      if (/^\/images\/(voice|audio|audios)\//.test(content.value)) return true;
      if (/^\/uploads\/.*\.(mp3|wav|amr|ogg)(\?.*)?$/i.test(content.value)) return true;
      return false;
    });

    // 判断是否是视频消息
    const isVideoMessage = computed(() => {
      if (props.message.messageType === 'VIDEO') return true;
      if (content.value.includes('[CQ:video')) return true;
      // 兼容后端已下载到本地的视频路径
      if (/^\/images\/(video|videos)\//.test(content.value)) return true;
      if (/^\/uploads\/.*\.(mp4|webm|mov)(\?.*)?$/i.test(content.value)) return true;
      return false;
    });
    
    // 判断是否是表情消息
    const isFaceMessage = computed(() => {
      if (props.message.messageType === 'FACE') return true;
      if (content.value.includes('[CQ:face')) return true;
      return false;
    });
    
    // 判断是否是小程序分享消息
    const isMiniAppMessage = computed(() => {
      if (props.message.messageType === 'APP') return true;
      if (content.value.includes('[CQ:json')) return true;
      if (props.message.miniAppContent) return true;
      return false;
    });
    
    // 从 JSON 对象提取小程序信息
    const extractMiniAppInfo = (json) => {
      if (!json) return {};
      
      let result = {};
      
      if (json.meta && json.meta.detail_1) {
        const meta = json.meta.detail_1;
        result = {
          title: meta.title || meta.desc || '小程序',
          desc: meta.desc || meta.title || '',
          icon: meta.icon || meta.thumb || '',
          preview: meta.preview || meta.image || '',
          url: meta.url || meta.qqdocurl || meta.jumpUrl || '',
          appName: meta.title || json.host?.nick || '小程序'
        };
      } else if (json.meta && json.meta.news) {
        const news = json.meta.news;
        result = {
          title: news.title || '新闻',
          desc: news.desc || '',
          icon: news.tagIcon || news.icon || '',
          preview: news.preview || news.image || '',
          url: news.jumpUrl || news.url || '',
          appName: news.tag || '新闻'
        };
      } else if (json.app && json.app.meta) {
        const meta = json.app.meta;
        result = {
          title: meta.title || json.app.name || '小程序',
          desc: meta.description || meta.desc || '',
          icon: meta.icon || meta.thumb || '',
          preview: meta.preview || '',
          url: meta.url || json.app.url || '',
          appName: meta.title || json.app.name || '小程序'
        };
      } else if (json.data) {
        const data = json.data;
        result = {
          title: data.title || data.appName || '小程序',
          desc: data.desc || data.description || '',
          icon: data.icon || '',
          preview: data.preview || '',
          url: data.url || data.jumpUrl || '',
          appName: data.source || data.appName || '小程序'
        };
      } else {
        result = {
          title: json.title || json.appName || '小程序',
          desc: json.desc || json.description || '',
          icon: json.icon || '',
          preview: json.preview || '',
          url: json.url || json.jumpUrl || '',
          appName: json.source || '小程序'
        };
      }
      
      return result;
    };

    // 解析小程序数据
    const miniAppData = computed(() => {
      let miniAppContent = props.message.miniAppContent;
      
      // 如果 miniAppContent 为空，尝试从 content 中的 [CQ:json,data=...] 提取
      if (!miniAppContent && content.value && content.value.includes('[CQ:json')) {
        const dataMatch = content.value.match(/data=({.+})/);
        if (dataMatch && dataMatch[1]) {
          miniAppContent = dataMatch[1];
        }
      }

      if (!miniAppContent) {
        // 如果 content 中包含 json 字符串，尝试提取
        if (content.value) {
          const jsonMatch = content.value.match(/{.+}/);
          if (jsonMatch) {
            try {
              const json = JSON.parse(decodeHtmlEntities(jsonMatch[0]));
              return extractMiniAppInfo(json);
            } catch (e) {
              // 忽略解析错误
            }
          }
        }
        return {};
      }
      
      try {
        let jsonStr = decodeHtmlEntities(miniAppContent);
        logger.debug('解码后的小程序JSON长度:', jsonStr.length);

        // 尝试解析 JSON，如果失败则尝试提取第一个有效的 JSON 对象
        let json = null;
        try {
          json = JSON.parse(jsonStr);
        } catch (parseErr) {
          // 可能是字符串包含多个 JSON 或额外字符，尝试提取第一个 {} 包裹的对象
          const firstBrace = jsonStr.indexOf('{');
          if (firstBrace >= 0) {
            let braceCount = 0;
            let endPos = -1;
            for (let i = firstBrace; i < jsonStr.length; i++) {
              if (jsonStr[i] === '{') braceCount++;
              else if (jsonStr[i] === '}') {
                braceCount--;
                if (braceCount === 0) {
                  endPos = i;
                  break;
                }
              }
            }
            if (endPos > firstBrace) {
              const extracted = jsonStr.substring(firstBrace, endPos + 1);
              logger.debug('尝试提取第一个JSON对象:', extracted.substring(0, 200));
              json = JSON.parse(extracted);
            }
          }
          if (!json) throw parseErr;
        }
        return extractMiniAppInfo(json);
      } catch (e) {
        logger.error('解析小程序数据失败:', e);
        logger.error('原始数据前200字符:', miniAppContent.substring(0, 200));
        logger.error('原始数据长度:', miniAppContent.length);
        return {};
      }
    });
    
    const openMiniAppUrl = () => {
      let url = miniAppData.value.url;

      if (!url) {
        url = content.value.match(/url=([^,\]]+)/);
        if (url && url[1]) {
          url = url[1].replace(/&amp;/g, '&').trim();
        } else {
          url = '';
        }
      }

      logger.debug('小程序URL:', url);
      if (url) {
        if (!url.startsWith('http://') && !url.startsWith('https://')) {
          url = 'https://' + url;
        }
        window.open(url, '_blank');
      } else {
        logger.warn('小程序URL为空');
        showToast('无法获取小程序链接', 'info');
      }
    };
    
    const onMiniAppIconError = (e) => {
      e.target.style.display = 'none';
    };
    
    const onMiniAppPreviewError = (e) => {
      e.target.parentElement.style.display = 'none';
    };
    
    // 获取本地媒体URL
    const getLocalMediaUrl = (path) => {
      if (!path) return '';
      if (path.startsWith('http')) return path;
      if (path.startsWith('/images/') || path.startsWith('/uploads/')) {
        return `http://localhost:8081${path}`;
      }
      return path;
    };

    // 从 CQ 码或本地路径中提取媒体 URL（用于被引用内容可能是 CQ 码的场景）
    const extractMediaUrl = (text) => {
      if (!text) return '';
      const trimmed = text.trim();
      if (trimmed.startsWith('http')) return trimmed;
      if (trimmed.startsWith('/images/') || trimmed.startsWith('/uploads/')) {
        return `http://localhost:8081${trimmed}`;
      }
      // 兼容被引用内容里仍包裹 CQ 码的情况
      const cqMatch = trimmed.match(/\[CQ:(?:image|video|record|voice)[^\]]*\]/);
      if (cqMatch) {
        const cq = cqMatch[0];
        const backtickMatch = cq.match(/url=`([^`]+)`/);
        if (backtickMatch && backtickMatch[1]) {
          const url = backtickMatch[1].replace(/&amp;/g, '&').trim();
          if (url) return url;
        }
        const urlMatch = cq.match(/url=([^,\]]+)/);
        if (urlMatch && urlMatch[1]) {
          const url = urlMatch[1].replace(/&amp;/g, '&').trim();
          if (url) return url;
        }
        const fileMatch = cq.match(/file=([^,\]]+)/);
        if (fileMatch && fileMatch[1]) {
          const file = fileMatch[1].replace(/&amp;/g, '&').trim();
          if (file.startsWith('http')) return file;
          if (file.startsWith('/images/') || file.startsWith('/uploads/')) {
            return `http://localhost:8081${file}`;
          }
        }
      }
      return '';
    };
    
    // 提取图片URL
    const imageUrl = computed(() => {
      // 优先使用后端下载的本地 URL（合并转发子消息）
      if (props.message.localUrl) {
        return getLocalMediaUrl(props.message.localUrl);
      }
      const c = content.value;
      // 如果已经是本地路径
      if (/^\/images\//.test(c) || /^\/uploads\//.test(c)) {
        return getLocalMediaUrl(c);
      }
      // 从CQ码提取，优先 url，其次 file
      const backtickMatch = c.match(/url=`([^`]+)`/);
      if (backtickMatch && backtickMatch[1]) {
        const url = backtickMatch[1].replace(/&amp;/g, '&').trim();
        if (url) return url;
      }
      const urlMatch = c.match(/url=([^,\]]+)/);
      if (urlMatch && urlMatch[1]) {
        const url = urlMatch[1].replace(/&amp;/g, '&').trim();
        if (url) return url;
      }
      const fileMatch = c.match(/file=([^,\]]+)/);
      if (fileMatch && fileMatch[1]) {
        const file = fileMatch[1].replace(/&amp;/g, '&').trim();
        if (file) return getLocalMediaUrl(file);
      }
      return '';
    });

    // 提取语音URL
    const voiceUrl = computed(() => {
      // 优先使用后端下载的本地 URL（合并转发子消息）
      if (props.message.localUrl) {
        return getLocalMediaUrl(props.message.localUrl);
      }
      const c = content.value;
      // 如果已经是本地路径
      if (/^\/images\//.test(c) || /^\/uploads\//.test(c)) {
        return getLocalMediaUrl(c);
      }
      // 如果是完整URL
      if (c.startsWith('http')) {
        return c;
      }
      // 从CQ码提取，优先 url，其次 file
      const backtickMatch = c.match(/url=`([^`]+)`/);
      if (backtickMatch && backtickMatch[1]) {
        const url = backtickMatch[1].replace(/&amp;/g, '&').trim();
        if (url) return url;
      }
      const urlMatch = c.match(/url=([^,\]]+)/);
      if (urlMatch && urlMatch[1]) {
        const url = urlMatch[1].replace(/&amp;/g, '&').trim();
        if (url) return url;
      }
      const fileMatch = c.match(/file=([^,\]]+)/);
      if (fileMatch && fileMatch[1]) {
        const file = fileMatch[1].replace(/&amp;/g, '&').trim();
        if (file) return getLocalMediaUrl(file);
      }
      return '';
    });

    // 提取语音文件大小
    const voiceSize = computed(() => {
      const c = content.value;
      const sizeMatch = c.match(/file_size=(\d+)/);
      return sizeMatch ? parseInt(sizeMatch[1]) : 0;
    });

    // 提取视频URL
    const videoUrl = computed(() => {
      // 优先使用后端下载的本地 URL（合并转发子消息）
      if (props.message.localUrl) {
        return getLocalMediaUrl(props.message.localUrl);
      }
      const c = content.value;
      // 如果已经是本地路径
      if (/^\/images\//.test(c) || /^\/uploads\//.test(c)) {
        return getLocalMediaUrl(c);
      }
      // 从CQ码提取，优先 url，其次 file
      const backtickMatch = c.match(/url=`([^`]+)`/);
      if (backtickMatch && backtickMatch[1]) {
        const url = backtickMatch[1].replace(/&amp;/g, '&').trim();
        if (url) return url;
      }
      const urlMatch = c.match(/url=([^,\]]+)/);
      if (urlMatch && urlMatch[1]) {
        const url = urlMatch[1].replace(/&amp;/g, '&').trim();
        if (url) return url;
      }
      const fileMatch = c.match(/file=([^,\]]+)/);
      if (fileMatch && fileMatch[1]) {
        const file = fileMatch[1].replace(/&amp;/g, '&').trim();
        if (file) return getLocalMediaUrl(file);
      }
      return '';
    });
    
    // 判断视频内容是否其实是预览图(后端在视频原片缺失时落盘 QQ 缩略图)
    const videoPreviewImage = computed(() => /\.(png|jpe?g|webp|gif|bmp)(\?.*)?$/i.test(videoUrl.value || ''));

    // 提取视频文件大小
    const videoSize = computed(() => {
      const c = content.value;
      const sizeMatch = c.match(/file_size=(\d+)/);
      return sizeMatch ? parseInt(sizeMatch[1]) : 0;
    });
    
    // 提取表情ID
    const faceId = computed(() => {
      const c = content.value;
      const idMatch = c.match(/id=(\d+)/);
      return idMatch ? idMatch[1] : '1';
    });
    
    // 表情图片URL（使用QQ表情）
    const faceUrl = computed(() => {
      // 使用QQ表情的URL，这里使用一个示例
      // 实际项目中可能需要自己准备表情图片
      return `https://qqface.netlify.app/static/face/${faceId.value}.gif`;
    });
    
    // 显示内容（过滤掉CQ码后的纯文本，但保留 @某人）
    const displayContent = computed(() => {
      let text = content.value;
      // 先把 @ 消息转换为带样式的 HTML 标签
      text = formatAtCqCode(text, true);
      // 再移除其它 CQ 码
      text = text.replace(/\[CQ:[^\]]+\]/g, '').trim();
      return text || '[不支持的消息类型]';
    });
    
    // formatFileSize 从 services/api 导入
    
    // 语音播放控制（使用 new Audio 避免进入聊天时自动加载触发错误提示）
    const isConverting = ref(false);
    const convertedVoiceUrl = ref('');
    const toggleVoicePlay = async () => {
      let url = voiceUrl.value;
      if (!url) {
        showToast('音频地址为空', 'error');
        return;
      }

      // 停止当前播放
      if (currentAudio) {
        currentAudio.pause();
        currentAudio = null;
      }

      if (isPlaying.value) {
        isPlaying.value = false;
        return;
      }

      // 检查是否为浏览器不支持的格式（.amr/.silk），需要转码
      const isUnsupportedFormat = /\.(amr|silk)(\?.*)?$/i.test(url);
      if (isUnsupportedFormat) {
        // 如果已转码过，直接使用转码后的 URL
        if (convertedVoiceUrl.value) {
          url = convertedVoiceUrl.value;
        } else {
          isConverting.value = true;
          showToast('正在转码语音格式...', 'info');
          try {
            // 提取原始相对路径（去除 domain 部分）
            let relativePath = url;
            if (url.startsWith('http')) {
              const urlObj = new URL(url);
              relativePath = urlObj.pathname;
            }
            const data = await systemApi.convertVoice(relativePath);
            if (data && data.audioUrl) {
              // 使用转码后的 URL
              const newUrl = getLocalMediaUrl(data.audioUrl);
              convertedVoiceUrl.value = newUrl;
              url = newUrl;
              showToast('语音转码成功', 'success');
            } else {
              showToast(data?.message || '语音转码失败', 'error');
              isConverting.value = false;
              return;
            }
          } catch (err) {
            logger.error('语音转码失败:', err);
            showToast('语音转码失败: ' + (err.message || '未知错误'), 'error');
            isConverting.value = false;
            return;
          }
          isConverting.value = false;
        }
      }

      const audio = new Audio(url);
      currentAudio = audio;

      audio.addEventListener('loadedmetadata', () => {
        voiceDuration.value = Math.round(audio.duration) || 0;
      });

      audio.addEventListener('ended', () => {
        isPlaying.value = false;
        currentAudio = null;
      });

      audio.addEventListener('error', (e) => {
        const errorCode = audio.error?.code;
        logger.warn('语音加载错误:', url, 'code:', errorCode);
        if (errorCode === 2 || errorCode === 4 || errorCode === 1) {
          showToast('音频已删除', 'info');
        } else {
          showToast('语音加载失败，请稍后重试', 'error');
        }
        isPlaying.value = false;
        currentAudio = null;
      });

      audio.play().then(() => {
        isPlaying.value = true;
      }).catch(e => {
        logger.error('播放失败:', e);
        logger.error('音频URL:', url);
        showToast('语音播放失败: ' + e.message, 'error');
        isPlaying.value = false;
        currentAudio = null;
      });
    };
    
    // 格式化时长
    const formatDuration = (seconds) => {
      if (!seconds || isNaN(seconds)) return '0';
      return Math.round(seconds);
    };
    
    return {
      isReplyMessage,
      isForwardMessage,
      isMediaFailed,
      mediaFailLabel,
      mediaLoadingLabel,
      replyTarget,
      replyPreview,
      replyText,
      onReplyClick,
      forwardSummary,
      forwardFallbackText,
      forwardMessages,
      isForwardExpanded,
      toggleForwardExpanded,
      formatForwardTime,
      replyPreviewType,
      replyMediaUrl,
      replyImageError,
      onReplyImageError,
      isImageMessage,
      isVoiceMessage,
      isVideoMessage,
      isFaceMessage,
      isMiniAppMessage,
      miniAppData,
      openMiniAppUrl,
      onMiniAppIconError,
      onMiniAppPreviewError,
      imageError,
      videoError,
      imageUrl,
      voiceUrl,
      voiceSize,
      voiceDuration,
      videoUrl,
      videoSize,
      videoPreviewImage,
      faceUrl,
      faceId,
      displayContent,
      formatFileSize,
      formatDuration,
      openImage,
      toggleVoicePlay,
      onImageError,
      onVideoError,
      isPlaying,
      isConverting
    };
  }
};
</script>

<style scoped>
.message-content {
  word-break: break-word;
}

:deep(.message-text) {
  line-height: 1.5;
  white-space: pre-wrap;
  text-align: left;
}

/* 合并转发消息内部的文本强制使用深色并左对齐，避免继承外层气泡白色/居中 */
.forward-list :deep(.message-text),
.forward-list :deep(.message-content) {
  color: #1a1a1a !important;
  text-align: left !important;
  display: block;
  width: 100%;
}

/* 回复/引用消息 */
.message-reply-wrapper {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.message-reply {
  display: flex;
  gap: 8px;
  padding: 8px 10px;
  background-color: rgba(0, 0, 0, 0.04);
  border-radius: 6px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.message-reply:hover {
  background-color: rgba(0, 0, 0, 0.08);
}

.reply-bar {
  width: 3px;
  flex-shrink: 0;
  background-color: #3498db;
  border-radius: 2px;
}

.reply-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.reply-sender {
  font-size: 13px;
  color: #3498db;
  font-weight: 500;
}

.reply-preview {
  font-size: 12px;
  color: #666;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reply-preview-image {
  max-width: 120px;
  max-height: 80px;
  border-radius: 4px;
  object-fit: cover;
  cursor: zoom-in;
  display: block;
}

.reply-preview-placeholder {
  color: #999;
  font-style: italic;
}

.reply-text {
  line-height: 1.5;
  white-space: pre-wrap;
}

/* 聊天记录/合并转发消息 */
.message-forward {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  background-color: rgba(255, 255, 255, 0.6);
  border: 1px solid rgba(0, 0, 0, 0.06);
  border-radius: 10px;
  max-width: 280px;
  cursor: pointer;
  transition: background-color 0.2s;
}

.message-forward:hover {
  background-color: rgba(255, 255, 255, 0.9);
}

.message-forward.is-empty {
  opacity: 0.9;
}

.message-forward.is-empty:hover {
  background-color: rgba(255, 255, 255, 0.75);
}

.forward-empty {
  padding: 8px 10px;
  font-size: 12px;
  color: #999;
  background-color: rgba(0, 0, 0, 0.03);
  border-radius: 6px;
  text-align: center;
}

.forward-fallback {
  color: #666;
  font-weight: 500;
  margin-bottom: 2px;
}

.forward-hint {
  font-size: 11px;
  color: #bbb;
  margin-top: 4px;
}

.forward-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
  cursor: pointer;
}

.forward-title {
  font-size: 14px;
  font-weight: 600;
  color: #2c3e50;
}

.forward-summary {
  font-size: 12px;
  color: #666;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.forward-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-left: 10px;
  border-left: 2px solid #ddd;
  margin-left: 4px;
}

.forward-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.forward-item-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
}

.forward-item-sender {
  color: #3498db;
  font-weight: 500;
}

.forward-item-time {
  color: #999;
}

.forward-toggle-btn {
  align-self: flex-start;
  padding: 4px 10px;
  font-size: 12px;
  color: #3498db;
  background-color: transparent;
  border: 1px solid #3498db;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
}

.forward-toggle-btn:hover {
  color: #fff;
  background-color: #3498db;
}

.message-image img {
  max-width: 200px;
  max-height: 200px;
  border-radius: 8px;
  cursor: pointer;
  transition: transform 0.2s;
}

.message-image img:hover {
  transform: scale(1.02);
}

.message-voice {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  background-color: #f0f0f0;
  border-radius: 8px;
}

.message-voice .voice-icon {
  font-size: 24px;
}

.message-voice .voice-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #666;
}

.message-voice .voice-player {
  width: 100%;
  height: 30px;
}

/* QQ样式语音消息 */
.message-voice-qq {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background-color: #95ec69;
  border-radius: 8px;
  cursor: pointer;
  min-width: 80px;
  max-width: 200px;
  transition: background-color 0.2s;
}

.message-voice-qq:hover {
  background-color: #85d95e;
}

.voice-wave {
  display: flex;
  align-items: center;
  gap: 2px;
}

.wave-dot {
  width: 3px;
  height: 12px;
  background-color: #333;
  border-radius: 1px;
}

/* 只有播放时才显示动画 */
.voice-wave.is-playing .wave-dot {
  animation: wave 1s ease-in-out infinite;
}

.voice-wave.is-playing .wave-dot:nth-child(2) {
  animation-delay: 0.1s;
  height: 16px;
}

.voice-wave.is-playing .wave-dot:nth-child(3) {
  animation-delay: 0.2s;
  height: 10px;
}

@keyframes wave {
  0%, 100% { transform: scaleY(0.5); }
  50% { transform: scaleY(1); }
}

.voice-duration {
  font-size: 14px;
  color: #333;
  margin-left: auto;
}

.message-video {
  border-radius: 8px;
  overflow: hidden;
  max-width: 280px;
}

.message-video .video-player {
  width: 100%;
  border-radius: 8px;
  display: block;
}

/* 视频原片未缓存时展示 QQ 缩略图预览 */
.message-video .video-preview-wrap {
  position: relative;
  display: block;
}

.message-video .video-preview-img {
  width: 100%;
  max-height: 320px;
  object-fit: contain;
  border-radius: 8px;
  display: block;
  cursor: zoom-in;
  background-color: #000;
}

.message-video .video-preview-label {
  position: absolute;
  left: 8px;
  bottom: 8px;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  color: #fff;
  background: rgba(0, 0, 0, 0.55);
}

.message-video .video-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 24px;
  background-color: #f0f0f0;
  border-radius: 8px;
}

.video-icon-svg {
  width: 40px;
  height: 40px;
  color: #999;
}

.message-video .video-label {
  font-size: 13px;
  color: #666;
}

.message-video .video-size {
  font-size: 11px;
  color: #999;
}

.message-face {
  display: inline-block;
}

.message-face .face-text {
  color: #666;
  font-style: italic;
  background-color: #f0f0f0;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
}

.media-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 24px;
  background-color: #f5f5f5;
  border-radius: 8px;
  border: 1px dashed #ddd;
  min-width: 160px;
  cursor: not-allowed;
}

.placeholder-label {
  font-size: 12px;
  color: #999;
}

/* 媒体加载中占位符 */
.media-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 24px;
  background-color: #f5f5f5;
  border-radius: 8px;
  border: 1px dashed #ccc;
  min-width: 160px;
}

.loading-spinner {
  width: 28px;
  height: 28px;
  border: 3px solid #ddd;
  border-top-color: #3498db;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

.loading-label {
  font-size: 12px;
  color: #999;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 小程序分享消息 */
.message-mini-app {
  max-width: 320px;
}

.mini-app-card {
  display: flex;
  flex-direction: column;
  background-color: #fff;
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.2s;
  overflow: hidden;
}

.mini-app-card:hover {
  background-color: #f8f9fa;
  border-color: rgba(0, 0, 0, 0.12);
}

.mini-app-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
}

.mini-app-icon {
  flex-shrink: 0;
  width: 48px;
  height: 48px;
  border-radius: 8px;
  overflow: hidden;
  background-color: #f0f0f0;
}

.mini-app-icon img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.mini-app-icon-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.mini-app-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.mini-app-title {
  font-size: 14px;
  font-weight: 500;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mini-app-desc {
  font-size: 12px;
  color: #666;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mini-app-source {
  font-size: 11px;
  color: #999;
}

.mini-app-arrow {
  flex-shrink: 0;
  opacity: 0.6;
}

.mini-app-preview {
  width: 100%;
}

.mini-app-preview img {
  width: 100%;
  max-height: 200px;
  object-fit: cover;
  cursor: pointer;
  transition: transform 0.2s;
  display: block;
}

.mini-app-preview img:hover {
  transform: scale(1.02);
}
</style>
