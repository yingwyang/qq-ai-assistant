<template>
  <div class="message-content">
    <!-- 图片消息 -->
    <div v-if="isImageMessage" class="message-image">
      <img :src="imageUrl" @click="openImage(imageUrl)" />
    </div>
    
    <!-- 语音消息 - QQ样式 -->
    <div v-else-if="isVoiceMessage" class="message-voice-qq" @click="toggleVoicePlay">
      <div class="voice-wave" :class="{ 'is-playing': isPlaying }">
        <span class="wave-dot"></span>
        <span class="wave-dot"></span>
        <span class="wave-dot"></span>
      </div>
      <span class="voice-duration">{{ formatDuration(voiceDuration) }}"</span>
      <audio v-if="voiceUrl" ref="voiceAudio" :src="voiceUrl" @ended="onVoiceEnded" @loadedmetadata="onVoiceLoaded" @error="onVoiceError"></audio>
    </div>
    
    <!-- 视频消息 -->
    <div v-else-if="isVideoMessage" class="message-video">
      <div class="video-icon">🎬</div>
      <div class="video-info">
        <span class="video-label">视频消息</span>
        <span class="video-size">{{ formatFileSize(videoSize) }}</span>
      </div>
      <video v-if="videoUrl" :src="videoUrl" controls class="video-player"></video>
    </div>
    
    <!-- 表情消息 -->
    <div v-else-if="isFaceMessage" class="message-face">
      <span class="face-text">[表情 {{ faceId }}]</span>
    </div>
    
    <!-- 文本消息 -->
    <div v-else class="message-text">{{ displayContent }}</div>
  </div>
</template>

<script>
import { computed, ref } from 'vue';
import { showToast } from './Toast.vue';

export default {
  name: 'MessageContent',
  props: {
    message: {
      type: Object,
      required: true
    }
  },
  setup(props) {
    const voiceAudio = ref(null);
    const isPlaying = ref(false);
    const voiceDuration = ref(0);
    const content = computed(() => props.message.content || '');
    
    // 判断是否是图片消息
    const isImageMessage = computed(() => {
      if (props.message.messageType === 'IMAGE') return true;
      if (content.value.includes('[CQ:image')) return true;
      return false;
    });
    
    // 判断是否是语音消息
    const isVoiceMessage = computed(() => {
      if (props.message.messageType === 'VOICE' || props.message.messageType === 'RECORD') return true;
      if (content.value.includes('[CQ:record')) return true;
      // 检查是否是本地语音文件路径
      if (content.value.startsWith('/images/voice/') || content.value.includes('/voice/')) return true;
      return false;
    });
    
    // 判断是否是视频消息
    const isVideoMessage = computed(() => {
      if (props.message.messageType === 'VIDEO') return true;
      if (content.value.includes('[CQ:video')) return true;
      return false;
    });
    
    // 判断是否是表情消息
    const isFaceMessage = computed(() => {
      if (props.message.messageType === 'FACE') return true;
      if (content.value.includes('[CQ:face')) return true;
      return false;
    });
    
    // 获取本地媒体URL
    const getLocalMediaUrl = (path) => {
      if (!path) return '';
      if (path.startsWith('http')) return path;
      if (path.startsWith('/images/')) {
        return `http://localhost:8081${path}`;
      }
      return path;
    };
    
    // 提取图片URL
    const imageUrl = computed(() => {
      const c = content.value;
      // 如果已经是本地路径
      if (c.startsWith('/images/images/')) {
        return getLocalMediaUrl(c);
      }
      if (c.startsWith('/images/')) {
        return getLocalMediaUrl(c);
      }
      // 从CQ码提取
      const backtickMatch = c.match(/url=`([^`]+)`/);
      if (backtickMatch && backtickMatch[1]) {
        return backtickMatch[1].replace(/&amp;/g, '&').trim();
      }
      const urlMatch = c.match(/url=([^,\]]+)/);
      if (urlMatch && urlMatch[1]) {
        return urlMatch[1].replace(/&amp;/g, '&').trim();
      }
      return '';
    });
    
    // 提取语音URL
    const voiceUrl = computed(() => {
      const c = content.value;
      // 如果已经是本地路径（以/images/开头）
      if (c.startsWith('/images/')) {
        return getLocalMediaUrl(c);
      }
      // 如果是完整URL
      if (c.startsWith('http')) {
        return c;
      }
      // 从CQ码提取
      const backtickMatch = c.match(/url=`([^`]+)`/);
      if (backtickMatch && backtickMatch[1]) {
        return backtickMatch[1].replace(/&amp;/g, '&').trim();
      }
      const urlMatch = c.match(/url=([^,\]]+)/);
      if (urlMatch && urlMatch[1]) {
        return urlMatch[1].replace(/&amp;/g, '&').trim();
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
      const c = content.value;
      // 如果已经是本地路径
      if (c.startsWith('/images/video/')) {
        return getLocalMediaUrl(c);
      }
      // 从CQ码提取
      const backtickMatch = c.match(/url=`([^`]+)`/);
      if (backtickMatch && backtickMatch[1]) {
        return backtickMatch[1].replace(/&amp;/g, '&').trim();
      }
      const urlMatch = c.match(/url=([^,\]]+)/);
      if (urlMatch && urlMatch[1]) {
        return urlMatch[1].replace(/&amp;/g, '&').trim();
      }
      return '';
    });
    
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
    
    // 显示内容（过滤掉CQ码后的纯文本）
    const displayContent = computed(() => {
      return content.value
        .replace(/\[CQ:[^\]]+\]/g, '')
        .trim() || '[不支持的消息类型]';
    });
    
    // 格式化文件大小
    const formatFileSize = (bytes) => {
      if (bytes === 0) return '0 B';
      const k = 1024;
      const sizes = ['B', 'KB', 'MB', 'GB'];
      const i = Math.floor(Math.log(bytes) / Math.log(k));
      return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
    };
    
    // 打开图片预览
    const openImage = (url) => {
      if (url) {
        window.open(url, '_blank');
      }
    };
    
    // 语音播放控制
    const toggleVoicePlay = () => {
      console.log('语音播放点击, voiceUrl:', voiceUrl.value);
      if (!voiceAudio.value) {
        console.error('音频元素未找到');
        showToast('音频元素未加载，请刷新页面重试', 'error');
        return;
      }
      if (isPlaying.value) {
        voiceAudio.value.pause();
        isPlaying.value = false;
      } else {
        voiceAudio.value.play().then(() => {
          console.log('播放成功');
        }).catch(e => {
          console.error('播放失败:', e);
          console.error('音频URL:', voiceUrl.value);
          showToast('语音播放失败: ' + e.message, 'error');
        });
        isPlaying.value = true;
      }
    };
    
    const onVoiceEnded = () => {
      isPlaying.value = false;
    };
    
    const onVoiceLoaded = () => {
      if (voiceAudio.value) {
        voiceDuration.value = Math.round(voiceAudio.value.duration) || 0;
      }
    };
    
    const onVoiceError = (e) => {
      const errorCode = voiceAudio.value?.error?.code;
      const errorMessage = voiceAudio.value?.error?.message;
      
      console.error('音频加载错误:', e);
      console.error('音频URL:', voiceUrl.value);
      console.error('音频元素错误代码:', errorCode);
      console.error('音频元素错误信息:', errorMessage);
      
      // 错误码说明: 1=ABORTED, 2=NETWORK, 3=DECODE, 4=SRC_NOT_SUPPORTED
      let userMessage = '语音加载失败';
      switch(errorCode) {
        case 1:
          userMessage = '语音加载被中断';
          break;
        case 2:
          userMessage = '语音网络错误，请检查连接';
          break;
        case 3:
          userMessage = '语音解码错误，文件可能损坏';
          break;
        case 4:
          userMessage = '语音格式不支持或文件不存在';
          break;
      }
      
      showToast(userMessage, 'error');
      isPlaying.value = false;
    };
    
    // 格式化时长
    const formatDuration = (seconds) => {
      if (!seconds || isNaN(seconds)) return '0';
      return Math.round(seconds);
    };
    
    return {
      isImageMessage,
      isVoiceMessage,
      isVideoMessage,
      isFaceMessage,
      imageUrl,
      voiceUrl,
      voiceSize,
      voiceDuration,
      videoUrl,
      videoSize,
      faceUrl,
      displayContent,
      formatFileSize,
      formatDuration,
      openImage,
      toggleVoicePlay,
      onVoiceEnded,
      onVoiceLoaded,
      onVoiceError,
      voiceAudio,
      isPlaying
    };
  }
};
</script>

<style scoped>
.message-content {
  word-break: break-word;
}

.message-text {
  line-height: 1.5;
  white-space: pre-wrap;
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
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  background-color: #f0f0f0;
  border-radius: 8px;
}

.message-video .video-icon {
  font-size: 24px;
}

.message-video .video-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #666;
}

.message-video .video-player {
  max-width: 100%;
  max-height: 200px;
  border-radius: 8px;
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
</style>
