import { createRouter, createWebHistory } from 'vue-router';
import LoginPage from '../views/LoginPage.vue';
import HomeView from '../views/HomeView.vue';
import UserCenter from '../views/UserCenter.vue';

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: LoginPage,
    meta: { public: true }
  },
  {
    path: '/',
    name: 'Home',
    component: HomeView,
    meta: { requiresAuth: true }
  },
  {
    path: '/user-center',
    name: 'UserCenter',
    component: UserCenter,
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
    meta: { requiresAuth: true }
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

// 路由守卫：通过 /api/auth/me 验证登录状态（同源 Cookie 自动携带）
router.beforeEach(async (to, from, next) => {
  const cachedRole = localStorage.getItem('user_role') || 'USER';

  // 公开页面：已登录则按角色跳转（先不验证，信任缓存角色快速判断）
  if (to.path === '/login') {
    if (cachedRole) {
      if (cachedRole === 'ADMIN') {
        next('/admin');
      } else {
        next('/');
      }
      return;
    }
    next();
    return;
  }

  if (to.meta.requiresAuth) {
    try {
      // 通过同源 Cookie 认证，后端返回当前用户信息
      const res = await fetch('/api/auth/me');
      if (res.status === 401) {
        // 未登录 → 跳转登录页（不清 localStorage，handleUnauthorized 会处理）
        localStorage.removeItem('user_role');
        next('/login');
        return;
      }
      if (!res.ok) {
        // 其他错误（如 500）→ 仍放行到目标页，让页面内请求兜底处理
        next();
        return;
      }
      const data = await res.json();
      const realRole = data?.data?.role || data?.role || cachedRole;
      localStorage.setItem('user_role', realRole);

      if (to.meta.requiresAdmin && realRole !== 'ADMIN') {
        next('/');
        return;
      }
      next();
    } catch (e) {
      // 网络错误（后端未启动等）→ 跳转登录页
      next('/login');
    }
  } else {
    next();
  }
});

export default router;