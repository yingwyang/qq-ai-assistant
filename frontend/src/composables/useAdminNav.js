import { computed, onMounted, ref, watch } from 'vue';
import logger from '../utils/logger';

/**
 * 系统管理中心导航状态（tab 解析 / URL 深链接 / localStorage 续接）。
 *
 * 背景：改造前 activeTab 只是一个 ref('dashboard')，既不写 URL 也不落本地存储，
 * 管理员刷新页面就回到数据概览，也没法把"某个具体页面"发给自己/同事。
 * 这里把 tab 变成可寻址状态：路由 query 优先 > 本地存储 > 默认值。
 */

const TAB_STORAGE_KEY = 'admin_active_tab';

export const DEFAULT_ADMIN_TAB = 'dashboard';

/**
 * URL 上属于"具体页面"的附加参数：只在直达该页面时有意义。
 * 切换 tab 时清掉，避免 ?tab=users&userId=5 这种残留参数误导后续页面。
 */
const TAB_SCOPED_QUERY_KEYS = ['userId', 'orderNo'];

/**
 * 侧栏分组顺序。前两组不渲染分组标题（置顶即主入口），
 * 后两组渲染标题，避免侧栏变成 14 行平铺的清单。
 */
export const ADMIN_NAV_GROUPS = [
  { key: 'overview', label: '概览', showTitle: false },
  { key: 'user', label: '用户', showTitle: false },
  { key: 'system', label: '系统', showTitle: true },
  { key: 'credits', label: '积分管理', showTitle: true },
];

/**
 * 全部 admin 页面。loader 供 defineAsyncComponent 懒加载；
 * title/subtitle 供页头与 document.title 使用。
 */
export const ADMIN_TABS = [
  {
    key: 'dashboard',
    label: '数据概览',
    icon: 'dashboard',
    group: 'overview',
    title: '数据概览',
    subtitle: '系统运行状态、消息趋势与收支概况',
    loader: () => import('../views/admin/AdminDashboard.vue'),
  },
  {
    key: 'users',
    label: '用户管理',
    icon: 'group',
    group: 'user',
    title: '用户管理',
    subtitle: '账号、角色、启用状态与积分总览',
    loader: () => import('../views/admin/AdminUsers.vue'),
  },
  {
    key: 'components',
    label: '组件控制',
    icon: 'settings',
    group: 'system',
    title: '组件控制',
    subtitle: 'AstrBot / NapCat / GPT-SoVITS 的启停与状态',
    loader: () => import('../views/admin/AdminComponents.vue'),
  },
  {
    key: 'config',
    label: '配置管理',
    icon: 'config',
    group: 'system',
    title: '配置管理',
    subtitle: '运行时配置项（保存后按标记决定是否需重启）',
    loader: () => import('../views/admin/AdminConfig.vue'),
  },
  {
    key: 'log',
    label: '系统日志',
    icon: 'file',
    group: 'system',
    title: '系统日志',
    subtitle: '应用运行日志与管理员操作审计',
    loader: () => import('../views/admin/AdminLogs.vue'),
  },
  {
    key: 'media',
    label: '媒体管理',
    icon: 'image',
    group: 'system',
    title: '媒体管理',
    subtitle: '上传目录中的图片 / 视频 / 音频 / 文件',
    loader: () => import('../views/admin/AdminMedia.vue'),
  },
  {
    key: 'maintenance',
    label: '数据维护',
    icon: 'backup',
    group: 'system',
    title: '数据维护',
    subtitle: '数据库备份、下载与历史消息归档',
    loader: () => import('../views/admin/AdminBackup.vue'),
  },
  {
    key: 'ai-summary',
    label: 'AI 摘要',
    icon: 'robot',
    group: 'system',
    title: 'AI 摘要',
    subtitle: '摘要开关、生成参数与历史消息补摘要',
    loader: () => import('../views/admin/AdminAiSummary.vue'),
  },
  {
    key: 'credit-rule',
    label: '规则配置',
    icon: 'speed',
    group: 'credits',
    title: '积分规则配置',
    subtitle: '计费参数、直购积分与会员月卡定价',
    loader: () => import('../views/admin/AdminCreditsRule.vue'),
  },
  {
    key: 'credit-users',
    label: '用户积分',
    icon: 'user',
    group: 'credits',
    title: '用户积分',
    subtitle: '余额、累计消耗与人工调账',
    loader: () => import('../views/admin/AdminCreditsUsers.vue'),
  },
  {
    key: 'credit-transactions',
    label: '资金流水',
    icon: 'list',
    group: 'credits',
    title: '资金流水',
    subtitle: '积分变动与现金收支明细',
    loader: () => import('../views/admin/AdminTransactions.vue'),
  },
  {
    key: 'credit-orders',
    label: '订单管理',
    icon: 'file-text',
    group: 'credits',
    title: '订单管理',
    subtitle: '订阅订单查询、补单、退款与作废',
    loader: () => import('../views/admin/AdminOrders.vue'),
  },
  {
    key: 'credit-refund-approve',
    label: '退款审批',
    icon: 'coin',
    group: 'credits',
    title: '退款审批',
    subtitle: '待审批的退款申请',
    badgeKey: 'pendingRefund',
    loader: () => import('../views/admin/AdminRefundApprove.vue'),
  },
  {
    key: 'credit-dispute',
    label: '纠纷处理',
    icon: 'warning',
    group: 'credits',
    title: '纠纷处理',
    subtitle: '用户发起的纠纷申请',
    badgeKey: 'pendingDispute',
    loader: () => import('../views/admin/AdminDispute.vue'),
  },
];

const ADMIN_TAB_MAP = new Map(ADMIN_TABS.map(tab => [tab.key, tab]));

/** 判断是否为合法 tab key（非法值一律回落默认页） */
export function isAdminTabKey(key) {
  return ADMIN_TAB_MAP.has(key);
}

/** 任意输入 → 合法 tab key */
export function resolveAdminTab(raw) {
  const key = typeof raw === 'string' ? raw.trim() : '';
  return isAdminTabKey(key) ? key : DEFAULT_ADMIN_TAB;
}

/** 取 tab 元信息（title/subtitle/loader 等） */
export function adminTabMeta(raw) {
  return ADMIN_TAB_MAP.get(resolveAdminTab(raw));
}

function readStoredTab() {
  try {
    return resolveAdminTab(localStorage.getItem(TAB_STORAGE_KEY));
  } catch (e) {
    return DEFAULT_ADMIN_TAB;
  }
}

function writeStoredTab(key) {
  try {
    localStorage.setItem(TAB_STORAGE_KEY, key);
  } catch (e) {
    // 隐私模式等场景下 localStorage 不可用，忽略即可（不影响功能）
  }
}

/**
 * @param {object} options
 * @param {import('vue-router').RouteLocationNormalizedLoaded} options.route
 * @param {import('vue-router').Router} options.router
 * @param {import('vue').ComputedRef<Record<string, number>>} [options.badgeCounts]
 */
export function useAdminNav({ route, router, badgeCounts } = {}) {
  const initialRaw = route && typeof route.query.tab === 'string' ? route.query.tab : '';
  const activeTab = ref(initialRaw ? resolveAdminTab(initialRaw) : readStoredTab());

  const changeHandlers = [];

  /** 注册"有效 tab 变化"回调（用于切组件 + 按需加载数据） */
  const onTabChange = (fn) => {
    if (typeof fn === 'function') changeHandlers.push(fn);
  };

  const emitTabChange = (key, prev) => {
    changeHandlers.forEach((fn) => {
      try {
        fn(key, prev);
      } catch (e) {
        logger.error('admin tab 切换回调异常:', e);
      }
    });
  };

  const applyTab = (key) => {
    const prev = activeTab.value;
    if (key === prev) return false;
    activeTab.value = key;
    writeStoredTab(key);
    emitTabChange(key, prev);
    return true;
  };

  /** 把当前 tab 写回 URL（保留非页面级参数，清理页面级残留参数） */
  const syncUrl = (key) => {
    if (!route || !router) return;
    const currentRaw = typeof route.query.tab === 'string' ? route.query.tab : '';
    if (currentRaw === key) return;
    const query = { ...(route.query || {}) };
    for (const scoped of TAB_SCOPED_QUERY_KEYS) delete query[scoped];
    query.tab = key;
    try {
      const result = router.replace({ query });
      if (result && typeof result.catch === 'function') {
        result.catch((e) => logger.warn('admin tab URL 同步失败（忽略）:', e));
      }
    } catch (e) {
      logger.warn('admin tab URL 同步失败（忽略）:', e);
    }
  };

  /** 切换 tab：更新状态 + 落存储 + 写 URL */
  const setActiveTab = (key) => {
    const next = resolveAdminTab(key);
    applyTab(next);
    syncUrl(next);
  };

  // 浏览器前进/后退、或外部改 URL 时跟随（不重复写 URL）
  if (route) {
    watch(
      () => route.query.tab,
      (raw) => {
        const next = resolveAdminTab(raw);
        const changed = applyTab(next);
        if (!changed) return;
        if (next !== raw) syncUrl(next);
      }
    );
  }

  onMounted(() => {
    writeStoredTab(activeTab.value);
    // 首次进入把 URL 规整为合法 tab：非法值回落、缺省值补齐（便于直接分享链接）
    syncUrl(activeTab.value);
  });

  const navItems = computed(() => {
    const counts = (badgeCounts && badgeCounts.value) || {};
    const items = [];
    for (const group of ADMIN_NAV_GROUPS) {
      if (group.showTitle) {
        items.push({ key: `group-${group.key}`, label: group.label, isGroup: true });
      }
      for (const tab of ADMIN_TABS) {
        if (tab.group !== group.key) continue;
        const count = tab.badgeKey ? Number(counts[tab.badgeKey] || 0) : 0;
        items.push({
          key: tab.key,
          icon: tab.icon,
          label: count > 0 ? `${tab.label} (${count})` : tab.label,
        });
      }
    }
    return items;
  });

  const currentTabMeta = computed(() => adminTabMeta(activeTab.value));

  return {
    activeTab,
    setActiveTab,
    onTabChange,
    navItems,
    currentTabMeta,
  };
}
