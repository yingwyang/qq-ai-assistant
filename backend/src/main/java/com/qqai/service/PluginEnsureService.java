package com.qqai.service;

import com.qqai.component.ComponentOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 登录后自动启动组件：只做“延迟 + 交给编排器”，不再维护第二份启动编排。
 */
@Service
public class PluginEnsureService {

    private static final Logger log = LoggerFactory.getLogger(PluginEnsureService.class);

    private final ComponentOrchestrator orchestrator;
    private volatile boolean isStarting = false;

    public PluginEnsureService(ComponentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Async
    public void ensurePluginsStartedAsync() {
        if (isStarting) {
            log.info("组件启动检查进行中，跳过重复请求");
            return;
        }
        isStarting = true;
        try {
            TimeUnit.SECONDS.sleep(3);
            log.info("开始检查组件启动状态（按 descriptor order）...");
            orchestrator.startAllIfNotRunning();
            log.info("组件启动检查完成");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("组件启动检查被中断");
        } catch (Exception e) {
            log.error("组件启动检查异常: {}", e.getMessage());
        } finally {
            isStarting = false;
        }
    }
}
