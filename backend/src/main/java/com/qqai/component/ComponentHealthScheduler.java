package com.qqai.component;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每 component.health-interval-ms（默认 5s）巡检一次组件健康。 */
@Component
public class ComponentHealthScheduler {

    private final ComponentOrchestrator orchestrator;

    public ComponentHealthScheduler(ComponentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(initialDelay = 15000, fixedDelayString = "${component.health-interval-ms:5000}")
    public void tick() {
        orchestrator.healthCheckAll();
    }
}
