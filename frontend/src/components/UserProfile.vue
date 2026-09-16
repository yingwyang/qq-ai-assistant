<template>
  <div class="user-profile-modal" v-if="visible" @click.self="closeModal">
    <div class="profile-container">
      <div class="profile-header">
        <h2>个人信息</h2>
        <button class="close-btn" @click="closeModal"><Icon name="close" :size="16" /></button>
      </div>

      <div class="profile-content">
        <!-- 用户基本信息 -->
        <div class="section">
          <h3>基本信息</h3>
          <div class="user-info-card">
            <div class="avatar-section">
              <img
                :src="getUserAvatarUrl(userInfo.avatar)"
                alt="avatar"
                class="user-avatar"
                @error="handleAvatarError"
              />
              <button class="change-avatar-btn" @click="showAvatarUpload = true">更换头像</button>
            </div>
            <div class="info-fields">
              <div class="field">
                <label>账号</label>
                <span>{{ userInfo.username }}</span>
              </div>
              <div class="field">
                <label>昵称</label>
                <input v-if="isEditing" v-model="editForm.nickname" type="text" />
                <span v-else>{{ userInfo.nickname || '未设置' }}</span>
              </div>
              <div class="field">
                <label>角色</label>
                <span class="role-badge" :class="userInfo.role">{{ userInfo.role === 'ADMIN' ? '管理员' : '用户' }}</span>
              </div>
            </div>
          </div>
          <div class="action-buttons">
            <button v-if="!isEditing" class="btn-edit" @click="startEdit">编辑资料</button>
            <template v-else>
              <button class="btn-save" @click="saveProfile" :disabled="isSaving">
                {{ isSaving ? '保存中...' : '保存' }}
              </button>
              <button class="btn-cancel" @click="cancelEdit">取消</button>
            </template>
          </div>
        </div>

        <!-- QQ账号绑定 -->
        <div class="section">
          <div class="section-header">
            <h3>QQ账号绑定</h3>
            <button class="btn-add" @click="showBindQqModal = true">+ 绑定QQ</button>
          </div>
          
          <div v-if="qqBindings.length === 0" class="empty-state">
            <p>尚未绑定QQ账号</p>
            <p class="hint">绑定QQ账号后可查看对应的消息记录</p>
          </div>
          
          <div v-else class="qq-bindings-list">
            <div 
              v-for="binding in qqBindings" 
              :key="binding.id" 
              class="qq-binding-item"
              :class="{ 'is-default': binding.isDefault }"
            >
              <div class="qq-avatar">
                <img :src="binding.avatar || defaultQqAvatar" alt="qq avatar" @error="handleAvatarError" />
              </div>
              <div class="qq-info">
                <div class="qq-number">{{ binding.qqNumber }}</div>
                <div class="qq-nickname">{{ binding.nickname || 'QQ用户' }}</div>
                <span v-if="binding.isDefault" class="default-badge">默认</span>
              </div>
              <div class="qq-actions">
                <button 
                  v-if="!binding.isDefault" 
                  class="btn-set-default" 
                  @click="setDefaultQq(binding.id)"
                >
                  设为默认
                </button>
                <button class="btn-unbind" @click="unbindQq(binding.id)">解绑</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 绑定QQ弹窗 -->
    <div v-if="showBindQqModal" class="modal-overlay" @click.self="closeBindModal">
      <div class="modal-content">
        <h3>绑定QQ账号</h3>
        <p class="bind-hint">需验证QQ主人身份，验证码将发送到该QQ的私信</p>
        <div class="form-group">
          <label>QQ号</label>
          <div class="input-with-btn">
            <input v-model="bindForm.qqNumber" type="text" placeholder="请输入QQ号" :disabled="codeSent" />
            <button 
              class="btn-send-code" 
              @click="sendVerificationCode" 
              :disabled="isSendingCode || countdown > 0 || !bindForm.qqNumber.trim()"
            >
              {{ countdown > 0 ? `${countdown}秒后重试` : (isSendingCode ? '发送中...' : '获取验证码') }}
            </button>
          </div>
        </div>
        <div class="form-group">
          <label>验证码</label>
          <input v-model="bindForm.verificationCode" type="text" placeholder="请输入QQ私信收到的验证码" maxlength="6" />
        </div>
        <div class="form-group">
          <label>昵称（可选）</label>
          <input v-model="bindForm.nickname" type="text" placeholder="请输入昵称" />
        </div>
        <div class="form-actions">
          <button class="btn-confirm" @click="bindQq" :disabled="isBinding || !bindForm.verificationCode.trim()">
            {{ isBinding ? '绑定中...' : '绑定' }}
          </button>
          <button class="btn-cancel" @click="closeBindModal">取消</button>
        </div>
      </div>
    </div>

    <!-- 头像上传弹窗 -->
    <div v-if="showAvatarUpload" class="modal-overlay" @click.self="showAvatarUpload = false">
      <div class="modal-content">
        <h3>更换头像</h3>
        <div class="avatar-upload">
          <input type="file" accept="image/*" @change="handleAvatarChange" ref="avatarInput" />
          <div v-if="avatarPreview" class="avatar-preview">
            <img :src="avatarPreview" alt="preview" />
          </div>
        </div>
        <div class="form-actions">
          <button class="btn-confirm" @click="uploadAvatar" :disabled="isUploading">
            {{ isUploading ? '上传中...' : '确认' }}
          </button>
          <button class="btn-cancel" @click="showAvatarUpload = false">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, reactive, onMounted } from 'vue';
import Icon from './Icon.vue';
import { userApi } from '../services/api';
import { showToast } from './Toast.vue';
import { showConfirm } from './ConfirmDialog.vue';
import logger from '../utils/logger';

export default {
  components: { Icon },
  name: 'UserProfile',
  props: {
    visible: {
      type: Boolean,
      default: false
    }
  },
  emits: ['update:visible', 'profile-updated'],
  setup(props, { emit }) {
    const defaultAvatar = '/default-avatar.svg';
    const defaultQqAvatar = '/default-avatar.svg';

    const userInfo = reactive({
      id: null,
      username: '',
      nickname: '',
      role: 'USER',
      avatar: ''
    });

    // 获取用户头像完整URL
    const getUserAvatarUrl = (avatar) => {
      if (!avatar) return defaultAvatar;
      if (avatar.startsWith('http')) return avatar;
      // 兼容相对路径是否带前导斜杠
      const normalized = avatar.startsWith('/') ? avatar : '/' + avatar;
      return `http://localhost:8081${normalized}`;
    };

    // 头像加载失败时使用本地默认头像
    const handleAvatarError = (e) => {
      e.target.src = defaultAvatar;
    };

    const qqBindings = ref([]);
    const isEditing = ref(false);
    const isSaving = ref(false);
    const isBinding = ref(false);
    const isUploading = ref(false);
    const showBindQqModal = ref(false);
    const showAvatarUpload = ref(false);
    const avatarPreview = ref('');
    const avatarFile = ref(null);
    const avatarInput = ref(null);
    const isSendingCode = ref(false);
    const codeSent = ref(false);
    const countdown = ref(0);
    let countdownTimer = null;

    const editForm = reactive({
      nickname: ''
    });

    const bindForm = reactive({
      qqNumber: '',
      nickname: '',
      verificationCode: ''
    });

    // 加载用户信息
    const loadUserProfile = async () => {
      try {
        const profile = await userApi.getProfile();
        Object.assign(userInfo, profile);
        editForm.nickname = profile.nickname || '';
      } catch (error) {
        logger.error('加载用户信息失败:', error);
      }
    };

    // 加载QQ绑定列表
    const loadQqBindings = async () => {
      try {
        const bindings = await userApi.getQqBindings();
        qqBindings.value = bindings;
      } catch (error) {
        logger.error('加载QQ绑定失败:', error);
      }
    };

    // 开始编辑
    const startEdit = () => {
      editForm.nickname = userInfo.nickname || '';
      isEditing.value = true;
    };

    // 取消编辑
    const cancelEdit = () => {
      isEditing.value = false;
    };

    // 保存资料
    const saveProfile = async () => {
      isSaving.value = true;
      try {
        await userApi.updateProfile({ nickname: editForm.nickname });
        userInfo.nickname = editForm.nickname;
        isEditing.value = false;
        emit('profile-updated');
      } catch (error) {
        logger.error('保存失败:', error);
        showToast('保存失败: ' + error.message, 'error');
      } finally {
        isSaving.value = false;
      }
    };

    // 发送验证码
    const sendVerificationCode = async () => {
      if (!bindForm.qqNumber.trim()) {
        showToast('请输入QQ号', 'warning');
        return;
      }

      isSendingCode.value = true;
      try {
        await userApi.sendQqBindingCode(bindForm.qqNumber.trim());
        codeSent.value = true;
        countdown.value = 60;
        countdownTimer = setInterval(() => {
          countdown.value--;
          if (countdown.value <= 0) {
            clearInterval(countdownTimer);
          }
        }, 1000);
        showToast('验证码已发送到QQ私信，请查收', 'success');
      } catch (error) {
        logger.error('发送验证码失败:', error);
        showToast('发送失败: ' + error.message, 'error');
      } finally {
        isSendingCode.value = false;
      }
    };

    // 关闭绑定弹窗
    const closeBindModal = () => {
      showBindQqModal.value = false;
      bindForm.qqNumber = '';
      bindForm.nickname = '';
      bindForm.verificationCode = '';
      codeSent.value = false;
      countdown.value = 0;
      if (countdownTimer) {
        clearInterval(countdownTimer);
        countdownTimer = null;
      }
    };

    // 绑定QQ
    const bindQq = async () => {
      if (!bindForm.qqNumber.trim()) {
        showToast('请输入QQ号', 'warning');
        return;
      }
      if (!bindForm.verificationCode.trim()) {
        showToast('请输入验证码', 'warning');
        return;
      }

      isBinding.value = true;
      try {
        await userApi.bindQq({
          qqNumber: bindForm.qqNumber.trim(),
          nickname: bindForm.nickname.trim() || undefined,
          verificationCode: bindForm.verificationCode.trim()
        });
        closeBindModal();
        await loadQqBindings();
        emit('profile-updated');
        showToast('QQ账号绑定成功', 'success');
      } catch (error) {
        logger.error('绑定失败:', error);
        showToast('绑定失败: ' + error.message, 'error');
      } finally {
        isBinding.value = false;
      }
    };

    // 解绑QQ
    const unbindQq = async (bindingId) => {
      const confirmed = await showConfirm({
        title: '解绑确认',
        message: '确定要解绑这个QQ账号吗？解绑后将无法查看该账号的消息记录。',
        type: 'warning',
        confirmText: '确定解绑',
        cancelText: '取消'
      });

      if (!confirmed) {
        return;
      }

      try {
        await userApi.unbindQq(bindingId);
        await loadQqBindings();
        emit('profile-updated');
        showToast('QQ账号已解绑', 'success');
      } catch (error) {
        logger.error('解绑失败:', error);
        showToast('解绑失败: ' + error.message, 'error');
      }
    };

    // 设置默认QQ
    const setDefaultQq = async (bindingId) => {
      try {
        await userApi.setDefaultQq(bindingId);
        await loadQqBindings();
        emit('profile-updated');
        showToast('默认QQ账号已设置', 'success');
      } catch (error) {
        logger.error('设置失败:', error);
        showToast('设置失败: ' + error.message, 'error');
      }
    };

    // 处理头像选择
    const handleAvatarChange = (event) => {
      const file = event.target.files[0];
      if (file) {
        avatarFile.value = file;
        const reader = new FileReader();
        reader.onload = (e) => {
          avatarPreview.value = e.target.result;
        };
        reader.readAsDataURL(file);
      }
    };

    // 上传头像
    const uploadAvatar = async () => {
      if (!avatarFile.value) {
        showToast('请选择图片', 'warning');
        return;
      }

      isUploading.value = true;
      try {
        const result = await userApi.uploadAvatar(avatarFile.value);
        const avatarUrl = result.avatarUrl || result.url;
        // 将新头像持久化到数据库，刷新后才不会再变回旧头像
        await userApi.updateProfile({ avatar: avatarUrl });
        userInfo.avatar = avatarUrl;
        showAvatarUpload.value = false;
        avatarPreview.value = '';
        avatarFile.value = null;
        emit('profile-updated');
        showToast('头像上传成功', 'success');
      } catch (error) {
        logger.error('上传失败:', error);
        showToast('上传失败: ' + error.message, 'error');
      } finally {
        isUploading.value = false;
      }
    };

    // 关闭弹窗
    const closeModal = () => {
      emit('update:visible', false);
    };

    onMounted(() => {
      if (props.visible) {
        loadUserProfile();
        loadQqBindings();
      }
    });

    return {
      userInfo,
      qqBindings,
      isEditing,
      isSaving,
      isBinding,
      isUploading,
      showBindQqModal,
      showAvatarUpload,
      avatarPreview,
      avatarInput,
      isSendingCode,
      codeSent,
      countdown,
      editForm,
      bindForm,
      defaultAvatar,
      defaultQqAvatar,
      startEdit,
      cancelEdit,
      saveProfile,
      sendVerificationCode,
      closeBindModal,
      bindQq,
      unbindQq,
      setDefaultQq,
      handleAvatarChange,
      uploadAvatar,
      closeModal,
      loadUserProfile,
      loadQqBindings,
      getUserAvatarUrl,
      handleAvatarError
    };
  },
  watch: {
    visible(newVal) {
      if (newVal) {
        this.loadUserProfile();
        this.loadQqBindings();
      }
    }
  }
};
</script>

<style scoped>
.user-profile-modal {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: var(--modal-overlay, rgba(0, 0, 0, 0.5));
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
}

.profile-container {
  background: var(--card-bg, var(--card-bg, white));
  border-radius: 12px;
  width: 90%;
  max-width: 600px;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: 0 4px 20px var(--card-shadow, rgba(0, 0, 0, 0.15));
}

.profile-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid var(--border-color, #e0e0e0);
}

.profile-header h2 {
  margin: 0;
  font-size: 20px;
  color: var(--text-primary, #333);
}

.close-btn {
  background: none;
  border: none;
  font-size: 24px;
  color: var(--text-muted, #999);
  cursor: pointer;
  padding: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  transition: all 0.2s;
}

.close-btn:hover {
  background-color: var(--bg-tertiary, #f5f5f5);
  color: var(--text-primary, #333);
}

.profile-content {
  padding: 24px;
}

.section {
  margin-bottom: 32px;
}

.section:last-child {
  margin-bottom: 0;
}

.section h3 {
  margin: 0 0 16px 0;
  font-size: 16px;
  color: var(--text-primary, #333);
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.section-header h3 {
  margin: 0;
}

.user-info-card {
  display: flex;
  gap: 24px;
  padding: 20px;
  background-color: var(--bg-tertiary, #f8f9fa);
  border-radius: 8px;
}

.avatar-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.user-avatar {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  object-fit: cover;
  border: 3px solid var(--card-bg, #fff);
  box-shadow: 0 2px 8px var(--card-shadow, rgba(0, 0, 0, 0.1));
}

.change-avatar-btn {
  padding: 6px 12px;
  font-size: 12px;
  color: var(--accent-color, #3498db);
  background: none;
  border: 1px solid var(--accent-color, #3498db);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
}

.change-avatar-btn:hover {
  background-color: var(--accent-color, #3498db);
  color: white;
}

.info-fields {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.field {
  display: flex;
  align-items: center;
  gap: 12px;
}

.field label {
  width: 60px;
  font-size: 14px;
  color: var(--text-secondary, #666);
}

.field span {
  font-size: 14px;
  color: var(--text-primary, #333);
}

.field input {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid var(--input-border, #ddd);
  border-radius: 4px;
  font-size: 14px;
  background: var(--input-bg, #fff);
  color: var(--text-primary, #333);
}

.role-badge {
  padding: 4px 12px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 500;
}

.role-badge.ADMIN {
  background-color: #e74c3c;
  color: white;
}

.role-badge.USER {
  background-color: #3498db;
  color: white;
}

.action-buttons {
  display: flex;
  gap: 12px;
  margin-top: 16px;
}

.btn-edit,
.btn-save,
.btn-cancel,
.btn-add,
.btn-confirm {
  padding: 8px 20px;
  border-radius: 4px;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s;
  border: none;
}

.btn-edit {
  background-color: var(--accent-color, #3498db);
  color: white;
}

.btn-edit:hover {
  background-color: var(--accent-hover, #2980b9);
}

.btn-save {
  background-color: #27ae60;
  color: white;
}

.btn-save:hover:not(:disabled) {
  background-color: #229954;
}

.btn-cancel {
  background-color: #95a5a6;
  color: white;
}

.btn-cancel:hover {
  background-color: #7f8c8d;
}

.btn-add {
  background-color: #27ae60;
  color: white;
}

.btn-add:hover {
  background-color: #229954;
}

.btn-confirm {
  background-color: var(--accent-color, #3498db);
  color: white;
}

.btn-confirm:hover:not(:disabled) {
  background-color: var(--accent-hover, #2980b9);
}

button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.empty-state {
  text-align: center;
  padding: 40px 20px;
  color: var(--text-muted, #999);
}

.empty-state p {
  margin: 0;
}

.empty-state .hint {
  font-size: 12px;
  margin-top: 8px;
  color: #bbb;
}

.qq-bindings-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.qq-binding-item {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  background-color: var(--bg-tertiary, #f8f9fa);
  border-radius: 8px;
  border: 2px solid var(--border-color, #ddd);
  transition: all 0.2s;
}

.qq-binding-item.is-default {
  border-color: var(--accent-color, #3498db);
  background-color: var(--bg-tertiary, #ebf5fb);
}

.qq-avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  overflow: hidden;
  background-color: #ddd;
}

.qq-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.qq-info {
  flex: 1;
}

.qq-number {
  font-size: 16px;
  font-weight: 500;
  color: var(--text-primary, #333);
}

.qq-nickname {
  font-size: 13px;
  color: var(--text-secondary, #666);
  margin-top: 4px;
}

.default-badge {
  display: inline-block;
  margin-top: 4px;
  padding: 2px 8px;
  background-color: #3498db;
  color: white;
  font-size: 11px;
  border-radius: 10px;
}

.qq-actions {
  display: flex;
  gap: 8px;
}

.btn-set-default,
.btn-unbind {
  padding: 6px 12px;
  font-size: 12px;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.2s;
  border: none;
}

.btn-set-default {
  background-color: var(--accent-color, #3498db);
  color: white;
}

.btn-set-default:hover {
  background-color: var(--accent-hover, #2980b9);
}

.btn-unbind {
  background-color: #e74c3c;
  color: white;
}

.btn-unbind:hover {
  background-color: #c0392b;
}

.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: var(--modal-overlay, rgba(0, 0, 0, 0.5));
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1100;
}

.modal-content {
  background: var(--card-bg, var(--card-bg, white));
  padding: 24px;
  border-radius: 8px;
  width: 90%;
  max-width: 400px;
}

.modal-content h3 {
  margin: 0 0 20px 0;
  font-size: 18px;
}

.form-group {
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  margin-bottom: 6px;
  font-size: 14px;
  color: var(--text-secondary, #666);
}

.form-group input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--input-border, #ddd);
  border-radius: 4px;
  font-size: 14px;
  box-sizing: border-box;
  background: var(--input-bg, #fff);
  color: var(--text-primary, #333);
}

.bind-hint {
  font-size: 13px;
  color: #e67e22;
  margin: -10px 0 16px 0;
  padding: 8px 12px;
  background-color: #fef5e7;
  border-radius: 4px;
}

.input-with-btn {
  display: flex;
  gap: 8px;
}

.input-with-btn input {
  flex: 1;
}

.btn-send-code {
  padding: 8px 14px;
  background-color: var(--accent-color, #3498db);
  color: white;
  border: none;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
  white-space: nowrap;
  min-width: 100px;
}

.btn-send-code:hover:not(:disabled) {
  background-color: var(--accent-hover, #2980b9);
}

.btn-send-code:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
}

.form-actions {
  display: flex;
  gap: 12px;
  margin-top: 24px;
}

.form-actions button {
  flex: 1;
}

.avatar-upload {
  margin: 20px 0;
}

.avatar-upload input[type="file"] {
  width: 100%;
  padding: 10px;
  border: 2px dashed var(--input-border, #ddd);
  border-radius: 4px;
  cursor: pointer;
}

.avatar-preview {
  margin-top: 16px;
  text-align: center;
}

.avatar-preview img {
  width: 120px;
  height: 120px;
  border-radius: 50%;
  object-fit: cover;
  border: 3px solid var(--card-bg, #fff);
  box-shadow: 0 2px 8px var(--card-shadow, rgba(0, 0, 0, 0.1));
}
</style>
