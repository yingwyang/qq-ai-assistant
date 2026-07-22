import { ref } from 'vue';
import { adminApi } from '../services/api';

export function useMaintenance({ showSystemMsg } = {}) {
  const isBackingUp = ref(false);
  const backupList = ref([]);
  const isArchiving = ref(false);
  const archiveDays = ref(90);

  const triggerBackup = async () => {
    isBackingUp.value = true;
    try {
      const res = await adminApi.triggerBackup();
      if (showSystemMsg) showSystemMsg(res.message || '备份成功');
      await loadBackupList();
    } catch (error) {
      if (showSystemMsg) showSystemMsg('备份失败: ' + error.message, 'error');
    } finally {
      isBackingUp.value = false;
    }
  };

  const loadBackupList = async () => {
    try {
      backupList.value = await adminApi.getBackupList();
    } catch (error) {
      console.error('加载备份列表失败:', error);
    }
  };

  const getDownloadUrl = (fileName) => adminApi.downloadBackupUrl(fileName);

  const triggerArchive = async () => {
    isArchiving.value = true;
    try {
      const res = await adminApi.triggerArchive(archiveDays.value);
      if (showSystemMsg) showSystemMsg(res.message || '归档成功');
    } catch (error) {
      if (showSystemMsg) showSystemMsg('归档失败: ' + error.message, 'error');
    } finally {
      isArchiving.value = false;
    }
  };

  return {
    isBackingUp, backupList, isArchiving, archiveDays,
    triggerBackup, loadBackupList, getDownloadUrl, triggerArchive,
  };
}
