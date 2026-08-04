<template>
  <div v-if="visible" class="gt-mask" @click.self="handleClose">
    <div class="gt-dialog">
      <div class="gt-header">
        <div>
          <div class="gt-title">群类型设置</div>
          <div class="gt-sub">
            设置正确的群类型可让 AI 分析输出更有针对性的「融入圈子」建议
            <span v-if="groupName" class="gt-gn"> · {{ groupName }}</span>
          </div>
        </div>
        <button class="gt-close" @click="handleClose" title="关闭">
          <Icon name="close" :size="16" />
        </button>
      </div>

      <!-- 当前类型展示 -->
      <div v-if="currentTypeKey" class="gt-current">
        <span class="gt-current-label">当前：</span>
        <span :class="['gt-pill', `gt-pill-${currentTypeKey}`]">
          {{ GT_MAP[currentTypeKey]?.icon }} {{ GT_MAP[currentTypeKey]?.label }}
        </span>
      </div>

      <!-- 类型选择网格 -->
      <div class="gt-grid">
        <button
          v-for="opt in groupTypeOptions"
          :key="opt.value"
          :class="['gt-card', { active: opt.value === currentTypeKey, disabled: isSaving }]"
          :disabled="isSaving"
          @click="selectType(opt.value)"
        >
          <div :class="['gt-icon', `gt-icon-${opt.value}`]">{{ opt.icon }}</div>
          <div class="gt-info">
            <div class="gt-name">{{ opt.label }}</div>
            <div class="gt-desc">{{ opt.desc }}</div>
            <div class="gt-rec">推荐：{{ opt.recLabel }}</div>
          </div>
          <div v-if="opt.value === currentTypeKey" class="gt-check">
            <Icon name="check-circle" :size="18" />
          </div>
        </button>
      </div>

      <!-- AI 识别区域 -->
      <div class="gt-ai-box">
        <div class="gt-ai-left">
          <div class="gt-ai-title">🤖 让 AI 自动识别</div>
          <div class="gt-ai-desc">基于最近群聊内容推断群类型（消耗 1 积分）</div>
          <div v-if="recognized && recognized.groupType" class="gt-ai-result">
            <span>识别结果：</span>
            <span :class="['gt-pill', `gt-pill-${recognized.groupType}`]">
              {{ GT_MAP[recognized.groupType]?.icon }} {{ GT_MAP[recognized.groupType]?.label }}
            </span>
            <span v-if="typeof recognized.confidence === 'number'" class="gt-ai-conf">
              置信度 {{ (recognized.confidence * 100).toFixed(0) }}%
            </span>
          </div>
          <div v-if="recognized && recognized.reason" class="gt-ai-reason">
            <span>判断依据：</span>{{ recognized.reason }}
          </div>
        </div>
        <button
          :class="['gt-ai-btn', { loading: isRecognizing }]"
          :disabled="isRecognizing || !groupId"
          @click="triggerRecognize"
        >
          <span v-if="isRecognizing" class="gt-spin"></span>
          {{ isRecognizing ? '识别中...' : 'AI 识别群类型' }}
        </button>
      </div>

      <!-- 操作按钮 -->
      <div class="gt-actions">
        <button class="gt-btn gt-btn-ghost" :disabled="isSaving" @click="handleClose">取消</button>
        <button
          v-if="recognized && recognized.groupType && recognized.groupType !== currentTypeKey"
          class="gt-btn gt-btn-primary"
          :disabled="isSaving"
          @click="applyRecognized"
        >
          采用识别结果
        </button>
        <button
          class="gt-btn gt-btn-primary"
          :disabled="isSaving || !pendingTypeKey || pendingTypeKey === currentTypeKey"
          @click="saveType"
        >
          {{ isSaving ? '保存中...' : '保存选择' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, computed, watch } from 'vue';
import Icon from './Icon.vue';
import { messageApi } from '../services/api';
import { showToast } from './Toast.vue';

// 群类型元数据（与后端 GroupType 枚举 + GROUP_TYPE_RECOMMEND 保持一致）
const GT_MAP = {
  GAME:   { label: '游戏群', icon: '🎮', color: '#6366f1', desc: '讨论游戏攻略、组队、赛事、版本更新',
            rec: ['meme-dictionary', 'integration-guide', 'summary'], recLabel: '梗词典 / 融入指南 / 速览' },
  STUDY:  { label: '学习群', icon: '📚', color: '#10b981', desc: '科目学习、课程答疑、作业讨论、资源分享',
            rec: ['summary', 'topic-trend', 'integration-guide'], recLabel: '速览 / 话题趋势 / 融入指南' },
  WORK:   { label: '工作群', icon: '💼', color: '#f59e0b', desc: '项目讨论、任务分配、职场沟通协作',
            rec: ['summary', 'integration-guide', 'social-graph'], recLabel: '速览 / 融入指南 / 社交图谱' },
  HOBBY:  { label: '兴趣群', icon: '🎨', color: '#ec4899', desc: '动漫、运动、音乐、手工等兴趣爱好',
            rec: ['meme-dictionary', 'persona-match', 'summary'], recLabel: '梗词典 / 人设匹配 / 速览' },
  LIFE:   { label: '生活群', icon: '☕', color: '#14b8a6', desc: '家庭、邻里、日常琐事、生活经验分享',
            rec: ['integration-guide', 'topic-trend', 'summary'], recLabel: '融入指南 / 话题趋势 / 速览' },
  SOCIAL: { label: '社交群', icon: '💬', color: '#3b82f6', desc: '闲聊交友、聚会活动、拓展人脉',
            rec: ['social-graph', 'persona-match', 'integration-guide'], recLabel: '社交图谱 / 人设匹配 / 融入指南' },
  OTHER:  { label: '其他群', icon: '🏷️', color: '#6b7280', desc: '暂时无法归类或混合主题的群',
            rec: ['summary', 'integration-guide'], recLabel: '速览 / 融入指南' }
};

export default {
  name: 'GroupTypeSelector',
  components: { Icon },
  props: {
    visible: { type: Boolean, default: false },
    groupId: { type: String, default: null },
    ownerQq: { type: String, default: null },
    groupName: { type: String, default: null },
    initialGroupType: { type: String, default: null }
  },
  emits: ['update:visible', 'save', 'close'],
  setup(props, { emit }) {
    const currentTypeKey = ref(props.initialGroupType || 'OTHER');
    const pendingTypeKey = ref(props.initialGroupType || null);
    const isSaving = ref(false);
    const isRecognizing = ref(false);
    const recognized = ref(null);

    const groupTypeOptions = computed(() =>
      Object.entries(GT_MAP).map(([value, meta]) => ({
        value, label: meta.label, icon: meta.icon,
        desc: meta.desc, recLabel: meta.recLabel
      }))
    );

    watch(() => props.initialGroupType, (v) => {
      currentTypeKey.value = v || 'OTHER';
      pendingTypeKey.value = v || null;
    });
    watch(() => props.visible, (v) => {
      if (v) {
        currentTypeKey.value = props.initialGroupType || 'OTHER';
        pendingTypeKey.value = props.initialGroupType || null;
        recognized.value = null;
      }
    });

    const selectType = (val) => { pendingTypeKey.value = val; };

    const triggerRecognize = async () => {
      if (!props.groupId) return;
      isRecognizing.value = true;
      try {
        const res = await messageApi.recognizeGroupType(props.groupId, true);
        if (res && res.status === 'ok') {
          const { recognizedType, confidence, reason } = res;
          if (recognizedType && GT_MAP[recognizedType]) {
            recognized.value = { groupType: recognizedType, confidence, reason };
            showToast(`AI 识别为「${GT_MAP[recognizedType].label}」，可点击采用结果`, 'success');
          } else {
            recognized.value = { groupType: 'OTHER', confidence: confidence ?? 0, reason: reason || '无法判断' };
            showToast('AI 未能识别出明确类型，已默认设为其他群', 'info');
          }
        } else {
          showToast(res?.message || '识别失败，请稍后重试', 'error');
        }
      } catch (e) {
        // 积分不足 / 未登录等：后端会带 errorCode
        if (e && e.errorCode === 'INSUFFICIENT_CREDITS') {
          showToast(`积分不足（需要 ${(e.details?.need || 0)} 积分），无法执行 AI 识别`, 'warning');
        } else {
          showToast(e?.message || '识别失败，请稍后重试', 'error');
        }
      } finally {
        isRecognizing.value = false;
      }
    };

    const applyRecognized = () => {
      if (recognized.value && recognized.value.groupType) {
        pendingTypeKey.value = recognized.value.groupType;
      }
    };

    const saveType = async () => {
      const toSave = pendingTypeKey.value;
      if (!toSave || toSave === currentTypeKey.value) {
        handleClose();
        return;
      }
      isSaving.value = true;
      try {
        const res = await messageApi.setGroupType(props.groupId, toSave);
        if (res && res.status === 'ok') {
          currentTypeKey.value = toSave;
          const meta = GT_MAP[toSave] || GT_MAP.OTHER;
          showToast(`已设置为「${meta.label}」`, 'success');
          emit('save', { groupType: toSave, groupId: props.groupId, ownerQq: props.ownerQq });
          handleClose();
        } else {
          showToast(res?.message || '保存失败', 'error');
        }
      } catch (e) {
        showToast(e?.message || '保存失败，请稍后重试', 'error');
      } finally {
        isSaving.value = false;
      }
    };

    const handleClose = () => {
      emit('update:visible', false);
      emit('close');
    };

    return {
      GT_MAP,
      currentTypeKey,
      pendingTypeKey,
      isSaving,
      isRecognizing,
      recognized,
      groupTypeOptions,
      selectType,
      triggerRecognize,
      applyRecognized,
      saveType,
      handleClose
    };
  }
};
</script>

<style scoped>
.gt-mask {
  position: fixed; inset: 0;
  background: rgba(0,0,0,0.45);
  z-index: 1100;
  display: flex; align-items: center; justify-content: center;
  backdrop-filter: blur(2px);
}
.gt-dialog {
  width: min(560px, 94vw);
  max-height: 90vh;
  background: white;
  border-radius: 14px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.22);
  display: flex; flex-direction: column;
  overflow: hidden;
  animation: gt-pop 0.22s ease-out;
}
@keyframes gt-pop {
  from { opacity: 0; transform: translateY(6px) scale(0.98); }
  to   { opacity: 1; transform: translateY(0)   scale(1);    }
}
.gt-header {
  padding: 18px 22px 14px;
  border-bottom: 1px solid #eef0f3;
  display: flex; justify-content: space-between; align-items: flex-start;
  background: linear-gradient(180deg, #f8faff 0%, #fff 100%);
}
.gt-title { font-size: 18px; font-weight: 700; color: #1f2937; margin-bottom: 4px; }
.gt-sub   { font-size: 13px; color: #6b7280; }
.gt-gn    { color: #5b5bd6; font-weight: 500; }
.gt-close {
  width: 30px; height: 30px; border-radius: 8px;
  border: none; background: transparent; color: #6b7280;
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  transition: all 0.15s;
}
.gt-close:hover { background: #f0f1f5; color: #1f2937; }

.gt-current {
  padding: 12px 22px 0;
  font-size: 13px;
  color: #374151;
  display: flex; align-items: center; gap: 8px;
}
.gt-current-label { color: #6b7280; }
.gt-pill {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 2px 10px; border-radius: 999px;
  font-size: 12px; font-weight: 600;
  color: white;
}
.gt-pill-GAME   { background: #6366f1; }
.gt-pill-STUDY  { background: #10b981; }
.gt-pill-WORK   { background: #f59e0b; }
.gt-pill-HOBBY  { background: #ec4899; }
.gt-pill-LIFE   { background: #14b8a6; }
.gt-pill-SOCIAL { background: #3b82f6; }
.gt-pill-OTHER  { background: #6b7280; }

.gt-grid {
  padding: 16px 22px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  overflow-y: auto;
}
.gt-card {
  text-align: left;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1.5px solid #e5e7eb;
  background: #fafbfc;
  cursor: pointer;
  display: flex; gap: 10px; align-items: flex-start;
  position: relative;
  transition: all 0.18s;
  color: inherit; font: inherit;
}
.gt-card:hover:not(:disabled):not(.active) {
  border-color: #c4c7ff; background: #f4f5ff; transform: translateY(-1px);
}
.gt-card.active {
  border-color: #5b5bd6;
  background: #eceefe;
  box-shadow: 0 0 0 3px rgba(91,91,214,0.12);
}
.gt-card.disabled, .gt-card:disabled { opacity: 0.55; cursor: not-allowed; }
.gt-icon {
  width: 40px; height: 40px; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  font-size: 20px; flex-shrink: 0;
  background: #eef2ff;
}
.gt-icon-GAME   { background: #eef0ff; }
.gt-icon-STUDY  { background: #ecfdf5; }
.gt-icon-WORK   { background: #fffbeb; }
.gt-icon-HOBBY  { background: #fdf2f8; }
.gt-icon-LIFE   { background: #ccfbf1; }
.gt-icon-SOCIAL { background: #dbeafe; }
.gt-icon-OTHER  { background: #f3f4f6; }
.gt-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.gt-name { font-size: 14px; font-weight: 600; color: #1f2937; }
.gt-desc { font-size: 11.5px; color: #6b7280; line-height: 1.45; }
.gt-rec  { font-size: 11px; color: #5b5bd6; margin-top: 4px; font-weight: 500; }
.gt-check {
  position: absolute; top: 8px; right: 8px;
  color: #5b5bd6;
}

.gt-ai-box {
  margin: 0 22px 0;
  padding: 14px 16px;
  border-radius: 12px;
  background: linear-gradient(135deg, #f5f8ff 0%, #fef3f9 100%);
  border: 1px solid #e3e7ff;
  display: flex; align-items: center; justify-content: space-between;
  gap: 14px;
}
.gt-ai-left { flex: 1; min-width: 0; }
.gt-ai-title { font-size: 13.5px; font-weight: 600; color: #1f2937; margin-bottom: 2px; }
.gt-ai-desc  { font-size: 12px; color: #6b7280; }
.gt-ai-result { font-size: 12.5px; color: #374151; margin-top: 8px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.gt-ai-conf   { font-size: 11.5px; color: #10b981; font-weight: 600; }
.gt-ai-reason { font-size: 12px; color: #6b7280; margin-top: 4px; line-height: 1.55; }
.gt-ai-reason span { color: #374151; font-weight: 500; }
.gt-ai-btn {
  padding: 9px 16px;
  border-radius: 10px;
  border: none;
  background: linear-gradient(135deg, #8a8fff 0%, #5b5bd6 100%);
  color: white;
  font-size: 13px; font-weight: 600;
  cursor: pointer;
  display: flex; align-items: center; gap: 6px;
  transition: all 0.18s;
  white-space: nowrap;
}
.gt-ai-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 6px 14px rgba(91,91,214,0.28); }
.gt-ai-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.gt-ai-btn.loading .gt-spin {
  width: 14px; height: 14px;
  border: 2px solid rgba(255,255,255,0.45);
  border-top-color: white;
  border-radius: 50%;
  animation: gt-spin 0.7s linear infinite;
}
@keyframes gt-spin { to { transform: rotate(360deg); } }

.gt-actions {
  padding: 16px 22px 22px;
  display: flex; gap: 10px; justify-content: flex-end;
  border-top: 1px solid #eef0f3;
  margin-top: 16px;
}
.gt-btn {
  padding: 8px 18px;
  border-radius: 10px;
  font-size: 13.5px; font-weight: 600;
  cursor: pointer;
  border: 1.5px solid transparent;
  transition: all 0.15s;
}
.gt-btn-ghost {
  background: white; border-color: #e5e7eb; color: #374151;
}
.gt-btn-ghost:hover:not(:disabled) { border-color: #c7cbd1; background: #f9fafb; }
.gt-btn-primary {
  background: #5b5bd6; color: white;
}
.gt-btn-primary:hover:not(:disabled) {
  background: #4f54c0; transform: translateY(-1px);
  box-shadow: 0 6px 14px rgba(91,91,214,0.3);
}
.gt-btn:disabled { opacity: 0.5; cursor: not-allowed; transform: none !important; box-shadow: none !important; }

@media (max-width: 560px) {
  .gt-grid { grid-template-columns: 1fr; }
  .gt-ai-box { flex-direction: column; align-items: stretch; }
  .gt-ai-btn { width: 100%; justify-content: center; }
}
</style>
