import { computed, ref } from 'vue';
import { adminApi } from '../services/api';

/**
 * 配置管理 composable。
 *
 * 说明：后端对 secret 字段永远返回 `***`，不会回显真实值 —— 所以「显示密钥」只对
 * **编辑输入框**有意义（password ↔ text），列表态一律显示 ***。
 */
export function useConfigManagement({ showSystemMsg } = {}) {
  const configGroups = ref([]);
  const configLoading = ref(false);
  const configError = ref('');
  const configEditing = ref({});
  const configEditValues = ref({});
  const configShowSecret = ref({});
  /** 保存后需要重启才生效的配置项（后端返回的可读文本） */
  const restartRequired = ref([]);
  const configSaving = ref(false);

  // 折叠状态 / 搜索 / 密钥显示开关（仅本次会话）
  const collapsedGroups = ref({});
  const configKeyword = ref('');
  const showAllSecrets = ref(false);

  const loadConfig = async () => {
    configLoading.value = true;
    configError.value = '';
    try {
      configGroups.value = await adminApi.getConfig();
    } catch (error) {
      configError.value = error.message || '加载配置失败';
      if (showSystemMsg) showSystemMsg('加载配置失败: ' + error.message, 'error');
    } finally {
      configLoading.value = false;
    }
  };

  const toggleGroup = (groupName) => {
    collapsedGroups.value = { ...collapsedGroups.value, [groupName]: !collapsedGroups.value[groupName] };
  };

  /** 关键字过滤：只按配置项 label / key 匹配，命中时自动展开 */
  const filteredGroups = computed(() => {
    const kw = configKeyword.value.trim().toLowerCase();
    if (!kw) return configGroups.value;
    return configGroups.value
      .map((group) => ({
        ...group,
        items: group.items.filter((item) =>
          (item.label || '').toLowerCase().includes(kw) || (item.key || '').toLowerCase().includes(kw)),
      }))
      .filter((group) => group.items.length > 0);
  });

  const startEditConfig = (groupName) => {
    configEditing.value = { ...configEditing.value, [groupName]: true };
    const editValues = { ...configEditValues.value };
    const group = configGroups.value.find(g => g.name === groupName);
    if (group) {
      for (const item of group.items) {
        // secret 留空表示不修改；非 secret 预填当前值
        editValues[item.key] = item.secret ? '' : item.value;
      }
    }
    configEditValues.value = editValues;
    // 进入编辑时清掉上一轮的重启提示
    restartRequired.value = [];
  };

  const cancelEditConfig = (groupName) => {
    const newEditing = { ...configEditing.value };
    delete newEditing[groupName];
    configEditing.value = newEditing;
  };

  /** 未保存改动提示：编辑态下值被改过就算 dirty */
  const isGroupDirty = (group) => {
    if (!configEditing.value[group.name]) return false;
    return group.items.some((item) => {
      const current = configEditValues.value[item.key];
      if (item.secret) return current !== undefined && current !== '';
      return current !== undefined && current !== item.value;
    });
  };

  const anyDirty = computed(() => configGroups.value.some((group) => isGroupDirty(group)));

  /**
   * 字段级校验（与后端约束保持一致的最小集）：
   * - *-api-url / minio.endpoint 必须是 http(s) URL
   * - jwt.expiration 必须是正整数
   */
  const validateItem = (item) => {
    const value = configEditValues.value[item.key];
    if (value === undefined || value === '') return '';
    const key = item.key || '';
    if (/\.api-url$/.test(key) || key === 'minio.endpoint') {
      if (!/^https?:\/\/[^\s]+$/.test(value.trim())) {
        return '需填写 http(s):// 开头的完整地址';
      }
    }
    if (key === 'jwt.expiration') {
      if (!/^\d+$/.test(value.trim()) || Number(value) <= 0) {
        return '需填写正整数（毫秒）';
      }
    }
    return '';
  };

  const groupErrors = (groupName) => {
    const group = configGroups.value.find(g => g.name === groupName);
    if (!group) return {};
    const errors = {};
    for (const item of group.items) {
      if (!configEditing.value[groupName]) continue;
      const message = validateItem(item);
      if (message) errors[item.key] = message;
    }
    return errors;
  };

  const canSaveGroup = (groupName) => Object.keys(groupErrors(groupName)).length === 0;

  const toggleSecret = (key) => {
    configShowSecret.value = { ...configShowSecret.value, [key]: !configShowSecret.value[key] };
  };

  const toggleShowAllSecrets = () => {
    showAllSecrets.value = !showAllSecrets.value;
    const next = { ...configShowSecret.value };
    // 只影响当前处于编辑态的分组里的密钥字段
    for (const group of configGroups.value) {
      if (!configEditing.value[group.name]) continue;
      for (const item of group.items) {
        if (item.secret) next[item.key] = showAllSecrets.value;
      }
    }
    configShowSecret.value = next;
  };

  const saveConfig = async (groupName) => {
    const group = configGroups.value.find(g => g.name === groupName);
    if (!group) return false;

    if (!canSaveGroup(groupName)) {
      const first = Object.values(groupErrors(groupName))[0];
      if (showSystemMsg) showSystemMsg('存在不合法的配置项：' + first, 'error');
      return false;
    }

    const updates = {};
    for (const item of group.items) {
      const val = configEditValues.value[item.key];
      if (val !== undefined && val !== '') {
        updates[item.key] = val;
      }
    }

    if (Object.keys(updates).length === 0) {
      cancelEditConfig(groupName);
      return false;
    }

    configSaving.value = true;
    try {
      const res = await adminApi.updateConfig(updates);
      cancelEditConfig(groupName);
      await loadConfig();

      restartRequired.value = (res && res.restartRequired) || [];
      if (restartRequired.value.length > 0) {
        if (showSystemMsg) showSystemMsg(`配置已更新。以下配置需要重启生效：${restartRequired.value.join(', ')}`, 'error');
      } else {
        if (showSystemMsg) showSystemMsg('配置更新成功');
      }
      return true;
    } catch (error) {
      if (showSystemMsg) showSystemMsg('更新配置失败: ' + error.message, 'error');
      return false;
    } finally {
      configSaving.value = false;
    }
  };

  const dismissRestartBanner = () => {
    restartRequired.value = [];
  };

  /** 离开页面时若有未保存改动，交由调用方决定是否拦截 */
  const hasUnsavedChanges = computed(() => anyDirty.value);

  const discardAllChanges = () => {
    configEditing.value = {};
    configEditValues.value = {};
  };

  return {
    configGroups, configLoading, configError, configEditing, configEditValues, configShowSecret,
    collapsedGroups, configKeyword, showAllSecrets, restartRequired, configSaving, filteredGroups,
    anyDirty, hasUnsavedChanges,
    loadConfig, startEditConfig, cancelEditConfig, saveConfig,
    isGroupDirty, groupErrors, canSaveGroup, toggleGroup, toggleSecret, toggleShowAllSecrets,
    dismissRestartBanner, discardAllChanges,
  };
}
