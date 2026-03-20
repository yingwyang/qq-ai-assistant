<template>
  <div class="app">
    <header class="app-header">
      <h1>铃音QQ对话</h1>
      
    </header>
    
    <main class="app-main">
      <!-- 登录页面 -->
      <template v-if="currentPage === 'login'">
        <section class="section">
          <NapCatLogin @login-status-changed="handleLoginStatusChanged" />
        </section>
        
        <section class="section">
          <ProjectIntro />
        </section>
        
        <section class="section">
          <SystemControl />
        </section>
      </template>
      
      <!-- 消息页面 -->
      <template v-else-if="currentPage === 'messages'">
        <section class="section">
          <SystemControl />
        </section>
        
        <section class="section" style="grid-column: 1 / -1;">
          <MessageDisplay />
        </section>
        
        <section class="section" style="grid-column: 1 / -1;">
          <button @click="currentPage = 'login'" class="btn-back">返回登录页面</button>
        </section>
      </template>
    </main>
    
    <footer class="app-footer">
      <p>© 2026 QQ智能聊天辅助系统</p>
    </footer>
  </div>
</template>

<script>
import { ref } from 'vue';
import SystemControl from './components/SystemControl.vue';
import NapCatLogin from './components/NapCatLogin.vue';
import MessageDisplay from './components/MessageDisplay.vue';
import ProjectIntro from './components/ProjectIntro.vue';

export default {
  name: 'App',
  components: {
    SystemControl,
    NapCatLogin,
    MessageDisplay,
    ProjectIntro
  },
  setup() {
    const isLoggedIn = ref(false);
    const currentPage = ref('login');

    const handleLoginStatusChanged = (status) => {
      isLoggedIn.value = status;
      if (status) {
        // 登录成功后跳转到消息页面
        currentPage.value = 'messages';
      }
    };

    return {
      isLoggedIn,
      currentPage,
      handleLoginStatusChanged
    };
  }
};
</script>

<style>
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

body {
  font-family: Arial, sans-serif;
  line-height: 1.6;
  color: #333;
  background-color: #f5f5f5;
}

.app {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px;
}

.app-header {
  text-align: center;
  margin-bottom: 40px;
  padding: 20px;
  background-color: #2196F3;
  color: white;
  border-radius: 8px;
  box-shadow: 0 2px 4px rgba(0,0,0,0.1);
}

.app-header h1 {
  font-size: 2.5em;
  margin-bottom: 10px;
}

.app-header p {
  font-size: 1.2em;
  opacity: 0.9;
}

.app-main {
  display: grid;
  grid-template-columns: 1fr;
  gap: 30px;
  margin-bottom: 40px;
}

@media (min-width: 992px) {
  .app-main {
    grid-template-columns: 1fr 1fr;
  }
  
  .app-main .section:nth-child(3),
  .app-main .section:last-child {
    grid-column: 1 / -1;
  }
}

.section {
  padding: 20px;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  background-color: #f9f9f9;
  box-shadow: 0 2px 4px rgba(0,0,0,0.1);
  overflow: hidden;
}

.app-footer {
  text-align: center;
  padding: 20px;
  background-color: #333;
  color: white;
  border-radius: 8px;
  margin-top: 40px;
}

.btn-back {
  display: block;
  margin: 0 auto;
  padding: 10px 20px;
  background-color: #2196F3;
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 16px;
}

.btn-back:hover {
  background-color: #1976D2;
}
</style>
