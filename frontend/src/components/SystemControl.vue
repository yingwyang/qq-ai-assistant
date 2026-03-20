<template>
  <div class="system-control">
    <h2>系统控制</h2>
    <div class="control-buttons">
      <button 
        @click="startAllComponents" 
        class="btn-start" 
        :disabled="isLoading"
      >
        {{ isLoading ? '启动中...' : '启动所有组件' }}
      </button>
      <button 
        @click="stopAllComponents" 
        class="btn-stop" 
        :disabled="isLoading"
      >
        {{ isLoading ? '停止中...' : '停止所有组件' }}
      </button>
    </div>
    <div v-if="statusMessage" class="status-message">
      <p>{{ statusMessage }}</p>
    </div>
  </div>
</template>

<script>
import { ref } from 'vue';
import { systemApi } from '../services/api';

export default {
  name: 'SystemControl',
  setup() {
    const isLoading = ref(false);
    const statusMessage = ref('');

    const startAllComponents = async () => {
      isLoading.value = true;
      statusMessage.value = '正在启动所有组件...';
      try {
        const response = await systemApi.startAllComponents();
        let message = '启动结果：\n';
        for (const [component, status] of Object.entries(response)) {
          message += `${component}: ${status}\n`;
        }
        statusMessage.value = message;
      } catch (error) {
        statusMessage.value = '启动失败: ' + error.message;
      } finally {
        isLoading.value = false;
      }
    };

    const stopAllComponents = async () => {
      isLoading.value = true;
      statusMessage.value = '正在停止所有组件...';
      try {
        const response = await systemApi.stopAllComponents();
        let message = '停止结果：\n';
        for (const [component, status] of Object.entries(response)) {
          message += `${component}: ${status}\n`;
        }
        statusMessage.value = message;
      } catch (error) {
        statusMessage.value = '停止失败: ' + error.message;
      } finally {
        isLoading.value = false;
      }
    };

    return {
      isLoading,
      statusMessage,
      startAllComponents,
      stopAllComponents
    };
  }
};
</script>

<style scoped>
.system-control {
  /* 容器样式已移至App.vue的.section类中 */
}

.control-buttons {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}

.btn-start,
.btn-stop {
  flex: 1;
  padding: 10px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-weight: bold;
}

.btn-start {
  background-color: #4CAF50;
  color: white;
}

.btn-stop {
  background-color: #f44336;
  color: white;
}

.btn-start:hover:not(:disabled) {
  background-color: #45a049;
}

.btn-stop:hover:not(:disabled) {
  background-color: #da190b;
}

.btn-start:disabled,
.btn-stop:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.status-message {
  margin-top: 20px;
  padding: 10px;
  background-color: #e3f2fd;
  border-left: 4px solid #2196F3;
  border-radius: 4px;
  white-space: pre-line;
}
</style>
