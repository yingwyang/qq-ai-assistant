package com.qqai.service;

import com.qqai.common.ProcessManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 插件自动启动服务
 * 在用户登录后异步检查三个插件是否启动，未启动则自动启动
 */
@Service
public class PluginEnsureService {

    private static final Logger log = LoggerFactory.getLogger(PluginEnsureService.class);

    private volatile boolean isStarting = false;

    @Autowired
    private ProcessManager processManager;

    @Autowired
    private AstrBotService astrBotService;

    @Autowired
    private NapCatService napCatService;

    @Autowired
    private GptSovitsService gptSovitsService;

    /**
     * 异步检查并启动三个插件（登录后延迟 3 秒执行）
     */
    @Async
    public void ensurePluginsStartedAsync() {
        if (isStarting) {
            log.info("插件正在启动中，跳过重复启动请求");
            return;
        }
        isStarting = true;
        try {
            // 延迟 3 秒，避免影响登录响应体验
            TimeUnit.SECONDS.sleep(3);
            log.info("开始检查三个插件启动状态...");

            // 启动顺序：NapCat → AstrBot → GPT-SoVITS（NapCat 是消息入口，需优先启动）
            ensureNapCatStarted();

            ensureAstrBotStarted();

            ensureGptSovitsStarted();

            log.info("插件启动检查完成");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("插件启动检查被中断");
        } catch (Exception e) {
            log.error("插件启动检查异常: {}", e.getMessage());
        } finally {
            isStarting = false;
        }
    }

    private void ensureAstrBotStarted() {
        try {
            if (!processManager.isPortOpen("localhost", 6185)) {
                log.info("AstrBot 未启动，正在自动启动...");
                astrBotService.startAstrBot();
            } else {
                log.debug("AstrBot 已在运行（端口 6185 监听）");
            }
        } catch (Exception e) {
            log.warn("自动启动 AstrBot 失败: {}", e.getMessage());
        }
    }

    private void ensureNapCatStarted() {
        try {
            if (!processManager.isPortOpen("localhost", 6099)) {
                log.info("NapCat 未启动，正在自动启动...");
                napCatService.startNapCat();
            } else {
                log.debug("NapCat 已在运行（端口 6099 监听）");
            }
        } catch (Exception e) {
            log.warn("自动启动 NapCat 失败: {}", e.getMessage());
        }
    }

    private void ensureGptSovitsStarted() {
        try {
            if (!processManager.isPortOpen("localhost", 8000)) {
                log.info("GPT-SoVITS 未启动，正在自动启动...");
                gptSovitsService.startGptSovits();
            } else {
                log.debug("GPT-SoVITS 已在运行（端口 8000 监听）");
            }
        } catch (Exception e) {
            log.warn("自动启动 GPT-SoVITS 失败: {}", e.getMessage());
        }
    }
}