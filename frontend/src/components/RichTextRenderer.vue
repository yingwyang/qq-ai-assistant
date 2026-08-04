<template>
  <div class="rich-text" :class="className" @click="handleClick">
    <div ref="contentRef" v-html="renderedHtml"></div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, watch } from 'vue';
import MarkdownIt from 'markdown-it';
import DOMPurify from 'dompurify';

const props = defineProps({
  content: {
    type: String,
    default: ''
  },
  enableMarkdown: {
    type: Boolean,
    default: true
  },
  className: {
    type: String,
    default: ''
  },
  sectionMode: {
    type: Boolean,
    default: false
  },
  scrollContainer: {
    type: Object,
    default: null
  }
});

const contentRef = ref(null);

const md = new MarkdownIt({
  html: true,
  linkify: true,
  typographer: false,
  breaks: true
});

const markdownPattern = /[#*`[\]<!]|^\s*[-+]\s|^\s*\d+\.\s|^\s*>\s|\|.*\||<[^>]+>/m;

function escapeHtml(text) {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;');
}

/**
 * 处理目录点击事件，在滚动容器内平滑滚动到目标位置
 */
const handleClick = (e) => {
  const target = e.target.closest('a');
  if (!target) return;

  const href = target.getAttribute('href');
  if (!href || !href.startsWith('#')) return;

  const targetId = href.substring(1);
  if (!targetId) return;

  // 检查是否是目录链接
  const isTocLink = target.closest('.toc, .rt-section-toc');
  if (!isTocLink) return;

  e.preventDefault();

  // 查找目标元素
  const el = contentRef.value?.querySelector(`[id="${targetId}"]`);
  if (!el) return;

  // 找到滚动容器
  let container = props.scrollContainer;
  if (!container) {
    // 尝试向上查找滚动容器
    let parent = contentRef.value?.parentElement;
    while (parent) {
      const style = window.getComputedStyle(parent);
      const overflow = style.overflowY;
      if ((overflow === 'auto' || overflow === 'scroll') && parent.scrollHeight > parent.clientHeight) {
        container = parent;
        break;
      }
      parent = parent.parentElement;
    }
  }

  if (container) {
    // 计算目标位置相对于滚动容器的偏移
    const elRect = el.getBoundingClientRect();
    const containerRect = container.getBoundingClientRect();
    const offset = elRect.top - containerRect.top + container.scrollTop - 20;

    container.scrollTo({
      top: offset,
      behavior: 'smooth'
    });

    // 添加高亮效果
    el.classList.add('rt-highlight');
    setTimeout(() => el.classList.remove('rt-highlight'), 2000);
  } else {
    // 回退到默认锚点跳转
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
    el.classList.add('rt-highlight');
    setTimeout(() => el.classList.remove('rt-highlight'), 2000);
  }
};

/**
 * 检测以 <p><strong>标题</strong></p> 形式呈现的章节，并：
 * 1. 为每个章节包裹 <section class="rt-section"> 卡片
 * 2. 在顶部生成可点击跳转的章节导航目录
 * 3. 标题渲染为 rt-section-title，内容渲染为 rt-section-content
 */
function processSections(html) {
  if (!html) return html;
  const hasCandidate = /<(?:strong|b|h[2-4])\b/i.test(html);
  if (!hasCandidate) return html;

  const parser = new DOMParser();
  const doc = parser.parseFromString(html, 'text/html');
  const body = doc.body;
  if (!body || body.children.length === 0) return html;

  const isSectionTitle = (el) => {
    const text = (el.textContent || '').trim();
    if (!text || text.length > 80) return false;

    if (/^H[2-4]$/.test(el.tagName)) return true;

    if (el.tagName === 'P' && el.childNodes.length === 1) {
      const child = el.firstElementChild;
      if (child && (child.tagName === 'STRONG' || child.tagName === 'B')) {
        return true;
      }
    }

    if (/^[（(][一二三四五六七八九十]+[）)]\s*[^\n]+$/.test(text)) return true;
    if (/^[一二三四五六七八九十]+[、.．:]\s*[^\n]+$/.test(text)) return true;
    if (/^\d+[、.．:]\s*[^\n]+$/.test(text)) return true;
    if (/[:：]$/.test(text)) return true;

    return false;
  };

  const children = Array.from(body.children);
  const firstTitleIndex = children.findIndex(isSectionTitle);
  if (firstTitleIndex === -1) return html;

  const preamble = children.slice(0, firstTitleIndex);
  const rest = children.slice(firstTitleIndex);

  const sections = [];
  let current = null;

  rest.forEach((el) => {
    if (isSectionTitle(el)) {
      if (current) sections.push(current);
      current = { title: el.textContent.trim(), titleEl: el, contentEls: [] };
    } else if (current) {
      current.contentEls.push(el);
    } else {
      preamble.push(el);
    }
  });
  if (current) sections.push(current);

  if (sections.length < 2) return html;

  const toc = doc.createElement('div');
  toc.className = 'rt-section-toc';
  const tocTitle = doc.createElement('div');
  tocTitle.className = 'rt-section-toc-title';
  tocTitle.textContent = '📑 目录';
  toc.appendChild(tocTitle);
  const tocList = doc.createElement('ul');
  toc.appendChild(tocList);

  const fragment = doc.createDocumentFragment();
  preamble.forEach((el) => fragment.appendChild(el));
  fragment.appendChild(toc);

  sections.forEach((section, index) => {
    const id = `rt-section-${index}`;
    const sectionWrap = doc.createElement('section');
    sectionWrap.className = 'rt-section';
    sectionWrap.id = id;

    section.titleEl.className = 'rt-section-title';
    sectionWrap.appendChild(section.titleEl);

    const contentWrap = doc.createElement('div');
    contentWrap.className = 'rt-section-content';
    section.contentEls.forEach((contentEl) => contentWrap.appendChild(contentEl));
    sectionWrap.appendChild(contentWrap);

    fragment.appendChild(sectionWrap);

    const li = doc.createElement('li');
    const a = doc.createElement('a');
    a.href = `#${id}`;
    a.textContent = section.title;
    li.appendChild(a);
    tocList.appendChild(li);
  });

  body.innerHTML = '';
  body.appendChild(fragment);

  return body.innerHTML;
}

function renderToc(html) {
  const headings = [];
  const regex = /<h([2-4])[^>]*>(.*?)<\/h\1>/g;
  let match;
  while ((match = regex.exec(html)) !== null) {
    const level = parseInt(match[1], 10);
    const title = match[2].replace(/<[^>]+>/g, '');
    const id = 'toc-' + headings.length;
    headings.push({ level, title, id });
  }

  if (headings.length === 0) {
    return html.replace('[TOC]', '');
  }

  let tocHtml = '<div class="toc"><div class="toc-title">目录</div><ul>';
  let lastLevel = headings[0].level;
  headings.forEach((h) => {
    while (h.level > lastLevel) {
      tocHtml += '<ul>';
      lastLevel++;
    }
    while (h.level < lastLevel) {
      tocHtml += '</ul>';
      lastLevel--;
    }
    tocHtml += `<li><a href="#${h.id}">${h.title}</a></li>`;
  });
  while (lastLevel > headings[0].level) {
    tocHtml += '</ul>';
    lastLevel--;
  }
  tocHtml += '</ul></div>';

  let index = 0;
  const htmlWithIds = html.replace(/<h([2-4])([^>]*)>/g, (full, level, attrs) => {
    const id = headings[index++].id;
    return `<h${level}${attrs} id="${id}">`;
  });

  return htmlWithIds.replace('[TOC]', tocHtml);
}

const renderedHtml = computed(() => {
  if (!props.content) return '';

  let html;
  if (props.enableMarkdown && markdownPattern.test(props.content)) {
    html = md.render(props.content);
    html = html.replace(/<a /g, '<a target="_blank" rel="noopener noreferrer" ');
    if (html.includes('[TOC]')) {
      html = renderToc(html);
    }
    if (props.sectionMode && !html.includes('rt-section') && !html.includes('class="toc"')) {
      html = processSections(html);
    }
  } else {
    html = escapeHtml(props.content).replace(/\n/g, '<br>');
  }

  return DOMPurify.sanitize(html, {
    ALLOWED_TAGS: [
      'a', 'b', 'strong', 'i', 'em', 'code', 'pre', 'blockquote',
      'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'p', 'br', 'hr',
      'table', 'thead', 'tbody', 'tr', 'th', 'td', 'details', 'summary', 'div', 'span', 'section'
    ],
    ALLOWED_ATTR: ['href', 'target', 'rel', 'class', 'title', 'id', 'open']
  });
});
</script>

<style scoped>
.rich-text {
  line-height: 1.6;
  word-break: break-word;
}

.rich-text :deep(h1),
.rich-text :deep(h2),
.rich-text :deep(h3),
.rich-text :deep(h4),
.rich-text :deep(h5),
.rich-text :deep(h6) {
  margin: 0.8em 0 0.4em;
  font-weight: 600;
  line-height: 1.3;
}

.rich-text :deep(h1) { font-size: 1.4em; }
.rich-text :deep(h2) { font-size: 1.25em; }
.rich-text :deep(h3) { font-size: 1.15em; }
.rich-text :deep(h4) { font-size: 1.05em; }

.rich-text :deep(p) {
  margin: 0.5em 0;
}

.rich-text :deep(ul),
.rich-text :deep(ol) {
  margin: 0.5em 0;
  padding-left: 1.5em;
}

.rich-text :deep(li) {
  margin: 0.25em 0;
}

.rich-text :deep(blockquote) {
  margin: 0.5em 0;
  padding: 0.4em 0.8em;
  border-left: 4px solid #dfe2e5;
  background-color: rgba(0, 0, 0, 0.03);
  color: #555;
}

.rich-text :deep(pre) {
  margin: 0.6em 0;
  padding: 0.8em;
  background-color: #f6f8fa;
  border-radius: 6px;
  overflow-x: auto;
}

.rich-text :deep(code) {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 0.9em;
  padding: 0.15em 0.35em;
  background-color: rgba(175, 184, 193, 0.2);
  border-radius: 3px;
}

.rich-text :deep(pre code) {
  padding: 0;
  background-color: transparent;
}

.rich-text :deep(a) {
  color: #0366d6;
  text-decoration: none;
}

.rich-text :deep(a:hover) {
  text-decoration: underline;
}

.rich-text :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 0.6em 0;
}

.rich-text :deep(th),
.rich-text :deep(td) {
  border: 1px solid #dfe2e5;
  padding: 0.4em 0.6em;
  text-align: left;
}

.rich-text :deep(th) {
  background-color: #f6f8fa;
  font-weight: 600;
}

.rich-text :deep(details) {
  margin: 0.6em 0;
  padding: 0.6em;
  border: 1px solid #e1e4e8;
  border-radius: 6px;
  background-color: #fafbfc;
}

.rich-text :deep(summary) {
  font-weight: 600;
  cursor: pointer;
  outline: none;
}

.rich-text :deep(.toc) {
  margin: 0.6em 0;
  padding: 0.6em 0.8em;
  border: 1px solid #e1e4e8;
  border-radius: 6px;
  background-color: #fafbfc;
}

.rich-text :deep(.toc-title) {
  font-weight: 600;
  margin-bottom: 0.4em;
}

.rich-text :deep(.toc ul) {
  margin: 0;
  padding-left: 1.2em;
}

.rich-text :deep(.toc li) {
  margin: 0.2em 0;
}

.rich-text :deep(.toc a) {
  cursor: pointer;
  color: #0366d6;
  font-size: 0.9em;
}

.rich-text :deep(.toc a:hover) {
  text-decoration: underline;
}

.rich-text :deep(.at-mention) {
  display: inline-block;
  color: #0366d6;
  background-color: rgba(3, 102, 214, 0.1);
  padding: 0 6px;
  border-radius: 4px;
  font-weight: 500;
  cursor: default;
}

/* ===== 章节卡片与目录导航 ===== */
.rich-text :deep(.rt-section-toc) {
  margin: 0.6em 0 1em;
  padding: 0.6em 0.8em;
  border: 1px solid #e1e4e8;
  border-radius: 8px;
  background: linear-gradient(135deg, #fafbfc 0%, #f0f4f8 100%);
  text-align: left;
}

.rich-text :deep(.rt-section-toc-title) {
  font-weight: 600;
  font-size: 0.95em;
  margin-bottom: 0.4em;
  color: #1a1a1a;
}

.rich-text :deep(.rt-section-toc ul) {
  margin: 0;
  padding-left: 1.2em;
}

.rich-text :deep(.rt-section-toc li) {
  margin: 0.25em 0;
}

.rich-text :deep(.rt-section-toc a) {
  cursor: pointer;
  color: #0366d6;
  font-size: 0.9em;
  text-decoration: none;
}

.rich-text :deep(.rt-section-toc a:hover) {
  text-decoration: underline;
}

.rich-text :deep(.rt-section) {
  margin: 0.8em 0;
  padding: 0.8em 1em;
  border-radius: 8px;
  border-left: 4px solid #0366d6;
  background-color: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
  text-align: left;
  scroll-margin-top: 12px;
}

.rich-text :deep(.rt-section:nth-child(4n+1)) {
  border-left-color: #0366d6;
  background-color: #f6fbff;
}

.rich-text :deep(.rt-section:nth-child(4n+2)) {
  border-left-color: #28a745;
  background-color: #f6fff9;
}

.rich-text :deep(.rt-section:nth-child(4n+3)) {
  border-left-color: #f59e0b;
  background-color: #fffbf5;
}

.rich-text :deep(.rt-section:nth-child(4n)) {
  border-left-color: #8b5cf6;
  background-color: #faf8ff;
}

.rich-text :deep(.rt-section-title) {
  margin: 0 0 0.5em;
  font-size: 1.05em;
  font-weight: 600;
  color: #1a1a1a;
}

.rich-text :deep(.rt-section-title strong) {
  font-weight: 600;
}

.rich-text :deep(.rt-section-content) {
  color: #333;
}

.rich-text :deep(.rt-section-content > *:first-child) {
  margin-top: 0;
}

.rich-text :deep(.rt-section-content > *:last-child) {
  margin-bottom: 0;
}

/* 目录跳转高亮效果 */
.rich-text :deep(.rt-highlight) {
  animation: highlight 2s ease-out;
}

@keyframes highlight {
  0% {
    background-color: rgba(3, 102, 214, 0.2);
    box-shadow: 0 0 0 3px rgba(3, 102, 214, 0.3);
  }
  100% {
    background-color: transparent;
    box-shadow: none;
  }
}
</style>
