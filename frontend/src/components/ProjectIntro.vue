<template>
  <div class="project-intro">
    <h2>项目介绍</h2>
    <div class="intro-content">
      <p>铃音QQ对话是一个基于SpringBoot + Vue3 + AstrBot + NapCat + GPT-SoVITS的跨端智能聊天辅助系统。</p>
      <h3>主要功能：</h3>
      <ul>
        <li>QQ消息监听与管理</li>
        <li>AI消息总结与分析</li>
        <li>语音合成功能</li>
        <li>系统组件一键控制</li>
        <li>NapCat二维码登录</li>
      </ul>
    </div>
  </div>
</template>

<script>
export default {
  name: 'ProjectIntro'
};
</script>

<style scoped>
.project-intro {
  height: 100%;
}

.intro-content {
  margin-top: 20px;
}

.intro-content p {
  margin-bottom: 15px;
  line-height: 1.5;
}

.intro-content h3 {
  margin-top: 20px;
  margin-bottom: 10px;
  color: #333;
}

.intro-content ul {
  margin-left: 20px;
  margin-bottom: 15px;
}

.intro-content li {
  margin-bottom: 5px;
}
</style>