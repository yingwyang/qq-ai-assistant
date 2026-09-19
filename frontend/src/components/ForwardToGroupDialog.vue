<template>
  <div v-if="show" class="fw-mask" @click="close">
    <div class="fw-dialog" @click.stop>
      <div class="fw-head">
        <strong>转发到群聊</strong>
        <button class="fw-close" @click="close">&times;</button>
      </div>

      <!-- 1. 选群 -->
      <div class="fw-section">
        <div class="fw-label">
          发送到
          <span class="fw-hint">有群可见性的用户都能发；以机器人账号身份发出</span>
        </div>
        <input v-model="groupSearch" class="fw-input" type="text" placeholder="搜索群名或群号" />
        <div class="fw-groups">
          <div v-if="loadingGroups" class="fw-empty">正在读取群列表…</div>
          <template v-else>
            <div
              v-for="g in filteredGroups"
              :key="g.groupId"
              class="fw-group"
              :class="{ active: targetGroupId === g.groupId }"
              @click="pickGroup(g.groupId)"
            >
              <span class="fw-group-name">{{ g.groupName || g.groupId }}</span>
              <span class="fw-group-id">{{ g.groupId }}</span>
            </div>
            <div v-if="filteredGroups.length === 0" class="fw-empty">
              没有匹配的群；也可以在下面直接填群号
            </div>
          </template>
        </div>
        <input
          v-model="manualGroupId"
          class="fw-input"
          type="text"
          placeholder="或直接填群号（需你有该群权限）"
          @input="targetGroupId = manualGroupId.trim()"
        />
        <div v-if="targetGroupId" class="fw-target">目标群：{{ targetGroupLabel }}</div>
        <div v-else class="fw-target missing">还没选群</div>
      </div>

      <!-- 2. 内容 -->
      <div class="fw-section">
        <div class="fw-label">
          内容
          <label class="fw-check">
            <input v-model="cleanMarkdown" type="checkbox" />
            清理 Markdown 标记（QQ 不渲染，去掉后更整齐）
          </label>
        </div>
        <textarea v-model="draft" class="fw-textarea" rows="8"></textarea>
        <div class="fw-meta">
          <span :class="{ over: finalText.length > MAX_LEN }">{{ finalText.length }} / {{ MAX_LEN }} 字</span>
          <span v-if="segments.length > 1" class="fw-split">将分 {{ segments.length }} 条发送</span>
        </div>
      </div>

      <div v-if="message" class="fw-message" :class="messageKind">{{ message }}</div>

      <div class="fw-foot">
        <button class="fw-btn" @click="close">取消</button>
        <button
          class="fw-btn primary"
          :disabled="sending || !targetGroupId || !finalText.trim()"
          @click="submit"
        >
          {{ sending ? '发送中…' : (segments.length > 1 ? `分 ${segments.length} 条发送` : '发送') }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, computed, watch } from 'vue';
import { messageApi } from '../services/api';
import { showToast } from './Toast.vue';

/** 单条消息上限（与后端 /groups/{id}/send 的限制一致） */
const MAX_LEN = 2000;
/** 分段时每段的目标长度（留出余量，避免正好卡在边界） */
const SEGMENT_LEN = 1800;

/**
 * 把 Markdown 文本清理成适合 QQ 纯文本显示的版本。
 * QQ 不渲染 Markdown，直接转发会看到一堆 ## 和 **，所以默认清理。
 */
export function markdownToPlainText(raw) {
  if (!raw) return '';
  let text = String(raw).replace(/\r\n/g, '\n');
  text = text.replace(/```[a-zA-Z0-9_-]*\n([\s\S]*?)```/g, '$1');   // 代码块：留内容去围栏
  text = text.replace(/`([^`]+)`/g, '$1');                          // 行内代码
  text = text.replace(/!\[([^\]]*)\]\(([^)]+)\)/g, '$1 $2');        // 图片
  text = text.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '$1 ($2)');        // 链接
  text = text.replace(/^\s{0,3}#{1,6}\s*/gm, '');                    // 标题
  text = text.replace(/^\s{0,3}>\s?/gm, '');                         // 引用
  text = text.replace(/^\s{0,3}[-*+]\s+/gm, '· ');                   // 无序列表
  text = text.replace(/\*\*([^*]+)\*\*/g, '$1').replace(/__([^_]+)__/g, '$1');
  text = text.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1$2');
  text = text.replace(/^\s{0,3}([-*_])\s*\1\s*\1[\s\S]*?$/gm, '');    // 分隔线
  text = text.replace(/\n{3,}/g, '\n\n');
  return text.trim();
}

/** 按行边界切成 ≤SEGMENT_LEN 的若干段，尽量不在句子中间断开 */
export function splitForQQ(text, limit = SEGMENT_LEN) {
  const clean = (text || '').trim();
  if (!clean) return [];
  if (clean.length <= MAX_LEN) return [clean];
  const lines = clean.split('\n');
  const chunks = [];
  let current = '';
  const pushCurrent = () => {
    if (current.trim()) chunks.push(current.trim());
    current = '';
  };
  for (const line of lines) {
    if (line.length > limit) {
      // 单行超长：按 limit 硬切
      pushCurrent();
      for (let i = 0; i < line.length; i += limit) chunks.push(line.slice(i, i + limit));
      continue;
    }
    if ((current + '\n' + line).length > limit) pushCurrent();
    current = current ? current + '\n' + line : line;
  }
  pushCurrent();
  return chunks;
}

export default {
  name: 'ForwardToGroupDialog',
  props: {
    show: { type: Boolean, default: false },
    text: { type: String, default: '' },
    defaultGroupId: { type: String, default: '' },
    defaultGroupName: { type: String, default: '' },
  },
  emits: ['close', 'sent'],
  setup(props, { emit }) {
    const groups = ref([]);
    const loadingGroups = ref(false);
    const groupSearch = ref('');
    const targetGroupId = ref('');
    const manualGroupId = ref('');
    const draft = ref('');
    const cleanMarkdown = ref(true);
    const sending = ref(false);
    const message = ref('');
    const messageKind = ref('info');

    const filteredGroups = computed(() => {
      const kw = groupSearch.value.trim().toLowerCase();
      if (!kw) return groups.value;
      return groups.value.filter(g =>
        String(g.groupName || '').toLowerCase().includes(kw) || String(g.groupId).includes(kw)
      );
    });

    const finalText = computed(() => (cleanMarkdown.value ? markdownToPlainText(draft.value) : draft.value));
    const segments = computed(() => splitForQQ(finalText.value));

    const targetGroupLabel = computed(() => {
      const found = groups.value.find(g => g.groupId === targetGroupId.value);
      return found ? (found.groupName || found.groupId) : targetGroupId.value;
    });

    const pickGroup = (groupId) => {
      targetGroupId.value = groupId;
      manualGroupId.value = '';
      message.value = '';
    };

    const loadGroups = async () => {
      loadingGroups.value = true;
      try {
        const res = await messageApi.getRecentGroups();
        const list = Array.isArray(res) ? res : (res?.data || []);
        groups.value = list
          .map(g => ({ groupId: String(g.groupId ?? g.id ?? ''), groupName: g.groupName || g.name || '' }))
          .filter(g => g.groupId);
      } catch (e) {
        groups.value = [];
        messageKind.value = 'warn';
        message.value = '读取群列表失败：' + (e?.message || e) + '（可直接填群号）';
      } finally {
        loadingGroups.value = false;
      }
    };

    const submit = async () => {
      const parts = segments.value;
      if (!targetGroupId.value || !parts.length || sending.value) return;
      sending.value = true;
      messageKind.value = 'info';
      message.value = parts.length > 1 ? `正在分 ${parts.length} 条发送…` : '正在发送…';
      try {
        let last = null;
        for (let i = 0; i < parts.length; i++) {
          // eslint-disable-next-line no-await-in-loop
          last = await messageApi.sendGroupText(targetGroupId.value, { text: parts[i] });
          if (i < parts.length - 1) {
            // eslint-disable-next-line no-await-in-loop
            await new Promise(r => setTimeout(r, 600));
          }
        }
        messageKind.value = 'ok';
        message.value = `已发送到「${targetGroupLabel.value}」${parts.length > 1 ? `（${parts.length} 条）` : ''}`;
        showToast(`已转发到「${targetGroupLabel.value}」`, 'success');
        emit('sent', { groupId: targetGroupId.value, groupName: targetGroupLabel.value, parts: parts.length, result: last });
        setTimeout(() => emit('close'), 700);
      } catch (e) {
        messageKind.value = 'warn';
        message.value = '发送失败：' + (e?.message || e);
        showToast(e?.message || '发送失败', 'error');
      } finally {
        sending.value = false;
      }
    };

    const close = () => {
      if (sending.value) return;
      emit('close');
    };

    watch(() => props.show, (visible) => {
      if (!visible) return;
      draft.value = props.text || '';
      cleanMarkdown.value = true;
      groupSearch.value = '';
      message.value = '';
      targetGroupId.value = props.defaultGroupId || '';
      manualGroupId.value = props.defaultGroupId ? '' : '';
      loadGroups();
    });

    return {
      MAX_LEN,
      groups,
      loadingGroups,
      groupSearch,
      filteredGroups,
      targetGroupId,
      manualGroupId,
      targetGroupLabel,
      draft,
      cleanMarkdown,
      finalText,
      segments,
      sending,
      message,
      messageKind,
      pickGroup,
      submit,
      close,
    };
  },
};
</script>

<style scoped>
.fw-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 3200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.fw-dialog {
  width: min(560px, 100%);
  max-height: 88vh;
  overflow: auto;
  background: var(--card-bg, #fff);
  color: var(--text-primary, #2c3e50);
  border-radius: 10px;
  padding: 16px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.3);
}

.fw-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 15px;
  margin-bottom: 12px;
}

.fw-close {
  border: none;
  background: transparent;
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
  color: var(--text-secondary, #7f8c8d);
}

.fw-section { margin-bottom: 14px; }

.fw-label {
  font-size: 13px;
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.fw-hint,
.fw-check {
  font-size: 11px;
  font-weight: 400;
  color: var(--text-secondary, #7f8c8d);
}

.fw-check { display: inline-flex; align-items: center; gap: 4px; cursor: pointer; }

.fw-input,
.fw-textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 6px;
  padding: 7px 9px;
  font-size: 13px;
  background: var(--bg-primary, #fff);
  color: var(--text-primary, #2c3e50);
}

.fw-textarea { resize: vertical; font-family: inherit; line-height: 1.6; }

.fw-groups {
  max-height: 168px;
  overflow: auto;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 6px;
  margin: 6px 0;
}

.fw-group {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 10px;
  font-size: 13px;
  cursor: pointer;
  border-bottom: 1px solid var(--border-color, #f1f3f4);
}

.fw-group:last-child { border-bottom: none; }
.fw-group:hover { background: var(--hover-bg, rgba(52, 152, 219, 0.08)); }
.fw-group.active { background: rgba(52, 152, 219, 0.14); }

.fw-group-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fw-group-id { font-size: 11px; color: var(--text-secondary, #7f8c8d); flex-shrink: 0; }

.fw-empty {
  padding: 12px;
  font-size: 12px;
  color: var(--text-secondary, #7f8c8d);
  text-align: center;
}

.fw-target { font-size: 12px; margin-top: 6px; color: #27ae60; }
.fw-target.missing { color: #b7950b; }

.fw-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--text-secondary, #7f8c8d);
  margin-top: 4px;
}

.fw-meta .over { color: #e74c3c; }
.fw-split { color: #2980b9; }

.fw-message {
  font-size: 12px;
  border-radius: 6px;
  padding: 7px 10px;
  margin-bottom: 10px;
}

.fw-message.info { color: #2980b9; background: rgba(52, 152, 219, 0.1); }
.fw-message.ok { color: #27ae60; background: rgba(46, 204, 113, 0.12); }
.fw-message.warn { color: #b7950b; background: rgba(241, 196, 15, 0.15); }

.fw-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.fw-btn {
  padding: 7px 16px;
  border-radius: 6px;
  border: 1px solid var(--border-color, #e5e7eb);
  background: transparent;
  color: var(--text-primary, #2c3e50);
  font-size: 13px;
  cursor: pointer;
}

.fw-btn.primary { background: #3498db; border-color: #3498db; color: #fff; }
.fw-btn:disabled { opacity: 0.55; cursor: not-allowed; }
</style>
