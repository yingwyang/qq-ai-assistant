<template>
  <div class="login-page">
    <!-- 动态背景 -->
    <div class="background-effects">
      <div class="gradient-bg"></div>
      <div class="floating-orbs">
        <div class="orb orb-1"></div>
        <div class="orb orb-2"></div>
        <div class="orb orb-3"></div>
        <div class="orb orb-4"></div>
        <div class="orb orb-5"></div>
      </div>
      <div class="particles">
        <div v-for="(style, n) in particleStyles" :key="n" class="particle" :style="style"></div>
      </div>
    </div>

    <!-- 登录卡片 -->
    <div class="login-card">
      <!-- 卡片光效装饰 -->
      <div class="card-glow card-glow-top"></div>
      <div class="card-glow card-glow-bottom"></div>
      
      <div class="login-brand">
        <div class="brand-icon-wrapper">
          <div class="brand-icon"><Icon name="robot" :size="56" /></div>
          <div class="icon-ring"></div>
        </div>
        <h1 class="brand-title">QQ AI 助手</h1>
        <p class="brand-subtitle">智能群聊管理与 AI 对话平台</p>
        <div class="brand-decoration">
          <span class="decor-line"></span>
          <span class="decor-dot"></span>
          <span class="decor-line"></span>
        </div>
      </div>

      <div class="login-form-wrapper">
        <h2 class="form-title">{{ isRegistering ? '注册账号' : '用户登录' }}</h2>

        <!-- 登录表单 -->
        <form v-if="!isRegistering" @submit.prevent="handleLogin" class="login-form">
          <div class="form-group">
            <div class="input-wrapper">
              <svg class="input-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
                <circle cx="12" cy="7" r="4"></circle>
              </svg>
              <input
                v-model="loginForm.username"
                type="text"
                placeholder="请输入账号"
                required
                :disabled="isProcessing"
                maxlength="32"
                class="form-input"
              />
            </div>
          </div>

          <div class="form-group">
            <div class="input-wrapper">
              <svg class="input-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect>
                <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
              </svg>
              <input
                v-model="loginForm.password"
                :type="showLoginPassword ? 'text' : 'password'"
                placeholder="请输入密码"
                required
                :disabled="isProcessing"
                maxlength="64"
                class="form-input"
              />
              <button type="button" class="password-toggle" @click="showLoginPassword = !showLoginPassword">
                <svg v-if="showLoginPassword" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a8.06 8.06 0 0 1 11.37-5.74"></path>
                  <path d="M9.66 9.66a3 3 0 1 1 4.24 4.24"></path>
                  <path d="M15.73 7.27a6.98 6.98 0 0 1 0 9.86"></path>
                </svg>
                <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M21 12c0 3.31-2.69 6-6 6s-6-2.69-6-6 2.69-6 6-6 6 2.69 6 6z"></path>
                  <path d="M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"></path>
                </svg>
              </button>
            </div>
          </div>

          <div v-if="errorMessage" class="error-message">
            <svg class="error-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
              <line x1="12" y1="9" x2="12" y2="13"></line>
              <line x1="12" y1="17" x2="12.01" y2="17"></line>
            </svg>
            {{ errorMessage }}
          </div>

          <button type="submit" class="btn-submit" :disabled="isProcessing">
            <span v-if="isProcessing" class="btn-loader"></span>
            {{ isProcessing ? '登录中...' : '登 录' }}
          </button>

          <div class="form-switch">
            <span>还没有账号？</span>
            <a href="#" @click.prevent="switchToRegister">立即注册</a>
          </div>
        </form>

        <!-- 注册表单 -->
        <form v-else @submit.prevent="handleRegister" class="login-form">
          <div class="form-group">
            <div class="input-wrapper">
              <svg class="input-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
                <circle cx="12" cy="7" r="4"></circle>
              </svg>
              <input
                v-model="registerForm.username"
                type="text"
                placeholder="请输入账号"
                required
                :disabled="isProcessing"
                maxlength="32"
                class="form-input"
              />
            </div>
          </div>

          <div class="form-group">
            <div class="input-wrapper">
              <svg class="input-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"></polygon>
              </svg>
              <input
                v-model="registerForm.nickname"
                type="text"
                placeholder="请输入昵称（可选）"
                :disabled="isProcessing"
                maxlength="32"
                class="form-input"
              />
            </div>
          </div>

          <div class="form-group">
            <div class="input-wrapper">
              <svg class="input-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect>
                <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
              </svg>
              <input
                v-model="registerForm.password"
                :type="showRegisterPassword ? 'text' : 'password'"
                placeholder="请输入密码"
                required
                :disabled="isProcessing"
                maxlength="64"
                class="form-input"
              />
              <button type="button" class="password-toggle" @click="showRegisterPassword = !showRegisterPassword">
                <svg v-if="showRegisterPassword" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a8.06 8.06 0 0 1 11.37-5.74"></path>
                  <path d="M9.66 9.66a3 3 0 1 1 4.24 4.24"></path>
                  <path d="M15.73 7.27a6.98 6.98 0 0 1 0 9.86"></path>
                </svg>
                <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M21 12c0 3.31-2.69 6-6 6s-6-2.69-6-6 2.69-6 6-6 6 2.69 6 6z"></path>
                  <path d="M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"></path>
                </svg>
              </button>
            </div>
          </div>

          <div class="form-group">
            <div class="input-wrapper">
              <svg class="input-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                <polyline points="17 8 12 3 7 8"></polyline>
                <line x1="12" y1="3" x2="12" y2="15"></line>
              </svg>
              <input
                v-model="registerForm.confirmPassword"
                :type="showRegisterPassword ? 'text' : 'password'"
                placeholder="请再次输入密码"
                required
                :disabled="isProcessing"
                maxlength="64"
                class="form-input"
              />
            </div>
          </div>

          <div v-if="errorMessage" class="error-message">
            <svg class="error-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
              <line x1="12" y1="9" x2="12" y2="13"></line>
              <line x1="12" y1="17" x2="12.01" y2="17"></line>
            </svg>
            {{ errorMessage }}
          </div>

          <button type="submit" class="btn-submit" :disabled="isProcessing">
            <span v-if="isProcessing" class="btn-loader"></span>
            {{ isProcessing ? '注册中...' : '注 册' }}
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
import { ref, reactive, onMounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import Icon from '../components/Icon.vue';
import { authApi } from '../services/api';

// 表单持久化 key（sessionStorage：刷新不丢失，关闭标签页清除）
const LOGIN_FORM_KEY = 'login_form_draft';
const REGISTER_FORM_KEY = 'register_form_draft';
const ACTIVE_TAB_KEY = 'login_active_tab';

export default {
  name: 'LoginPage',
  components: { Icon },
  setup() {
    const router = useRouter();
    const isRegistering = ref(false);
    const isProcessing = ref(false);
    const errorMessage = ref('');
    const showLoginPassword = ref(false);
    const showRegisterPassword = ref(false);

    // 从 sessionStorage 恢复表单草稿
    const loadDraft = (key, fallback) => {
      try {
        const raw = sessionStorage.getItem(key);
        return raw ? { ...fallback, ...JSON.parse(raw) } : { ...fallback };
      } catch (e) {
        return { ...fallback };
      }
    };

    const loginForm = reactive(loadDraft(LOGIN_FORM_KEY, {
      username: '',
      password: ''
    }));

    const registerForm = reactive(loadDraft(REGISTER_FORM_KEY, {
      username: '',
      nickname: '',
      password: '',
      confirmPassword: ''
    }));

    // 恢复上次的登录/注册标签页
    try {
      isRegistering.value = sessionStorage.getItem(ACTIVE_TAB_KEY) === 'register';
    } catch (e) {
      // ignore
    }

    // 监听表单变化，自动持久化到 sessionStorage
    watch(loginForm, (val) => {
      try {
        sessionStorage.setItem(LOGIN_FORM_KEY, JSON.stringify(val));
      } catch (e) {
        // ignore
      }
    }, { deep: true });

    watch(registerForm, (val) => {
      try {
        sessionStorage.setItem(REGISTER_FORM_KEY, JSON.stringify(val));
      } catch (e) {
        // ignore
      }
    }, { deep: true });

    watch(isRegistering, (val) => {
      try {
        sessionStorage.setItem(ACTIVE_TAB_KEY, val ? 'register' : 'login');
      } catch (e) {
        // ignore
      }
    });

    // 如果已有 Cookie 登录态，自动跳转首页（快速判断：有缓存的 user_role 即视为已登录）
    onMounted(() => {
      const cachedRole = localStorage.getItem('user_role');
      if (cachedRole) {
        if (cachedRole === 'ADMIN') {
          router.replace('/admin');
        } else {
          router.replace('/');
        }
      }
    });

    const handleLogin = async () => {
      if (!loginForm.username || !loginForm.password) {
        errorMessage.value = '请输入账号和密码';
        return;
      }

      isProcessing.value = true;
      errorMessage.value = '';

      try {
        const data = await authApi.login(loginForm.username, loginForm.password);

        // 登录成功（Cookie 已由后端 Set-Cookie 自动设置），通过 /me 获取角色后跳转
        // 保存用户基本信息到 localStorage（非敏感缓存）
        localStorage.setItem('user_info', JSON.stringify({
          username: data.username,
          nickname: data.nickname,
          role: data.role,
          avatar: data.avatar,
          email: data.email,
          createdAt: data.createdAt,
          lastLoginTime: data.lastLoginTime
        }));

        // 清除表单草稿
        sessionStorage.removeItem(LOGIN_FORM_KEY);
        sessionStorage.removeItem(REGISTER_FORM_KEY);
        sessionStorage.removeItem(ACTIVE_TAB_KEY);

        // 通过 /api/auth/me 获取真实角色
        let role = data.role || 'USER';
        try {
          const meData = await authApi.getCurrentUser();
          role = meData?.role || role;
        } catch (e) {
          // me 失败时用登录返回的 role 兜底
        }
        localStorage.setItem('user_role', role);

        // 根据角色跳转（插件启动由后端 PluginEnsureService 异步处理）
        if (role === 'ADMIN') {
          router.push('/admin');
        } else {
          router.push('/');
        }
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

        const data = await authApi.login(registerForm.username, registerForm.password);

        // 登录成功（Cookie 已由后端 Set-Cookie 自动设置）
        localStorage.setItem('user_info', JSON.stringify({
          username: data.username,
          nickname: data.nickname,
          role: data.role,
          avatar: data.avatar,
          email: data.email,
          createdAt: data.createdAt,
          lastLoginTime: data.lastLoginTime
        }));

        // 清除表单草稿
        sessionStorage.removeItem(LOGIN_FORM_KEY);
        sessionStorage.removeItem(REGISTER_FORM_KEY);
        sessionStorage.removeItem(ACTIVE_TAB_KEY);

        // 通过 /api/auth/me 获取真实角色
        let role = data.role || 'USER';
        try {
          const meData = await authApi.getCurrentUser();
          role = meData?.role || role;
        } catch (e) {
          // me 失败时用登录返回的 role 兜底
        }
        localStorage.setItem('user_role', role);

        // 根据角色跳转（插件启动由后端 PluginEnsureService 异步处理）
        if (role === 'ADMIN') {
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

    // 粒子样式在初始化时一次性计算（避免每次渲染调用 Math.random 导致漂移）
    const colors = ['#fff', '#a855f7', '#6366f1', '#8b5cf6', '#c084fc'];
    const particleStyles = Array.from({ length: 20 }, (_, n) => ({
      left: `${Math.random() * 100}%`,
      animationDelay: `${Math.random() * 5}s`,
      animationDuration: `${5 + Math.random() * 10}s`,
      backgroundColor: colors[n % colors.length],
      width: `${2 + Math.random() * 4}px`,
      height: `${2 + Math.random() * 4}px`
    }));

    return {
      isRegistering,
      isProcessing,
      errorMessage,
      showLoginPassword,
      showRegisterPassword,
      loginForm,
      registerForm,
      handleLogin,
      handleRegister,
      switchToRegister,
      switchToLogin,
      particleStyles
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
  position: relative;
  overflow: hidden;
  animation: pageFadeIn 0.8s ease-out;
}

@keyframes pageFadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

/* 动态背景效果 */
.background-effects {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  z-index: 0;
}

.gradient-bg {
  position: absolute;
  width: 100%;
  height: 100%;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 50%, #f093fb 100%);
  animation: gradientShift 15s ease infinite;
}

@keyframes gradientShift {
  0%, 100% {
    background: linear-gradient(135deg, #667eea 0%, #764ba2 50%, #f093fb 100%);
  }
  33% {
    background: linear-gradient(135deg, #764ba2 0%, #f093fb 50%, #667eea 100%);
  }
  66% {
    background: linear-gradient(135deg, #f093fb 0%, #667eea 50%, #764ba2 100%);
  }
}

/* 浮动光球 */
.floating-orbs {
  position: absolute;
  width: 100%;
  height: 100%;
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.5;
  animation: floatOrb 8s ease-in-out infinite;
}

.orb-1 {
  width: 400px;
  height: 400px;
  background: #a855f7;
  top: -100px;
  left: -100px;
  animation-delay: 0s;
}

.orb-2 {
  width: 300px;
  height: 300px;
  background: #6366f1;
  top: 50%;
  right: -150px;
  animation-delay: -2s;
}

.orb-3 {
  width: 250px;
  height: 250px;
  background: #ec4899;
  bottom: -100px;
  left: 30%;
  animation-delay: -4s;
}

.orb-4 {
  width: 200px;
  height: 200px;
  background: #8b5cf6;
  top: 30%;
  left: 60%;
  animation-delay: -1s;
}

.orb-5 {
  width: 150px;
  height: 150px;
  background: #06b6d4;
  bottom: 20%;
  right: 20%;
  animation-delay: -3s;
}

@keyframes floatOrb {
  0%, 100% {
    transform: translate(0, 0) scale(1);
  }
  25% {
    transform: translate(30px, -30px) scale(1.1);
  }
  50% {
    transform: translate(-20px, 20px) scale(0.95);
  }
  75% {
    transform: translate(20px, 10px) scale(1.05);
  }
}

/* 粒子效果 */
.particles {
  position: absolute;
  width: 100%;
  height: 100%;
  overflow: hidden;
}

.particle {
  position: absolute;
  border-radius: 50%;
  bottom: -20px;
  opacity: 0.6;
  animation: particleFloat linear infinite;
}

@keyframes particleFloat {
  0% {
    transform: translateY(0) translateX(0);
    opacity: 0;
  }
  10% {
    opacity: 0.6;
  }
  90% {
    opacity: 0.6;
  }
  100% {
    transform: translateY(-100vh) translateX(50px);
    opacity: 0;
  }
}

/* 登录卡片 */
.login-card {
  position: relative;
  z-index: 10;
  background: rgba(255, 255, 255, 0.15);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: 24px;
  box-shadow: 
    0 8px 32px rgba(0, 0, 0, 0.2),
    inset 0 1px 0 rgba(255, 255, 255, 0.3);
  width: 90%;
  max-width: 440px;
  overflow: hidden;
  animation: cardSlideUp 0.6s ease-out 0.2s both;
  text-align: right;
}

@keyframes cardSlideUp {
  from {
    opacity: 0;
    transform: translateY(30px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 卡片光效装饰 */
.card-glow {
  position: absolute;
  width: 200px;
  height: 200px;
  border-radius: 50%;
  filter: blur(60px);
  opacity: 0.3;
  pointer-events: none;
}

.card-glow-top {
  background: linear-gradient(135deg, #a855f7, #6366f1);
  top: -100px;
  left: -50px;
}

.card-glow-bottom {
  background: linear-gradient(135deg, #ec4899, #f093fb);
  bottom: -100px;
  right: -50px;
}

/* 品牌区域 */
.login-brand {
  padding: 45px 30px;
  text-align: center;
  position: relative;
}

.brand-icon-wrapper {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 16px;
}

.brand-icon {
  font-size: 56px;
  color: white;
  position: relative;
  z-index: 2;
  animation: iconFloat 3s ease-in-out infinite;
}

@keyframes iconFloat {
  0%, 100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-10px);
  }
}

.icon-ring {
  position: absolute;
  width: 80px;
  height: 80px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-radius: 50%;
  animation: ringRotate 8s linear infinite;
}

.icon-ring::before {
  content: '';
  position: absolute;
  top: -4px;
  left: 50%;
  width: 8px;
  height: 8px;
  background: white;
  border-radius: 50%;
  transform: translateX(-50%);
}

@keyframes ringRotate {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.brand-title {
  margin: 0 0 10px 0;
  font-size: 28px;
  font-weight: 700;
  background: linear-gradient(135deg, #fff, #e0e7ff);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  text-shadow: 0 2px 10px rgba(0, 0, 0, 0.2);
  letter-spacing: 2px;
}

.brand-subtitle {
  margin: 0 0 20px 0;
  opacity: 0.9;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.9);
  letter-spacing: 1px;
}

.brand-decoration {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.decor-line {
  width: 40px;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.5), transparent);
}

.decor-dot {
  width: 6px;
  height: 6px;
  background: white;
  border-radius: 50%;
  box-shadow: 0 0 10px rgba(255, 255, 255, 0.5);
}

/* 表单区域 */
.login-form-wrapper {
  padding: 35px 30px;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(10px);
}

.form-title {
  margin: 0 0 28px 0;
  font-size: 22px;
  color: #1e293b;
  text-align: center;
  font-weight: 600;
  font-family: "Lucida Console", Monaco, monospace;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.input-wrapper {
  position: relative;
}

.input-icon {
  position: absolute;
  left: 14px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 16px;
  z-index: 2;
}

.form-input {
  width: 100%;
  padding: 14px 14px 14px 44px;
  border: 2px solid #e2e8f0;
  border-radius: 12px;
  font-size: 15px;
  transition: all 0.3s ease;
  background: #f8fafc;
  box-sizing: border-box;
}

.form-input:focus {
  outline: none;
  border-color: #8b5cf6;
  background: white;
  box-shadow: 
    0 0 0 3px rgba(139, 92, 246, 0.1),
    0 4px 20px rgba(139, 92, 246, 0.1);
  transform: translateY(-1px);
}

.form-input::placeholder {
  color: #94a3b8;
}

.form-input:disabled {
  background-color: #f1f5f9;
  cursor: not-allowed;
  opacity: 0.7;
}

.password-toggle {
  position: absolute;
  right: 10px;
  top: 50%;
  transform: translateY(-50%);
  background: none;
  border: none;
  font-size: 16px;
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  transition: background-color 0.2s;
}

.password-toggle:hover {
  background-color: rgba(0, 0, 0, 0.05);
}

/* 错误消息 */
.error-message {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #dc2626;
  font-size: 13px;
  padding: 12px 16px;
  background-color: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 10px;
  animation: errorShake 0.3s ease;
}

@keyframes errorShake {
  0%, 100% {
    transform: translateX(0);
  }
  25% {
    transform: translateX(-5px);
  }
  75% {
    transform: translateX(5px);
  }
}

.error-icon {
  font-size: 14px;
}

/* 提交按钮 */
.btn-submit {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 16px;
  background: linear-gradient(135deg, #8b5cf6 0%, #6366f1 100%);
  color: white;
  border: none;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s ease;
  position: relative;
  overflow: hidden;
  letter-spacing: 2px;
}

.btn-submit::before {
  content: '';
  position: absolute;
  top: 0;
  left: -100%;
  width: 100%;
  height: 100%;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.2), transparent);
  transition: left 0.5s ease;
}

.btn-submit:hover:not(:disabled)::before {
  left: 100%;
}

.btn-submit:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 8px 25px rgba(139, 92, 246, 0.35);
}

.btn-submit:active:not(:disabled) {
  transform: translateY(-1px);
}

.btn-submit:disabled {
  opacity: 0.7;
  cursor: not-allowed;
  transform: none;
}

/* 加载动画 */
.btn-loader {
  width: 18px;
  height: 18px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: white;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* 切换链接 */
.form-switch {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 14px;
  color: #64748b;
}

.form-switch a {
  color: #8b5cf6;
  text-decoration: none;
  font-weight: 600;
  transition: color 0.2s;
}

.form-switch a:hover {
  color: #7c3aed;
  text-decoration: underline;
}
</style>
