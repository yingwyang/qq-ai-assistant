<template>
  <div class="napcat-login">
    <h2>NapCat 登录</h2>
    <div v-if="!isLoggedIn" class="login-container">
      <div v-if="serviceAvailable && qrCode" class="qrcode-container">
        <img :src="qrCode" alt="NapCat登录二维码" class="qrcode" />
        <p>请使用QQ扫码登录</p>
        <button @click="refreshQrCode" class="btn-refresh">刷新二维码</button>
      </div>
      <div v-else-if="!serviceAvailable" class="loading">
        <p>NapCat 服务未启动，请点击"启动所有组件"</p>
      </div>
      <div v-else class="loading">
        <p>获取登录二维码中...</p>
      </div>
    </div>
    <div v-else class="logged-in">
      <p>✅ 已登录成功！</p>
      <p class="login-info">正在跳转到消息页面...</p>
      <button @click="manualRedirect" class="btn-redirect">手动进入消息页面</button>
    </div>
  </div>
</template>

<script>
import { ref, onMounted, onUnmounted } from 'vue';
import { systemApi } from '../services/api';

export default {
  name: 'NapCatLogin',
  emits: ['login-status-changed'],
  setup(props, { emit }) {
    const qrCode = ref('');
    const isLoggedIn = ref(false);
    const serviceAvailable = ref(false);
    let checkStatusInterval = null;

    const getQrCode = async () => {
      try {
        // 使用新的API端点获取二维码图片
        qrCode.value = '/api/system/napcat/qrcode-image?timestamp=' + new Date().getTime();
      } catch (error) {
        console.error('获取二维码失败:', error);
      }
    };

    const checkServiceHealth = async () => {
      try {
        const response = await systemApi.healthCheck();
        serviceAvailable.value = response.napcat === 'available';
        return serviceAvailable.value;
      } catch (error) {
        console.error('健康检查失败:', error);
        serviceAvailable.value = false;
        return false;
      }
    };

    const checkLoginStatus = async () => {
      try {
        // 先检查服务是否可用
        const isHealthy = await checkServiceHealth();
        if (!isHealthy) {
          console.log('NapCat 服务暂时不可用，跳过登录状态检查');
          return;
        }

        const response = await systemApi.checkNapCatLoginStatus();
        console.log('登录状态响应:', response);
        
        if (response.loggedIn !== isLoggedIn.value) {
          isLoggedIn.value = response.loggedIn;
          console.log('登录状态变化:', isLoggedIn.value);
          emit('login-status-changed', isLoggedIn.value);
        }
      } catch (error) {
        console.error('检查登录状态失败:', error);
      }
    };

    const refreshQrCode = () => {
      getQrCode();
    };

    const manualRedirect = () => {
      // 手动触发登录状态变化，通知父组件跳转
      isLoggedIn.value = true;
      emit('login-status-changed', true);
    };

    onMounted(() => {
      // 先检查服务健康状态
      checkServiceHealth().then((healthy) => {
        if (healthy) {
          getQrCode();
        }
      });
      
      // 每3秒检查一次登录状态
      checkStatusInterval = setInterval(() => {
        checkLoginStatus();
      }, 3000);
    });

    onUnmounted(() => {
      if (checkStatusInterval) {
        clearInterval(checkStatusInterval);
      }
    });

    return {
      qrCode,
      isLoggedIn,
      serviceAvailable,
      refreshQrCode,
      checkLoginStatus,
      manualRedirect
    };
  }
};
</script>

<style scoped>
.napcat-login {
  /* 容器样式已移至App.vue的.section类中 */
}

.login-container {
  text-align: center;
}

.qrcode-container {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.qrcode {
  width: 200px;
  height: 200px;
  margin: 20px auto;
  display: block;
}

.loading {
  padding: 40px 0;
  color: #666;
}

.logged-in {
  text-align: center;
  padding: 40px 0;
}

.login-info {
  margin-top: 10px;
  color: #666;
  font-size: 0.9em;
}

.btn-refresh,
.btn-check,
.btn-redirect {
  margin-top: 10px;
  padding: 8px 16px;
  background-color: #4CAF50;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
}

.btn-redirect {
  background-color: #2196F3;
  margin-top: 15px;
}

.btn-refresh:hover,
.btn-check:hover {
  background-color: #45a049;
}

.btn-redirect:hover {
  background-color: #1976D2;
}
</style>
