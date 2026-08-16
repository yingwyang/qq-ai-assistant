export const categories = [
  {
    id: 'usage',
    slug: 'usage',
    title: '使用指南',
    iconName: 'book-open',
    sortOrder: 1,
    children: [
      {
        id: 'getting-started',
        slug: 'getting-started',
        title: '快速入门',
        parentId: 'usage',
        sortOrder: 1,
        pages: [
          { slug: 'welcome', title: '欢迎使用', filePath: '/docs/getting-started/welcome.md', sortOrder: 1 },
          { slug: 'system-config', title: '系统配置', filePath: '/docs/getting-started/system-config.md', sortOrder: 2 },
          { slug: 'plugin-install', title: '插件安装指南', filePath: '/docs/getting-started/plugin-install.md', sortOrder: 3 }
        ]
      },
      {
        id: 'commands',
        slug: 'commands',
        title: '命令与指令',
        parentId: 'usage',
        sortOrder: 2,
        pages: [
          { slug: 'basic-commands', title: '基本命令', filePath: '/docs/commands/basic-commands.md', sortOrder: 1 },
          { slug: 'ai-commands', title: 'AI 对话命令', filePath: '/docs/commands/ai-commands.md', sortOrder: 2 }
        ]
      }
    ]
  },
  {
    id: 'faq',
    slug: 'faq',
    title: '常见问题',
    iconName: 'help-circle',
    sortOrder: 2,
    children: [],
    pages: [
      { slug: 'login-issues', title: '登录问题', filePath: '/docs/faq/login-issues.md', sortOrder: 1 },
      { slug: 'plugin-issues', title: '插件问题', filePath: '/docs/faq/plugin-issues.md', sortOrder: 2 }
    ]
  },
  {
    id: 'dev',
    slug: 'dev',
    title: '开发文档',
    iconName: 'code',
    sortOrder: 3,
    children: [],
    pages: [
      { slug: 'api-reference', title: 'API 接口文档', filePath: '/docs/development/api-reference.md', sortOrder: 1 },
      { slug: 'rabbitmq-guide', title: 'RabbitMQ 消息队列', filePath: '/docs/development/rabbitmq-guide.md', sortOrder: 2 },
      { slug: 'architecture', title: '架构说明', filePath: '/docs/development/architecture.md', sortOrder: 3 }
    ]
  }
]

// Flatten all pages for easy lookup
export const allPages = [
  { slug: 'welcome', title: '欢迎使用', categorySlug: 'usage', subCategorySlug: 'getting-started', filePath: '/docs/getting-started/welcome.md' },
  { slug: 'system-config', title: '系统配置', categorySlug: 'usage', subCategorySlug: 'getting-started', filePath: '/docs/getting-started/system-config.md' },
  { slug: 'plugin-install', title: '插件安装指南', categorySlug: 'usage', subCategorySlug: 'getting-started', filePath: '/docs/getting-started/plugin-install.md' },
  { slug: 'basic-commands', title: '基本命令', categorySlug: 'usage', subCategorySlug: 'commands', filePath: '/docs/commands/basic-commands.md' },
  { slug: 'ai-commands', title: 'AI 对话命令', categorySlug: 'usage', subCategorySlug: 'commands', filePath: '/docs/commands/ai-commands.md' },
  { slug: 'login-issues', title: '登录问题', categorySlug: 'faq', subCategorySlug: null, filePath: '/docs/faq/login-issues.md' },
  { slug: 'plugin-issues', title: '插件问题', categorySlug: 'faq', subCategorySlug: null, filePath: '/docs/faq/plugin-issues.md' },
  { slug: 'api-reference', title: 'API 接口文档', categorySlug: 'dev', subCategorySlug: null, filePath: '/docs/development/api-reference.md' },
  { slug: 'rabbitmq-guide', title: 'RabbitMQ 消息队列', categorySlug: 'dev', subCategorySlug: null, filePath: '/docs/development/rabbitmq-guide.md' },
  { slug: 'architecture', title: '架构说明', categorySlug: 'dev', subCategorySlug: null, filePath: '/docs/development/architecture.md' }
]

// Get page metadata by slug
export function getPageMeta(slug) {
  return allPages.find(p => p.slug === slug) || null
}

// Get parent category info for a page
export function getCategoryForPage(slug) {
  const page = getPageMeta(slug)
  if (!page) return null
  const category = categories.find(c => c.slug === page.categorySlug)
  return category || null
}