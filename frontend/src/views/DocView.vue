<template>
  <div class="doc-view" :class="'theme-' + (currentTheme || 'light')">
    <!-- 左侧目录树 -->
    <aside class="doc-sidebar">
      <div class="sidebar-header">
        <h3>帮助与反馈</h3>
        <button class="btn-home" @click="goHome">
          <Icon name="home" :size="14" />
          返回首页
        </button>
      </div>
      <nav class="doc-tree">
        <template v-for="node in docTree" :key="node.id">
          <div v-if="node.type === 'category'" class="tree-category">
            <div class="category-item" @click="toggleCategory(node.slug)">
              <span class="expand-icon" :class="{ expanded: expandedCategories.has(node.slug) }">›</span>
              <span class="category-name">{{ node.name }}</span>
            </div>
            <template v-if="expandedCategories.has(node.slug)">
              <template v-for="child in node.children" :key="child.id">
                <div v-if="child.type === 'category'" class="tree-subcategory">
                  <div class="category-item" @click="toggleCategory(child.slug)">
                    <span class="expand-icon" :class="{ expanded: expandedCategories.has(child.slug) }">›</span>
                    <span class="category-name">{{ child.name }}</span>
                  </div>
                  <template v-if="expandedCategories.has(child.slug)">
                    <div
                      v-for="subChild in child.children"
                      :key="subChild.id"
                      class="page-item"
                      :class="{ active: currentSlug === subChild.slug }"
                      @click="selectPage(subChild)"
                    >
                      {{ subChild.name }}
                    </div>
                  </template>
                </div>
                <div
                  v-else
                  class="page-item"
                  :class="{ active: currentSlug === child.slug }"
                  @click="selectPage(child)"
                >
                  {{ child.name }}
                </div>
              </template>
            </template>
          </div>
          <div v-else class="page-item" :class="{ active: currentSlug === node.slug }" @click="selectPage(node)">
            {{ node.name }}
          </div>
        </template>
      </nav>
    </aside>

    <!-- 中间内容区 -->
    <main class="doc-main">
      <div v-if="loading" class="doc-loading">加载中...</div>
      <div v-else-if="!currentPage" class="doc-empty">
        <p>选择左侧目录中的文档开始阅读</p>
      </div>
      <article v-else class="doc-article">
        <h1 class="doc-title">{{ currentPage.title }}</h1>
        <div class="doc-meta">
          <span v-if="currentPage.categoryName">{{ currentPage.categoryName }}</span>
          <span v-if="currentPage.updatedAt">更新于 {{ formatDate(currentPage.updatedAt) }}</span>
        </div>
        <div class="doc-content" v-html="renderedContent"></div>
      </article>
    </main>

    <!-- 右侧页面索引 -->
    <aside class="doc-index">
      <h4>本页索引</h4>
      <ul v-if="headings.length" class="index-list">
        <li v-for="(h, i) in headings" :key="i" :class="'level-' + h.level">
          <a :href="'#' + h.id" @click.prevent="scrollToHeading(h.id)">{{ h.text }}</a>
        </li>
      </ul>
      <p v-else class="no-index">暂无索引</p>
    </aside>
  </div>
</template>

<script>
import { ref, computed, onMounted, watch, nextTick } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import MarkdownIt from 'markdown-it';
import { useDocs } from '../composables/useDocs';
import { useTheme } from '../composables/useTheme';
import Icon from '../components/Icon.vue';

export default {
  name: 'DocView',
  components: { Icon },
  setup() {
    const route = useRoute();
    const router = useRouter();
    const { theme: currentTheme } = useTheme();
    const { categories, getPage, getAllSlugs, getFirstSlug } = useDocs();

    const docTree = ref([]);
    const currentPage = ref(null);
    const currentSlug = ref('');
    const loading = ref(false);
    const expandedCategories = ref(new Set());

    const md = new MarkdownIt({
      html: true,
      linkify: true,
      typographer: true
    });

    // Convert tree.js categories to the format expected by the template
    const buildDocTree = () => {
      return categories.value.map(cat => {
        const node = {
          type: 'category',
          id: cat.id,
          slug: cat.slug,
          name: cat.title,
          children: []
        };

        // Add pages directly under this category (for faq and dev which have no subcategories)
        if (cat.pages && cat.pages.length) {
          cat.pages.forEach(page => {
            node.children.push({
              type: 'page',
              id: page.slug,
              slug: page.slug,
              name: page.title
            });
          });
        }

        // Add subcategories (for usage which has getting-started and commands)
        if (cat.children && cat.children.length) {
          cat.children.forEach(sub => {
            const subNode = {
              type: 'category',
              id: sub.id,
              slug: sub.slug,
              name: sub.title,
              children: []
            };
            if (sub.pages && sub.pages.length) {
              sub.pages.forEach(page => {
                subNode.children.push({
                  type: 'page',
                  id: page.slug,
                  slug: page.slug,
                  name: page.title
                });
              });
            }
            node.children.push(subNode);
          });
        }

        return node;
      });
    };

    const renderedContent = computed(() => {
      if (!currentPage.value?.content) return '';
      return md.render(currentPage.value.content);
    });

    const headings = ref([]);

    const extractHeadings = () => {
      const container = document.querySelector('.doc-content');
      if (!container) {
        headings.value = [];
        return;
      }
      const elements = container.querySelectorAll('h1, h2, h3');
      const result = [];
      elements.forEach((el, i) => {
        el.id = el.id || ('heading-' + i);
        result.push({ id: el.id, text: el.textContent, level: parseInt(el.tagName.substring(1)) });
      });
      headings.value = result;
    };

    watch(renderedContent, () => {
      nextTick(() => {
        extractHeadings();
      });
    });

    const initDocTree = () => {
      docTree.value = buildDocTree();
      // 默认展开所有顶级分类
      docTree.value.forEach(node => {
        if (node.type === 'category') {
          expandedCategories.value.add(node.slug);
          // Also expand subcategories
          if (node.children) {
            node.children.forEach(child => {
              if (child.type === 'category') {
                expandedCategories.value.add(child.slug);
              }
            });
          }
        }
      });
    };

    const loadPage = (slug) => {
      if (!slug) {
        currentPage.value = null;
        headings.value = [];
        return;
      }
      loading.value = true;
      
      // Use synchronous getPage from useDocs
      const pageData = getPage(slug);
      if (!pageData || !pageData.content) {
        currentPage.value = null;
        headings.value = [];
        loading.value = false;
        return;
      }
      
      currentPage.value = pageData;
      currentSlug.value = slug;
      
      nextTick(() => {
        extractHeadings();
        loading.value = false;
      });
    };

    const findFirstPage = (nodes) => {
      for (const node of nodes) {
        if (node.type === 'page') return node.slug;
        if (node.children && node.children.length) {
          const found = findFirstPage(node.children);
          if (found) return found;
        }
      }
      return null;
    };

    const toggleCategory = (slug) => {
      if (expandedCategories.value.has(slug)) {
        expandedCategories.value.delete(slug);
      } else {
        expandedCategories.value.add(slug);
      }
      // 触发响应式更新
      expandedCategories.value = new Set(expandedCategories.value);
    };

    const selectPage = (node) => {
      router.push('/docs/' + node.slug);
    };

    const scrollToHeading = (id) => {
      const el = document.getElementById(id);
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    };

    const formatDate = (dateStr) => {
      if (!dateStr) return '';
      const d = new Date(dateStr);
      return d.toLocaleDateString('zh-CN');
    };

    const goHome = () => {
      if (router.currentRoute.value.path === '/') {
        window.dispatchEvent(new CustomEvent('app:refresh'));
      } else {
        router.push('/');
      }
    };

    // 监听路由变化
    watch(() => route.params.pathMatch, async (newPath) => {
      if (newPath) {
        const slug = Array.isArray(newPath) ? newPath.join('/') : newPath;
        loadPage(slug);
      } else {
        // 无 slug，加载第一个可用页面
        const firstSlug = findFirstPage(docTree.value);
        if (firstSlug) {
          router.replace('/docs/' + firstSlug);
        }
      }
    }, { immediate: true });

    onMounted(() => {
      initDocTree();
      // 如果当前没有 slug，自动跳转到第一个页面
      if (!route.params.pathMatch) {
        const firstSlug = findFirstPage(docTree.value);
        if (firstSlug) {
          router.replace('/docs/' + firstSlug);
        }
      }
    });

    return {
      docTree,
      currentPage,
      currentSlug,
      loading,
      expandedCategories,
      currentTheme,
      renderedContent,
      headings,
      toggleCategory,
      selectPage,
      scrollToHeading,
      formatDate,
      goHome
    };
  }
};
</script>

<style scoped>
.doc-view {
  display: flex;
  height: 100vh;
  background-color: var(--bg-primary, #f5f5f5);
  color: var(--text-primary, #333);
}

/* 左侧目录树 */
.doc-sidebar {
  width: 260px;
  background-color: var(--card-bg, #fff);
  border-right: 1px solid var(--border-color, #e0e0e0);
  overflow-y: auto;
  flex-shrink: 0;
}

.sidebar-header {
  padding: 16px;
  border-bottom: 1px solid var(--border-color, #e0e0e0);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.sidebar-header h3 {
  margin: 0;
  font-size: 16px;
  color: var(--text-primary, #333);
}
.btn-home {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  background: var(--bg-tertiary, #f0f0f0);
  border: 1px solid var(--border-color, #e0e0e0);
  border-radius: 6px;
  color: var(--text-secondary, #666);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}
.btn-home:hover {
  background: var(--accent-color, #3498db);
  color: #fff;
  border-color: var(--accent-color, #3498db);
}

.doc-tree {
  padding: 8px 0;
}

.tree-category {
  margin-bottom: 4px;
}

.category-item {
  display: flex;
  align-items: center;
  padding: 8px 16px;
  cursor: pointer;
  font-weight: 600;
  font-size: 14px;
  color: var(--text-primary, #333);
  transition: background 0.15s;
}
.category-item:hover {
  background-color: var(--bg-tertiary, #f0f0f0);
}

.expand-icon {
  display: inline-block;
  width: 16px;
  transition: transform 0.2s;
  color: var(--text-muted, #999);
  font-size: 12px;
}
.expand-icon.expanded {
  transform: rotate(90deg);
}

.category-name {
  margin-left: 4px;
}

.tree-subcategory {
  padding-left: 16px;
}

.page-item {
  padding: 6px 16px 6px 36px;
  cursor: pointer;
  font-size: 13px;
  color: var(--text-secondary, #666);
  transition: background 0.15s, color 0.15s;
}
.page-item:hover {
  background-color: var(--bg-tertiary, #f0f0f0);
  color: var(--text-primary, #333);
}
.page-item.active {
  background-color: var(--accent-color, #3498db);
  color: #fff;
  font-weight: 500;
}

/* 中间内容区 */
.doc-main {
  flex: 1;
  overflow-y: auto;
  padding: 40px 60px;
  min-width: 0;
}

.doc-loading, .doc-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: var(--text-muted, #999);
  font-size: 16px;
}

.doc-article {
  max-width: 800px;
  margin: 0 auto;
}

.doc-title {
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 8px;
  color: var(--text-primary, #333);
}

.doc-meta {
  display: flex;
  gap: 16px;
  padding-bottom: 16px;
  margin-bottom: 24px;
  border-bottom: 1px solid var(--border-color, #e0e0e0);
  color: var(--text-muted, #999);
  font-size: 13px;
}

.doc-content {
  line-height: 1.8;
  font-size: 15px;
  color: var(--text-primary, #333);
}

.doc-content :deep(h1),
.doc-content :deep(h2),
.doc-content :deep(h3) {
  margin-top: 24px;
  margin-bottom: 12px;
  line-height: 1.3;
}

.doc-content :deep(h1) { font-size: 24px; }
.doc-content :deep(h2) { font-size: 20px; }
.doc-content :deep(h3) { font-size: 17px; }

.doc-content :deep(p) {
  margin-bottom: 16px;
}

.doc-content :deep(code) {
  background-color: var(--bg-tertiary, #f0f0f0);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 0.9em;
}

.doc-content :deep(pre) {
  background-color: var(--bg-tertiary, #f0f0f0);
  padding: 16px;
  border-radius: 8px;
  overflow-x: auto;
  margin-bottom: 16px;
}

.doc-content :deep(pre code) {
  background: none;
  padding: 0;
}

.doc-content :deep(blockquote) {
  border-left: 4px solid var(--accent-color, #3498db);
  padding-left: 16px;
  color: var(--text-secondary, #666);
  margin-bottom: 16px;
}

.doc-content :deep(ul),
.doc-content :deep(ol) {
  padding-left: 24px;
  margin-bottom: 16px;
}

.doc-content :deep(li) {
  margin-bottom: 4px;
}

.doc-content :deep(a) {
  color: var(--accent-color, #3498db);
  text-decoration: none;
}
.doc-content :deep(a:hover) {
  text-decoration: underline;
}

/* 右侧页面索引 */
.doc-index {
  width: 220px;
  padding: 24px 16px;
  border-left: 1px solid var(--border-color, #e0e0e0);
  overflow-y: auto;
  flex-shrink: 0;
}

.doc-index h4 {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary, #333);
  margin: 0 0 16px 0;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border-color, #e0e0e0);
}

.index-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.index-list li {
  padding: 4px 0;
  font-size: 13px;
}

.index-list li a {
  color: var(--text-secondary, #666);
  text-decoration: none;
  display: block;
  padding: 2px 0;
  border-left: 2px solid transparent;
  padding-left: 8px;
  transition: all 0.15s;
}

.index-list li a:hover {
  color: var(--accent-color, #3498db);
  border-left-color: var(--accent-color, #3498db);
}

.index-list li.level-2 a {
  padding-left: 8px;
}

.index-list li.level-3 a {
  padding-left: 20px;
  font-size: 12px;
}

.no-index {
  color: var(--text-muted, #999);
  font-size: 13px;
}

/* 响应式 */
@media (max-width: 900px) {
  .doc-index { display: none; }
}

@media (max-width: 600px) {
  .doc-sidebar { width: 200px; }
  .doc-main { padding: 20px; }
}
</style>
