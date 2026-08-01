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
          
          <!-- 分别启动各组件 -->
          <div class="component-buttons">
            <div class="component-row">
              <span class="component-name">AstrBot</span>
              <button 
                @click="startAstrBot" 
                class="btn-component btn-start" 
                :disabled="isStartingAstrBot"
              >
                <span v-if="isStartingAstrBot">启动中...</span>
                <span v-else>启动</span>
              </button>
              <button 
                @click="stopAstrBot" 
                class="btn-component btn-stop" 
                :disabled="isStoppingAstrBot"
              >
                <span v-if="isStoppingAstrBot">停止中...</span>
                <span v-else>停止</span>
              </button>
            </div>
            <div class="component-row">
              <span class="component-name">NapCat</span>
              <button 
                @click="startNapCat" 
                class="btn-component btn-start" 
                :disabled="isStartingNapCat"
              >
                <span v-if="isStartingNapCat">启动中...</span>
                <span v-else>启动</span>
              </button>
              <button 
                @click="stopNapCat" 
                class="btn-component btn-stop" 
                :disabled="isStoppingNapCat"
              >
                <span v-if="isStoppingNapCat">停止中...</span>
                <span v-else>停止</span>
              </button>
            </div>
            <div class="component-row">
              <span class="component-name">GPT-SoVITS</span>
              <button 
                @click="startGptSovits" 
                class="btn-component btn-start" 
                :disabled="isStartingGptSovits"
              >
                <span v-if="isStartingGptSovits">启动中...</span>
                <span v-else>启动</span>
              </button>
              <button 
                @click="stopGptSovits" 
                class="btn-component btn-stop" 
                :disabled="isStoppingGptSovits"
              >
                <span v-if="isStoppingGptSovits">停止中...</span>
                <span v-else>停止</span>
              </button>
            </div>
          </div>
          <div v-if="systemMessage" class="system-message" :class="{ success: systemMessageType === 'success', error: systemMessageType === 'error' }">
            {{ systemMessage }}
          </div>
        </div>

        <!-- 登录模块 -->
        <div class="login-section">
          <h3>登录 NapCat</h3>
          <div class="login-container">
            <div v-if="qrCode" class="qrcode-container">
              <img :src="qrCode" alt="NapCat登录二维码" class="qrcode" />
              <p>请使用QQ扫码登录</p>
              <button @click="refreshQrCode" class="btn-refresh">刷新二维码</button>
            </div>
            <div v-else class="loading">
              <p>获取登录二维码中...</p>
            </div>
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
    const qrCodeError = ref(false);
    
    // 组件状态
    const componentStatus = ref({
      astrbot: { running: false },
      napcat: { running: false },
      gptsovits: { running: false }
    });
    
    // 分别控制的状态
    const isStartingAstrBot = ref(false);
    const isStoppingAstrBot = ref(false);
    const isStartingNapCat = ref(false);
    const isStoppingNapCat = ref(false);
    const isStartingGptSovits = ref(false);
    const isStoppingGptSovits = ref(false);
    
    let checkStatusInterval = null;

    const getQrCode = async () => {
      try {
        qrCodeError.value = false;
        // 使用完整URL，添加时间戳防止缓存
        const timestamp = new Date().getTime();
        qrCode.value = `http://localhost:8081/api/system/napcat/qrcode-image?timestamp=${timestamp}`;
      } catch (error) {
        console.error('获取二维码失败:', error);
        qrCodeError.value = true;
      }
    };

    const onQrCodeError = () => {
      qrCodeError.value = true;
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
          if (response.loggedIn) {
            try {
              await systemApi.autoConfigureNapCat();
              systemMessage.value = '登录成功，已自动配置 NapCat';
              systemMessageType.value = 'success';
            } catch (e) {
              console.error('自动配置 NapCat 失败:', e);
            }
          }
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
        await getComponentStatus();
      } catch (error) {
        systemMessage.value = '停止失败: ' + error.message;
        systemMessageType.value = 'error';
      } finally {
        isStopping.value = false;
      }
    };

    // 获取组件状态
    const getComponentStatus = async () => {
      try {
        const response = await systemApi.getComponentStatus();
        componentStatus.value = response;
      } catch (error) {
        console.error('获取组件状态失败:', error);
      }
    };

    // 分别控制各个组件
    const startAstrBot = async () => {
      isStartingAstrBot.value = true;
      systemMessage.value = '';
      try {
        const response = await systemApi.startAstrBot();
        systemMessage.value = response.message || 'AstrBot 启动成功';
        systemMessageType.value = 'success';
        await getComponentStatus();
      } catch (error) {
        systemMessage.value = 'AstrBot 启动失败: ' + error.message;
        systemMessageType.value = 'error';
      } finally {
        isStartingAstrBot.value = false;
      }
    };

    const stopAstrBot = async () => {
      isStoppingAstrBot.value = true;
      systemMessage.value = '';
      try {
        const response = await systemApi.stopAstrBot();
        systemMessage.value = response.message || 'AstrBot 停止成功';
        systemMessageType.value = 'success';
        await getComponentStatus();
      } catch (error) {
        systemMessage.value = 'AstrBot 停止失败: ' + error.message;
        systemMessageType.value = 'error';
      } finally {
        isStoppingAstrBot.value = false;
      }
    };

    const startNapCat = async () => {
      isStartingNapCat.value = true;
      systemMessage.value = '';
      try {
        const response = await systemApi.startNapCat();
        systemMessage.value = response.message || 'NapCat 启动成功';
        systemMessageType.value = 'success';
        await getComponentStatus();
        setTimeout(() => {
          checkServiceHealth();
          getQrCode();
        }, 3000);
      } catch (error) {
        systemMessage.value = 'NapCat 启动失败: ' + error.message;
        systemMessageType.value = 'error';
      } finally {
        isStartingNapCat.value = false;
      }
    };

    const stopNapCat = async () => {
      isStoppingNapCat.value = true;
      systemMessage.value = '';
      try {
        const response = await systemApi.stopNapCat();
        systemMessage.value = response.message || 'NapCat 停止成功';
        systemMessageType.value = 'success';
        isLoggedIn.value = false;
        emit('login-status-changed', false);
        await getComponentStatus();
      } catch (error) {
        systemMessage.value = 'NapCat 停止失败: ' + error.message;
        systemMessageType.value = 'error';
      } finally {
        isStoppingNapCat.value = false;
      }
    };

    const startGptSovits = async () => {
      isStartingGptSovits.value = true;
      systemMessage.value = '';
      try {
        const response = await systemApi.startGptSovits();
        systemMessage.value = response.message || 'GPT-SoVITS 启动成功';
        systemMessageType.value = 'success';
        await getComponentStatus();
      } catch (error) {
        systemMessage.value = 'GPT-SoVITS 启动失败: ' + error.message;
        systemMessageType.value = 'error';
      } finally {
        isStartingGptSovits.value = false;
      }
    };

    const stopGptSovits = async () => {
      isStoppingGptSovits.value = true;
      systemMessage.value = '';
      try {
        const response = await systemApi.stopGptSovits();
        systemMessage.value = response.message || 'GPT-SoVITS 停止成功';
        systemMessageType.value = 'success';
        await getComponentStatus();
      } catch (error) {
        systemMessage.value = 'GPT-SoVITS 停止失败: ' + error.message;
        systemMessageType.value = 'error';
      } finally {
        isStoppingGptSovits.value = false;
      }
    };

    const closeModal = () => {
      emit('update:visible', false);
    };

    const initModal = async () => {
      if (props.visible) {
        await getComponentStatus();
        await checkServiceHealth();
        getQrCode();
        if (serviceAvailable.value) {
          await checkLoginStatus();
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
      qrCodeError,
      componentStatus,
      isStartingAstrBot,
      isStoppingAstrBot,
      isStartingNapCat,
      isStoppingNapCat,
      isStartingGptSovits,
      isStoppingGptSovits,
      refreshQrCode,
      onQrCodeError,
      startAllComponents,
      stopAllComponents,
      startAstrBot,
      stopAstrBot,
      startNapCat,
      stopNapCat,
      startGptSovits,
      stopGptSovits,
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

.component-buttons {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 15px;
}

.component-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background-color: #f8f9fa;
  border-radius: 6px;
}

.component-name {
  flex: 1;
  font-size: 14px;
  font-weight: 500;
  color: #2c3e50;
}

.btn-component {
  padding: 6px 14px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  transition: all 0.2s;
  min-width: 60px;
}

.btn-component.btn-start:not(:disabled) {
  background-color: #3498db;
  color: white;
}

.btn-component.btn-start:not(:disabled):hover {
  background-color: #2980b9;
}

.btn-component.btn-stop:not(:disabled) {
  background-color: #e74c3c;
  color: white;
}

.btn-component.btn-stop:not(:disabled):hover {
  background-color: #c0392b;
}

.btn-component:disabled {
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

/* 组件状态显示 */
.component-status {
  display: flex;
  gap: 15px;
  margin-bottom: 15px;
  padding: 10px;
  background-color: #f8f9fa;
  border-radius: 4px;
}

.status-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #666;
}

.status-item.active {
  color: #27ae60;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: #e74c3c;
}

.status-item.active .status-dot {
  background-color: #27ae60;
}

.status-text {
  font-size: 12px;
  color: #999;
}

.status-item.active .status-text {
  color: #27ae60;
}

/* 分别控制按钮 */
.component-buttons {
  margin-bottom: 20px;
}

.component-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  padding: 8px;
  background-color: #f8f9fa;
  border-radius: 4px;
}

.component-name {
  flex: 1;
  font-size: 14px;
  font-weight: 500;
  color: #2c3e50;
}

.btn-component {
  padding: 6px 12px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  transition: all 0.2s;
  min-width: 60px;
}

.btn-component:not(:disabled) {
  background-color: #3498db;
  color: white;
}

.btn-component:not(:disabled):hover {
  background-color: #2980b9;
}

.btn-component.btn-stop:not(:disabled) {
  background-color: #e74c3c;
}

.btn-component.btn-stop:not(:disabled):hover {
  background-color: #c0392b;
}

.btn-component:disabled {
  background-color: #bdc3c7;
  color: #7f8c8d;
  cursor: not-allowed;
}
</style>
