package com.qqai.component;

import org.springframework.stereotype.Component;

/** 根据描述符的 launchMode 选择运行器；测试可替换为 fake 工厂。 */
@Component
public class ComponentRuntimeFactory {

    private final SupervisedRuntime supervisedRuntime;
    private final DetachedLauncherRuntime detachedLauncherRuntime;

    public ComponentRuntimeFactory(SupervisedRuntime supervisedRuntime,
                                   DetachedLauncherRuntime detachedLauncherRuntime) {
        this.supervisedRuntime = supervisedRuntime;
        this.detachedLauncherRuntime = detachedLauncherRuntime;
    }

    public ComponentRuntime forDescriptor(ComponentModel.ComponentDescriptor descriptor) {
        return descriptor.getLaunchMode() == ComponentModel.LaunchMode.DETACHED_LAUNCHER
                ? detachedLauncherRuntime
                : supervisedRuntime;
    }
}
