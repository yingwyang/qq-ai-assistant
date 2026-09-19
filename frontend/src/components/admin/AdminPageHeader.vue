<template>
  <header class="page-header">
    <div class="page-header-text">
      <h2 class="page-title">{{ title }}</h2>
      <p v-if="subtitle" class="page-subtitle">{{ subtitle }}</p>
    </div>
    <div class="page-header-actions">
      <slot name="meta" />
      <slot />
    </div>
  </header>
</template>

<script>
/**
 * 管理后台统一页头：标题 + 一句说明 + 右侧操作区。
 * 样式定义在 admin-shared.css（非 scoped，全局共享），保证 14 个页面外观一致。
 */
export default {
  name: 'AdminPageHeader',
  props: {
    title: { type: String, required: true },
    subtitle: { type: String, default: '' },
  },
};
</script>
