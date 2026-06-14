import { createRouter, createWebHistory } from 'vue-router';
import LoginPage from '../views/LoginPage.vue';
import HomeView from '../views/HomeView.vue';

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
    path: '/admin',
    name: 'Admin',
    component: () => import('../views/AdminView.vue'),
    meta: { requiresAuth: true, requiresAdmin: true }
  }
];

const router = createRouter({
  history: createWebHistory(),
  routes
});

// 路由守卫：检查登录状态和管理员权限
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('auth_token');
  const isLoggedIn = !!token;
  const userRole = localStorage.getItem('user_role') || 'USER';
  const isAdmin = userRole === 'ADMIN';

  if (to.meta.requiresAuth && !isLoggedIn) {
    // 未登录且访问需要授权的页面，跳转到登录页
    next('/login');
  } else if (to.meta.requiresAdmin && !isAdmin) {
    // 非管理员访问管理员页面，跳转到首页
    next('/');
  } else if (to.path === '/login' && isLoggedIn) {
    // 已登录但访问登录页，根据角色跳转
    if (isAdmin) {
      next('/admin');
    } else {
      next('/');
    }
  } else {
    next();
  }
});

export default router;
