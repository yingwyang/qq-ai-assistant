const { chromium } = require('playwright-core');
const path = require('path');
const fs = require('fs');

const screenshotDir = path.join(__dirname, 'verify-screenshots');
if (!fs.existsSync(screenshotDir)) fs.mkdirSync(screenshotDir);

(async () => {
  const browser = await chromium.launch({
    headless: true,
    executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  });
  const context = await browser.newContext({ viewport: { width: 1400, height: 900 } });
  const page = await context.newPage();

  const consoleLogs = [];
  const pageErrors = [];
  page.on('console', msg => {
    const text = `[${msg.type()}] ${msg.text()}`;
    consoleLogs.push(text);
    console.log(text);
  });
  page.on('pageerror', err => {
    const text = `[PAGEERROR] ${err.message}`;
    pageErrors.push(text);
    console.log(text);
  });
  page.on('response', async response => {
    if (response.status() >= 400) {
      const url = response.url();
      try {
        const body = await response.text();
        console.log(`[HTTP ${response.status()}] ${url} ${body.slice(0, 200)}`);
      } catch (e) {
        console.log(`[HTTP ${response.status()}] ${url}`);
      }
    }
  });

  // 1. 打开登录页
  await page.goto('http://localhost:5173/login');
  await page.waitForTimeout(1000);
  await page.screenshot({ path: path.join(screenshotDir, '01-login.png') });

  // 2. 登录
  await page.fill('input[type="text"]', 'admin');
  await page.fill('input[type="password"]', 'admin123');
  await page.click('button[type="submit"]');
  await page.waitForTimeout(2000);
  await page.screenshot({ path: path.join(screenshotDir, '02-admin-route.png') });

  // 3. 检查是否在 /admin（修改前 ADMIN 会被路由到 /admin，修改后应保持弹窗）
  const currentUrl1 = page.url();
  console.log('After login URL:', currentUrl1);

  // 4. 返回首页，点击系统管理按钮打开 AdminDashboard 弹窗
  await page.goto('http://localhost:5173/');
  await page.waitForTimeout(1500);
  await page.screenshot({ path: path.join(screenshotDir, '03-home.png') });
  await page.click('.system-btn');
  await page.waitForTimeout(1500);
  await page.screenshot({ path: path.join(screenshotDir, '04-admin-dashboard-modal.png') });

  // 5. 滚动到媒体文件管理
  await page.evaluate(() => {
    const sections = document.querySelectorAll('.section-card h4');
    for (const h of sections) {
      if (h.textContent.includes('媒体文件管理')) {
        h.scrollIntoView({ behavior: 'instant', block: 'start' });
        break;
      }
    }
  });
  await page.waitForTimeout(800);
  await page.screenshot({ path: path.join(screenshotDir, '05-media-manager.png') });

  // 6. 点击筛选按钮（全部/图片/视频/音频）
  const filterButtons = await page.$$('.media-filter .chart-btn');
  for (let i = 0; i < filterButtons.length; i++) {
    await filterButtons[i].click();
    await page.waitForTimeout(800);
    await page.screenshot({ path: path.join(screenshotDir, `06-filter-${i}.png`) });
  }

  // 7. 尝试全选复选框（如果有文件的话）
  const checkboxes = await page.$$('.media-table tbody input[type="checkbox"]');
  if (checkboxes.length > 0) {
    const selectAll = await page.$('.media-table thead input[type="checkbox"]');
    if (selectAll) {
      await selectAll.click();
      await page.waitForTimeout(500);
      await page.screenshot({ path: path.join(screenshotDir, '07-select-all.png') });
    }
  } else {
    await page.screenshot({ path: path.join(screenshotDir, '07-no-files.png') });
  }

  // 8. 输出结果
  console.log('=== Verification Summary ===');
  console.log(`Media filter buttons found: ${filterButtons.length}`);
  console.log(`Data rows found: ${checkboxes.length}`);
  console.log(`Console logs: ${consoleLogs.length}`);
  console.log(`Page errors: ${pageErrors.length}`);

  await browser.close();
})().catch(err => {
  console.error('Verification failed:', err);
  process.exit(1);
});
