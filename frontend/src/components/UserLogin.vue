<template>
  <div v-if="visible" class="login-overlay" @click="closeIfNotProcessing">
    <div class="login-container" @click.stop>
      <div class="login-header">
        <h2>{{ isRegistering ? '注册账号' : '用户登录' }}</h2>
        <button class="close-btn" @click="closeModal" :disabled="isProcessing">×</button>
      </div>
      
      <div class="login-body">
        <!-- 登录表单 -->
        <form v-if="!isRegistering" @submit.prevent="handleLogin">
          <div class="form-group">
            <label for="username">账号</label>
            <input 
              id="username"
              v-model="loginForm.username" 
              type="text" 
              placeholder="请输入账号"
              required
              :disabled="isProcessing"
            />
          </div>
          
          <div class="form-group">
            <label for="password">密码</label>
            <input 
              id="password"
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
          
          <div class="form-footer">
            <span>还没有账号？</span>
            <a href="#" @click.prevent="switchToRegister">立即注册</a>
          </div>
        </form>
        
        <!-- 注册表单 -->
        <form v-else @submit.prevent="handleRegister">
          <div class="form-group">
            <label for="reg-username">账号</label>
            <input 
              id="reg-username"
              v-model="registerForm.username" 
              type="text" 
              placeholder="请输入账号"
              required
              :disabled="isProcessing"
            />
          </div>
          
          <div class="form-group">
            <label for="reg-nickname">昵称</label>
            <input 
              id="reg-nickname"
              v-model="registerForm.nickname" 
              type="text" 
              placeholder="请输入昵称（可选）"
              :disabled="isProcessing"
            />
          </div>
          
          <div class="form-group">
            <label for="reg-password">密码</label>
            <input 
              id="reg-password"
              v-model="registerForm.password" 
              type="password" 
              placeholder="请输入密码"
              required
              :disabled="isProcessing"
            />
          </div>
          
          <div class="form-group">
            <label for="reg-confirm-password">确认密码</label>
            <input 
              id="reg-confirm-password"
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
          
          <div class="form-footer">
            <span>已有账号？</span>
            <a href="#" @click.prevent="switchToLogin">立即登录</a>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, reactive } from 'vue';
import { authApi } from '../services/api';

export default {
  name: 'UserLogin',
  props: {
    visible: {
      type: Boolean,
      default: false
    }
  },
  emits: ['update:visible', 'login-success'],
  setup(props, { emit }) {
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
    
    const handleLogin = async () => {
      if (!loginForm.username || !loginForm.password) {
        errorMessage.value = '请输入账号和密码';
        return;
      }
      
      isProcessing.value = true;
      errorMessage.value = '';
      
      try {
        const response = await authApi.login(loginForm.username, loginForm.password);
        
        // 保存token和用户信息
        localStorage.setItem('auth_token', response.token);
        localStorage.setItem('user_info', JSON.stringify({
          id: response.id,
          username: response.username,
          nickname: response.nickname,
          role: response.role,
          avatar: response.avatar
        }));
        
        emit('login-success', response);
        closeModal();
        
        // 清空表单
        loginForm.username = '';
        loginForm.password = '';
      } catch (error) {
        errorMessage.value = error.message || '登录失败，请检查账号和密码';
      } finally {
        isProcessing.value = false;
      }
    };
    
    const handleRegister = async () => {
      if (!registerForm.username || !registerForm.password) {
        errorMessage.value = '请输入账号和密码';
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
        
        // 注册成功后自动登录
        const response = await authApi.login(registerForm.username, registerForm.password);
        
        localStorage.setItem('auth_token', response.token);
        localStorage.setItem('user_info', JSON.stringify({
          username: response.username,
          nickname: response.nickname,
          role: response.role,
          avatar: response.avatar
        }));
        
        emit('login-success', response);
        closeModal();
        
        // 清空表单
        registerForm.username = '';
        registerForm.nickname = '';
        registerForm.password = '';
        registerForm.confirmPassword = '';
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
    
    const closeModal = () => {
      emit('update:visible', false);
    };
    
    const closeIfNotProcessing = () => {
      if (!isProcessing.value) {
        closeModal();
      }
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
      switchToLogin,
      closeModal,
      closeIfNotProcessing
    };
  }
};
</script>

<style scoped>
.login-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 3000;
}

.login-container {
  background-color: white;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
  width: 90%;
  max-width: 400px;
  overflow: hidden;
}

.login-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24px 24px 0;
}

.login-header h2 {
  margin: 0;
  color: #2c3e50;
  font-size: 24px;
  font-weight: 600;
}

.close-btn {
  background: none;
  border: none;
  font-size: 28px;
  cursor: pointer;
  color: #95a5a6;
  padding: 0;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  transition: all 0.2s;
}

.close-btn:hover:not(:disabled) {
  background-color: #f0f0f0;
  color: #2c3e50;
}

.close-btn:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.login-body {
  padding: 24px;
}

.form-group {
  margin-bottom: 20px;
}

.form-group label {
  display: block;
  margin-bottom: 8px;
  color: #2c3e50;
  font-size: 14px;
  font-weight: 500;
}

.form-group input {
  width: 100%;
  padding: 12px 16px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  font-size: 15px;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.form-group input:focus {
  outline: none;
  border-color: #3498db;
  box-shadow: 0 0 0 3px rgba(52, 152, 219, 0.1);
}

.form-group input:disabled {
  background-color: #f5f5f5;
  cursor: not-allowed;
}

.error-message {
  background-color: #fee;
  color: #c33;
  padding: 12px 16px;
  border-radius: 8px;
  margin-bottom: 20px;
  font-size: 14px;
}

.btn-submit {
  width: 100%;
  padding: 14px;
  background-color: #3498db;
  color: white;
  border: none;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 500;
  cursor: pointer;
  transition: background-color 0.2s;
}

.btn-submit:hover:not(:disabled) {
  background-color: #2980b9;
}

.btn-submit:disabled {
  background-color: #bdc3c7;
  cursor: not-allowed;
}

.form-footer {
  text-align: center;
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid #e0e0e0;
  color: #7f8c8d;
  font-size: 14px;
}

.form-footer a {
  color: #3498db;
  text-decoration: none;
  margin-left: 4px;
  font-weight: 500;
}

.form-footer a:hover {
  text-decoration: underline;
}
</style>
