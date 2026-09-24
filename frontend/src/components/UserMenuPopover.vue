<template>
  <Teleport to="body">
    <div v-if="visible" class="user-menu-overlay" @click.self="handleOverlayClick">
      <div 
        ref="popoverEl"
        class="user-menu-popover" 
        :class="'theme-' + (theme || 'light')"
      >
        <!-- 用户信息头部 -->
        <div v-if="userInfo" class="menu-user-header">
          <div class="menu-user-avatar">
            <img :src="userAvatarUrl" alt="avatar" @error="handleAvatarError" />
          </div>
          <div class="menu-user-details">
            <div class="menu-user-name">{{ userInfo.nickname || userInfo.username }}</div>
            <div class="menu-user-role">{{ userInfo.role === 'ADMIN' ? '管理员' : '用户' }}</div>
          </div>
        </div>

        <!-- 积分行：余额 + 升级 + 签到 -->
        <div v-if="userInfo" class="menu-credits-row">
          <div class="credits-balance" @click="goToCredits">
            <span class="credits-gem"><Icon name="diamond" :size="14" /></span>
            <span class="credits-num">{{ formatNumber(balance) }}</span>
            <span class="credits-unit">积分</span>
          </div>
          <div class="credits-actions">
            <button class="credits-btn upgrade" @click="goToUpgrade">
              <Icon name="layers" :size="12" />
              升级
            </button>
            <button
              v-if="todaySigned"
              class="credits-btn signed"
              disabled
            >
              <Icon name="check" :size="12" />
              今日已签
            </button>
            <button
              v-else
              class="credits-btn sign-in"
              :disabled="todaySigned || isSigningIn"
              @click="handleSignIn"
            >
              <Icon v-if="!isSigningIn" name="sparkles" :size="12" />
              <Icon v-else name="loader" :size="12" class="spin" />
              {{ isSigningIn ? '签到中' : '立即签到' }}
            </button>
          </div>
        </div>

        <div class="menu-list">
          <div class="menu-item" @click="$emit('open-user-center')">
            <Icon name="user" :size="18" />
            <span class="menu-label">个人中心</span>
          </div>
          <div v-if="userInfo?.role === 'ADMIN'" class="menu-item" @click="$emit('open-admin')">
            <Icon name="settings" :size="18" />
            <span class="menu-label">系统管理</span>
          </div>

          <!-- QQ 登录二维码：所有登录用户可见（普通用户也要能扫码登录 QQ），匿名仍拿不到 -->
          <div class="menu-item has-submenu" @click="toggleSubmenu($event, 'qq-login')">
            <Icon name="message-circle" :size="18" />
            <span class="menu-label">QQ登录</span>
            <span class="menu-arrow" :class="{ expanded: activeSubmenu === 'qq-login' }">›</span>
          </div>
          <div v-show="activeSubmenu === 'qq-login'" class="submenu qq-login-submenu">
            <div class="qq-login-content">
              <div v-if="componentStatus.napcat?.running" class="qq-qr-section">
                <div class="qq-qr-header">
                  <span class="qq-qr-title">NapCat 扫码登录</span>
                  <span class="qq-qr-refresh" @click.stop="refreshQrCode" title="刷新二维码">
                    <Icon name="refresh" :size="14" />
                  </span>
                </div>
                <div class="qq-qr-wrapper">
                  <img v-if="qrCode" :src="qrCode" class="qq-qr-image" alt="QQ登录二维码" />
                  <div v-else class="qq-qr-loading">
                    <Icon name="loader" :size="24" class="spin" />
                    <span>加载中...</span>
                  </div>
                </div>
                <div class="qq-qr-tip">请使用手机 QQ 扫描二维码登录</div>
              </div>
              <div v-else class="qq-qr-empty">
                <Icon name="alert-circle" :size="32" />
                <div>NapCat 未启动</div>
                <div class="qq-qr-hint">请先启动 NapCat 服务</div>
              </div>
            </div>
          </div>

          <!-- 主题切换（原来这个按钮被删掉了，这里实装回来） -->
          <div class="menu-item" @click="toggleTheme">
            <Icon :name="isDark ? 'sun' : 'moon'" :size="18" />
            <span class="menu-label">{{ isDark ? '浅色模式' : '深色模式' }}</span>
            <span class="menu-switch" :class="{ on: isDark }"><span class="knob"></span></span>
          </div>

          <div class="menu-item" @click="$emit('open-docs')">
            <Icon name="book" :size="18" />
            <span class="menu-label">帮助与反馈</span>
            <span class="menu-arrow">›</span>
          </div>

          <div class="menu-divider"></div>

          <div class="menu-item logout-item" @click="$emit('logout')">
            <Icon name="logout" :size="18" />
            <span class="menu-label">退出登录</span>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue';
import { useRouter } from 'vue-router';
import Icon from './Icon.vue';
import { useComponentControl } from '../composables/useComponentControl';
import { useUserCreditsStore } from '../composables/useUserCreditsStore';
import { useTheme } from '../composables/useTheme';

export default {
  name: 'UserMenuPopover',
  components: { Icon },
  props: {
    visible: { type: Boolean, required: true },
    userInfo: { type: Object, default: null },
    userAvatarUrl: { type: String, default: '' }
  },
  emits: ['close', 'open-user-center', 'open-admin', 'open-docs', 'logout'],
  setup(props, { emit }) {
    const router = useRouter();
    const activeSubmenu = ref('');
    const popoverEl = ref(null);

    // 主题（弹层被 Teleport 到 body，拿不到 .theme-* 的 CSS 变量，
    // 所以把主题类挂到弹层自身上，见模板 :class）
    const { theme, toggleTheme } = useTheme();
    const isDark = computed(() => theme.value === 'dark');

    // 全局共享积分状态（与 Sidebar / UserCenter 引用同一份 ref）
    const { balance, todaySigned, isSigningIn, signIn, reload: reloadCredits } = useUserCreditsStore();

    const formatNumber = (n) => {
      if (n === null || n === undefined) return '0';
      return Number(n).toLocaleString('zh-CN');
    };

    const close = () => emit('close');

    const goToUpgrade = () => {
      close();
      router.push('/user-center?tab=subscription&openUpgrade=1');
    };

    const goToCredits = () => {
      close();
      router.push('/user-center?tab=credits');
    };

    const handleSignIn = async () => {
      if (todaySigned.value || isSigningIn.value) return;
      try {
        await signIn();
      } catch (error) {
        // store.signIn() 已按 errorCode 显示友好提示，这里只做 UI 兜底
        if (error?.errorCode === 'ALREADY_SIGNED_IN') {
          todaySigned.value = true;
        }
      }
    };

    // 弹窗可见时刷新积分余额
    watch(() => props.visible, (v) => {
      if (v) reloadCredits();
    });

    const {
      componentStatus,
      qrCode,
      refreshQrCode,
      getComponentStatus,
      startPolling,
      stopPolling,
    } = useComponentControl();

    const positionPopover = () => {
      const trigger = document.querySelector('.footer-user-info');
      if (!trigger || !popoverEl.value) return;
      const rect = trigger.getBoundingClientRect();
      const popHeight = popoverEl.value.offsetHeight;
      const popWidth = popoverEl.value.offsetWidth || 260;
      popoverEl.value.style.position = 'fixed';
      popoverEl.value.style.bottom = 'auto';
      popoverEl.value.style.left = Math.round(rect.left) + 'px';
      // 优先放上方，空间不足则放下方
      const spaceAbove = rect.top - 10;
      const spaceBelow = window.innerHeight - rect.bottom - 10;
      if (spaceAbove >= popHeight || spaceAbove >= spaceBelow) {
        popoverEl.value.style.top = Math.max(10, rect.top - popHeight - 8) + 'px';
      } else {
        popoverEl.value.style.top = Math.min(window.innerHeight - popHeight - 10, rect.bottom + 8) + 'px';
      }
      // 水平方向不超出屏幕
      const maxLeft = window.innerWidth - popWidth - 10;
      if (rect.left > maxLeft) {
        popoverEl.value.style.left = Math.max(10, maxLeft) + 'px';
      }
    };

    const toggleSubmenu = async (event, name) => {
      event.stopPropagation();
      if (name === 'qq-login' && activeSubmenu.value !== 'qq-login') {
        await getComponentStatus();
        if (componentStatus.value.napcat?.running) {
          refreshQrCode();
        }
      }
      activeSubmenu.value = activeSubmenu.value === name ? '' : name;
      // 展开/收起后重新定位
      await nextTick();
      positionPopover();
    };

    const handleOverlayClick = () => {
      emit('close');
    };

    const handleAvatarError = (e) => {
      e.target.src = '/images/default-avatar.svg';
    };

    const handleKeydown = (e) => {
      if (e.key === 'Escape' && props.visible) {
        emit('close');
      }
    };

    onMounted(() => {
      document.addEventListener('keydown', handleKeydown);
      startPolling();
    });

    onBeforeUnmount(() => {
      document.removeEventListener('keydown', handleKeydown);
      stopPolling();
    });

    watch(() => props.visible, async (val) => {
      if (val) {
        await nextTick();
        positionPopover();
        getComponentStatus();
      } else {
        activeSubmenu.value = '';
      }
    });

    return {
      activeSubmenu,
      popoverEl,
      componentStatus,
      qrCode,
      toggleSubmenu,
      handleOverlayClick,
      handleAvatarError,
      refreshQrCode,
      balance,
      todaySigned,
      isSigningIn,
      formatNumber,
      goToUpgrade,
      goToCredits,
      handleSignIn,
      theme,
      toggleTheme,
      isDark,
    };
  }
};
</script>

<style scoped>
.user-menu-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 9999;
  display: flex;
  align-items: flex-start;
  justify-content: flex-start;
  pointer-events: auto;
}

.user-menu-popover {
  position: fixed;
  width: 280px;
  max-height: calc(100vh - 20px);
  background: var(--card-bg, #fff);
  border-radius: 12px;
  box-shadow: 0 8px 32px var(--card-shadow, rgba(0,0,0,0.15));
  overflow-y: auto;
  overflow-x: hidden;
  animation: popIn 0.2s ease-out;
  border: 1px solid var(--border-color, #e0e0e0);
}

@keyframes popIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}

.menu-list { padding: 8px 0; }

.menu-user-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  border-bottom: 1px solid var(--border-color, #eee);
}
.menu-user-avatar {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  overflow: hidden;
  flex-shrink: 0;
}
.menu-user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.menu-user-details { flex: 1; min-width: 0; }
.menu-user-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary, #333);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.menu-user-role {
  font-size: 12px;
  color: var(--text-muted, #999);
  margin-top: 2px;
}

/* 积分行 */
.menu-credits-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border-color, #eee);
  background: var(--bg-tertiary, #f8f9fa);
}
.credits-balance {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  min-width: 0;
}
.credits-balance:hover .credits-num {
  color: var(--accent-color, #3498db);
}
.credits-gem {
  font-size: 14px;
  line-height: 1;
}
.credits-num {
  font-size: 15px;
  font-weight: 700;
  color: var(--text-primary, #333);
  transition: color 0.15s;
}
.credits-unit {
  font-size: 11px;
  color: var(--text-muted, #999);
}
.credits-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.credits-btn {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 4px 10px;
  border: none;
  border-radius: 5px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  line-height: 1.4;
}
.credits-btn.upgrade {
  background: linear-gradient(135deg, #9b59b6, #8e44ad);
  color: #fff;
}
.credits-btn.upgrade:hover {
  transform: translateY(-1px);
  box-shadow: 0 2px 6px rgba(155, 89, 182, 0.35);
}
.credits-btn.sign-in {
  background: linear-gradient(135deg, #3498db, #2980b9);
  color: #fff;
}
.credits-btn.sign-in:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 2px 6px rgba(52, 152, 219, 0.35);
}
.credits-btn.signed,
.credits-btn:disabled {
  background: #bdc3c7;
  color: #fff;
  cursor: not-allowed;
  opacity: 0.85;
}
.credits-btn .spin {
  animation: creditsSpin 1s linear infinite;
}
@keyframes creditsSpin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.menu-item {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  cursor: pointer;
  transition: background 0.15s;
  color: var(--text-primary, #333);
  font-size: 14px;
  gap: 12px;
}
.menu-item:hover { background: var(--bg-tertiary, #f0f0f0); }
.menu-item .menu-label { flex: 1; }
.menu-item .menu-arrow {
  color: var(--text-muted, #999);
  font-size: 16px;
  transition: transform 0.2s;
}
.menu-item.has-submenu .menu-arrow.expanded {  transform: rotate(90deg);
}

/* 主题切换开关 */
.menu-switch {
  width: 34px; height: 18px; border-radius: 999px; flex-shrink: 0;
  background: var(--border-color, #d0d0d0);
  position: relative; transition: background 0.2s;
}
.menu-switch.on { background: var(--accent-color, #3498db); }
.menu-switch .knob {
  position: absolute; top: 2px; left: 2px;
  width: 14px; height: 14px; border-radius: 50%;
  background: var(--card-bg, #fff); box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
  transition: transform 0.2s;
}
.menu-switch.on .knob { transform: translateX(16px); }

.submenu {
  background: var(--bg-tertiary, #f8f9fa);
  padding: 4px 0;
}
.submenu-item {
  display: flex;
  align-items: center;
  padding: 10px 16px 10px 48px;
  cursor: pointer;
  font-size: 13px;
  color: var(--text-secondary, #666);
  gap: 10px;
  transition: background 0.15s;
}
.submenu-item:hover { background: var(--bg-secondary, #fff); color: var(--text-primary, #333); }
.submenu-item.active { color: var(--accent-color, #3498db); font-weight: 500; }

.menu-divider {
  height: 1px;
  background: var(--border-color, #eee);
  margin: 4px 0;
}

.logout-item { color: var(--danger-color, #e74c3c); }
.logout-item:hover { background: rgba(231, 76, 60, 0.1); }

/* QQ Login Submenu */
.qq-login-submenu {
  background: var(--bg-tertiary, #f8f9fa);
  padding: 8px;
}
.qq-login-content {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.qq-qr-section {
  background: var(--card-bg, #fff);
  border-radius: 8px;
  padding: 12px;
  border: 1px solid var(--border-color, #e0e0e0);
}
.qq-qr-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.qq-qr-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary, #333);
}
.qq-qr-refresh {
  cursor: pointer;
  color: var(--text-muted, #999);
  padding: 4px;
  border-radius: 4px;
  transition: all 0.15s;
}
.qq-qr-refresh:hover {
  color: var(--accent-color, #3498db);
  background: var(--bg-tertiary, #f0f0f0);
}
.qq-qr-wrapper {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 180px;
  background: var(--card-bg, #fff);
  border-radius: 6px;
  padding: 12px;
}
.qq-qr-image {
  width: 160px;
  height: 160px;
  object-fit: contain;
  image-rendering: pixelated;
}
.qq-qr-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: var(--text-muted, #999);
  font-size: 12px;
}
.qq-qr-loading .spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
.qq-qr-tip {
  text-align: center;
  font-size: 11px;
  color: var(--text-muted, #888);
  margin-top: 8px;
}
.qq-qr-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 16px;
  color: var(--text-muted, #888);
  font-size: 12px;
}
.qq-qr-empty svg {
  color: var(--warning-color, #f39c12);
}
.qq-qr-hint {
  font-size: 11px;
  color: var(--text-muted, #aaa);
}
</style>