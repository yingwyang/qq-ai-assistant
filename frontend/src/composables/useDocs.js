import { ref, computed } from 'vue';
import { categories, allPages, getPageMeta as getPageMetaFromTree, getCategoryForPage } from '../docs/tree.js';

// Use Vite's import.meta.glob to eagerly load all markdown files
const modules = import.meta.glob('../docs/**/*.md', {
  query: '?raw',
  import: 'default',
  eager: true
});

// Cache for loaded pages
const pageCache = ref({});

/**
 * Get the raw markdown content for a page by its file path
 * @param {string} filePath - The path to the markdown file (e.g., '/docs/getting-started/welcome.md')
 * @returns {string} The raw markdown content
 */
function getRawContent(filePath) {
  // Convert the tree.js filePath to the glob path format
  // tree.js uses '/docs/...' but glob uses '../docs/...'
  const globPath = filePath.replace('/docs/', '../docs/');
  
  if (modules[globPath]) {
    return modules[globPath];
  }
  
  // Try with different path formats
  const altPath = filePath.replace('/docs/', '/src/docs/');
  if (modules[altPath]) {
    return modules[altPath];
  }
  
  return null;
}

/**
 * Composable for document system
 * @returns {Object} Document utilities
 */
export function useDocs() {
  /**
   * All categories with their nested structure
   */
  const docCategories = computed(() => categories);
  
  /**
   * Get page metadata by slug
   * @param {string} slug - Page slug (e.g., 'welcome')
   * @returns {Object|null} Page metadata
   */
  function getPageMeta(slug) {
    return getPageMetaFromTree(slug);
  }
  
  /**
   * Get page content by slug
   * @param {string} slug - Page slug (e.g., 'welcome')
   * @returns {Object|null} Page object with title, content, meta
   */
  function getPage(slug) {
    const meta = getPageMetaFromTree(slug);
    if (!meta) return null;
    
    const content = getRawContent(meta.filePath);
    
    return {
      slug: meta.slug,
      title: meta.title,
      content: content || `# ${meta.title}\n\n文档内容加载失败，请检查文件路径：${meta.filePath}`,
      categorySlug: meta.categorySlug,
      subCategorySlug: meta.subCategorySlug,
      updatedAt: new Date().toISOString() // Static docs don't have timestamps
    };
  }
  
  /**
   * Get all available page slugs
   * @returns {string[]} Array of slugs
   */
  function getAllSlugs() {
    return allPages.map(p => p.slug);
  }
  
  /**
   * Check if a page exists
   * @param {string} slug - Page slug
   * @returns {boolean}
   */
  function pageExists(slug) {
    return allPages.some(p => p.slug === slug);
  }
  
  /**
   * Get the first page slug (for default route)
   * @returns {string}
   */
  function getFirstSlug() {
    return allPages.length > 0 ? allPages[0].slug : 'welcome';
  }
  
  return {
    categories: docCategories,
    getPageMeta,
    getPage,
    getAllSlugs,
    pageExists,
    getFirstSlug,
    getCategoryForPage
  };
}
