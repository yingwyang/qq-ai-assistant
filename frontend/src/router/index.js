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

// 路由守卫：检查登录状态和管理员权限
router.beforeEach(async (to, from, next) => {
  const token = localStorage.getItem('auth_token');
  const isLoggedIn = !!token;
  const cachedRole = localStorage.getItem('user_role') || 'USER';

  if (to.meta.requiresAuth && !isLoggedIn) {
    // 未登录且访问需要授权的页面，跳转到登录页
    next('/login');
    return;
  }

  if (to.meta.requiresAdmin) {
    if (!isLoggedIn) {
      next('/login');
      return;
    }
    // 必须向后端确认真实角色，localStorage 的 user_role 仅用于 UI 展示
    try {
      const res = await fetch('/api/auth/me', {
        headers: token ? { Authorization: `Bearer ${token}` } : {}
      });
      if (res.status === 401) {
        localStorage.removeItem('auth_token');
        localStorage.removeItem('user_role');
        next('/login');
        return;
      }
      const data = await res.json();
      const realRole = data?.data?.role || data?.role || cachedRole;
      localStorage.setItem('user_role', realRole);
      if (realRole !== 'ADMIN') {
        next('/');
        return;
      }
    } catch (e) {
      next('/');
      return;
    }
  }

  if (to.path === '/login' && isLoggedIn) {
    // 已登录但访问登录页，根据角色跳转
    if (cachedRole === 'ADMIN') {
      next('/admin');
    } else {
      next('/');
    }
    return;
  }

  next();
});

export default router;
