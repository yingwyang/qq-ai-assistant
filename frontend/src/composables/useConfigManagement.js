import { ref } from 'vue';
import { adminApi } from '../services/api';

export function useConfigManagement({ showSystemMsg } = {}) {
  const configGroups = ref([]);
  const configLoading = ref(false);
  const configEditing = ref({});
  const configEditValues = ref({});
  const configShowSecret = ref({});

  const loadConfig = async () => {
    configLoading.value = true;
    try {
      configGroups.value = await adminApi.getConfig();
    } catch (error) {
      if (showSystemMsg) showSystemMsg('加载配置失败: ' + error.message, 'error');
    } finally {
      configLoading.value = false;
    }
  };

  const startEditConfig = (groupName) => {
    configEditing.value = { ...configEditing.value, [groupName]: true };
    const editValues = { ...configEditValues.value };
    const group = configGroups.value.find(g => g.name === groupName);
    if (group) {
      for (const item of group.items) {
        editValues[item.key] = item.secret ? '' : item.value;
      }
    }
    configEditValues.value = editValues;
  };

  const cancelEditConfig = (groupName) => {
    const newEditing = { ...configEditing.value };
    delete newEditing[groupName];
    configEditing.value = newEditing;
  };

  const saveConfig = async (groupName) => {
    const group = configGroups.value.find(g => g.name === groupName);
    if (!group) return;

    const updates = {};
    for (const item of group.items) {
      const val = configEditValues.value[item.key];
      if (val !== undefined && val !== '') {
        updates[item.key] = val;
      }
    }

    if (Object.keys(updates).length === 0) {
      cancelEditConfig(groupName);
      return;
    }

    try {
      const res = await adminApi.updateConfig(updates);
      cancelEditConfig(groupName);
      await loadConfig();

      if (res.restartRequired && res.restartRequired.length > 0) {
        if (showSystemMsg) showSystemMsg(`配置已更新。以下配置需要重启生效：${res.restartRequired.join(', ')}`, 'error');
      } else {
        if (showSystemMsg) showSystemMsg('配置更新成功');
      }
    } catch (error) {
      if (showSystemMsg) showSystemMsg('更新配置失败: ' + error.message, 'error');
    }
  };

  return {
    configGroups, configLoading, configEditing, configEditValues, configShowSecret,
    loadConfig, startEditConfig, cancelEditConfig, saveConfig,
  };
}
