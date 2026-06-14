<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <div class="brand-icon"><Icon name="robot" :size="56" /></div>
        <h1>QQ AI 助手</h1>
        <p>智能群聊管理与 AI 对话平台</p>
      </div>

      <div class="login-form-wrapper">
        <h2>{{ isRegistering ? '注册账号' : '用户登录' }}</h2>

        <!-- 登录表单 -->
        <form v-if="!isRegistering" @submit.prevent="handleLogin" class="login-form">
          <div class="form-group">
            <label>用户名</label>
            <input
              v-model="loginForm.username"
              type="text"
              placeholder="请输入用户名"
              required
              :disabled="isProcessing"
            />
          </div>

          <div class="form-group">
            <label>密码</label>
            <input
              v-model="loginForm.password"
              type="password"
              placeholder="请输入密码"
              required
              :disabled="isProcessing"
            />
          </div>

          <div v-if="errorMessage" class="error-message">
            {{ errorMessage }}
          </div>

          <button type="submit" class="btn-submit" :disabled="isProcessing">
            {{ isProcessing ? '登录中...' : '登录' }}
          </button>

          <div class="form-switch">
            <span>还没有账号？</span>
            <a href="#" @click.prevent="switchToRegister">立即注册</a>
          </div>
        </form>

        <!-- 注册表单 -->
        <form v-else @submit.prevent="handleRegister" class="login-form">
          <div class="form-group">
            <label>用户名</label>
            <input
              v-model="registerForm.username"
              type="text"
              placeholder="请输入用户名"
              required
              :disabled="isProcessing"
            />
          </div>

          <div class="form-group">
            <label>昵称</label>
            <input
              v-model="registerForm.nickname"
              type="text"
              placeholder="请输入昵称（可选）"
              :disabled="isProcessing"
            />
          </div>

          <div class="form-group">
            <label>密码</label>
            <input
              v-model="registerForm.password"
              type="password"
              placeholder="请输入密码"
              required
              :disabled="isProcessing"
            />
          </div>

          <div class="form-group">
            <label>确认密码</label>
            <input
              v-model="registerForm.confirmPassword"
              type="password"
              placeholder="请再次输入密码"
              required
              :disabled="isProcessing"
            />
          </div>

          <div v-if="errorMessage" class="error-message">
            {{ errorMessage }}
          </div>

          <button type="submit" class="btn-submit" :disabled="isProcessing">
            {{ isProcessing ? '注册中...' : '注册' }}
          </button>

          <div class="form-switch">
            <span>已有账号？</span>
            <a href="#" @click.prevent="switchToLogin">立即登录</a>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, reactive, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import Icon from '../components/Icon.vue';
import { authApi } from '../services/api';

export default {
  name: 'LoginPage',
  components: { Icon },
  setup() {
    const router = useRouter();
    const isRegistering = ref(false);
    const isProcessing = ref(false);
    const errorMessage = ref('');

    const loginForm = reactive({
      username: '',
      password: ''
    });

    const registerForm = reactive({
      username: '',
      nickname: '',
      password: '',
      confirmPassword: ''
    });

    // 如果已登录，自动跳转到首页
    onMounted(() => {
      const token = localStorage.getItem('auth_token');
      if (token) {
        router.replace('/');
      }
    });

    const handleLogin = async () => {
      if (!loginForm.username || !loginForm.password) {
        errorMessage.value = '请输入用户名和密码';
        return;
      }

      isProcessing.value = true;
      errorMessage.value = '';

      try {
        const response = await authApi.login(loginForm.username, loginForm.password);

        localStorage.setItem('auth_token', response.token);
        localStorage.setItem('user_role', response.role || 'USER');
        localStorage.setItem('user_info', JSON.stringify({
          username: response.username,
          nickname: response.nickname,
          role: response.role,
          avatar: response.avatar
        }));
        localStorage.setItem('isLoggedIn', 'true');

        // 登录成功后根据角色跳转
        if (response.role === 'ADMIN') {
          router.push('/admin');
        } else {
          router.push('/');
        }
      } catch (error) {
        errorMessage.value = error.message || '登录失败，请检查用户名和密码';
      } finally {
        isProcessing.value = false;
      }
    };

    const handleRegister = async () => {
      if (!registerForm.username || !registerForm.password) {
        errorMessage.value = '请输入用户名和密码';
        return;
      }

      if (registerForm.password !== registerForm.confirmPassword) {
        errorMessage.value = '两次输入的密码不一致';
        return;
      }

      if (registerForm.password.length < 6) {
        errorMessage.value = '密码长度至少为6位';
        return;
      }

      isProcessing.value = true;
      errorMessage.value = '';

      try {
        await authApi.register(
          registerForm.username,
          registerForm.password,
          registerForm.nickname || registerForm.username
        );

        const response = await authApi.login(registerForm.username, registerForm.password);

        localStorage.setItem('auth_token', response.token);
        localStorage.setItem('user_role', response.role || 'USER');
        localStorage.setItem('user_info', JSON.stringify({
          username: response.username,
          nickname: response.nickname,
          role: response.role,
          avatar: response.avatar
        }));
        localStorage.setItem('isLoggedIn', 'true');

        // 注册并登录成功后根据角色跳转
        if (response.role === 'ADMIN') {
          router.push('/admin');
        } else {
          router.push('/');
        }
      } catch (error) {
        errorMessage.value = error.message || '注册失败，请稍后重试';
      } finally {
        isProcessing.value = false;
      }
    };

    const switchToRegister = () => {
      isRegistering.value = true;
      errorMessage.value = '';
    };

    const switchToLogin = () => {
      isRegistering.value = false;
      errorMessage.value = '';
    };

    return {
      isRegistering,
      isProcessing,
      errorMessage,
      loginForm,
      registerForm,
      handleLogin,
      handleRegister,
      switchToRegister,
      switchToLogin
    };
  }
};
</script>

<style scoped>
.login-page {
  width: 100vw;
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.login-card {
  background: white;
  border-radius: 16px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  width: 90%;
  max-width: 420px;
  overflow: hidden;
}

.login-brand {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 40px 30px;
  text-align: center;
  color: white;
}

.brand-icon {
  font-size: 56px;
  margin-bottom: 12px;
}

.login-brand h1 {
  margin: 0 0 8px 0;
  font-size: 24px;
  font-weight: 600;
}

.login-brand p {
  margin: 0;
  opacity: 0.9;
  font-size: 14px;
}

.login-form-wrapper {
  padding: 30px;
}

.login-form-wrapper h2 {
  margin: 0 0 24px 0;
  font-size: 20px;
  color: #2c3e50;
  text-align: center;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-group label {
  font-size: 14px;
  color: #555;
  font-weight: 500;
}

.form-group input {
  padding: 12px 14px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 15px;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.form-group input:focus {
  outline: none;
  border-color: #667eea;
  box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.1);
}

.form-group input:disabled {
  background-color: #f5f5f5;
  cursor: not-allowed;
}

.error-message {
  color: #e74c3c;
  font-size: 13px;
  text-align: center;
  padding: 8px;
  background-color: #fdf2f2;
  border-radius: 6px;
}

.btn-submit {
  padding: 14px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.2s, transform 0.2s;
}

.btn-submit:hover:not(:disabled) {
  opacity: 0.9;
  transform: translateY(-1px);
}

.btn-submit:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.form-switch {
  text-align: center;
  font-size: 14px;
  color: #666;
}

.form-switch a {
  color: #667eea;
  text-decoration: none;
  font-weight: 500;
}

.form-switch a:hover {
  text-decoration: underline;
}
</style>
