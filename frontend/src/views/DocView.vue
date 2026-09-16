<template>
  <div ref="pageRef" class="doc-page">
    <!-- ===== 顶部栏 ===== -->
    <header class="doc-topbar">
      <div class="topbar-left">
        <button class="icon-btn" title="返回应用首页" @click="goHome">
          <Icon name="arrow-left" :size="18" />
        </button>
        <div class="doc-brand">
          <Icon name="book-open" :size="18" />
          <span>使用文档</span>
        </div>
        <nav class="doc-crumb">
          <a @click="goIndex">全部文档</a>
          <template v-if="currentPage">
            <Icon name="forward" :size="12" />
            <span>{{ currentPage.categoryTitle }}</span>
            <Icon name="forward" :size="12" />
            <em>{{ currentPage.title }}</em>
          </template>
        </nav>
      </div>

      <div class="topbar-right">
        <button class="icon-btn nav-toggle" title="目录" @click="sidebarOpen = !sidebarOpen">
          <Icon name="list" :size="18" />
        </button>
        <div ref="searchRef" class="doc-search">
          <Icon name="search" :size="16" />
          <input
            v-model="keyword"
            type="text"
            placeholder="搜索文档…"
            @focus="searchOpen = true"
            @input="searchOpen = true"
            @keydown.esc="closeSearch"
          />
          <button v-if="keyword" class="search-clear" @click="keyword = ''">
            <Icon name="close" :size="14" />
          </button>
          <div v-if="searchOpen && keyword.trim()" class="search-panel">
            <p v-if="searchResults.length === 0" class="search-empty">没有匹配的文档</p>
            <a v-for="r in searchResults" :key="r.slug" class="search-item" @click="goto(r.slug)">
              <div class="search-item-head">
                <b>{{ r.title }}</b>
                <span>{{ r.categoryTitle }}</span>
              </div>
              <p>{{ r.snippet || r.description }}</p>
            </a>
          </div>
        </div>
        <button class="icon-btn" :title="isDark ? '切换为浅色' : '切换为深色'" @click="toggleTheme">
          <Icon :name="isDark ? 'sun' : 'moon'" :size="18" />
        </button>
        <button class="text-btn" @click="goHome">返回首页</button>
      </div>
    </header>

    <!-- 点击空白关闭搜索面板由 document 事件处理（不用遮罩：固定定位的遮罩会把滚轮事件
         交给被 App.vue 锁了 overflow 的 document，导致滚轮失效） -->

    <!-- ===== 文档索引页 ===== -->
    <main v-if="!currentPage" class="doc-index">
      <section class="index-hero">
        <h1>使用文档</h1>
        <p>这套系统能做什么、怎么用、出问题怎么查 —— 全部在这里。建议第一次先读「快速入门」。</p>
        <div class="hero-stats">
          <span><b>{{ totalPageCount }}</b> 篇文档</span>
          <span><b>{{ categories.length }}</b> 个分类</span>
          <span v-if="latestUpdated">最近更新 {{ latestUpdated }}</span>
        </div>
      </section>

      <section v-for="cat in categories" :key="cat.slug" class="index-cat">
        <div class="cat-head">
          <span class="cat-icon"><Icon :name="cat.iconName" :size="16" /></span>
          <div>
            <h2>{{ cat.title }}</h2>
            <p>{{ cat.description }}</p>
          </div>
        </div>
        <div class="card-grid">
          <a v-for="p in cat.pages" :key="p.slug" class="doc-card" @click="goto(p.slug)">
            <div class="card-main">
              <b>{{ p.title }}</b>
              <p>{{ p.description }}</p>
            </div>
            <div class="card-foot">
              <span v-if="p.updated">最后更新：{{ p.updated }}</span>
              <Icon name="forward" :size="14" />
            </div>
          </a>
        </div>
      </section>
    </main>

    <!-- ===== 阅读页 ===== -->
    <div v-else class="doc-body" :class="{ 'sidebar-open': sidebarOpen }">
      <aside class="doc-sidebar">
        <nav class="sidebar-nav">
          <div v-for="cat in categories" :key="cat.slug" class="nav-group">
            <div class="nav-group-title" @click="toggleCategory(cat.slug)">
              <Icon :name="cat.iconName" :size="14" />
              <span>{{ cat.title }}</span>
              <Icon :name="expanded.has(cat.slug) ? 'collapse' : 'expand'" :size="12" class="nav-caret" />
            </div>
            <ul v-show="expanded.has(cat.slug)" class="nav-list">
              <li v-for="p in cat.pages" :key="p.slug">
                <a :class="['nav-item', { active: p.slug === currentPage.slug }]" @click="goto(p.slug)">
                  {{ p.title }}
                </a>
              </li>
            </ul>
          </div>
        </nav>
      </aside>

      <main class="doc-main">
        <article class="doc-article">
          <header class="article-head">
            <h1>{{ currentPage.title }}</h1>
            <div class="article-meta">
              <span v-if="currentPage.description">{{ currentPage.description }}</span>
              <span v-if="currentPage.updated" class="meta-updated">最后更新：{{ currentPage.updated }}</span>
            </div>
          </header>
          <div ref="contentRef" class="doc-content" v-html="renderedHtml"></div>

          <nav class="doc-pager">
            <a v-if="siblings.prev" class="pager-link prev" @click="goto(siblings.prev.slug)">
              <span class="pager-label">上一篇</span>
              <span class="pager-title">{{ siblings.prev.title }}</span>
            </a>
            <span v-else class="pager-placeholder"></span>
            <a v-if="siblings.next" class="pager-link next" @click="goto(siblings.next.slug)">
              <span class="pager-label">下一篇</span>
              <span class="pager-title">{{ siblings.next.title }}</span>
            </a>
          </nav>
        </article>
      </main>

      <aside class="doc-toc">
        <div class="toc-title">本页面索引</div>
        <p v-if="headings.length === 0" class="toc-empty">暂无小节</p>
        <ul class="toc-list">
          <li v-for="h in headings" :key="h.id">
            <a
              :class="['toc-item', 'lv' + h.level, { active: activeHeading === h.id }]"
              @click="scrollToHeading(h.id)"
            >{{ h.text }}</a>
          </li>
        </ul>
      </aside>
    </div>
  </div>
</template>

<script>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue';
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
    const { theme, toggleTheme } = useTheme();
    const { categories, totalPageCount, getPage, getSiblingPages, searchDocs } = useDocs();

    const keyword = ref('');
    const searchOpen = ref(false);
    const sidebarOpen = ref(false);
    const expanded = ref(new Set(categories.value.map(c => c.slug)));
    const headings = ref([]);
    const activeHeading = ref('');
    const contentRef = ref(null);
    const pageRef = ref(null);   // 本页的滚动容器（见样式里 height:100vh; overflow-y:auto）
    const searchRef = ref(null);

    const md = new MarkdownIt({ html: true, linkify: true, typographer: true, breaks: false });

    const isDark = computed(() => theme.value === 'dark');

    /** 当前 slug（/docs 无 slug 时是索引页） */
    const currentSlug = computed(() => {
      const raw = route.params.pathMatch;
      const arr = Array.isArray(raw) ? raw : (raw ? [raw] : []);
      return arr.filter(Boolean).join('/');
    });

    const currentPage = computed(() => (currentSlug.value ? getPage(currentSlug.value) : null));
    const siblings = computed(() => (currentPage.value ? getSiblingPages(currentPage.value.slug) : { prev: null, next: null }));

    const renderedHtml = computed(() => (currentPage.value ? md.render(currentPage.value.body || '') : ''));

    const latestUpdated = computed(() => {
      const dates = categories.value.flatMap(c => c.pages.map(p => p.updated)).filter(Boolean).sort();
      return dates.length ? dates[dates.length - 1] : '';
    });

    const searchResults = computed(() => (searchOpen.value ? searchDocs(keyword.value) : []));

    /** 渲染后给标题加锚点 id，并收集右侧「本页面索引」 */
    const collectHeadings = () => {
      const container = contentRef.value;
      if (!container) {
        headings.value = [];
        return;
      }
      const els = container.querySelectorAll('h2, h3, h4');
      const list = [];
      els.forEach((el, i) => {
        const id = el.id || `sec-${i}`;
        el.id = id;
        list.push({ id, text: el.textContent.replace(/^#+\s*/, '').trim(), level: Number(el.tagName.substring(1)) });
      });
      headings.value = list;
      activeHeading.value = list.length ? list[0].id : '';
    };

    /** 滚动高亮当前小节 */
    const onScroll = () => {
      if (!headings.value.length) return;
      const offset = 120;
      let current = headings.value[0].id;
      for (const h of headings.value) {
        const el = document.getElementById(h.id);
        if (el && el.getBoundingClientRect().top <= offset) current = h.id;
      }
      activeHeading.value = current;
    };

    const scrollToHeading = id => {
      const el = document.getElementById(id);
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      activeHeading.value = id;
    };

    const goto = slug => {
      closeSearch();
      sidebarOpen.value = false;
      router.push('/docs/' + slug);
      // 切页后回到顶部（滚动发生在 .doc-page 容器里，不是 window）
      nextTick(() => pageRef.value?.scrollTo({ top: 0, behavior: 'auto' }));
    };

    const goIndex = () => router.push('/docs');
    const goHome = () => router.push('/');

    const closeSearch = () => {
      searchOpen.value = false;
      keyword.value = '';
    };

    // 搜索面板打开时：点击面板外或按 Esc 关闭（不依赖遮罩层，避免挡住滚轮）
    const onDocMouseDown = e => {
      if (searchRef.value && !searchRef.value.contains(e.target)) closeSearch();
    };
    const onDocKeyDown = e => {
      if (e.key === 'Escape') closeSearch();
    };
    watch(searchOpen, open => {
      if (open) {
        document.addEventListener('mousedown', onDocMouseDown);
        document.addEventListener('keydown', onDocKeyDown);
      } else {
        document.removeEventListener('mousedown', onDocMouseDown);
        document.removeEventListener('keydown', onDocKeyDown);
      }
    });

    const toggleCategory = slug => {
      const set = new Set(expanded.value);
      if (set.has(slug)) set.delete(slug);
      else set.add(slug);
      expanded.value = set;
    };

    watch(renderedHtml, () => {
      nextTick(() => {
        collectHeadings();
        // 切页后回到顶部（滚动容器为 .doc-page）
        if ((pageRef.value?.scrollTop || 0) > 40) {
          pageRef.value?.scrollTo({ top: 0 });
        }
      });
    });

    onMounted(() => {
      collectHeadings();
      // 监听容器滚动（不是 window），用于右侧目录高亮
      pageRef.value?.addEventListener('scroll', onScroll, { passive: true });
    });

    onUnmounted(() => {
      pageRef.value?.removeEventListener('scroll', onScroll);
      document.removeEventListener('mousedown', onDocMouseDown);
      document.removeEventListener('keydown', onDocKeyDown);
    });

    return {
      categories,
      totalPageCount,
      latestUpdated,
      currentPage,
      currentSlug,
      siblings,
      renderedHtml,
      contentRef,
      pageRef,
      searchRef,
      headings,
      activeHeading,
      keyword,
      searchOpen,
      searchResults,
      sidebarOpen,
      expanded,
      isDark,
      toggleTheme,
      scrollToHeading,
      goto,
      goIndex,
      goHome,
      closeSearch,
      toggleCategory
    };
  }
};
</script>

<style scoped>
/* ============================ 页面骨架 ============================ */
.doc-page {
  /* App.vue 全局锁了 html/body 的 overflow（整站靠内层容器滚动），
     所以文档页必须自己做滚动容器，否则滚轮完全失效 */
  height: 100vh;
  overflow-y: auto;
  overflow-x: hidden;
  background: #f6f7fb;
  color: #1f2937;
  font-size: 15px;
  /* 全局 style.css 里 #app 设了 text-align:center，会继承到本页导致侧栏与标题被居中，
     这里显式改回左对齐 */
  text-align: left;
}

.doc-topbar {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 24px;
  background: rgba(255, 255, 255, 0.92);
  border-bottom: 1px solid #e5e7eb;
  backdrop-filter: blur(10px);
}
.topbar-left,
.topbar-right { display: flex; align-items: center; gap: 12px; min-width: 0; }

.icon-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 34px; height: 34px; border-radius: 9px;
  border: 1px solid #e5e7eb; background: #fff; color: #475569;
  cursor: pointer; transition: all 0.15s;
}
.icon-btn:hover { background: #f1f5f9; color: #1f2937; border-color: #cbd5e1; }
.nav-toggle { display: none; }

.doc-brand { display: flex; align-items: center; gap: 8px; font-weight: 700; font-size: 15px; color: #4f46e5; white-space: nowrap; }
.doc-crumb { display: flex; align-items: center; gap: 8px; font-size: 13.5px; color: #64748b; min-width: 0; }
.doc-crumb a { color: #64748b; cursor: pointer; }
.doc-crumb a:hover { color: #4f46e5; text-decoration: underline; }
.doc-crumb em { font-style: normal; color: #1f2937; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.text-btn {
  padding: 8px 14px; border-radius: 9px; font-size: 13.5px; font-weight: 600;
  border: 1px solid #e5e7eb; background: #fff; color: #475569; cursor: pointer;
}
.text-btn:hover { background: #f1f5f9; color: #1f2937; }

/* 搜索 */
.doc-search {
  position: relative; display: flex; align-items: center; gap: 8px;
  width: 240px; padding: 0 10px; height: 34px;
  border: 1px solid #e5e7eb; border-radius: 9px; background: #fff; color: #94a3b8;
}
.doc-search input {
  flex: 1; border: none; outline: none; background: transparent;
  font-size: 13.5px; color: #1f2937; min-width: 0;
}
.search-clear { border: none; background: none; color: #94a3b8; cursor: pointer; display: flex; }
.search-clear:hover { color: #475569; }
.search-backdrop { position: fixed; inset: 0; z-index: 20; }
.search-panel {
  position: absolute; top: 42px; right: 0; z-index: 40;
  width: 420px; max-height: 420px; overflow-y: auto;
  padding: 8px; border-radius: 12px;
  background: #fff; border: 1px solid #e5e7eb;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.14);
}
.search-empty { margin: 12px; font-size: 13.5px; color: #64748b; }
.search-item { display: block; padding: 10px 12px; border-radius: 9px; cursor: pointer; }
.search-item:hover { background: #f5f3ff; }
.search-item-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.search-item-head b { font-size: 14px; color: #1f2937; }
.search-item-head span { font-size: 11.5px; color: #7c3aed; background: #f3f0ff; padding: 2px 8px; border-radius: 999px; }
.search-item p { margin: 5px 0 0; font-size: 12.5px; line-height: 1.55; color: #64748b; }

/* ============================ 索引页 ============================ */
.doc-index { max-width: 1440px; margin: 0 auto; padding: 36px 28px 72px; }
.index-hero { padding: 26px 0 30px; border-bottom: 1px solid #e5e7eb; margin-bottom: 34px; }
.index-hero h1 { margin: 0 0 10px; font-size: 34px; font-weight: 800; letter-spacing: 0.4px; }
.index-hero p { margin: 0 0 16px; font-size: 15.5px; line-height: 1.7; color: #4b5563; max-width: 720px; }
.hero-stats { display: flex; flex-wrap: wrap; gap: 18px; font-size: 13.5px; color: #6b7280; }
.hero-stats b { color: #4f46e5; font-size: 16px; margin-right: 4px; }

.index-cat { margin-bottom: 38px; }
.cat-head { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 16px; }
.cat-icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 32px; height: 32px; border-radius: 10px; flex-shrink: 0;
  background: #eef2ff; color: #4f46e5;
}
.cat-head h2 { margin: 0 0 3px; font-size: 19px; font-weight: 700; }
.cat-head p { margin: 0; font-size: 13.5px; color: #6b7280; }

.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px; }
.doc-card {
  display: flex; flex-direction: column; justify-content: space-between; gap: 14px;
  min-height: 118px; padding: 16px 18px;
  border-radius: 14px; border: 1px solid #e5e7eb; background: #fff;
  cursor: pointer; transition: all 0.18s;
}
.doc-card:hover { border-color: #c7d2fe; box-shadow: 0 10px 26px rgba(79, 70, 229, 0.1); transform: translateY(-2px); }
.card-main b { display: block; font-size: 15.5px; font-weight: 700; margin-bottom: 6px; }
.card-main p { margin: 0; font-size: 13px; line-height: 1.6; color: #6b7280; }
.card-foot { display: flex; align-items: center; justify-content: space-between; font-size: 12px; color: #9ca3af; }

/* ============================ 阅读页 ============================ */
.doc-body {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr) 240px;
  gap: 36px;
  width: 100%;
  max-width: 1760px;
  margin: 0 auto;
  padding: 28px 28px 80px;
  align-items: start;
}

/* 左侧栏 */
.doc-sidebar { position: sticky; top: 74px; max-height: calc(100vh - 100px); overflow-y: auto; }
.sidebar-nav { display: flex; flex-direction: column; gap: 14px; }
.nav-group-title {
  display: flex; align-items: center; gap: 8px;
  font-size: 13px; font-weight: 700; color: #4f46e5;
  padding: 6px 8px; border-radius: 8px; cursor: pointer; user-select: none;
}
.nav-group-title:hover { background: #eef2ff; }
.nav-caret { margin-left: auto; color: #a5b4fc; }
.nav-list { list-style: none; margin: 4px 0 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
.nav-item {
  display: block; padding: 7px 10px 7px 26px; border-radius: 8px;
  font-size: 13.5px; color: #4b5563; cursor: pointer; border-left: 2px solid transparent;
}
.nav-item:hover { background: #f3f4f6; color: #1f2937; }
.nav-item.active { background: #eef2ff; color: #4f46e5; font-weight: 600; border-left-color: #4f46e5; }

/* 正文 */
.doc-main { min-width: 0; }
.doc-article {
  padding: 32px 40px 40px;
  border-radius: 16px; border: 1px solid #e5e7eb; background: #fff;
}
.article-head { padding-bottom: 18px; border-bottom: 1px solid #f1f5f9; margin-bottom: 22px; }
.article-head h1 { margin: 0 0 10px; font-size: 27px; font-weight: 800; letter-spacing: 0.3px; }
.article-meta { display: flex; flex-wrap: wrap; gap: 14px; font-size: 13px; color: #6b7280; }
.meta-updated { color: #9ca3af; }

/* markdown 排版 */
.doc-content { font-size: 15px; line-height: 1.85; color: #374151; }
.doc-content :deep(h2) {
  margin: 34px 0 14px; padding-bottom: 8px; font-size: 21px; font-weight: 700; color: #111827;
  border-bottom: 1px solid #eef2f7; scroll-margin-top: 90px;
}
.doc-content :deep(h3) { margin: 26px 0 10px; font-size: 17.5px; font-weight: 700; color: #1f2937; scroll-margin-top: 90px; }
.doc-content :deep(h4) { margin: 20px 0 8px; font-size: 15.5px; font-weight: 600; color: #374151; scroll-margin-top: 90px; }
.doc-content :deep(p) { margin: 0 0 14px; }
.doc-content :deep(a) { color: #4f46e5; text-decoration: none; border-bottom: 1px solid #ddd6fe; }
.doc-content :deep(a:hover) { border-bottom-color: #4f46e5; }
.doc-content :deep(ul),
.doc-content :deep(ol) { margin: 0 0 16px; padding-left: 24px; }
.doc-content :deep(li) { margin-bottom: 6px; }
.doc-content :deep(li > p) { margin-bottom: 6px; }
.doc-content :deep(strong) { color: #111827; font-weight: 700; }
.doc-content :deep(hr) { margin: 28px 0; border: none; border-top: 1px solid #eef2f7; }
.doc-content :deep(code) {
  padding: 2px 6px; border-radius: 6px; background: #f1f5f9;
  font-family: "JetBrains Mono", Consolas, Monaco, monospace; font-size: 13px; color: #be185d;
}
.doc-content :deep(pre) {
  margin: 0 0 18px; padding: 16px 18px; overflow-x: auto;
  border-radius: 12px; background: #0f172a; border: 1px solid #1e293b;
}
.doc-content :deep(pre code) { padding: 0; background: none; color: #e2e8f0; font-size: 13px; line-height: 1.7; }
.doc-content :deep(blockquote) {
  margin: 0 0 16px; padding: 12px 16px;
  border-left: 3px solid #818cf8; border-radius: 0 10px 10px 0;
  background: #f5f3ff; color: #4338ca;
}
.doc-content :deep(blockquote p:last-child) { margin-bottom: 0; }
.doc-content :deep(table) {
  width: 100%; margin: 0 0 18px; border-collapse: collapse; font-size: 13.5px;
  border: 1px solid #e5e7eb; border-radius: 10px; overflow: hidden;
}
.doc-content :deep(thead) { background: #f8fafc; }
.doc-content :deep(th),
.doc-content :deep(td) { padding: 9px 12px; border-bottom: 1px solid #eef2f7; text-align: left; vertical-align: top; }
.doc-content :deep(th) { font-weight: 700; color: #111827; }
.doc-content :deep(tr:last-child td) { border-bottom: none; }
.doc-content :deep(img) { max-width: 100%; border-radius: 12px; margin: 6px 0 16px; }

/* 上/下一篇 */
.doc-pager { display: flex; gap: 14px; margin-top: 34px; padding-top: 22px; border-top: 1px solid #f1f5f9; }
.pager-placeholder { flex: 1; }
.pager-link {
  flex: 1; display: flex; flex-direction: column; gap: 4px;
  padding: 12px 16px; border-radius: 12px; border: 1px solid #e5e7eb;
  background: #fff; cursor: pointer; transition: all 0.15s;
}
.pager-link:hover { border-color: #c7d2fe; background: #f8faff; }
.pager-link.next { text-align: right; }
.pager-label { font-size: 11.5px; color: #9ca3af; }
.pager-title { font-size: 14px; font-weight: 600; color: #4f46e5; }

/* 右侧目录 */
.doc-toc { position: sticky; top: 74px; max-height: calc(100vh - 100px); overflow-y: auto; }
.toc-title {
  display: flex; align-items: center; gap: 8px;
  font-size: 13px; font-weight: 700; color: #4f46e5; margin-bottom: 10px;
}
.toc-list { list-style: none; margin: 0; padding: 0; border-left: 1px solid #e5e7eb; }
.toc-item {
  display: block; padding: 5px 10px; font-size: 13px; color: #6b7280;
  cursor: pointer; border-left: 2px solid transparent; margin-left: -1px;
}
.toc-item:hover { color: #4f46e5; }
.toc-item.lv3 { padding-left: 22px; font-size: 12.5px; }
.toc-item.lv4 { padding-left: 32px; font-size: 12.5px; color: #9ca3af; }
.toc-item.active { color: #4f46e5; font-weight: 600; border-left-color: #4f46e5; background: #f5f3ff; }
.toc-empty { font-size: 12.5px; color: #9ca3af; }

/* ============================ 暗色主题 ============================ */
.theme-dark .doc-page { background: #0b1120; color: #e5e7eb; }
.theme-dark .doc-topbar { background: rgba(17, 24, 39, 0.92); border-bottom-color: #1f2937; }
.theme-dark .icon-btn,
.theme-dark .text-btn { background: #111827; border-color: #1f2937; color: #cbd5e1; }
.theme-dark .icon-btn:hover,
.theme-dark .text-btn:hover { background: #1f2937; color: #f8fafc; border-color: #334155; }
.theme-dark .doc-brand { color: #a5b4fc; }
.theme-dark .doc-crumb { color: #94a3b8; }
.theme-dark .doc-crumb em { color: #e5e7eb; }
.theme-dark .doc-search { background: #111827; border-color: #1f2937; color: #64748b; }
.theme-dark .doc-search input { color: #e5e7eb; }
.theme-dark .search-panel { background: #111827; border-color: #1f2937; box-shadow: 0 18px 40px rgba(0, 0, 0, 0.5); }
.theme-dark .search-item:hover { background: #1e293b; }
.theme-dark .search-item-head b { color: #e5e7eb; }
.theme-dark .search-item-head span { background: #312e81; color: #c7d2fe; }
.theme-dark .search-item p { color: #94a3b8; }
.theme-dark .search-empty { color: #94a3b8; }

.theme-dark .index-hero { border-bottom-color: #1f2937; }
.theme-dark .index-hero p { color: #94a3b8; }
.theme-dark .hero-stats { color: #94a3b8; }
.theme-dark .hero-stats b { color: #a5b4fc; }
.theme-dark .cat-icon { background: #1e1b4b; color: #a5b4fc; }
.theme-dark .cat-head p { color: #94a3b8; }

.theme-dark .doc-card { background: #111827; border-color: #1f2937; }
.theme-dark .doc-card:hover { border-color: #4338ca; box-shadow: 0 10px 26px rgba(0, 0, 0, 0.45); }
.theme-dark .card-main b { color: #e5e7eb; }
.theme-dark .card-main p { color: #94a3b8; }
.theme-dark .card-foot { color: #64748b; }

.theme-dark .nav-group-title { color: #a5b4fc; }
.theme-dark .nav-group-title:hover { background: #1e1b4b; }
.theme-dark .nav-caret { color: #4c1d95; }
.theme-dark .nav-item { color: #cbd5e1; }
.theme-dark .nav-item:hover { background: #1e293b; color: #f1f5f9; }
.theme-dark .nav-item.active { background: #1e1b4b; color: #c7d2fe; border-left-color: #818cf8; }

.theme-dark .doc-article { background: #111827; border-color: #1f2937; }
.theme-dark .article-head { border-bottom-color: #1f2937; }
.theme-dark .article-meta { color: #94a3b8; }
.theme-dark .meta-updated { color: #64748b; }

.theme-dark .doc-content { color: #cbd5e1; }
.theme-dark .doc-content :deep(h2) { color: #f1f5f9; border-bottom-color: #1f2937; }
.theme-dark .doc-content :deep(h3) { color: #e5e7eb; }
.theme-dark .doc-content :deep(h4) { color: #cbd5e1; }
.theme-dark .doc-content :deep(strong) { color: #f8fafc; }
.theme-dark .doc-content :deep(a) { color: #a5b4fc; border-bottom-color: #4338ca; }
.theme-dark .doc-content :deep(hr) { border-top-color: #1f2937; }
.theme-dark .doc-content :deep(code) { background: #1e293b; color: #f9a8d4; }
.theme-dark .doc-content :deep(pre) { background: #020617; border-color: #1e293b; }
/* 代码块内的文字不要用行内代码的粉色（暗色下覆盖） */
.theme-dark .doc-content :deep(pre code) { background: none; color: #e2e8f0; }
.theme-dark .doc-content :deep(blockquote) { background: #1e1b4b; border-left-color: #6366f1; color: #c7d2fe; }
.theme-dark .doc-content :deep(table) { border-color: #1f2937; }
.theme-dark .doc-content :deep(thead) { background: #1e293b; }
.theme-dark .doc-content :deep(th) { color: #f1f5f9; }
.theme-dark .doc-content :deep(th),
.theme-dark .doc-content :deep(td) { border-bottom-color: #1f2937; }

.theme-dark .doc-pager { border-top-color: #1f2937; }
.theme-dark .pager-link { background: #111827; border-color: #1f2937; }
.theme-dark .pager-link:hover { background: #1e293b; border-color: #4338ca; }
.theme-dark .pager-label { color: #64748b; }
.theme-dark .pager-title { color: #a5b4fc; }

.theme-dark .toc-title { color: #a5b4fc; }
.theme-dark .toc-list { border-left-color: #1f2937; }
.theme-dark .toc-item { color: #94a3b8; }
.theme-dark .toc-item:hover { color: #c7d2fe; }
.theme-dark .toc-item.active { color: #c7d2fe; border-left-color: #818cf8; background: #1e1b4b; }
.theme-dark .toc-empty { color: #64748b; }

/* ============================ 响应式 ============================ */
/* 视口不够宽时先收起「本页面索引」，保证正文列够宽（1280 宽窗口下正文约 900px） */
@media (max-width: 1399px) {
  .doc-body { grid-template-columns: 250px minmax(0, 1fr); gap: 28px; }
  .doc-toc { display: none; }
}
@media (max-width: 1023px) {
  .doc-index { padding: 24px 16px 60px; }
  .card-grid { grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); }
  .index-hero h1 { font-size: 27px; }
  .doc-body { display: block; padding: 16px 14px 60px; }
  .nav-toggle { display: inline-flex; }
  .doc-toc { display: none; }
  .doc-sidebar {
    position: fixed; top: 0; left: 0; z-index: 50;
    width: 270px; height: 100vh; max-height: none;
    padding: 18px 14px; background: #fff; border-right: 1px solid #e5e7eb;
    transform: translateX(-100%); transition: transform 0.22s ease;
  }
  .doc-body.sidebar-open .doc-sidebar { transform: translateX(0); }
  .theme-dark .doc-sidebar { background: #0f172a; border-right-color: #1f2937; }
  .doc-article { padding: 22px 18px 26px; }
  .doc-search { width: 150px; }
  .doc-crumb { display: none; }
}
@media (max-width: 640px) {
  .card-grid { grid-template-columns: 1fr; }
  .doc-brand span { display: none; }
  .search-panel { width: min(92vw, 380px); right: -40px; }
}
</style>
