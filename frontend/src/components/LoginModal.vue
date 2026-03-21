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
          
          <!-- 组件状态显示 -->
          <div class="component-status">
            <div class="status-item" :class="{ active: componentStatus.astrbot?.running }">
              <span class="status-dot"></span>
              <span>AstrBot</span>
              <span class="status-text">{{ componentStatus.astrbot?.running ? '运行中' : '已停止' }}</span>
            </div>
            <div class="status-item" :class="{ active: componentStatus.napcat?.running }">
              <span class="status-dot"></span>
              <span>NapCat</span>
              <span class="status-text">{{ componentStatus.napcat?.running ? '运行中' : '已停止' }}</span>
            </div>
            <div class="status-item" :class="{ active: componentStatus.gptsovits?.running }">
              <span class="status-dot"></span>
              <span>GPT-SoVITS</span>
              <span class="status-text">{{ componentStatus.gptsovits?.running ? '运行中' : '已停止' }}</span>
            </div>
          </div>

          <!-- 分别控制按钮 -->
          <div class="component-buttons">
            <div class="component-row">
              <span class="component-name">AstrBot</span>
              <button 
                @click="startAstrBot" 
                class="btn-component"
                :disabled="isStartingAstrBot || componentStatus.astrbot?.running"
              >
                {{ isStartingAstrBot ? '启动中...' : '启动' }}
              </button>
              <button 
                @click="stopAstrBot" 
                class="btn-component btn-stop"
                :disabled="isStoppingAstrBot || !componentStatus.astrbot?.running"
              >
                {{ isStoppingAstrBot ? '停止中...' : '停止' }}
              </button>
            </div>
            <div class="component-row">
              <span class="component-name">NapCat</span>
              <button 
                @click="startNapCat" 
                class="btn-component"
                :disabled="isStartingNapCat || componentStatus.napcat?.running"
              >
                {{ isStartingNapCat ? '启动中...' : '启动' }}
              </button>
              <button 
                @click="stopNapCat" 
                class="btn-component btn-stop"
                :disabled="isStoppingNapCat || !componentStatus.napcat?.running"
              >
                {{ isStoppingNapCat ? '停止中...' : '停止' }}
              </button>
            </div>
            <div class="component-row">
              <span class="component-name">GPT-SoVITS</span>
              <button 
                @click="startGptSovits" 
                class="btn-component"
                :disabled="isStartingGptSovits || componentStatus.gptsovits?.running"
              >
                {{ isStartingGptSovits ? '启动中...' : '启动' }}
              </button>
              <button 
                @click="stopGptSovits" 
                class="btn-component btn-stop"
                :disabled="isStoppingGptSovits || !componentStatus.gptsovits?.running"
              >
                {{ isStoppingGptSovits ? '停止中...' : '停止' }}
              </button>
            </div>
          </div>

          <!-- 一键控制按钮 -->
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
        // 等待服务启动后刷新二维码
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
      componentStatus,
      isStartingAstrBot,
      isStoppingAstrBot,
      isStartingNapCat,
      isStoppingNapCat,
      isStartingGptSovits,
      isStoppingGptSovits,
      refreshQrCode,
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
