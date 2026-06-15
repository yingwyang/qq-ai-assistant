# QQ AI 助手功能改进 - 实现计划

## [x] Task 1: 删除用户设置区域的头像上传功能和设置图标
- **Priority**: P1
- **Depends On**: None
- **Description**: 
  - 删除UserProfile.vue中的头像上传相关div元素
  - 删除设置图标svg元素
- **Acceptance Criteria Addressed**: [AC-1]
- **Test Requirements**:
  - `human-judgement`: 确认用户设置区域已移除头像上传功能和设置图标

## [x] Task 2: 退出登录功能添加确认弹窗
- **Priority**: P0
- **Depends On**: None
- **Description**: 
  - 修改Sidebar.vue中的logout方法，调用showConfirm确认弹窗
  - 用户点击确认后执行退出登录，点击取消则关闭弹窗
- **Acceptance Criteria Addressed**: [AC-2]
- **Test Requirements**:
  - `human-judgement`: 点击退出登录按钮显示确认弹窗，确认后退出，取消则保留登录状态

## [x] Task 3: 优化后台服务错误提示
- **Priority**: P1
- **Depends On**: None
- **Description**: 
  - 修改api.js中的请求拦截器，统一处理错误响应
  - 隐藏技术堆栈，显示预设友好提示文字
- **Acceptance Criteria Addressed**: [AC-3]
- **Test Requirements**:
  - `human-judgement`: 服务未启动时显示"服务暂时不可用，请稍后重试"等友好提示

## [x] Task 4: 实现快速选择最近n条消息功能
- **Priority**: P1
- **Depends On**: None
- **Description**: 
  - 在ChatInterface.vue的选择工具栏中添加数量输入框和选择按钮
  - 实现selectRecentMessages方法，选中最近n条消息
- **Acceptance Criteria Addressed**: [AC-4]
- **Test Requirements**:
  - `human-judgement`: 输入数字后点击选择按钮，自动选中对应数量的最近消息

## [x] Task 5: 修复对话未存储问题
- **Priority**: P0
- **Depends On**: None
- **Description**: 
  - 检查消息接收和发送流程
  - 确保消息正确调用API保存到数据库
- **Acceptance Criteria Addressed**: [AC-5]
- **Test Requirements**:
  - `programmatic`: 发送消息后检查数据库是否有对应记录

## [x] Task 6: 添加GPT-SoVITS Web UI启动按钮
- **Priority**: P1
- **Depends On**: None
- **Description**: 
  - 在AdminDashboard.vue中添加"打开Web UI"按钮
  - 实现启动GPT-SoVITS服务的功能，执行命令: cd D:\ai\GPT-SoVITS-v2pro-20250604-nvidia50 && runtime\python.exe -I api_v2.py -a 127.0.0.1 -p 8000
- **Acceptance Criteria Addressed**: [AC-6]
- **Test Requirements**:
  - `human-judgement`: 点击按钮能启动GPT-SoVITS服务

## [x] Task 7: 登录页面UI美化
- **Priority**: P1
- **Depends On**: None
- **Description**: 
  - 优化LoginPage.vue的样式，添加动画效果
  - 改进输入框、按钮的视觉效果
  - 添加背景动画或渐变效果
- **Acceptance Criteria Addressed**: [AC-7]
- **Test Requirements**:
  - `human-judgement`: 登录页面具有现代美观的设计风格