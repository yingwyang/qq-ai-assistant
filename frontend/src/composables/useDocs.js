import { computed, ref } from 'vue';
import {
  categories,
  allPages,
  getPageMeta as getPageMetaFromTree,
  getCategoryForPage,
  getSiblingPages,
  totalPageCount
} from '../docs/tree.js';

// 构建期一次性内联所有 markdown 原文（eager），因此搜索与阅读都是纯前端、零请求
const modules = import.meta.glob('../docs/**/*.md', {
  query: '?raw',
  import: 'default',
  eager: true
});

/** 取某页 markdown 原文 */
function getRawContent(filePath) {
  const globPath = filePath.replace('/docs/', '../docs/');
  if (modules[globPath]) return modules[globPath];
  const altPath = filePath.replace('/docs/', '/src/docs/');
  if (altPath && modules[altPath]) return modules[altPath];
  return null;
}

/**
 * 解析 YAML front-matter（只支持 title / description / updated 这类单行键值）。
 * @returns {{meta: Object, body: string}}
 */
export function parseFrontMatter(raw) {
  if (!raw || !raw.startsWith('---')) return { meta: {}, body: raw || '' };
  const end = raw.indexOf('\n---', 3);
  if (end < 0) return { meta: {}, body: raw };
  const head = raw.slice(3, end).trim();
  const body = raw.slice(end + 4).replace(/^\s*\n/, '');
  const meta = {};
  for (const line of head.split('\n')) {
    const m = /^([A-Za-z_][\w-]*)\s*:\s*(.*)$/.exec(line.trim());
    if (!m) continue;
    let value = m[2].trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    meta[m[1]] = value;
  }
  return { meta, body };
}

// 预解析全部页面：正文 + front-matter（供阅读、索引卡片与搜索复用）
const parsedPages = allPages.map(p => {
  const raw = getRawContent(p.filePath);
  const { meta, body } = parseFrontMatter(raw || '');
  return {
    ...p,
    title: meta.title || p.title,
    description: meta.description || p.description || '',
    updated: meta.updated || '',
    body: raw ? body : `# ${p.title}\n\n> 文档加载失败：${p.filePath}`,
    available: !!raw
  };
});

const pageMap = new Map(parsedPages.map(p => [p.slug, p]));

/** 供搜索用的轻量语料（去掉 markdown 标记） */
const searchCorpus = parsedPages.map(p => ({
  slug: p.slug,
  title: p.title,
  description: p.description,
  categoryTitle: p.categoryTitle,
  text: p.body
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/[#>*`|_\-]/g, ' ')
    .replace(/\s+/g, ' ')
    .toLowerCase()
}));

export function useDocs() {
  const docCategories = computed(() =>
    categories.map(cat => ({
      ...cat,
      pages: cat.pages.map(p => {
        const full = pageMap.get(p.slug);
        return full || p;
      })
    }))
  );

  /** 索引页/侧栏用的分类（含 front-matter 后的标题与描述） */
  const getPageMeta = slug => pageMap.get(slug) || getPageMetaFromTree(slug);

  /** 阅读页数据 */
  const getPage = slug => pageMap.get(slug) || null;

  const getAllSlugs = () => parsedPages.map(p => p.slug);

  const pageExists = slug => pageMap.has(slug);

  const getFirstSlug = () => (parsedPages.length ? parsedPages[0].slug : 'welcome');

  /**
   * 站内搜索：标题命中权重最高，其次描述，最后正文。
   * @param {string} keyword
   * @param {number} limit
   */
  const searchDocs = (keyword, limit = 8) => {
    const kw = (keyword || '').trim().toLowerCase();
    if (!kw) return [];
    const results = [];
    for (const item of searchCorpus) {
      let score = 0;
      if (item.title.toLowerCase().includes(kw)) score += 100;
      if (item.description.toLowerCase().includes(kw)) score += 40;
      const idx = item.text.indexOf(kw);
      if (idx >= 0) score += 10;
      if (!score) continue;
      let snippet = '';
      if (idx >= 0) {
        const start = Math.max(0, idx - 30);
        snippet = (start > 0 ? '…' : '') + item.text.slice(start, idx + kw.length + 60).trim() + '…';
      }
      results.push({ ...item, score, snippet });
    }
    return results.sort((a, b) => b.score - a.score).slice(0, limit);
  };

  return {
    categories: docCategories,
    allPages: parsedPages,
    totalPageCount,
    getPageMeta,
    getPage,
    getAllSlugs,
    pageExists,
    getFirstSlug,
    getCategoryForPage,
    getSiblingPages,
    searchDocs
  };
}
