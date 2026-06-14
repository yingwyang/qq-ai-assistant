<template>
  <div class="persona-manager">
    <div class="persona-header">
      <h3>人格管理</h3>
      <button class="btn-add" @click="openCreateForm">+ 新建人格</button>
    </div>

    <!-- 人格列表 -->
    <div class="persona-list">
      <div
        v-for="p in personas"
        :key="p.id"
        class="persona-item"
        :class="{ active: defaultPersonaId === p.personaId }"
      >
        <div class="persona-info" @click="selectPersona(p)">
          <div class="persona-name">{{ p.personaId }}</div>
          <div class="persona-prompt-preview">{{ previewPrompt(p.systemPrompt) }}</div>
        </div>
        <div class="persona-actions">
          <button
            v-if="defaultPersonaId !== p.personaId"
            class="btn-set-default"
            @click="setDefault(p.personaId)"
          >
            设为默认
          </button>
          <span v-else class="default-badge">默认</span>
          <button class="btn-edit" @click="openEditForm(p)">编辑</button>
          <button class="btn-delete" @click="deletePersona(p.id)">删除</button>
        </div>
      </div>
    </div>

    <!-- 创建/编辑表单弹窗 -->
    <div v-if="showForm" class="modal-overlay" @click="closeForm">
      <div class="modal-content" @click.stop>
        <div class="modal-header">
          <h3>{{ isEditing ? '编辑人格' : '新建人格' }}</h3>
          <button class="close-btn" @click="closeForm">&times;</button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label>人格 ID</label>
            <input v-model="form.personaId" type="text" placeholder="请输入人格ID" :disabled="isEditing" />
          </div>
          <div class="form-group">
            <label>系统提示词</label>
            <textarea v-model="form.systemPrompt" rows="6" placeholder="请输入系统提示词"></textarea>
          </div>
          <div class="form-group">
            <label>自定义报错回复（可选）</label>
            <input v-model="form.customErrorMessage" type="text" placeholder="LLM请求失败时的回复" />
          </div>
          <div class="form-group">
            <label>预设对话（JSON格式，可选）</label>
            <textarea v-model="form.beginDialogs" rows="3" placeholder='[{&quot;role&quot;:&quot;user&quot;,&quot;content&quot;:&quot;你好&quot;}, ...]'></textarea>
          </div>
          <div class="form-group">
            <label>工具选择（JSON格式，可选）</label>
            <textarea v-model="form.tools" rows="2" placeholder='[&quot;tool1&quot;, &quot;tool2&quot;]'></textarea>
          </div>
          <div class="form-group">
            <label>Skills 选择（JSON格式，可选）</label>
            <textarea v-model="form.skills" rows="2" placeholder='[&quot;skill1&quot;, &quot;skill2&quot;]'></textarea>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn-cancel" @click="closeForm">取消</button>
          <button class="btn-save" @click="savePersona" :disabled="saving">
            {{ saving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, onMounted } from 'vue';
import { personaApi } from '../services/api';
import { showToast } from './Toast.vue';

export default {
  name: 'PersonaManager',
  setup() {
    const personas = ref([]);
    const defaultPersonaId = ref(null);
    const showForm = ref(false);
    const isEditing = ref(false);
    const saving = ref(false);
    const form = ref({
      id: null,
      personaId: '',
      systemPrompt: '',
      customErrorMessage: '',
      beginDialogs: '',
      tools: '',
      skills: '',
    });

    const loadPersonas = async () => {
      try {
        const list = await personaApi.list();
        personas.value = list;
      } catch (error) {
        console.error('加载人格列表失败:', error);
        showToast('加载人格列表失败', 'error');
      }
    };

    const loadDefault = async () => {
      try {
        const result = await personaApi.getDefault();
        defaultPersonaId.value = result.defaultPersonaId;
      } catch (error) {
        console.error('加载默认人格失败:', error);
      }
    };

    const setDefault = async (personaId) => {
      try {
        await personaApi.setDefault(personaId);
        defaultPersonaId.value = personaId;
        showToast('默认人格设置成功', 'success');
      } catch (error) {
        console.error('设置默认人格失败:', error);
        showToast('设置默认人格失败', 'error');
      }
    };

    const openCreateForm = () => {
      isEditing.value = false;
      form.value = {
        id: null,
        personaId: '',
        systemPrompt: '',
        customErrorMessage: '',
        beginDialogs: '',
        tools: '',
        skills: '',
      };
      showForm.value = true;
    };

    const openEditForm = (p) => {
      isEditing.value = true;
      form.value = {
        id: p.id,
        personaId: p.personaId,
        systemPrompt: p.systemPrompt || '',
        customErrorMessage: p.customErrorMessage || '',
        beginDialogs: p.beginDialogs ? JSON.stringify(p.beginDialogs, null, 2) : '',
        tools: p.tools ? JSON.stringify(p.tools, null, 2) : '',
        skills: p.skills ? JSON.stringify(p.skills, null, 2) : '',
      };
      showForm.value = true;
    };

    const closeForm = () => {
      showForm.value = false;
    };

    const parseJsonField = (str) => {
      if (!str || str.trim() === '') return null;
      try {
        return JSON.parse(str);
      } catch (e) {
        return null;
      }
    };

    const savePersona = async () => {
      if (!form.value.personaId.trim()) {
        showToast('人格ID不能为空', 'warning');
        return;
      }
      saving.value = true;
      try {
        const params = {
          personaId: form.value.personaId.trim(),
          systemPrompt: form.value.systemPrompt,
          customErrorMessage: form.value.customErrorMessage,
          beginDialogs: parseJsonField(form.value.beginDialogs),
          tools: parseJsonField(form.value.tools),
          skills: parseJsonField(form.value.skills),
        };
        if (isEditing.value) {
          await personaApi.update(form.value.id, params);
          showToast('人格更新成功', 'success');
        } else {
          await personaApi.create(params);
          showToast('人格创建成功', 'success');
        }
        closeForm();
        await loadPersonas();
      } catch (error) {
        console.error('保存人格失败:', error);
        showToast('保存人格失败: ' + error.message, 'error');
      } finally {
        saving.value = false;
      }
    };

    const deletePersona = async (id) => {
      if (!confirm('确定要删除这个人格吗？')) return;
      try {
        await personaApi.delete(id);
        showToast('人格删除成功', 'success');
        await loadPersonas();
      } catch (error) {
        console.error('删除人格失败:', error);
        showToast('删除人格失败', 'error');
      }
    };

    const previewPrompt = (prompt) => {
      if (!prompt) return '无系统提示词';
      return prompt.length > 50 ? prompt.substring(0, 50) + '...' : prompt;
    };

    const selectPersona = (p) => {
      // 可以扩展为选中后填充到聊天界面等
    };

    onMounted(() => {
      loadPersonas();
      loadDefault();
    });

    return {
      personas,
      defaultPersonaId,
      showForm,
      isEditing,
      saving,
      form,
      setDefault,
      openCreateForm,
      openEditForm,
      closeForm,
      savePersona,
      deletePersona,
      previewPrompt,
      selectPersona,
    };
  },
};
</script>

<style scoped>
.persona-manager {
  padding: 16px;
}

.persona-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.persona-header h3 {
  margin: 0;
  font-size: 16px;
  color: #2c3e50;
}

.btn-add {
  padding: 6px 14px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}

.persona-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.persona-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px;
  background-color: #f8f9fa;
  border-radius: 6px;
  border: 1px solid #e0e0e0;
  transition: all 0.2s;
}

.persona-item:hover {
  background-color: #e9ecef;
}

.persona-item.active {
  border-color: #3498db;
  background-color: #e3f2fd;
}

.persona-info {
  flex: 1;
  cursor: pointer;
}

.persona-name {
  font-weight: 600;
  font-size: 14px;
  color: #2c3e50;
  margin-bottom: 4px;
}

.persona-prompt-preview {
  font-size: 12px;
  color: #666;
}

.persona-actions {
  display: flex;
  gap: 6px;
  align-items: center;
}

.btn-set-default {
  padding: 4px 10px;
  background-color: #9b59b6;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}

.default-badge {
  padding: 4px 10px;
  background-color: #3498db;
  color: white;
  border-radius: 4px;
  font-size: 12px;
}

.btn-edit {
  padding: 4px 10px;
  background-color: #f39c12;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}

.btn-delete {
  padding: 4px 10px;
  background-color: #e74c3c;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}

/* Modal */
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
  z-index: 2000;
}

.modal-content {
  background-color: white;
  border-radius: 8px;
  width: 90%;
  max-width: 600px;
  max-height: 80vh;
  overflow-y: auto;
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid #e0e0e0;
}

.modal-header h3 {
  margin: 0;
  font-size: 16px;
}

.close-btn {
  background: none;
  border: none;
  font-size: 24px;
  cursor: pointer;
  color: #95a5a6;
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
  font-size: 13px;
  font-weight: 500;
  color: #2c3e50;
}

.form-group input,
.form-group textarea {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #dee2e6;
  border-radius: 4px;
  font-size: 13px;
  box-sizing: border-box;
  font-family: inherit;
}

.form-group textarea {
  resize: vertical;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 20px;
  border-top: 1px solid #e0e0e0;
}

.btn-cancel {
  padding: 8px 16px;
  background-color: #95a5a6;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}

.btn-save {
  padding: 8px 16px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}

.btn-save:disabled {
  background-color: #bdc3c7;
}
</style>
