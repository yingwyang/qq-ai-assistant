<template>
  <div class="tab-panel admin-astrbot-config">
    <AdminPageHeader
      title="AstrBot 配置"
      subtitle="提供商（provider_sources）、模型（providers）、配置档案（config-profiles）与摘要模型"
    >
      <template #meta>
        <span class="page-header-meta">{{ lastLoadedAt ? '已加载 ' + lastLoadedAt : '尚未加载' }}</span>
      </template>
      <button class="btn-action" :disabled="loading" @click="load">
        {{ loading ? '加载中...' : '重新加载' }}
      </button>
    </AdminPageHeader>

    <!-- 三态 · 加载中 -->
    <div v-if="loading && !loaded" class="loading-box">正在读取 AstrBot 配置...</div>

    <!-- 三态 · 加载失败 -->
    <div v-else-if="loadError" class="state-box is-error">
      <span class="state-title">加载 AstrBot 配置失败</span>
      <span>{{ loadError }}</span>
      <div class="state-actions">
        <button class="btn-action promote" @click="load">重试</button>
      </div>
    </div>

    <template v-else>
      <!-- ==================== 1. 摘要模型 ==================== -->
      <section class="section-card astrbot-section">
        <div class="section-card-header">
          <h4>
            摘要模型
            <span v-if="summaryDirty" class="dirty-badge">有未保存的修改</span>
          </h4>
          <span class="astrbot-hint">保存后<strong>实时生效</strong>，无需重启 AstrBot</span>
        </div>

        <p class="astrbot-desc">
          群消息摘要 / 日报使用的模型。候选来自「模型」区块中可用的提供商；留空表示沿用 AstrBot 默认模型。
        </p>

        <div class="astrbot-form-row">
          <div class="astrbot-field is-grow">
            <label for="astrbot-summary-model">摘要模型</label>
            <select
              id="astrbot-summary-model"
              v-model="summaryModelEdit"
              class="astrbot-select"
              :disabled="summarySaving"
            >
              <option value="">（不指定，使用 AstrBot 默认模型）</option>
              <option v-for="candidate in summaryOptions" :key="candidate.id" :value="candidate.id">
                {{ candidateText(candidate) }}
              </option>
            </select>
          </div>
          <div class="astrbot-field">
            <label>当前生效</label>
            <span class="astrbot-readonly" :title="summary.effective || ''">{{ summary.effective || '未设置' }}</span>
          </div>
          <div class="astrbot-field">
            <label>配置中记录</label>
            <span class="astrbot-readonly" :title="summary.model || ''">{{ summary.model || '未设置' }}</span>
          </div>
          <div class="astrbot-form-actions">
            <button class="btn-action primary" :disabled="summarySaving || !summaryDirty" @click="saveSummary">
              {{ summarySaving ? '保存中...' : '保存' }}
            </button>
          </div>
        </div>

        <p v-if="summaryCandidates.length === 0" class="astrbot-hint is-warn">
          暂无可选模型：请先在下方「模型」区块新增提供商模型，再回到这里选择。
        </p>
      </section>

      <!-- ==================== 2. 提供商（provider_sources） ==================== -->
      <section class="section-card astrbot-section">
        <div class="section-card-header">
          <h4>提供商（provider_sources）</h4>
          <div class="astrbot-header-actions">
            <button v-if="!sourceFormVisible" class="btn-action promote" @click="openSourceCreate">新增提供商</button>
            <button v-else class="btn-action" @click="closeSourceCreate">收起表单</button>
          </div>
        </div>

        <p class="astrbot-desc">
          API Key 由 AstrBot 保存在 provider_sources 里，后端只回显掩码；编辑时留空表示不修改。
          配置档案是整份配置快照，改 key / Base URL 后旧档案仍可能持有旧值，到「配置档案」区块同步即可。
        </p>

        <!-- 新增提供商表单 -->
        <div v-if="sourceFormVisible" class="astrbot-inline-form">
          <div class="astrbot-form-row">
            <div class="astrbot-field">
              <label>ID <span class="astrbot-required">*</span></label>
              <input v-model.trim="sourceForm.id" type="text" class="astrbot-input" placeholder="如 siliconflow / deepseek" />
            </div>
            <div class="astrbot-field">
              <label>类型</label>
              <input
                v-model.trim="sourceForm.type"
                type="text"
                class="astrbot-input"
                list="astrbot-source-type-options"
                placeholder="openai_chat_completion"
              />
              <datalist id="astrbot-source-type-options">
                <option v-for="type in sourceTypeOptions" :key="type" :value="type"></option>
              </datalist>
            </div>
            <div class="astrbot-field is-grow">
              <label>Base URL <span class="astrbot-required">*</span></label>
              <input v-model.trim="sourceForm.apiBase" type="text" class="astrbot-input" placeholder="如 https://api.deepseek.com/v1" />
            </div>
            <div class="astrbot-field">
              <label>API Key</label>
              <input v-model="sourceForm.apiKey" type="password" class="astrbot-input" placeholder="可留空，稍后补" autocomplete="new-password" />
            </div>
            <div class="astrbot-field is-narrow">
              <label>超时（秒）</label>
              <input v-model.number="sourceForm.timeout" type="number" min="1" class="astrbot-input" placeholder="60" />
            </div>
            <div class="astrbot-field is-narrow">
              <label>启用</label>
              <label class="astrbot-checkbox">
                <input v-model="sourceForm.enable" type="checkbox" /> 创建后启用
              </label>
            </div>
          </div>
          <p v-if="sourceFormError" class="astrbot-form-error">{{ sourceFormError }}</p>
          <div class="astrbot-form-actions astrbot-form-actions-row">
            <button class="btn-action primary" :disabled="sourceSubmitting" @click="submitSource">
              {{ sourceSubmitting ? '提交中...' : '提交' }}
            </button>
            <button class="btn-action" :disabled="sourceSubmitting" @click="closeSourceCreate">取消</button>
          </div>
        </div>

        <div class="table-wrap">
          <StatePanel
            v-if="sources.length === 0"
            state="empty"
            compact
            title="暂无提供商"
            hint="点击右上角「新增提供商」添加第一个 API 来源"
          />
          <table v-else class="user-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>类型</th>
                <th>Base URL</th>
                <th>Key</th>
                <th>超时</th>
                <th>模型数</th>
                <th>启用</th>
                <th class="action-cell">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="source in sources" :key="source.id">
                <td><code class="astrbot-code" :title="source.id">{{ source.id }}</code></td>
                <td>{{ source.type || '—' }}</td>
                <td class="astrbot-url" :title="source.apiBase || ''">{{ source.apiBase || '—' }}</td>
                <td>
                  <span v-if="source.keySet" class="astrbot-key" :title="source.keyMasked || ''">{{ source.keyMasked || '已设置' }}</span>
                  <span v-else class="astrbot-key is-missing">未设置</span>
                </td>
                <td>{{ formatTimeout(source.timeout) }}</td>
                <td>{{ sourceModelCount(source) }}</td>
                <td>
                  <span class="status-badge" :class="source.enable ? 'active' : 'inactive'">{{ source.enable ? '已启用' : '已停用' }}</span>
                </td>
                <td class="action-cell">
                  <div class="action-btns">
                    <button class="btn-action" :disabled="sourceDeletingId === source.id" @click="openSourceEdit(source)">编辑</button>
                    <button class="btn-action delete" :disabled="sourceDeletingId === source.id" @click="deleteSource(source)">
                      {{ sourceDeletingId === source.id ? '删除中...' : '删除' }}
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- ==================== 3. 模型（providers） ==================== -->
      <section class="section-card astrbot-section">
        <div class="section-card-header">
          <h4>模型（providers）</h4>
          <div class="astrbot-header-actions">
            <div class="astrbot-field is-inline">
              <label for="astrbot-model-filter">按来源筛选</label>
              <select id="astrbot-model-filter" v-model="modelSourceFilter" class="astrbot-select is-small">
                <option value="">全部来源</option>
                <option v-for="source in sources" :key="source.id" :value="source.id">{{ source.id }}</option>
              </select>
            </div>
            <button v-if="!modelFormVisible" class="btn-action promote" :disabled="sources.length === 0" @click="openModelCreate">新增模型</button>
            <button v-else class="btn-action" @click="closeModelCreate">收起表单</button>
          </div>
        </div>

        <p class="astrbot-desc">
          每个模型挂在某个提供商下；启用开关会立即写回 AstrBot（PATCH providers）。勾选「图片模态」后，该模型可用于带图提问。
        </p>

        <!-- 新增模型表单 -->
        <div v-if="modelFormVisible" class="astrbot-inline-form">
          <div class="astrbot-form-row">
            <div class="astrbot-field is-grow">
              <label>所属提供商 <span class="astrbot-required">*</span></label>
              <select v-model="modelForm.sourceId" class="astrbot-select">
                <option value="" disabled>请选择提供商</option>
                <option v-for="source in sources" :key="source.id" :value="source.id">{{ source.id }}</option>
              </select>
            </div>
            <div class="astrbot-field is-grow">
              <label>模型名 <span class="astrbot-required">*</span></label>
              <input v-model.trim="modelForm.model" type="text" class="astrbot-input" placeholder="如 deepseek-chat / Pro/deepseek-ai/DeepSeek-V3.2" />
            </div>
            <div class="astrbot-field is-narrow">
              <label>能力</label>
              <label class="astrbot-checkbox">
                <input v-model="modelForm.image" type="checkbox" /> 支持图片（视觉）
              </label>
            </div>
          </div>
          <p v-if="modelFormError" class="astrbot-form-error">{{ modelFormError }}</p>
          <div class="astrbot-form-actions astrbot-form-actions-row">
            <button class="btn-action primary" :disabled="modelSubmitting" @click="submitModel">
              {{ modelSubmitting ? '提交中...' : '提交' }}
            </button>
            <button class="btn-action" :disabled="modelSubmitting" @click="closeModelCreate">取消</button>
          </div>
        </div>

        <div class="table-wrap">
          <StatePanel
            v-if="filteredProviders.length === 0"
            state="empty"
            compact
            :title="modelSourceFilter ? '该提供商下暂无模型' : '暂无模型'"
            :hint="modelSourceFilter ? '换个来源筛选，或为它新增模型' : '点击右上角「新增模型」添加'"
          />
          <table v-else class="user-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>所属提供商</th>
                <th>模型名</th>
                <th>能力</th>
                <th>上下文</th>
                <th>启用</th>
                <th class="action-cell">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="model in filteredProviders" :key="model.id">
                <td><code class="astrbot-code" :title="model.id">{{ model.id }}</code></td>
                <td>{{ model.sourceId || '—' }}</td>
                <td :title="model.model || ''">{{ model.model || '—' }}</td>
                <td>
                  <span v-if="abilityTags(model.modalities).length === 0" class="astrbot-muted">—</span>
                  <span
                    v-for="tag in abilityTags(model.modalities)"
                    :key="tag.key"
                    class="astrbot-ability"
                    :class="{ 'is-vision': tag.isVision }"
                  >{{ tag.label }}</span>
                </td>
                <td :title="model.maxContextTokens ? model.maxContextTokens + ' tokens' : ''">{{ formatTokens(model.maxContextTokens) }}</td>
                <td>
                  <label class="astrbot-checkbox">
                    <input
                      type="checkbox"
                      :checked="!!model.enable"
                      :disabled="togglingModelId === model.id"
                      @change="toggleModelEnable(model, $event)"
                    />
                    {{ model.enable ? '已启用' : '已停用' }}
                  </label>
                </td>
                <td class="action-cell">
                  <button class="btn-action delete" :disabled="deletingModelId === model.id" @click="deleteModel(model)">
                    {{ deletingModelId === model.id ? '删除中...' : '删除' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- ==================== 4. 配置档案（config-profiles） ==================== -->
      <section class="section-card astrbot-section">
        <div class="section-card-header">
          <h4>配置档案（config-profiles）</h4>
          <div class="astrbot-header-actions">
            <button v-if="!syncPanelVisible" class="btn-action promote" @click="openSyncPanel">同步全局提供商</button>
            <button v-else class="btn-action" @click="closeSyncPanel">收起同步面板</button>
          </div>
        </div>

        <p class="astrbot-desc">
          配置档案是<strong>整份配置快照</strong>：新增 / 修改提供商后旧档案不会自动更新，需要「同步全局提供商」。
          底座档案（ray / vision / vision-task）被人格与视觉链路复用，删除前请确认。
        </p>

        <!-- 同步面板：先 dry-run 再 apply -->
        <div v-if="syncPanelVisible" class="astrbot-inline-form">
          <div class="astrbot-form-row">
            <div class="astrbot-field is-grow">
              <label for="astrbot-sync-main-model">统一主模型（可选，应用时写入所有档案）</label>
              <select
                id="astrbot-sync-main-model"
                v-model="syncMainModel"
                class="astrbot-select"
                :disabled="syncApplying"
                @change="resetSyncPreview"
              >
                <option value="">不修改各档案主模型</option>
                <option v-for="model in providers" :key="model.id" :value="model.id">{{ model.id }}（{{ model.model }}）</option>
              </select>
            </div>
            <button class="btn-action" :disabled="syncLoading || syncApplying" @click="runSyncPreview">
              {{ syncLoading ? '预检中...' : '预检（不写入）' }}
            </button>
            <button class="btn-action primary" :disabled="!canApplySync || syncApplying" @click="applySync">
              {{ syncApplying ? '应用中...' : '确认应用' }}
            </button>
          </div>
          <p class="astrbot-hint">预检只对比差异、不写入；「确认应用」才会真正修改 AstrBot 的配置档案。</p>
          <p v-if="syncError" class="astrbot-form-error">{{ syncError }}</p>

          <div v-if="syncPreview" class="astrbot-sync-result">
            <p class="astrbot-sync-summary">
              预检结果：共 {{ syncPreview.items.length }} 个档案，其中
              <strong>{{ syncChangedCount }}</strong> 个将发生变化。
            </p>
            <div class="table-wrap">
              <StatePanel v-if="syncPreview.items.length === 0" state="empty" compact title="没有可同步的档案" />
              <table v-else class="user-table">
                <thead>
                  <tr>
                    <th>档案</th>
                    <th>主模型（前 → 后）</th>
                    <th>模型数（前 → 后）</th>
                    <th>变化</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in syncPreview.items" :key="item.name">
                    <td><code class="astrbot-code">{{ item.name }}</code></td>
                    <td>
                      <span class="astrbot-before">{{ item.mainModelBefore || '未设置' }}</span>
                      <template v-if="item.mainModelBefore !== item.mainModelAfter">
                        <span class="astrbot-arrow">→</span>
                        <span class="astrbot-after">{{ item.mainModelAfter || '未设置' }}</span>
                      </template>
                    </td>
                    <td>
                      <span class="astrbot-before">{{ item.providerCountBefore }}</span>
                      <template v-if="item.providerCountBefore !== item.providerCountAfter">
                        <span class="astrbot-arrow">→</span>
                        <span class="astrbot-after">{{ item.providerCountAfter }}</span>
                      </template>
                    </td>
                    <td>
                      <span v-if="syncItemChanged(item)" class="dirty-badge">将更新</span>
                      <span v-else class="astrbot-muted">无变化</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <div class="table-wrap">
          <StatePanel
            v-if="profiles.length === 0"
            state="empty"
            compact
            title="暂无配置档案"
            hint="AstrBot 里至少应存在底座档案（ray / vision / vision-task）"
          />
          <table v-else class="user-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>主模型</th>
                <th>模型数</th>
                <th>来源数</th>
                <th>人格</th>
                <th class="action-cell">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="profile in profiles" :key="profile.id">
                <td>
                  <code class="astrbot-code">{{ profile.name }}</code>
                  <span v-if="isBaseProfileName(profile.name)" class="restart-badge astrbot-base-badge">底座档案</span>
                </td>
                <td :title="profile.mainModelId || ''">{{ profile.mainModelId || '未设置' }}</td>
                <td>{{ profile.providerCount != null ? profile.providerCount : '—' }}</td>
                <td>{{ profile.sourceCount != null ? profile.sourceCount : '—' }}</td>
                <td>{{ profile.personaId || '—' }}</td>
                <td class="action-cell">
                  <button
                    class="btn-action delete"
                    :disabled="deletingProfileId === profile.id || isBaseProfileName(profile.name)"
                    :title="isBaseProfileName(profile.name) ? '底座档案（ray / vision / vision-task）被人格与视觉链路依赖，后端禁止删除' : ''"
                    @click="deleteProfile(profile)"
                  >
                    {{ deletingProfileId === profile.id ? '删除中...' : '删除' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>

    <!-- 提供商编辑弹窗（key / Base URL / 启停 / 超时） -->
    <Teleport to="body">
      <div v-if="sourceEditing" class="astrbot-modal-overlay" @click.self="closeSourceEdit">
        <div class="astrbot-modal" role="dialog" aria-modal="true" aria-labelledby="astrbot-source-edit-title">
          <div class="astrbot-modal-header">
            <h3 id="astrbot-source-edit-title">编辑提供商</h3>
            <button class="astrbot-modal-close" type="button" aria-label="关闭" @click="closeSourceEdit">×</button>
          </div>
          <div class="astrbot-modal-body">
            <p class="astrbot-modal-desc">
              ID <code class="astrbot-code">{{ sourceEditing.id }}</code> 不可修改；
              API Key 留空表示不修改（当前：{{ sourceEditing.keySet ? sourceEditing.keyMasked : '未设置' }}）。
            </p>
            <div class="astrbot-field">
              <label>Base URL <span class="astrbot-required">*</span></label>
              <input v-model.trim="sourceEditForm.apiBase" type="text" class="astrbot-input" placeholder="https://..." />
            </div>
            <div class="astrbot-field">
              <label>API Key</label>
              <input v-model="sourceEditForm.apiKey" type="password" class="astrbot-input" placeholder="留空 = 不修改" autocomplete="new-password" />
            </div>
            <div class="astrbot-field">
              <label>超时（秒）</label>
              <input v-model.number="sourceEditForm.timeout" type="number" min="1" class="astrbot-input" placeholder="60" />
            </div>
            <div class="astrbot-field">
              <label>启用</label>
              <label class="astrbot-checkbox">
                <input v-model="sourceEditForm.enable" type="checkbox" /> 启用该提供商
              </label>
            </div>
            <p v-if="sourceEditError" class="astrbot-form-error">{{ sourceEditError }}</p>
          </div>
          <div class="astrbot-modal-footer">
            <button class="btn-action" :disabled="sourceEditSaving" @click="closeSourceEdit">取消</button>
            <button class="btn-action primary" :disabled="sourceEditSaving" @click="saveSourceEdit">
              {{ sourceEditSaving ? '保存中...' : '保存' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script>
import { computed, inject, onMounted, reactive, ref } from 'vue';
import AdminPageHeader from '../../components/admin/AdminPageHeader.vue';
import StatePanel from '../../components/common/StatePanel.vue';
import { adminApi } from '../../services/api';
import { showToast } from '../../components/Toast.vue';
import { showConfirm } from '../../components/ConfirmDialog.vue';

/** 底座档案：人格 / 视觉链路以它们为模板，删除前需要额外警告 */
const BASE_PROFILE_NAMES = ['ray', 'vision', 'vision-task'];
/** 新增提供商时的默认适配器类型（AstrBot 4.28 的通用 OpenAI 兼容适配器） */
const DEFAULT_SOURCE_TYPE = 'openai_chat_completion';
/** 常见适配器类型（仅作为 datalist 提示，可自由填写） */
const COMMON_SOURCE_TYPES = [
  'openai_chat_completion',
  'openai_responses',
  'anthropic_chat_completion',
  'googlegenai_chat_completion',
  'zhipu_chat_completion',
];
/** modalities → 中文能力标签 */
const MODALITY_LABELS = {
  text: '文本',
  image: '视觉',
  audio: '语音',
  video: '视频',
  file: '文件',
  tool: '工具',
  // AstrBot 4.28 的模型能力里工具调用叫 tool_use（真实数据里很常见）
  tool_use: '工具',
};

function emptyConfig() {
  return {
    summary: { model: null, effective: null, candidates: [] },
    sources: [],
    providers: [],
    profiles: [],
  };
}

function normalizeConfig(data) {
  const raw = data || {};
  const summary = raw.summary || {};
  return {
    summary: {
      model: summary.model != null ? summary.model : null,
      effective: summary.effective != null ? summary.effective : null,
      candidates: Array.isArray(summary.candidates) ? summary.candidates : [],
    },
    sources: Array.isArray(raw.sources) ? raw.sources : [],
    providers: Array.isArray(raw.providers) ? raw.providers : [],
    profiles: Array.isArray(raw.profiles) ? raw.profiles : [],
  };
}

function normalizeSyncResult(data) {
  const items = data && Array.isArray(data.items) ? data.items : [];
  return {
    applied: !!(data && data.applied),
    items: items.map((item) => ({
      name: item && item.name ? item.name : '—',
      mainModelBefore: (item && item.mainModelBefore) || '',
      mainModelAfter: (item && item.mainModelAfter) || '',
      providerCountBefore: Number((item && item.providerCountBefore) || 0),
      providerCountAfter: Number((item && item.providerCountAfter) || 0),
    })),
  };
}

export default {
  name: 'AdminAstrBotConfig',
  components: { AdminPageHeader, StatePanel },
  setup() {
    /**
     * 后台外壳（AdminView）用底部浮动提示条做统一反馈，且没有挂载全局 <Toast />；
     * 直接 showToast 在 admin 域里是不可见的。这里优先用外壳注入的提示，独立挂载时退回 Toast。
     */
    const adminShowMsg = inject('adminShowMsg', null);
    const notify = (message, type) => {
      if (typeof adminShowMsg === 'function') {
        adminShowMsg(message, type === 'error' ? 'error' : 'success');
        return;
      }
      showToast(message, type || 'success');
    };

    const config = ref(emptyConfig());
    const loading = ref(false);
    const loaded = ref(false);
    const loadError = ref('');
    const lastLoadedAt = ref('');

    const summary = computed(() => config.value.summary || { model: null, effective: null, candidates: [] });
    const summaryCandidates = computed(() => (
      Array.isArray(summary.value.candidates) ? summary.value.candidates : []
    ));
    /**
     * 下拉选项 = 候选列表；若配置里的 model 不在候选里（例如它刚被停用），
     * 补一条占位项，否则原生 select 会显示为空白、用户会以为"没配置"。
     */
    const summaryOptions = computed(() => {
      const options = summaryCandidates.value.slice();
      const current = summary.value.model;
      if (current && !options.some((candidate) => candidate && candidate.id === current)) {
        options.unshift({ id: current, label: '', sourceId: '', enabled: true });
      }
      return options;
    });
    const sources = computed(() => config.value.sources || []);
    const providers = computed(() => config.value.providers || []);
    const profiles = computed(() => config.value.profiles || []);

    // ===== 摘要模型 =====
    const summaryModelEdit = ref('');
    const summarySaving = ref(false);
    const summaryDirty = computed(() => (summaryModelEdit.value || '') !== (summary.value.model || ''));

    const candidateText = (candidate) => {
      if (!candidate) return '—';
      const source = candidate.sourceId ? '来源：' + candidate.sourceId : '来源未知';
      const label = candidate.label && candidate.label !== candidate.id ? '（' + candidate.label + '）' : '';
      const disabled = candidate.enabled === false ? ' · 已停用' : '';
      return candidate.id + label + ' · ' + source + disabled;
    };

    // ===== 通用 =====
    /** 模型列表的来源筛选（放在通用区：applyConfig 会用它做「来源被删」回落） */
    const modelSourceFilter = ref('');

    const applyConfig = (data) => {
      // 先算 dirty：替换 config 后 summary 会变，再算就永远是 false
      const keepSummaryEdit = summaryDirty.value;
      const next = normalizeConfig(data);
      config.value = next;
      if (!keepSummaryEdit) {
        summaryModelEdit.value = next.summary.model || '';
      }
      // 筛选来源被删掉时回落到「全部来源」，避免表格永久空白
      if (modelSourceFilter.value && !next.sources.some((source) => source.id === modelSourceFilter.value)) {
        modelSourceFilter.value = '';
      }
    };

    const load = async () => {
      loading.value = true;
      loadError.value = '';
      try {
        const data = await adminApi.getAstrBotConfig();
        applyConfig(data);
        loaded.value = true;
        lastLoadedAt.value = new Date().toLocaleTimeString('zh-CN');
      } catch (error) {
        loadError.value = (error && error.message) || '请求失败，请稍后重试';
      } finally {
        loading.value = false;
      }
    };

    /** 写操作成功后静默刷新；request() 层已统一 toast 错误，这里失败只当本次刷新没成功 */
    const reloadSilently = async () => {
      try {
        const data = await adminApi.getAstrBotConfig();
        applyConfig(data);
        loaded.value = true;
        lastLoadedAt.value = new Date().toLocaleTimeString('zh-CN');
      } catch (error) {
        // 忽略：手动「重新加载」或下次操作时会同步到最新状态
      }
    };

    // ===== 摘要模型保存 =====
    const saveSummary = async () => {
      summarySaving.value = true;
      try {
        const value = summaryModelEdit.value || '';
        const data = await adminApi.updateAstrBotSummary(value);
        // 契约：返回更新后的 summary；若后端只回 {ok}，用本地提交值兜底
        const looksLikeSummary = data && (
          Array.isArray(data.candidates) || data.model !== undefined || data.effective !== undefined
        );
        const nextSummary = looksLikeSummary
          ? data
          : { ...summary.value, model: value || null, effective: value || null };
        config.value = { ...config.value, summary: { ...summary.value, ...nextSummary } };
        summaryModelEdit.value = config.value.summary.model || '';
        notify('摘要模型已保存，已实时生效', 'success');
      } catch (error) {
        // request() 已 toast 错误提示
      } finally {
        summarySaving.value = false;
      }
    };

    // ===== 提供商（provider_sources） =====
    const sourceFormVisible = ref(false);
    const sourceSubmitting = ref(false);
    const sourceFormError = ref('');
    const sourceForm = reactive({ id: '', type: DEFAULT_SOURCE_TYPE, apiBase: '', apiKey: '', timeout: 60, enable: true });
    const sourceDeletingId = ref('');

    const sourceTypeOptions = computed(() => {
      const set = new Set(COMMON_SOURCE_TYPES);
      for (const source of sources.value) {
        if (source.type) set.add(source.type);
      }
      return [...set];
    });

    const sourceModelCount = (source) => {
      if (!source) return 0;
      const counted = providers.value.filter((model) => model.sourceId === source.id).length;
      return counted > 0 ? counted : Number(source.providerCount || 0);
    };

    const formatTimeout = (value) => {
      if (value === null || value === undefined || value === '') return '默认';
      return value + ' 秒';
    };

    const resetSourceForm = () => {
      sourceForm.id = '';
      sourceForm.type = DEFAULT_SOURCE_TYPE;
      sourceForm.apiBase = '';
      sourceForm.apiKey = '';
      sourceForm.timeout = 60;
      sourceForm.enable = true;
      sourceFormError.value = '';
    };

    const openSourceCreate = () => {
      resetSourceForm();
      sourceFormVisible.value = true;
    };

    const closeSourceCreate = () => {
      sourceFormVisible.value = false;
      sourceFormError.value = '';
    };

    const validateSource = (form, isEdit) => {
      if (!isEdit) {
        if (!form.id) return '请填写提供商 ID';
        if (sources.value.some((source) => source.id === form.id)) return '提供商「' + form.id + '」已存在';
      }
      if (!form.apiBase) return '请填写 Base URL';
      if (!/^https?:\/\/\S+$/i.test(form.apiBase)) return 'Base URL 需以 http:// 或 https:// 开头';
      if (form.timeout !== '' && form.timeout !== null && form.timeout !== undefined) {
        const timeout = Number(form.timeout);
        if (!Number.isFinite(timeout) || timeout <= 0) return '超时需为正整数（秒）';
      }
      return '';
    };

    const submitSource = async () => {
      const error = validateSource(sourceForm, false);
      sourceFormError.value = error;
      if (error) return;
      sourceSubmitting.value = true;
      try {
        const payload = {
          id: sourceForm.id,
          type: sourceForm.type || DEFAULT_SOURCE_TYPE,
          apiBase: sourceForm.apiBase,
          enable: !!sourceForm.enable,
        };
        if (sourceForm.apiKey) payload.apiKey = sourceForm.apiKey;
        if (sourceForm.timeout !== '' && sourceForm.timeout !== null) payload.timeout = Number(sourceForm.timeout);
        const data = await adminApi.createAstrBotSource(payload);
        notify((data && data.message) || ('提供商「' + payload.id + '」已创建'), 'success');
        sourceFormVisible.value = false;
        resetSourceForm();
        await reloadSilently();
      } catch (error) {
        sourceFormError.value = (error && error.message) || '新增提供商失败';
      } finally {
        sourceSubmitting.value = false;
      }
    };

    // 编辑弹窗
    const sourceEditing = ref(null);
    const sourceEditSaving = ref(false);
    const sourceEditError = ref('');
    const sourceEditForm = reactive({ apiBase: '', apiKey: '', timeout: '', enable: true });

    const openSourceEdit = (source) => {
      sourceEditing.value = source;
      sourceEditForm.apiBase = source.apiBase || '';
      sourceEditForm.apiKey = '';
      sourceEditForm.timeout = (source.timeout === null || source.timeout === undefined) ? '' : source.timeout;
      sourceEditForm.enable = !!source.enable;
      sourceEditError.value = '';
    };

    const closeSourceEdit = () => {
      sourceEditing.value = null;
      sourceEditError.value = '';
    };

    const saveSourceEdit = async () => {
      if (!sourceEditing.value) return;
      const error = validateSource(sourceEditForm, true);
      sourceEditError.value = error;
      if (error) return;
      sourceEditSaving.value = true;
      try {
        const payload = {
          id: sourceEditing.value.id,
          apiBase: sourceEditForm.apiBase,
          enable: !!sourceEditForm.enable,
        };
        // key 留空 = 不修改（后端不回显真实值，只能用"不传"表达）
        if (sourceEditForm.apiKey) payload.apiKey = sourceEditForm.apiKey;
        if (sourceEditForm.timeout !== '' && sourceEditForm.timeout !== null) {
          payload.timeout = Number(sourceEditForm.timeout);
        }
        const data = await adminApi.updateAstrBotSource(payload);
        notify((data && data.message) || ('提供商「' + payload.id + '」已更新'), 'success');
        closeSourceEdit();
        await reloadSilently();
      } catch (error) {
        sourceEditError.value = (error && error.message) || '保存失败';
      } finally {
        sourceEditSaving.value = false;
      }
    };

    const deleteSource = async (source) => {
      const count = sourceModelCount(source);
      const detail = count > 0 ? '其下 ' + count + ' 个模型会一并失去来源、无法再使用。' : '';
      const ok = await showConfirm({
        title: '删除提供商',
        message: '确定删除提供商「' + source.id + '」？' + detail + '该操作不可撤销。',
        type: 'error',
        confirmText: '删除',
      });
      if (!ok) return;
      sourceDeletingId.value = source.id;
      try {
        const data = await adminApi.deleteAstrBotSource(source.id);
        notify((data && data.message) || ('提供商「' + source.id + '」已删除'), 'success');
        await reloadSilently();
      } catch (error) {
        // request() 已 toast 错误提示
      } finally {
        sourceDeletingId.value = '';
      }
    };

    // ===== 模型（providers） =====
    const modelFormVisible = ref(false);
    const modelSubmitting = ref(false);
    const modelFormError = ref('');
    const modelForm = reactive({ sourceId: '', model: '', image: false });
    const togglingModelId = ref('');
    const deletingModelId = ref('');

    const filteredProviders = computed(() => {
      const filter = modelSourceFilter.value;
      if (!filter) return providers.value;
      return providers.value.filter((model) => model.sourceId === filter);
    });

    const abilityTags = (modalities) => {
      if (!Array.isArray(modalities)) return [];
      return modalities.map((modality) => ({
        key: String(modality),
        label: MODALITY_LABELS[modality] || String(modality),
        isVision: modality === 'image',
      }));
    };

    const formatTokens = (value) => {
      const tokens = Number(value);
      if (!Number.isFinite(tokens) || tokens <= 0) return '—';
      if (tokens >= 1024) {
        const k = tokens / 1024;
        const rounded = k >= 10 ? Math.round(k) : Math.round(k * 10) / 10;
        return rounded + 'K';
      }
      return String(tokens);
    };

    const openModelCreate = () => {
      modelForm.sourceId = modelSourceFilter.value || (sources.value[0] ? sources.value[0].id : '');
      modelForm.model = '';
      modelForm.image = false;
      modelFormError.value = '';
      modelFormVisible.value = true;
    };

    const closeModelCreate = () => {
      modelFormVisible.value = false;
      modelFormError.value = '';
    };

    const submitModel = async () => {
      if (!modelForm.sourceId) {
        modelFormError.value = '请选择所属提供商';
        return;
      }
      if (!modelForm.model) {
        modelFormError.value = '请填写模型名';
        return;
      }
      const duplicated = providers.value.some(
        (item) => item.sourceId === modelForm.sourceId && item.model === modelForm.model
      );
      if (duplicated) {
        modelFormError.value = '提供商「' + modelForm.sourceId + '」下已存在同名模型';
        return;
      }
      modelFormError.value = '';
      modelSubmitting.value = true;
      try {
        const payload = {
          sourceId: modelForm.sourceId,
          model: modelForm.model,
          modalities: modelForm.image ? ['text', 'image'] : ['text'],
          enable: true,
        };
        const data = await adminApi.createAstrBotProvider(payload);
        notify((data && data.message) || ('模型「' + payload.model + '」已创建'), 'success');
        closeModelCreate();
        await reloadSilently();
      } catch (error) {
        modelFormError.value = (error && error.message) || '新增模型失败';
      } finally {
        modelSubmitting.value = false;
      }
    };

    const toggleModelEnable = async (model, event) => {
      const target = event && event.target;
      const next = !!(target && target.checked);
      const prev = !!model.enable;
      if (next === prev) return;
      togglingModelId.value = model.id;
      try {
        await adminApi.patchAstrBotProvider(model.id, next);
        model.enable = next;
        notify('模型「' + model.id + '」已' + (next ? '启用' : '停用'), 'success');
        await reloadSilently();
      } catch (error) {
        // 失败时把开关拨回原位（vdom 没变，Vue 不会替我们重置 DOM）
        if (target) target.checked = prev;
      } finally {
        togglingModelId.value = '';
      }
    };

    const deleteModel = async (model) => {
      const ok = await showConfirm({
        title: '删除模型',
        message: '确定删除模型「' + model.id + '」？删除后该模型无法再被选择使用，此操作不可撤销。',
        type: 'error',
        confirmText: '删除',
      });
      if (!ok) return;
      deletingModelId.value = model.id;
      try {
        const data = await adminApi.deleteAstrBotProvider(model.id);
        notify((data && data.message) || ('模型「' + model.id + '」已删除'), 'success');
        await reloadSilently();
      } catch (error) {
        // request() 已 toast 错误提示
      } finally {
        deletingModelId.value = '';
      }
    };

    // ===== 配置档案（config-profiles） =====
    const syncPanelVisible = ref(false);
    const syncMainModel = ref('');
    const syncLoading = ref(false);
    const syncApplying = ref(false);
    const syncPreview = ref(null);
    const syncError = ref('');
    const deletingProfileId = ref('');

    const isBaseProfileName = (name) => BASE_PROFILE_NAMES.indexOf(String(name || '')) >= 0;

    const syncItemChanged = (item) => {
      if (!item) return false;
      const before = Number(item.providerCountBefore || 0);
      const after = Number(item.providerCountAfter || 0);
      return (item.mainModelBefore || '') !== (item.mainModelAfter || '') || before !== after;
    };

    const syncChangedCount = computed(() => {
      if (!syncPreview.value) return 0;
      return syncPreview.value.items.filter(syncItemChanged).length;
    });

    const canApplySync = computed(() => {
      if (!syncPreview.value || syncChangedCount.value === 0) return false;
      // 预检若已回 applied=true 说明后端其实已经写入，不再给二次「确认应用」入口；
      // applied 为 false / 0 / 缺省都视为「仅预检」（后端字段是 boolean 还是计数都能用）。
      return syncPreview.value.applied !== true;
    });

    const openSyncPanel = () => {
      syncPanelVisible.value = true;
      syncPreview.value = null;
      syncError.value = '';
      syncMainModel.value = '';
    };

    const closeSyncPanel = () => {
      syncPanelVisible.value = false;
      syncPreview.value = null;
      syncError.value = '';
      syncLoading.value = false;
      syncApplying.value = false;
    };

    const resetSyncPreview = () => {
      // 主模型改了，上一次预检结果就失效了
      syncPreview.value = null;
      syncError.value = '';
    };

    const buildSyncPayload = (apply) => {
      const payload = { apply: !!apply };
      if (syncMainModel.value) payload.mainModelId = syncMainModel.value;
      return payload;
    };

    const runSyncPreview = async () => {
      syncLoading.value = true;
      syncError.value = '';
      try {
        const data = await adminApi.syncAstrBotProfiles(buildSyncPayload(false));
        syncPreview.value = normalizeSyncResult(data);
        if (syncPreview.value.items.length > 0 && syncChangedCount.value === 0) {
          notify('所有档案已是最新，无需同步', 'info');
        }
      } catch (error) {
        syncPreview.value = null;
        syncError.value = (error && error.message) || '预检失败';
      } finally {
        syncLoading.value = false;
      }
    };

    const applySync = async () => {
      if (!syncPreview.value) return;
      const changed = syncChangedCount.value;
      if (changed === 0) {
        notify('没有需要应用的差异', 'info');
        return;
      }
      const mainModelText = syncMainModel.value ? '，并把主模型统一为「' + syncMainModel.value + '」' : '';
      const ok = await showConfirm({
        title: '应用配置档案同步',
        message: '将把全局提供商写入 ' + changed + ' 个配置档案' + mainModelText
          + '。配置档案是整份配置快照，应用后会覆盖其 provider 列表，确定继续？',
        type: 'warning',
        confirmText: '确认应用',
      });
      if (!ok) return;
      syncApplying.value = true;
      syncError.value = '';
      try {
        const data = await adminApi.syncAstrBotProfiles(buildSyncPayload(true));
        const appliedCount = (data && Array.isArray(data.items))
          ? data.items.filter(syncItemChanged).length
          : changed;
        notify('配置档案已同步（' + appliedCount + ' 个档案更新）', 'success');
        syncPreview.value = null;
        await reloadSilently();
      } catch (error) {
        syncError.value = (error && error.message) || '同步失败';
      } finally {
        syncApplying.value = false;
      }
    };

    const deleteProfile = async (profile) => {
      // 底座档案由后端保护（application.yml 里可改引用），这里直接挡住，避免"确认两次后报 400"的坏体验
      if (isBaseProfileName(profile.name)) {
        notify('「' + profile.name + '」是底座档案（人格/视觉链路依赖），不能删除', 'error');
        return;
      }
      const ok = await showConfirm({
        title: '删除配置档案',
        message: '确定删除配置档案「' + profile.name + '」？该档案是 AstrBot 的一份整份配置快照，删除后无法恢复。',
        type: 'error',
        confirmText: '删除',
      });
      if (!ok) return;
      deletingProfileId.value = profile.id;
      try {
        const data = await adminApi.deleteAstrBotProfile(profile.id);
        notify((data && data.message) || ('配置档案「' + profile.name + '」已删除'), 'success');
        await reloadSilently();
      } catch (error) {
        // request() 已 toast 错误提示
      } finally {
        deletingProfileId.value = '';
      }
    };

    onMounted(() => {
      load();
    });

    return {
      // 页面状态
      config, loading, loaded, loadError, lastLoadedAt, load,
      // 摘要模型
      summary, summaryCandidates, summaryOptions, summaryModelEdit, summarySaving, summaryDirty, candidateText, saveSummary,
      // 提供商
      sources, sourceFormVisible, sourceForm, sourceFormError, sourceSubmitting, sourceTypeOptions,
      openSourceCreate, closeSourceCreate, submitSource, sourceModelCount, formatTimeout,
      sourceDeletingId, openSourceEdit, deleteSource,
      sourceEditing, sourceEditForm, sourceEditError, sourceEditSaving, closeSourceEdit, saveSourceEdit,
      // 模型
      providers, filteredProviders, modelSourceFilter, modelFormVisible, modelForm, modelFormError,
      modelSubmitting, openModelCreate, closeModelCreate, submitModel, abilityTags, formatTokens,
      togglingModelId, toggleModelEnable, deletingModelId, deleteModel,
      // 配置档案
      profiles, isBaseProfileName, syncPanelVisible, syncMainModel, syncLoading, syncApplying,
      syncPreview, syncError, syncChangedCount, canApplySync, syncItemChanged, openSyncPanel,
      closeSyncPanel, resetSyncPreview, runSyncPreview, applySync, deletingProfileId, deleteProfile,
    };
  },
};
</script>

<style scoped>
.astrbot-section { margin-bottom: 16px; }
.astrbot-section:last-child { margin-bottom: 0; }

.astrbot-hint { font-size: 11.5px; color: var(--text-muted, #999); }
.astrbot-hint.is-warn { color: #ef6c00; }
.theme-dark .astrbot-hint.is-warn { color: #ffb74d; }

.astrbot-desc { font-size: 12.5px; color: var(--text-muted, #888); line-height: 1.7; margin: 0 0 14px 0; }

.astrbot-header-actions { display: flex; align-items: flex-end; gap: 10px; flex-wrap: wrap; }

.astrbot-inline-form {
  padding: 14px;
  margin-bottom: 14px;
  border: 1px dashed var(--border-color, #d8dee9);
  border-radius: 8px;
  background: var(--bg-tertiary, #fafbfc);
}
.theme-dark .astrbot-inline-form { background: rgba(255, 255, 255, 0.03); border-color: var(--border-color, #333344); }

.astrbot-form-row { display: flex; align-items: flex-end; gap: 12px; flex-wrap: wrap; }
.astrbot-form-actions { display: flex; align-items: center; gap: 8px; }
.astrbot-form-actions-row { margin-top: 10px; }

.astrbot-field { display: flex; flex-direction: column; gap: 5px; min-width: 120px; }
.astrbot-field.is-grow { flex: 1; min-width: 200px; }
.astrbot-field.is-narrow { min-width: 100px; }
.astrbot-field.is-inline { flex-direction: row; align-items: center; gap: 8px; min-width: 0; }
.astrbot-field > label { font-size: 12px; color: var(--text-muted, #888); }

.astrbot-input,
.astrbot-select {
  padding: 7px 10px;
  border: 1px solid var(--border-color, #d8dee9);
  border-radius: 6px;
  background: var(--input-bg, #fff);
  color: var(--text-primary, #333);
  font-size: 13px;
  outline: none;
  box-sizing: border-box;
  max-width: 100%;
}
.astrbot-input:focus,
.astrbot-select:focus { border-color: var(--accent-color, #3498db); }
.astrbot-select.is-small { padding: 5px 8px; font-size: 12px; }

.astrbot-checkbox {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--text-secondary, #666);
  cursor: pointer;
  white-space: nowrap;
}

.astrbot-required { color: var(--danger-color, #e74c3c); }
.astrbot-form-error { margin: 10px 0 0 0; font-size: 12px; color: var(--danger-color, #e74c3c); }
.astrbot-muted { color: var(--text-muted, #aaa); }

.astrbot-readonly {
  display: inline-block;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 7px 0;
  font-size: 13px;
  color: var(--text-primary, #333);
}

.astrbot-code {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: var(--accent-color, #3498db);
  max-width: 320px;
  display: inline-block;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: middle;
}

.astrbot-url {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--text-secondary, #666);
}

.astrbot-key { font-family: 'Consolas', 'Monaco', monospace; font-size: 12px; color: var(--text-secondary, #666); }
.astrbot-key.is-missing { color: var(--text-muted, #aaa); font-family: inherit; }

.astrbot-ability {
  display: inline-block;
  padding: 2px 8px;
  margin-right: 4px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
  background: rgba(52, 152, 219, 0.14);
  color: #2980b9;
}
.astrbot-ability.is-vision { background: rgba(155, 89, 182, 0.16); color: #8e44ad; }
.theme-dark .astrbot-ability { background: rgba(52, 152, 219, 0.22); color: #64b5f6; }
.theme-dark .astrbot-ability.is-vision { background: rgba(155, 89, 182, 0.26); color: #ce93d8; }

.astrbot-base-badge { margin-left: 6px; }

.astrbot-sync-result { margin-top: 12px; }
.astrbot-sync-summary { font-size: 12.5px; color: var(--text-secondary, #666); margin: 0 0 10px 0; }
.astrbot-sync-summary strong { color: var(--accent-color, #3498db); }
.astrbot-before { color: var(--text-muted, #888); }
.astrbot-after { color: var(--success-color, #27ae60); font-weight: 600; }
.astrbot-arrow { margin: 0 6px; color: var(--text-muted, #aaa); }

/* 编辑弹窗（Teleport 到 body，样式自带作用域隔离） */
.astrbot-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 9997;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(2px);
}
.astrbot-modal {
  width: 90%;
  max-width: 460px;
  max-height: 86vh;
  overflow-y: auto;
  border-radius: 12px;
  background: var(--card-bg, #fff);
  border: 1px solid var(--border-color, #e8e8e8);
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}
.astrbot-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border-color, #f0f0f0);
}
.astrbot-modal-header h3 { margin: 0; font-size: 15px; color: var(--text-primary, #333); }
.astrbot-modal-close {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 4px;
  background: none;
  color: var(--text-muted, #999);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
}
.astrbot-modal-close:hover { background: var(--bg-tertiary, #f5f5f5); color: var(--text-secondary, #666); }
.astrbot-modal-body { padding: 18px 20px; display: flex; flex-direction: column; gap: 14px; }
.astrbot-modal-desc { margin: 0; font-size: 12.5px; line-height: 1.7; color: var(--text-muted, #888); }
.astrbot-modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid var(--border-color, #f0f0f0);
  background: var(--bg-tertiary, #fafafa);
}
.theme-dark .astrbot-modal-footer { background: rgba(255, 255, 255, 0.03); }
</style>
