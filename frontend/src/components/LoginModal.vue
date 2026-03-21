<template>
  <div v-if="visible" class="modal-overlay" @click="closeModal">
    <div class="modal-content" @click.stop>
      <div class="modal-header">
        <h2>NapCat 登录</h2>
        <button class="close-btn" @click="closeModal">×</button>
      </div>
      <div class="modal-body">
        <!-- 系统控制模块 -->
        <div class="system-control-section">
          <h3>系统控制</h3>
          <div class="system-buttons">
            <button 
              @click="startAllComponents" 
              class="btn-system" 
              :disabled="isStarting"
            >
              <span v-if="isStarting">启动中...</span>
              <span v-else>启动所有组件</span>
            </button>
            <button 
              @click="stopAllComponents" 
              class="btn-system btn-stop"
              :disabled="isStopping"
            >
              <span v-if="isStopping">停止中...</span>
              <span v-else>停止所有组件</span>
            </button>
          </div>
          <div v-if="systemMessage" class="system-message" :class="{ success: systemMessageType === 'success', error: systemMessageType === 'error' }">
            {{ systemMessage }}
          </div>
        </div>

        <!-- 登录模块 -->
        <div class="login-section">
          <h3>登录 NapCat</h3>
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
            <button @click="closeModal" class="btn-confirm">确定</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, onMounted, onUnmounted, watch } from 'vue';
import { systemApi } from '../services/api';

export default {
  name: 'LoginModal',
  props: {
    visible: {
      type: Boolean,
      default: false
    }
  },
  emits: ['update:visible', 'login-status-changed'],
  setup(props, { emit }) {
    const qrCode = ref('');
    const isLoggedIn = ref(false);
    const serviceAvailable = ref(false);
    const isStarting = ref(false);
    const isStopping = ref(false);
    const systemMessage = ref('');
    const systemMessageType = ref('');
    let checkStatusInterval = null;

    const getQrCode = async () => {
      try {
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
        const isHealthy = await checkServiceHealth();
        if (!isHealthy) {
          return;
        }

        const response = await systemApi.checkNapCatLoginStatus();
        if (response.loggedIn !== isLoggedIn.value) {
          isLoggedIn.value = response.loggedIn;
          emit('login-status-changed', isLoggedIn.value);
        }
      } catch (error) {
        console.error('检查登录状态失败:', error);
      }
    };

    const refreshQrCode = () => {
      getQrCode();
    };

    const startAllComponents = async () => {
      isStarting.value = true;
      systemMessage.value = '';
      try {
        const response = await systemApi.startAllComponents();
        systemMessage.value = '启动成功: ' + JSON.stringify(response);
        systemMessageType.value = 'success';
        
        // 等待组件启动
        setTimeout(() => {
          checkServiceHealth();
          getQrCode();
        }, 5000);
      } catch (error) {
        systemMessage.value = '启动失败: ' + error.message;
        systemMessageType.value = 'error';
      } finally {
        isStarting.value = false;
      }
    };

    const stopAllComponents = async () => {
      isStopping.value = true;
      systemMessage.value = '';
      try {
        const response = await systemApi.stopAllComponents();
        systemMessage.value = '停止成功: ' + JSON.stringify(response);
        systemMessageType.value = 'success';
        isLoggedIn.value = false;
        emit('login-status-changed', false);
      } catch (error) {
        systemMessage.value = '停止失败: ' + error.message;
        systemMessageType.value = 'error';
      } finally {
        isStopping.value = false;
      }
    };

    const closeModal = () => {
      emit('update:visible', false);
    };

    const initModal = async () => {
      if (props.visible) {
        await checkServiceHealth();
        if (serviceAvailable.value) {
          await checkLoginStatus();
          if (!isLoggedIn.value) {
            getQrCode();
          }
        }
      }
    };

    watch(() => props.visible, (newValue) => {
      if (newValue) {
        initModal();
      }
    });

    onMounted(() => {
      if (props.visible) {
        initModal();
      }
      
      checkStatusInterval = setInterval(() => {
        if (props.visible) {
          checkLoginStatus();
        }
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
      isStarting,
      isStopping,
      systemMessage,
      systemMessageType,
      refreshQrCode,
      startAllComponents,
      stopAllComponents,
      closeModal
    };
  }
};
</script>

<style scoped>
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
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
  width: 90%;
  max-width: 500px;
  max-height: 80vh;
  overflow-y: auto;
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px;
  border-bottom: 1px solid #e0e0e0;
}

.modal-header h2 {
  margin: 0;
  color: #2c3e50;
  font-size: 18px;
}

.close-btn {
  background: none;
  border: none;
  font-size: 24px;
  cursor: pointer;
  color: #95a5a6;
  padding: 0;
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  transition: background-color 0.2s;
}

.close-btn:hover {
  background-color: #f0f0f0;
}

.modal-body {
  padding: 20px;
}

.system-control-section,
.login-section {
  margin-bottom: 30px;
}

.system-control-section h3,
.login-section h3 {
  margin: 0 0 15px 0;
  color: #2c3e50;
  font-size: 16px;
  border-bottom: 1px solid #e0e0e0;
  padding-bottom: 8px;
}

.system-buttons {
  display: flex;
  gap: 10px;
  margin-bottom: 15px;
}

.btn-system {
  flex: 1;
  padding: 10px 15px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
  transition: all 0.2s;
}

.btn-system:not(:disabled) {
  background-color: #3498db;
  color: white;
}

.btn-system:not(:disabled):hover {
  background-color: #2980b9;
}

.btn-system.btn-stop:not(:disabled) {
  background-color: #e74c3c;
}

.btn-system.btn-stop:not(:disabled):hover {
  background-color: #c0392b;
}

.btn-system:disabled {
  background-color: #bdc3c7;
  color: #7f8c8d;
  cursor: not-allowed;
}

.system-message {
  padding: 10px;
  border-radius: 4px;
  font-size: 14px;
  margin-top: 10px;
}

.system-message.success {
  background-color: #d4edda;
  color: #155724;
  border: 1px solid #c3e6cb;
}

.system-message.error {
  background-color: #f8d7da;
  color: #721c24;
  border: 1px solid #f5c6cb;
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
  border: 1px solid #e0e0e0;
  border-radius: 4px;
}

.loading {
  padding: 40px 0;
  color: #666;
}

.btn-refresh {
  margin-top: 15px;
  padding: 8px 16px;
  background-color: #f8f9fa;
  border: 1px solid #dee2e6;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
}

.btn-refresh:hover {
  background-color: #e9ecef;
}

.logged-in {
  text-align: center;
  padding: 40px 0;
}

.logged-in p {
  margin-bottom: 20px;
  font-size: 16px;
  color: #27ae60;
}

.btn-confirm {
  padding: 10px 20px;
  background-color: #27ae60;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
  transition: background-color 0.2s;
}

.btn-confirm:hover {
  background-color: #219a52;
}
</style>
