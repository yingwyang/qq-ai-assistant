/**
 * 文档树定义（唯一结构来源）
 *
 * 约定：
 * - 每个页面的正文在 `frontend/src/docs/<dir>/<file>.md`，文件头部用 YAML front-matter 提供
 *   `title` / `description` / `updated`（页面展示优先使用 front-matter，这里只做兜底）。
 * - `allPages` 的顺序 = 侧栏顺序 = 上一页/下一页顺序。
 * - 新增页面：放好 md 文件 → 在下面对应分类的 pages 里加一行即可。
 */

export const categories = [
  {
    id: 'start',
    slug: 'start',
    title: '快速入门',
    iconName: 'rocket',
    description: '第一次接触这套系统？先了解它能做什么，再把它跑起来。',
    pages: [
      { slug: 'welcome', title: '欢迎使用', filePath: '/docs/getting-started/welcome.md', description: '系统能力总览与文档导读' },
      { slug: 'quick-start', title: '5 分钟上手', filePath: '/docs/getting-started/quick-start.md', description: '从登录到用上 AI 的最短路径' },
      { slug: 'system-config', title: '系统配置', filePath: '/docs/getting-started/system-config.md', description: '端口清单、必填环境变量与启动顺序' },
      { slug: 'group-setup', title: '群聊与群类型', filePath: '/docs/getting-started/group-setup.md', description: '群聊如何接入，群类型怎么设置与识别' }
    ]
  },
  {
    id: 'guide',
    slug: 'guide',
    title: '使用指南',
    iconName: 'book-open',
    description: '按功能逐个说明：消息与媒体、AI 摘要与对话、积分订阅、账号与后台。',
    pages: [
      { slug: 'messages', title: '消息与媒体', filePath: '/docs/guide/messages.md', description: '消息入库、图片视频语音的保存与展示' },
      { slug: 'ai-summary', title: 'AI 摘要', filePath: '/docs/guide/ai-summary.md', description: '按需生成摘要、群类型影响与失败排查' },
      { slug: 'ai-chat', title: 'AI 对话与语音', filePath: '/docs/guide/ai-chat.md', description: '网页对话、人格设置与 TTS 语音合成' },
      { slug: 'credits', title: '积分体系', filePath: '/docs/guide/credits.md', description: '积分怎么得、怎么花，签到与月卡加成' },
      { slug: 'subscription', title: '订阅与订单', filePath: '/docs/guide/subscription.md', description: '直购档位、月卡权益、退款与纠纷流程' },
      { slug: 'account', title: '账号与安全', filePath: '/docs/guide/account.md', description: '资料修改、改密码、忘记密码与记住我' },
      { slug: 'admin', title: '管理后台导览', filePath: '/docs/guide/admin.md', description: '14 个管理页面各自能做什么，含 ?tab= 深链接' }
    ]
  },
  {
    id: 'faq',
    slug: 'faq',
    title: '常见问题',
    iconName: 'help-circle',
    description: '遇到问题时先查这里：现象、原因、处理步骤、验证方法。',
    pages: [
      { slug: 'login-account', title: '登录与账号', filePath: '/docs/faq/login-account.md', description: '忘记密码、账号被禁用、登录被限流' },
      { slug: 'messages-not-arriving', title: '收不到 QQ 消息', filePath: '/docs/faq/messages-not-arriving.md', description: 'NapCat 掉线是最常见原因' },
      { slug: 'media-fail', title: '媒体存不下来', filePath: '/docs/faq/media-fail.md', description: '图片/视频/语音下载失败与过期占位' },
      { slug: 'ai-summary-issues', title: 'AI 摘要为空', filePath: '/docs/faq/ai-summary-issues.md', description: '摘要按需生成、AstrBot 未运行等' },
      { slug: 'credits-issues', title: '积分与扣费疑问', filePath: '/docs/faq/credits-issues.md', description: '积分去向、签到加成、积分不足与调账' },
      { slug: 'components', title: '组件启动失败', filePath: '/docs/faq/components.md', description: 'NapCat / AstrBot / GPT-SoVITS / MQ 自检' },
      { slug: 'admin-orders', title: '订单与后台疑问', filePath: '/docs/faq/admin-orders.md', description: '订单状态、补单退款、筛选残留等问题' }
    ]
  },
  {
    id: 'dev',
    slug: 'dev',
    title: '开发文档',
    iconName: 'code',
    description: '架构、接口、消息队列与部署运维，给二次开发和维护用。',
    pages: [
      { slug: 'architecture', title: '架构说明', filePath: '/docs/development/architecture.md', description: '拓扑、链路、队列与鉴权体系' },
      { slug: 'api-reference', title: 'API 接口文档', filePath: '/docs/development/api-reference.md', description: '按模块列出接口与统一响应结构' },
      { slug: 'rabbitmq-guide', title: '消息队列指南', filePath: '/docs/development/rabbitmq-guide.md', description: '交换机队列全表、重试与积压排查' },
      { slug: 'deployment', title: '部署与运维', filePath: '/docs/development/deployment.md', description: '环境要求、启动顺序、日志与备份' },
      { slug: 'troubleshooting-playbook', title: '排障手册索引', filePath: '/docs/development/troubleshooting-playbook.md', description: '通用四步定位法与历史问题清单' },
      { slug: 'ai-summary-design', title: 'AI 摘要实装方案', filePath: '/docs/development/ai-summary-design.md', description: '设计稿：数据模型、接口、交互与落地计划' },
      { slug: 'rag-knowledge-base-design', title: '知识库（RAG）接入方案', filePath: '/docs/development/rag-knowledge-base-design.md', description: '设计稿：三条选型路线、数据模型、成本估算与分阶段落地' }
    ]
  }
];

/** 扁平页面列表（顺序 = 上一页/下一页顺序） */
export const allPages = categories.flatMap(cat =>
  cat.pages.map(p => ({
    ...p,
    categorySlug: cat.slug,
    categoryTitle: cat.title,
    categoryIcon: cat.iconName
  }))
);

/** 按 slug 取页面元数据 */
export function getPageMeta(slug) {
  return allPages.find(p => p.slug === slug) || null;
}

/** 页面所属分类 */
export function getCategoryForPage(slug) {
  const page = getPageMeta(slug);
  if (!page) return null;
  return categories.find(c => c.slug === page.categorySlug) || null;
}

/** 上一页 / 下一页（用于文章底部导航） */
export function getSiblingPages(slug) {
  const idx = allPages.findIndex(p => p.slug === slug);
  if (idx < 0) return { prev: null, next: null };
  return {
    prev: idx > 0 ? allPages[idx - 1] : null,
    next: idx < allPages.length - 1 ? allPages[idx + 1] : null
  };
}

/** 文档总数 */
export const totalPageCount = allPages.length;
