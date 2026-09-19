import { ref } from 'vue';
import { adminApi } from '../services/api';
import logger from '../utils/logger';
import { showConfirm } from '../components/ConfirmDialog.vue';

export function useMaintenance({ showSystemMsg } = {}) {
  const isBackingUp = ref(false);
  const backupList = ref([]);
  const backupListLoading = ref(false);
  const deletingBackup = ref('');
  const isArchiving = ref(false);
  const archiveDays = ref(90);
  /** 归档预检结果（只统计不执行） */
  const archivePreview = ref(null);
  const archivePreviewLoading = ref(false);

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
    backupListLoading.value = true;
    try {
      backupList.value = await adminApi.getBackupList();
    } catch (error) {
      logger.error('加载备份列表失败:', error);
      if (showSystemMsg) showSystemMsg('加载备份列表失败: ' + error.message, 'error');
    } finally {
      backupListLoading.value = false;
    }
  };

  const getDownloadUrl = (fileName) => adminApi.downloadBackupUrl(fileName);

  /**
   * 删除备份：二次确认里带上文件名与体积，避免误删最近一次备份。
   */
  const deleteBackup = async (file) => {
    const name = typeof file === 'string' ? file : file.fileName;
    const size = typeof file === 'string' ? null : file.size;
    const ok = await showConfirm({
      title: '删除备份文件',
      message: `确定删除备份 ${name}${size ? `（${(size / 1024 / 1024).toFixed(2)} MB）` : ''} 吗？删除后无法恢复。`,
      type: 'error',
      confirmText: '删除备份',
    });
    if (!ok) return;
    deletingBackup.value = name;
    try {
      await adminApi.deleteBackup(name);
      if (showSystemMsg) showSystemMsg(`备份 ${name} 已删除`);
      await loadBackupList();
    } catch (error) {
      if (showSystemMsg) showSystemMsg('删除备份失败: ' + error.message, 'error');
    } finally {
      deletingBackup.value = '';
    }
  };

  /** 归档预检：执行前先看将影响多少条消息 */
  const previewArchive = async () => {
    archivePreviewLoading.value = true;
    try {
      archivePreview.value = await adminApi.previewArchive(archiveDays.value);
    } catch (error) {
      logger.error('归档预检失败:', error);
      if (showSystemMsg) showSystemMsg('归档预检失败: ' + error.message, 'error');
      archivePreview.value = null;
    } finally {
      archivePreviewLoading.value = false;
    }
  };

  const triggerArchive = async () => {
    const preview = archivePreview.value;
    const ok = await showConfirm({
      title: '执行消息归档',
      message: preview
        ? `将把 ${preview.messageCount} 条历史消息标记为已归档（归档后聊天界面不再显示，数据不会删除）。继续吗？`
        : '将把指定天数之前的消息标记为已归档，归档后聊天界面不再显示。继续吗？',
      type: 'warning',
      confirmText: '执行归档',
    });
    if (!ok) return;
    isArchiving.value = true;
    try {
      const res = await adminApi.triggerArchive(archiveDays.value);
      if (showSystemMsg) showSystemMsg(res.message || '归档成功');
      await previewArchive();
    } catch (error) {
      if (showSystemMsg) showSystemMsg('归档失败: ' + error.message, 'error');
    } finally {
      isArchiving.value = false;
    }
  };

  const setArchiveDays = (value) => {
    archiveDays.value = Number(value) || 90;
    archivePreview.value = null;   // 天数变了，旧预检结果作废
  };

  return {
    isBackingUp, backupList, backupListLoading, deletingBackup,
    isArchiving, archiveDays, archivePreview, archivePreviewLoading,
    triggerBackup, loadBackupList, getDownloadUrl, deleteBackup,
    triggerArchive, previewArchive, setArchiveDays,
  };
}
