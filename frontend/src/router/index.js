import { createRouter, createWebHistory } from 'vue-router';

// 所有顶层路由一律按需加载（懒加载）：
// 之前 LoginPage/HomeView/UserCenter 是静态 import，导致它们依赖的重型库（echarts ~1MB、
// AstrBotChat、CreditsDashboard 等）全部被打进入口包，首屏白屏时间偏长。
// 现在入口只保留 vue/vue-router/App 外壳，各页面按访问路径分包加载。
const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/LoginPage.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    name: 'Home',
    component: () => import('../views/HomeView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/user-center',
    name: 'UserCenter',
    component: () => import('../views/UserCenter.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/admin',
    name: 'Admin',
    component: () => import('../views/AdminView.vue'),
    meta: { requiresAuth: true, requiresAdmin: true }
  },
  {
    path: '/docs/:pathMatch(.*)*',
    name: 'Docs',
    component: () => import('../views/DocView.vue'),
    // 文档为前端内置 markdown，无需登录即可阅读（登录页「使用文档」入口依赖这一点）
    meta: { public: true }
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

// 路由守卫:通过 /api/auth/me 验证登录状态(同源 Cookie 自动携带)。
// 注意:只有显式缓存的 'ADMIN'/'USER' 才视为已登录,缺失时不得用 || 'USER' 兜底,
// 否则未登录用户访问 /login 会被误判为已登录并踢回 /,形成重定向死循环导致白屏。
router.beforeEach(async (to, from, next) => {
  // 公开页面 /login:已登录(有角色缓存)则按角色跳转,否则放行渲染登录页
  if (to.path === '/login') {
    const cachedRole = localStorage.getItem('user_role');
    if (cachedRole === 'ADMIN') {
      next('/admin');
      return;
    }
    if (cachedRole === 'USER') {
      next('/');
      return;
    }
    next();
    return;
  }

  if (to.meta.requiresAuth) {
    try {
      // 通过同源 Cookie 认证,后端返回当前用户信息
      const res = await fetch('/api/auth/me');
      if (res.status === 401) {
        // 未登录 → 清除角色缓存并跳转登录页
        localStorage.removeItem('user_role');
        next('/login');
        return;
      }
      if (!res.ok) {
        // 其他错误(如 500)→ 仍放行到目标页,让页面内请求兜底处理
        next();
        return;
      }
      const data = await res.json();
      const realRole = data?.data?.role || data?.role || localStorage.getItem('user_role') || 'USER';
      localStorage.setItem('user_role', realRole);

      if (to.meta.requiresAdmin && realRole !== 'ADMIN') {
        next('/');
        return;
      }
      next();
    } catch (e) {
      // 网络错误(后端未启动等)→ 跳转登录页
      next('/login');
    }
  } else {
    next();
  }
});

export default router;
